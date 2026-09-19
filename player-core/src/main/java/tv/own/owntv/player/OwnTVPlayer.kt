package tv.own.owntv.player

import androidx.compose.runtime.Immutable

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.R
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.player.PlayerBudget
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SubtitleStyle
import tv.own.owntv.core.theme.AppFontFamily
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A selectable audio/subtitle track. [mpvId] is the mpv track id used for `aid`/`sid` (when ExoPlayer
 * owns playback its tracks reuse this field as an opaque ordinal). [image] flags an image-based subtitle
 * (PGS/VOBSUB/DVB) — selecting one on a VOD hands playback to ExoPlayer to render it. [typeIndex] is the
 * track's 0-based position among tracks of its own type, used to line a picked sub up with ExoPlayer's. */
enum class TrackLabelKind { AUDIO, SUBTITLE }

enum class ExternalSubtitleSource { LOCAL, OPENSUBTITLES }

@Immutable
data class TrackOption(
    /** Raw engine/provider label. It is an identity, not translated in the player engine. */
    val label: String,
    val mpvId: Int,
    val selected: Boolean,
    val image: Boolean = false,
    val codec: String? = null,
    val lang: String? = null,
    val typeIndex: Int = -1,
    val labelKind: TrackLabelKind = TrackLabelKind.AUDIO,
)

/** Image-based subtitle codecs. They carry no text, so the app-drawn (direct render) overlay can't show
 *  them on mpv's direct path. On VOD we hand playback to ExoPlayer, which renders them on its own layer. */
private val BITMAP_SUB_CODECS = setOf(
    "hdmv_pgs_subtitle", "pgssub", "dvd_subtitle", "dvdsub", "vobsub", "dvb_subtitle", "dvbsub", "xsub",
)

/** Audio codecs ExoPlayer can reliably decode (MediaCodec / built-in). If the active audio isn't one of
 *  these (e.g. DTS, TrueHD) we DON'T hand off — the handoff would just fail and bounce back to mpv. mpv
 *  decodes these in software via FFmpeg; ExoPlayer doesn't. Video is the same MediaCodec under both, so
 *  only audio gates the handoff. Matched against the mpv `codec` string with a prefix check. */
private val EXO_SAFE_AUDIO_CODECS = setOf(
    "aac", "ac3", "eac3", "mp3", "mp2", "opus", "vorbis", "flac", "pcm", "alac",
)

/** Metadata shown in the player HUD (breadcrumb path, year, channel logo). */
@Immutable
data class MediaMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val year: String? = null,
    val logoUrl: String? = null,
    /** P6 — stable per-item identity for the VOD engine pin (see
     *  [tv.own.owntv.core.player.enginePinKey]). Null falls back to the stream URL, which is what
     *  every pin used to be keyed on and is still correct for M3U/Xtream. */
    val contentKey: String? = null,
    /** Optional semantic season/episode numbers; localized at the UI boundary. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Semantic live-rewind start; formatted by the current HUD locale, never in the ViewModel. */
    val rewindStartMs: Long? = null,
)

/** An item in a play queue (e.g. a season's episodes), for prev/next.
 *  [resolveUrl] mints the playable URL right before this item loads (Stalker episodes: a per-episode
 *  `create_link` — the stored [url] is the season cmd shared by the whole queue, and each play needs
 *  a fresh short-lived link). Null (M3U/Xtream) = load [url] directly, exactly as before. */
@Immutable
data class PlaylistItem(
    val url: String,
    val meta: MediaMeta = MediaMeta(),
    val resolveUrl: (suspend () -> String)? = null,
    /** Per-item HTTP headers (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`), stored `Key: Value` per line.
     *  Applied to whichever engine loads this item; null = the source's own UA/headers only. */
    val httpHeaders: String? = null,
    /** Widevine/ClearKey licence details (#115); non-null pins this item to ExoPlayer. */
    val drmConfig: String? = null,
)

/** Whether prev/next are available in the current queue. */
@Immutable
data class NavState(val hasPrev: Boolean, val hasNext: Boolean)

/** Video scaling modes exposed in the player's zoom menu. */
enum class ZoomMode(@param:androidx.annotation.StringRes val labelRes: Int) {
    FIT(tv.own.owntv.core.R.string.player_zoom_fit_screen), FILL(tv.own.owntv.core.R.string.player_zoom_fill_crop), STRETCH(tv.own.owntv.core.R.string.player_zoom_stretch),
    ORIGINAL(tv.own.owntv.core.R.string.player_zoom_original), FORCE_16_9(tv.own.owntv.core.R.string.player_zoom_force_16_9), FORCE_4_3(tv.own.owntv.core.R.string.player_zoom_force_4_3),
}

/**
 * App-wide single libmpv player. mpv (FFmpeg) decodes virtually any codec/container and exposes every
 * audio/subtitle track — the right engine for IPTV (ExoPlayer only surfaced device-decodable tracks).
 * Also gives caching, playback speed, etc. State is published as StateFlows for the Compose HUD.
 *
 * For the one case mpv's direct path can't render — a VOD with an **image** subtitle (PGS/VOBSUB/DVB) —
 * it hands playback to [ExoSubtitleEngine] (ExoPlayer), which keeps video zero-copy AND draws the bitmap
 * sub on its own layer. The handoff is transparent: ExoPlayer's state is mirrored into these same flows.
 *
 * ## Threading (load-bearing — read before touching a libmpv call)
 *
 * libmpv calls are synchronous and can block for seconds while the core sits in a stalling network read,
 * so the rules are not stylistic:
 *
 * 1. **Commands and property reads/writes go through [mpvAsync] / `mpvExecutor`**, never the UI thread.
 *    Issuing them from main caused ANRs ("Input dispatching timed out"). A single worker thread also
 *    preserves the order the calls were made in, which the stop/loadfile classification depends on.
 * 2. **Surface attach/detach must be on the MAIN thread** — [attachSurface], [detachSurface] and
 *    [setSurfaceSize] are driven by `SurfaceHolder.Callback` (see [MpvVideoSurface]), and the surface is
 *    only valid for the duration of those callbacks. These few calls therefore touch `mpv` directly
 *    rather than going through the executor, and [assertMainThread] enforces it in debug builds.
 * 3. **Event callbacks ([event], [eventProperty]) arrive on mpv's own event thread**, not main and not
 *    the executor. They may read state, but anything that issues a libmpv call must hop via [mpvAsync].
 *
 * A future change that breaks rule 1 or 2 fails loudly in a debug build instead of racing intermittently
 * on one TV model.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwnTVPlayer(
    private val context: Context,

    private val settings: SettingsRepository,
    private val connectivity: tv.own.owntv.core.network.ConnectivityObserver,
    private val streamingHttp: tv.own.owntv.core.network.StreamingHttpClient,
    private val diagnostics: PlayerDiagnostics,
    private val proxyHolder: tv.own.owntv.core.network.ProxyConfigHolder,
    private val vodEngineStore: tv.own.owntv.core.player.VodEngineStore,
    private val localeStore: LocaleStore,
    private val playbackPrefs: tv.own.owntv.core.player.PlaybackPrefsStore,
) : MPVLib.EventObserver {
    private val toastRenderer = PlayerToastRenderer(context, localeStore)

    /** Lets mpv play a download or recording saved into a folder the user picked (a SAF document). */
    private val contentFds = ContentFds(context)

    /**
     * The same context, for the rest of this module. [SubtitleController] needs one to resolve a
     * finished download's stored location before it can fingerprint it, and this player is the only
     * thing it already holds.
     */
    internal val appContext: Context get() = context
    private val toastEpoch = AtomicInteger(0)
    private var activeToast: android.widget.Toast? = null

    /** How mpv's last HTTP status should be treated when deciding whether to repeat a request. */
    internal enum class HttpRefusal {
        /** Not an HTTP refusal (network, decoder, or no error at all). */
        NONE,

        /** The server will not serve this request, and asking again identically cannot change that
         *  (401/403/404/410 and the rest of 4xx). */
        HARD,

        /** The server is busy, not refusing: `408` timeout, `429` rate limit, and the `458` Xtream
         *  panels invent for "the account's one session is already in use"
         *  ([LiveStreamQuirks.isSessionLimit]). The stream is fine — *we* are the second client, or
         *  we asked too fast — so the identical request IS worth repeating after a back-off, which is
         *  exactly what the ExoPlayer side already does. */
        BUSY,
    }

    companion object {
        const val TAG = "OwnTVPlayer"

        // mpv's stock subtitle values, restored verbatim for every option of the custom look (#96)
        // that is left on "Default" — or whenever the master toggle is off.
        private const val MPV_DEFAULT_SUB_COLOR = "#FFFFFFFF"
        private const val MPV_DEFAULT_SUB_BACK_COLOR = "#00000000"
        private const val MPV_DEFAULT_SUB_SCALE = 1.0
        private const val MPV_DEFAULT_SUB_FONT = "sans-serif"

        /**
         * Routing for an END_FILE that arrives before FILE_LOADED ever did. For a VOD that means the
         * demuxer rejected the file outright and a hard reset is the fast, correct answer. For live it
         * means nothing of the sort — providers drop the first connection routinely — and hard-resetting
         * returns before the live retry ladder (EOF grace, offline check, catch-up/`.m3u8` alternates,
         * software decode, short-UA retry) gets a chance, so the channel dies on the first hiccup.
         */
        fun shouldHardResetOnEarlyEndFile(fileLoaded: Boolean, expectingPlayback: Boolean, isLive: Boolean): Boolean =
            !fileLoaded && expectingPlayback && !isLive

        const val MAX_AUTO_RETRIES = 3 // silent retries (backoff) before showing the error UI
        // Ceiling on outstanding app-caused END_FILE credits (see incrementPendingStopCounter). A handoff
        // issues at most a stop + a loadfile, so anything beyond a small number means credits are leaking.
        const val MAX_PENDING_STOP_CREDITS = 4
        const val END_TOLERANCE_MS = 8_000L // how close to the duration still counts as "finished"

        /**
         * Did this item finish, as opposed to dropping out mid-stream? The flat 8 s tolerance alone was
         * wrong for short items: anything under ~8 s satisfied `pos >= dur - 8000` at position 0, so a
         * short clip was "complete" the instant it opened — it never resumed where you left off, and in a
         * queue it auto-advanced immediately. The tolerance is now also capped at a quarter of the item,
         * which is identical to the old behaviour for anything 32 s or longer.
         */
        fun reachedEnd(durationMs: Long, positionMs: Long): Boolean {
            if (durationMs <= 0) return false // unknown duration — never guess "finished"
            val tolerance = minOf(END_TOLERANCE_MS, durationMs / 4)
            return positionMs >= durationMs - tolerance
        }
        // --- Live silent-freeze watchdog (mpv) -----------------------------------------------------
        // A live feed can wedge with the socket still open: mpv keeps pause=false / paused-for-cache=false
        // and emits no END_FILE, but time-pos stops advancing — a frozen channel with "nothing happening".
        // Poll time-pos; sustained no-progress while "playing" == a dropped feed → spinner + reconnect.
        // Poll interval and limit for the live no-progress watchdog, device-tiered the way
        // [PlayerBudget] tiers memory. The pair is chosen so the DETECTION TIME barely moves while the
        // number of wake-ups drops on the hardware that can least afford them:
        //
        //   normal   4 × 2,500ms = 10.0s to a verdict, 24 wake-ups per minute
        //   lowSpec  3 × 4,000ms = 12.0s to a verdict, 15 wake-ups per minute
        //
        // Two extra seconds on a frozen channel is not perceptible next to the reconnect that follows;
        // a third fewer JNI round-trips on a 2 GB TV is. Read through [liveStallPollMs]/[liveStallLimit],
        // never directly — the tier is a property of the device, not a compile-time constant.
        const val LIVE_STALL_POLL_MS = 2_500L
        const val LIVE_STALL_LIMIT = 4
        const val LIVE_STALL_POLL_LOW_SPEC_MS = 4_000L
        const val LIVE_STALL_LIMIT_LOW_SPEC = 3
        const val MAX_LIVE_RECONNECTS = 6        // consecutive stall-reconnects before the error UI takes over
        // Identical reconnects that must fail before a live stall is treated as a damaged mux rather than a
        // flaky network. Halfway through the budget: late enough that a transient blip has had its chances,
        // early enough that the tolerant reopen still gets several attempts before the error UI.
        const val TOLERANT_DEMUX_AFTER_RECONNECTS = 3
        const val LIVE_OPEN_TIMEOUT_MS = 10_000L // bound FFmpeg/network loops that never emit FILE_LOADED/END_FILE

        /** How long a statistics read may wait for [mpvExecutor] before it is abandoned as unknown. The
         *  executor can be busy with a real command (a load, a decoder switch) and no readout is worth
         *  holding a caller behind one. */
        internal const val MPV_READ_TIMEOUT_MS = 1_000L
        internal const val STREAM_RECONNECT_OPTIONS =
            "reconnect=1,reconnect_streamed=1,reconnect_delay_max=8,reconnect_on_http_error=5xx"

        /**
         * FFmpeg stream-layer options for one load. Every stream gets the plain reconnect set — that is
         * the long-shipped behaviour and providers depend on it.
         *
         * The single exception is `reconnect_at_eof`, which keeps a CONTINUOUS stream alive across
         * mid-stream EOFs but also reconnects on the EOF that ends a *finite* HTTP response. On an HLS
         * playlist that means re-fetching the same ~2 KB manifest forever without ever demuxing, so it is
         * enabled only for live raw MPEG-TS. [hls] must be the *effective* answer (see
         * [LiveStreamQuirks.isHlsUrl]) — a panel that redirects `…/id.ts` to a manifest is HLS no matter
         * what its URL says, and treating it as raw TS is exactly the permanent black screen this guards
         * against. VOD/catch-up EOF is real and must end playback, so they never get it either.
         */
        internal fun streamLavfOptionsFor(url: String, live: Boolean, hls: Boolean): String = when {
            // Path only — a `.ts` appearing in a query string says nothing about the transport, and the
            // effective-HLS test above already reads the path alone.
            live && !hls && url.substringBefore('?').contains(".ts", ignoreCase = true) ->
                "$STREAM_RECONNECT_OPTIONS,reconnect_at_eof=1"
            else -> STREAM_RECONNECT_OPTIONS
        }

        /**
         * Classify mpv's last error, when it is the server refusing with an HTTP status rather than a
         * network/decoder problem.
         *
         * Everything 4xx used to be treated as [HttpRefusal.HARD], which contradicted the ExoPlayer
         * side over `458` (F29): Exo backs off and reconnects on a one-session panel while mpv gave up
         * after a single repeat — the "mpv can't play what ExoPlayer plays" symptom on exactly the
         * panels where the first engine simply had not let go yet.
         *
         * For [HttpRefusal.HARD] the ladder's backed-off retries are a request storm against a panel
         * already saying no, so the repeats are cut; the fallbacks that *change* the request —
         * `.ts`↔`.m3u8`, the short `vlc` User-Agent — stay armed either way. (mpv surfaces only the
         * status line, so a `Retry-After` header cannot be honoured here; the ladder's own back-off is
         * what spaces the repeats.)
         */
        /**
         * The 4xx status in mpv's error line, or null when it doesn't carry one.
         *
         * Deliberately narrower than [PlayerErrors.httpStatusIn], which reads any 3-digit status out of
         * any error text: everything downstream of here decides whether to *repeat a request*, and only
         * a 4xx tells us the server refused this specific ask. Widening it would silently reclassify
         * 5xx as a refusal, so the two parsers stay separate on purpose.
         */
        internal fun httpStatusOf(mpvError: String?): Int? =
            HTTP_REFUSAL_RX.find(mpvError ?: return null)?.groupValues?.get(1)?.toIntOrNull()

        internal fun httpRefusalKind(mpvError: String?): HttpRefusal {
            val code = httpStatusOf(mpvError) ?: return HttpRefusal.NONE
            return when {
                code == 408 || code == 429 || LiveStreamQuirks.isSessionLimit(code) -> HttpRefusal.BUSY
                else -> HttpRefusal.HARD
            }
        }

        /** True only for a refusal repeating cannot fix — see [httpRefusalKind]. */
        internal fun isHardHttpRefusal(mpvError: String?): Boolean =
            httpRefusalKind(mpvError) == HttpRefusal.HARD

        private val HTTP_REFUSAL_RX = Regex("""HTTP error (4\d\d)""", RegexOption.IGNORE_CASE)

        /** Identical requests allowed after a [isHardHttpRefusal] — one, so the format/UA fallbacks
         *  (which need `item.autoRetries >= 1`) still get their turn without a storm in between. */
        internal const val HARD_REFUSAL_MAX_RETRIES = 1

        /** Longest an engine switch waits for mpv to finish tearing its stream down. */
        internal const val MPV_RELEASE_TIMEOUT_MS = 1_500L

        /**
         * FFmpeg's error-tolerance switches: drop corrupted packets instead of ending the file, and
         * synthesise the timestamps a damaged mux is missing. This is what a forgiving player (VLC, whose
         * TS demuxer is its own rather than FFmpeg's) does by nature and mpv does not — the reason a
         * re-streamed feed with packet loss or malformed PSI tables plays there and `END_FILE`s here.
         *
         * Never a default. Discarding packets and inventing timestamps costs accuracy on a stream whose
         * data was fine, so it is a retry rung only, remembered per stream in
         * [LiveStreamQuirks.rememberNeedsTolerantDemux].
         */
        private const val TOLERANT_FFLAGS = "+discardcorrupt+genpts"
        private const val TOLERANT_LAVF_OPTIONS = "err_detect=ignore_err"

        /**
         * FFmpeg demuxer options for one load.
         *
         * The trimmed fast-zap probe needs `+nobuffer+genpts` (see [applyProbeProfile]); [tolerant] adds
         * the error tolerance above. `fflags` may appear only once, so the two sets are merged rather
         * than concatenated.
         *
         * Live HLS keeps FFmpeg's default `live_start_index` (`-3`). An earlier attempt pinned it further
         * back (`-5`) to dodge the 403s on the traced panel, and the logs showed that made things *worse*:
         * that panel signs every segment URL with a short-lived token, so starting deeper in the window
         * only asks for staler — more certainly expired — URLs. There is nothing to tune here.
         */
        internal fun demuxerLavfOptionsFor(
            trimmedRawTsProbe: Boolean,
            tolerant: Boolean,
        ): String {
            val fflags = when {
                trimmedRawTsProbe && tolerant -> "+nobuffer+genpts+discardcorrupt" // +genpts already present
                trimmedRawTsProbe -> "+nobuffer+genpts"
                tolerant -> TOLERANT_FFLAGS
                else -> ""
            }
            return buildList {
                if (fflags.isNotEmpty()) add("fflags=$fflags")
                if (trimmedRawTsProbe) add("seekable=1")
                if (tolerant) add(TOLERANT_LAVF_OPTIONS)
            }.joinToString(",")
        }
        // --- Engine-handoff / reconnect timing --------------------------------------------------------
        // Hardware assumptions live here, tunable in one place. TV boxes expose ONE hardware decoder:
        // when playback moves between mpv and ExoPlayer the outgoing engine's MediaCodec must finish
        // releasing before the incoming engine claims it, or the claim fails instantly.
        //
        // Each wait below is marked MEASURED or ESTIMATED. That distinction matters to anyone tuning
        // them later: an ESTIMATED value is a round number that has never failed in testing, and can
        // be moved on evidence. A MEASURED one was set from an actual timing on real hardware, and
        // lowering it re-opens the fault it was raised to fix.
        // ESTIMATED. The longest of the release waits because it covers the full engine swap, where
        // the outgoing decoder is torn down and the incoming one is built immediately after.
        const val DECODER_RELEASE_MS = 600L        // outgoing engine's MediaCodec release (engine swap / next episode)
        // MEASURED — 508 ms for the surface release to complete on the owner's Realtek box. This is the
        // one number here with a real observation behind it, and 500 is that observation rounded down
        // to the beat the rest of the file uses. Do not lower it on the grounds that it "looks like a
        // guess": the guess would be a shorter wait, and the shorter wait is what produced the black
        // screen this constant exists to prevent.
        const val SURFACE_HANDOFF_MS = 500L        // shorter release wait on the surface-attach handoff paths
        // ESTIMATED. A settle beat after the mpv core and the surface are both rebuilt from scratch.
        const val CORE_RESET_SETTLE_MS = 500L      // fresh mpv core + recreated surface settle after a hard reset
        // The Exo position emit (500ms) and the fps-chip recheck (1,500ms) used to live here. Both now
        // ride [PlayerHeartbeat]'s shared 1 Hz beat, so neither has an interval of its own any more.
        const val EXO_SUB_DELAY_DEBOUNCE_MS = 350L // settle time before a timing change re-prepares on Exo (§8)
        const val FREEZE_MAX_W = 960               // capture width cap for the engine-swap freeze frame
        const val SURROUND_CHECK_MS = 7_000L       // wait before verifying surround audio actually produces sound
        // Window the audio clock is sampled over for the "sink accepted the format then played silence"
        // check. Long enough that a single stalled packet can't fake a freeze, short enough that a user
        // isn't sitting in silence while we make up our mind.
        const val SURROUND_SILENCE_CHECK_MS = 4_000L
        const val DECODE_CHECK_MS = 4_000L         // wait before verifying video decode actually produces frames
        const val LIVE_FPS_PROBE_MS = 6_000L       // settle time before measuring fps on a stream with no container-fps
        /** Frame rates a broadcast can plausibly be; a measurement is only trusted when it lands on one. */
        val STANDARD_FPS = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        const val LIVE_RECONNECT_DELAY_MS = 3_500L // pause before reconnecting a dropped live stream
        const val EOF_GRACE_MS = 1_500L            // grace for a late FILE_LOADED after an early EOF on live
        const val FALLBACK_RETRY_DELAY_MS = 300L   // brief spinner beat before retrying with a URL/UA variant
        // ESTIMATED. The shortest wait in the group, and the only one that is not about a decoder
        // handing hardware to another decoder — just a frame or two for a render-config change to land.
        const val RENDER_RECONFIG_MS = 200L        // let a render-config change apply before resuming
        // Warn-level mpv lines worth keeping as the failure reason (HTTP codes, open/decode failures, …).
        val FAILURE_RX = Regex(
            "http|error|fail|refus|timed out|unrecogn|cannot|no such|invalid|denied|forbidden|not found|" +
                "unsupported|connection|reset|4\\d\\d|5\\d\\d",
            RegexOption.IGNORE_CASE,
        )
        /** mpv's own report that a live feed's video timestamps can't be trusted. See [LiveStreamQuirks]. */
        val BROKEN_PTS_RX = Regex(
            "invalid video timestamp|non-monotonic|desynchroni",
            RegexOption.IGNORE_CASE,
        )

        /**
         * How many broken-timestamp warnings before free-running video timing is switched on.
         *
         * A single discontinuity (an ad break, a mux glitch) is normal on live TV and must NOT cost the
         * stream its A/V sync; the feeds that need the workaround emit this on essentially every frame,
         * so a couple of seconds' worth is an unambiguous signature.
         */
        internal const val BROKEN_PTS_HITS = 20

        // Generic "consequence" lines that shouldn't overwrite a more specific captured cause.
        val GENERIC_FAIL_RX = Regex(
            "failed to open|opening failed|could not open|loading failed|was aborted|finished playback",
            RegexOption.IGNORE_CASE,
        )
    }

    // The app renders with mpv's direct decoder-to-surface output (vo=mediacodec_embed) — the same
    // zero-copy pipeline YouTube/Netflix use, and the right one for TV hardware. mpv's GL renderer is
    // kept ONLY as the automatic software-decode rescue (hwdec=no): the direct surface can't display
    // software-decoded frames, so those go through vo=gpu. The Android emulator's *translated* GL is
    // broken and hard-crashes the process, so on emulators we never attempt the GL rescue — we show a
    // clean "can't decode" error instead. Real TVs (incl. Fire TV) run the GL rescue fine.
    private val glUnsupported: Boolean by lazy { isProbablyEmulator() }
    private fun isProbablyEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        val model = android.os.Build.MODEL
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            fp.contains("emulator", true) || fp.contains("/sdk_") ||
            model.contains("google_sdk") || model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            android.os.Build.MANUFACTURER.contains("Genymotion") ||
            android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("emulator") ||
            android.os.Build.PRODUCT.contains("simulator")
    }

    private var mpv: MPVLib? = null
    private var initialized = false

    /**
     * The parts of [ensureInit] that belong to the player object rather than to an mpv core: the
     * logcat diagnostics tail and the `_error` → `_errorInfo` collector. A hard reset destroys the
     * core and clears [initialized] so the core is rebuilt, but these must run exactly once —
     * re-launching the collector left the old one alive, so after N resets a single error wrote N
     * entries into the Settings playback error log and pushed the real history out of its slots
     * ([PlaybackErrorLog] keeps a fixed number of the newest entries).
     */
    private var oneTimeInitDone = false
    private var currentUrlField: String? = null
    /** Every assignment also refreshes [active] — see [hasActiveStream], whose value this mirrors. */
    private var currentUrl: String?
        get() = currentUrlField
        set(value) {
            currentUrlField = value
            refreshActive()
        }
    /** P6 — stable engine-pin key of the loaded item (see [MediaMeta.contentKey]); null = key on the URL. */
    private var currentContentKey: String? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null
    private var currentRewindStartMs: Long? = null

    /**
     * Reconnect URL provider — set ONLY for an expiring-URL source (Stalker, plan §5.4.1). The live
     * stall-reconnect watchdogs and HUD Retry await it before reloading, so a stream that dies
     * mid-session gets a fresh create_link instead of looping on the dead resolved URL. Null
     * (default) → M3U/Xtream behavior unchanged (replay the stored URL).
     *
     * **Lifetime is the load, not the player** (F12). Live tunes install/clear it through
     * `LiveViewModel`; every [play] resets it to what that item needs, so a movie can never inherit
     * the previous channel's provider and retry into a live stream. Stalker VOD passes its own
     * provider — a `create_link` URL expires in a couple of hours, which is well inside a film.
     */
    @Volatile var reconnectUrlProvider: tv.own.owntv.core.stalker.ReconnectUrlProvider? = null
    private var consecutiveHardResets = 0
    // Snapshot of a non-live item taken when the app backgrounds (screensaver / Home), so it can be restored
    // paused at its position on return — otherwise the stream is freed and Play does nothing until a reload.
    /** A URL and a position are not enough to reopen an item: the request identity ([userAgent],
     *  [httpHeaders]) and, for an expiring-URL source, its [reconnectProvider] have to come back with it,
     *  or the restore 403s a movie that had just been playing. */
    private data class BackgroundRestore(
        val url: String,
        val meta: MediaMeta,
        val positionMs: Long,
        val wasPlaying: Boolean,
        val userAgent: String?,
        val httpHeaders: String?,
        val reconnectProvider: tv.own.owntv.core.stalker.ReconnectUrlProvider?,
    )
    @Volatile private var backgroundRestore: BackgroundRestore? = null
    private var playlist: List<PlaylistItem> = emptyList()
    private var playlistIndex = 0
    // mpv's android video output needs a surface at loadfile time, or it deselects video (audio-only).
    // So when no surface is attached yet we defer the load until attachSurface().
    private var surfaceAttached = false
    private var pendingUrlField: String? = null
    private var pendingUrl: String?
        get() = pendingUrlField
        set(value) {
            pendingUrlField = value
            refreshActive()
        }
    private var hdrHint = true
    private var playerBudget: PlayerBudget? = null

    // Last successfully-decoded video height (persists across loads). Used to decide recovery when a load
    // fails before any frame: a stream we know is >1080p must NOT fall back to software decode (the guard
    // would just kill it) — we retry the hardware decoder instead, which is what a manual Retry does.
    @Volatile private var lastVideoHeightPx = 0

    // The raw custom User-Agent from the source settings, or null if the user left it blank.
    // null = use DEFAULT_USER_AGENT on first attempt, FALLBACK_USER_AGENT on suspicious failure.
    // non-null = always use the given UA, no automatic fallback.
    private var currentUserAgent: String? = null
    // Per-channel HTTP headers for the item being played (M3U `#EXTVLCOPT`/`#EXTHTTP`/`#KODIPROP`,
    // F16). A `User-Agent` in here overrides the per-source one — it is the more specific setting.
    private var currentHeaders: Map<String, String> = emptyMap()
    /** This item's DRM licence details (#115), decoded once per load. Non-null means ExoPlayer is the
     *  only engine that can play it: libmpv has no CDM, so it cannot fetch a key from a licence
     *  server, and the ladder must never offer mpv. */
    private var currentDrm: tv.own.owntv.core.drm.DrmConfig? = null
    // The source-level UA for the queue currently loaded (playEpisodes). Each item re-derives
    // currentUserAgent from its own headers and falls back to this.
    private var queueUserAgent: String? = null
    // The request identity of the loaded item, kept exactly as the caller supplied it, so a background
    // restore replays the same request instead of re-opening with the URL alone — an item needing a
    // Referer or a custom UA came back from the screensaver with neither and 403'd.
    @Volatile private var tunedUserAgent: String? = null
    @Volatile private var tunedHttpHeaders: String? = null
    // Diagnostics for the "smooth on the first mpv channel, slightly juddery from the second onward"
    // report: how many loads this (reused) mpv core has served, and whether the last one recreated the
    // SurfaceView. Read back in the one-shot "display timing" log.
    @Volatile private var mpvLoadCount = 0
    @Volatile private var usedFreshSurface = false

    /** Refresh rate Android reports for the default display right now, e.g. "30.000002Hz@modeId=2". */
    private fun androidDisplayHz(): String = runCatching {
        val d = defaultDisplay() ?: return "unknown"
        "${d.refreshRate}Hz@modeId=${d.mode?.modeId}"
    }.getOrElse { "unknown" }

    private fun defaultDisplay(): android.view.Display? = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }.getOrNull()

    // Vsync-aligned presentation was TRIED for live and does not work on this VO — do not re-attempt it
    // via mpv options. Measured 2026-07-27 on a 30.000002Hz panel, 4K live, vo=mediacodec_embed:
    //   - default (video-sync=desync): mpv presents with no vsync alignment. The TV's own pipeline logged
    //     ~94 mistimed frames/min (W/VideoClient "vSyncDiff"), average interval 33668µs vs the correct
    //     33333µs. That is the mild judder users see; decode is clean (frame-drops=0, decoder-drops=0).
    //   - feeding mpv the panel rate (display-fps-override, accepted: display-fps read back 30.000002)
    //     and switching to video-sync=display-resample made it slightly WORSE, not better: ~76 mistimed
    //     frames/min and a 26202µs average. The reason is in the same log line — mpv reported
    //     estimated-display-fps=23.9 and vsync-jitter=0.55 while the panel was a steady 30Hz, i.e. mpv
    //     cannot MEASURE vsync under mediacodec_embed. Every display-* sync mode is built on that
    //     measurement, so handing it a correct nominal rate doesn't help.
    //   - moving live to the GL path made it clearly WORSE, twice. First with vo=gpu + display-resample
    //     (unmeasurable: the decoder leaves the TV's video port — vdoPort=RHAL_CRM_VIDEO_PORT_NONE,
    //     tunnel=0 — so the vSyncDiff counter goes silent; zero events meant "no instrument", not "no
    //     judder"). Then properly, with vo=gpu-next + opengl-swapinterval=1 + display-resample, which
    //     DID give mpv a real display clock ("Estimated source FPS: 30.000, display FPS: 30.000" from
    //     libplacebo) — but gpu-next routes mediacodec frames through the aimagereader interop, and this
    //     GPU (GL_RENDERER='PowerVR B-Series BXE-4-32') can't feed it 4K: the log fills with
    //     "aimagereader: Waiting for frame timed out! / acquireLatestImage failed: -30001" and a
    //     "Forcing queue refill, PTS(0.6) < VPTS(8.37)". Owner confirmed clearly worse, visible stutter.
    //     The GL path is a dead end on TV-class GPUs; both attempts removed.
    // Also note the judder is random per load, not per channel: across 10 measured loads the rate ranged
    // 0.39–6.93 mistimed frames/s with no predictor (channel, engine toggle vs direct start, and fresh vs
    // reused Surface all failed). That is the signature of two free-running clocks, i.e. no phase lock.
    // ExoPlayer is smooth on the identical stream only because MediaCodecVideoRenderer releases each
    // output buffer with a vsync-adjusted presentation timestamp (VideoFrameReleaseHelper). Matching that
    // needs a timed releaseOutputBuffer (+ a Choreographer vsync source and a presentation thread) inside
    // libmpv's android VO — a native-side change, not an option. libmpv is already on its newest release
    // (1.0.0 = mpv 0.41.0), and mpv master still uses the untimed av_mediacodec_release_buffer, so there
    // is no upgrade that fixes this. Accepted: live on mpv keeps mild judder; ExoPlayer is the smooth
    // engine and mpv remains the fallback for streams ExoPlayer can't open.

    private val _directRender = MutableStateFlow(false)
    /** True while the direct (decoder-to-surface) output is in use — HUD hides zoom, app draws subs. */
    val directRender: StateFlow<Boolean> = _directRender.asStateFlow()

    /** Hardware decoding effectively in use right now — the global setting, minus a per-item override
     *  forced on after the hardware decoder failed to start a stream. */
    private fun hwDecodingActive(): Boolean = hwDecoding && !item.forceSoftwareThisLoad && !item.ccSoftwareOverride

    /** Flip the CC software-decode override and reinit the decoder/output if it changed. */
    private fun setCcSoftwareOverride(on: Boolean) {
        if (item.ccSoftwareOverride == on) return
        item.ccSoftwareOverride = on
        android.util.Log.i(TAG, "CC software-decode override ${if (on) "ON" else "OFF"} (height=${load.currentHeightPx}px)")
        if (initialized) mpvAsync { applyRenderConfig() }
    }

    /** Silent-retry budget per content type: Live TV is worth retrying (cold-boot decoder lag, server
     *  hiccups). VOD gets 2 — most failures are bad links, but a back-to-back load (e.g. auto-play to the
     *  next episode) can hit a transient hardware-decoder error (Realtek 0x80001000) that a quick direct
     *  retry clears, exactly like a manual Retry. */
    private fun maxRetries(): Int = if (isLiveContent) MAX_AUTO_RETRIES else 2

    /** Exponential backoff between silent retries: 1s, 2s, 4s for attempts 1..3 — gives the cold-boot
     *  decoder a bit more breathing room each time. */
    private fun backoffMs(attempt: Int): Long = 1000L * (1L shl (attempt - 1).coerceIn(0, 5))

    // Image subtitles (PGS/VOBSUB/DVB) used to drop the whole player into GL compositing (vo=gpu +
    // hwdec=mediacodec-copy) to draw them — which copies every 4K HDR frame and made playback unwatchable
    // on TV-class hardware. That fallback is GONE: video now ALWAYS stays on the direct path, and image
    // subs on a VOD are handled by handing playback to ExoPlayer (see [handoffToExo]) instead.
    // ...with ONE exception: the copy rescue rung ([ItemState.forceCopyThisLoad]), which is only ever reached
    // after the direct path has already failed to produce a frame.
    private fun targetHwdec(): String = when {
        !hwDecodingActive() -> "no"
        item.forceCopyThisLoad -> "mediacodec-copy"
        else -> "mediacodec"
    }
    private fun targetVo(): String = if (hwDecodingActive() && !item.forceCopyThisLoad) "mediacodec_embed" else "gpu"

    /** Direct decoder-to-surface output. Non-direct = software decode only (hwdec off / per-item rescue),
     *  which the direct surface can't display, so it goes through the GL renderer. */
    private fun useDirect(): Boolean = targetVo() == "mediacodec_embed"

    /** Apply vo/hwdec for the current render path (also safe live — mpv reinits decoder/output). */
    private fun MPVLib.applyRenderConfig() {
        setPropertyString("hwdec", targetHwdec())
        if (surfaceAttached) setPropertyString("vo", targetVo())
        _directRender.value = targetVo() == "mediacodec_embed"
        applyDeinterlace()
    }

    /** Settings → Deinterlacing. Written on every render-config change because the render path decides
     *  whether it can do anything: no video filter runs on the direct decoder-to-surface output, so this
     *  only takes effect once mpv is rendering itself (hardware decoding off, or a software rescue). */
    private fun MPVLib.applyDeinterlace() {
        setPropertyString("deinterlace", if (deinterlace) "yes" else "no")
    }

    /** mpv `audio-channels`: multichannel allowed → multichannel LPCM where the sink **unambiguously**
     *  supports it (`auto-safe`), else a safe stereo downmix; Stereo only → force stereo. `auto-safe`
     *  (not `auto`) because some sinks falsely claim 5.1/7.1. If a sink claims support but actually
     *  mis-plays multichannel PCM (the "2× speed, no sound", #25) or goes silent, the failsafe latches
     *  [AudioOutputPolicy] and every engine forces stereo for the rest of the session. Always decoded
     *  PCM, so the audio clock stays alive. */
    private fun audioChannelsValue(): String =
        if (AudioOutputPolicy.allowsMultichannel(surroundMode)) "auto-safe" else "stereo"

    /** True when this load may use multichannel — the mode allows it and the session isn't latched. */
    private fun multichannelAllowed(): Boolean = AudioOutputPolicy.allowsMultichannel(surroundMode)

    /**
     * Trim FFmpeg's stream probe for **live** sources so channels start faster (the default ~5 MB / 5 s
     * probe adds ~1 s of black before the first frame). VOD keeps the full probe so HDR colorspace and
     * all tracks are detected. If a trimmed live load returns no audio, [ItemState.forceFullProbe] re-probes fully.
     */
    private fun MPVLib.applyProbeProfile(url: String) {
        val lower = url.lowercase()
        // Raw continuous MPEG-TS (Xtream live `…/id.ts`, catch-up timeshift `.ts`). These probe fast and
        // start mid-stream, so they're the streams fast-zap trimming was built for — and the proven-safe
        // case (Xtream live works). HLS (.m3u8) and other/extensionless live URLs are NOT trimmed: they need
        // the full probe (playlist + a segment) to open cleanly, and a trimmed probe handed mpv incomplete
        // info → the stream opened but the playloop wedged (regression: M3U HLS live hung after the decoder
        // inited, while v2.2.4 — which always full-probed — played). So trim ONLY raw TS, full-probe the rest.
        // A `.ts` URL the provider redirects to a manifest is HLS, not raw TS: trimming its probe wedges
        // mpv's HLS open (see below), so the learned quirk has to disqualify it here too. Scoped to live —
        // the redirect is a live-endpoint behaviour, and a catch-up `.ts` from the same host is still raw.
        val effectiveHls = lower.substringBefore('?').endsWith(".m3u8") ||
            (isLiveContent && LiveStreamQuirks.isKnownHlsHost(url))
        val rawTs = (lower.contains(".ts") || lower.contains("/timeshift/")) && !effectiveHls
        // Make FFmpeg RECONNECT when a live server closes the HTTP connection (some drop the socket every
        // few seconds; without this mpv hits EOF → the app reconnects → a black/decoder-churn loop). NOT for
        // VOD/catch-up (isLiveContent=false) — those have a real end and must be allowed to finish.
        // `reconnect_at_eof` keeps a CONTINUOUS stream going across mid-stream EOFs — but it also reconnects
        // on the EOF that ends a *finite* HTTP response (an HLS .m3u8 playlist, a redirect, …), looping
        // forever during OPEN so the stream never starts. [streamLavfOptionsFor] owns that distinction and
        // is the single place stream-lavf-o is decided, for this path and the loadfile path alike.
        setPropertyString("stream-lavf-o", streamLavfOptionsFor(url, isLiveContent, effectiveHls))
        // Live latency (#72): how far ahead the demuxer buffers. Live streams honour the user's choice
        // (or the device budget default when Balanced); VOD always uses the budget default.
        val budgetReadahead = playerBudget?.readaheadSecs ?: "30"
        // "Pre-buffer" (F07): mpv's own pre-roll gate — hold the picture until the cache
        // holds N seconds, and do the same after an underrun (which is what "pause 3-4 s then play
        // makes it smooth" was doing by hand). Live only; 0 restores mpv's defaults.
        val prerollSecs = effectivePrerollSecs()
        // The readahead must be able to HOLD the pre-roll, or the gate could never be satisfied.
        val liveReadahead = effectiveLiveBufferSecs()?.let { maxOf(it, prerollSecs) }
        setPropertyString("demuxer-readahead-secs", if (isLiveContent) (liveReadahead?.toString() ?: budgetReadahead) else budgetReadahead)
        if (isLiveContent && prerollSecs > 0) {
            setPropertyString("cache-pause-initial", "yes")
            setPropertyString("cache-pause-wait", prerollSecs.toString())
        } else {
            setPropertyString("cache-pause-initial", "no")
            setPropertyString("cache-pause-wait", "1")
        }
        if (isLiveContent) {
            android.util.Log.i(TAG, "live_buffer preroll=${prerollSecs}s readahead=${liveReadahead ?: budgetReadahead} latency=${effectiveLiveBufferSecs() ?: -1}")
        }
        // Broken-timestamp live streams (some IPTV 4K feeds send non-increasing/duplicate PTS): mpv is
        // strict about PTS and drops nearly every frame ("Invalid video timestamp: X -> X"), which looks
        // like lag even though decode is fine (ExoPlayer tolerates it). The workaround is to stop timing
        // video against the audio master clock — derive it from the container FPS (correct-pts=no),
        // present each frame for its nominal duration (video-sync=desync) and never drop (framedrop=no).
        //
        // It is applied ONLY to feeds mpv has actually complained about ([noteBrokenTimestamp], learned in
        // LiveStreamQuirks), never to live as a class. Free-running video timing is by definition unsynced
        // from audio, so on a normal feed — whose PTS are perfectly sound — it slowly drifts the picture
        // away from the sound, which is exactly the "audio early or late" a provider's raw MPEG-TS showed.
        // A healthy stream therefore keeps mpv's accurate, audio-synced default, same as VOD.
        val brokenPts = isLiveContent && LiveStreamQuirks.hasBrokenTimestamps(url)
        setPropertyString("correct-pts", if (brokenPts) "no" else "yes")
        // On `vo=gpu` (the software/copy rescue paths) mpv DOES control scan-out, so it can align frames
        // to the display's vsync and resample audio to match — which is what removes 24/25 fps judder.
        // On the direct `mediacodec_embed` path it is meaningless: frames go to the decoder's surface and
        // the display picks its own cadence, so the setting there stays `audio` and Auto frame rate is the
        // only cure (F13).
        setPropertyString(
            "video-sync",
            when {
                brokenPts -> "desync"
                !useDirect() -> "display-resample"
                else -> "audio"
            },
        )
        setPropertyString("framedrop", if (brokenPts) "no" else "decoder+vo")
        val trim = rawTs && !item.forceFullProbe
        usedTrimmedProbe = trim
        // Error tolerance: this load's retry rung, or a stream already caught needing it this session.
        val tolerant = item.tolerantDemuxThisLoad || LiveStreamQuirks.needsTolerantDemux(url)
        if (!trim) {
            // Full probe — needed for HDR, complete track lists, and to open HLS/other live cleanly. Capping
            // the analyze time (even to 2.5 s) wedges mpv's HLS open on this hardware — it never reaches the
            // decoder — so the full probe is required. This is the ~3–5 s full-screen startup floor for HLS
            // (vs the instant ExoPlayer preview).
            // NOTE: probesize MUST be a valid value >= 32. "0" is rejected by mpv ("must be >= 32: 0") — it
            // does NOT mean "use default" on this build. FFmpeg's default is 5 MB (5000000), so we set that
            // explicitly. Without a valid probesize, a malformed MP4 (broken UDTA atoms) sends the demuxer
            // into a multi-GB seek + retry loop that eventually kills the video output (blank screen).
            setPropertyString("demuxer-lavf-probesize", "5000000")
            setPropertyString("demuxer-lavf-analyzeduration", "0")
            val demuxerOptions = demuxerLavfOptionsFor(trimmedRawTsProbe = false, tolerant = tolerant)
            setPropertyString("demuxer-lavf-o", demuxerOptions)
            LiveDiagnosticsLog.event(
                "mpv_open hls=$effectiveHls live=$isLiveContent probe=full tolerant=$tolerant " +
                    "demuxerLavfO=\"$demuxerOptions\" streamLavfO=\"${streamLavfOptionsFor(url, isLiveContent, effectiveHls)}\"",
            )
            return
        }
        setPropertyString("demuxer-lavf-probesize", "1000000")
        setPropertyString("demuxer-lavf-analyzeduration", "1.0") // ~1s keeps HDR/colorspace detection safe
        val trimmedOptions = demuxerLavfOptionsFor(trimmedRawTsProbe = true, tolerant = tolerant)
        setPropertyString("demuxer-lavf-o", trimmedOptions)
        LiveDiagnosticsLog.event(
            "mpv_open hls=$effectiveHls live=$isLiveContent probe=trimmed tolerant=$tolerant " +
                "demuxerLavfO=\"$trimmedOptions\" " +
                "streamLavfO=\"${streamLavfOptionsFor(url, isLiveContent, effectiveHls)}\"",
        )
    }

    /** Reload the current item at its position (used when a setting change needs the chain re-inited). */
    private fun reloadCurrentInPlace() {
        val url = currentUrl ?: return
        val gen = loadGeneration
        scope.launch {
            if (gen != loadGeneration) return@launch
            loadUrl(url, currentMetaSnapshot(), isLiveContent, if (isLiveContent) 0L else _position.value, resetRetries = false)
        }
    }

    // Video Player Settings — cached so ensureInit can apply them as mpv options, and the observers
    // below apply changes live to a running player.
    private var hwDecoding = true

    /** Settings → Deinterlacing (Off / Auto). See [applyDeinterlace] for where it can take effect. */
    private var deinterlace = false
    // Escape-hatch toggle: when off, no live fps/bitrate measuring runs at all (declared values only).
    private var measuredStreamStats = true
    // Live latency (#72): demuxer readahead seconds for live streams; null = keep the device budget
    // default (Balanced). Applied per-load in applyProbeProfile (live only, so VOD is never affected).
    @Volatile private var liveBufferSecs: Int? = null
    // "Pre-buffer" (F07): the global choice, plus the per-playlist override the current item
    // was opened with (null = follow the global one). Live only; applied per-load in applyProbeProfile.
    @Volatile private var livePrerollSecs: Int = 0
    @Volatile private var prerollOverrideSecs: Int? = null
    private fun effectivePrerollSecs(): Int = prerollOverrideSecs ?: livePrerollSecs
    /** This playlist's Live latency override, or null to follow the global setting. Wrapped because the
     *  override's own value may be null (Balanced) — see [LiveBuffer.Override]. */
    @Volatile private var liveBufferOverride: LiveBuffer.Override? = null
    private fun effectiveLiveBufferSecs(): Int? = liveBufferOverride?.secs ?: liveBufferSecs
    // The global "Movies & Series player" setting: which engine an item starts on and whether the other
    // may rescue it. Default mpv-first — see SettingsRepository.vodEnginePreference.
    @Volatile private var vodEngine = tv.own.owntv.core.player.EnginePreference.MPV_FIRST
    // The preference in force for the item currently loaded, after a per-item pin and the HUD toggle
    // have had their say. Read by the two auto-fallback paths, which is why it is resolved once at load
    // time rather than recomputed from the setting: the setting can change mid-film, and an item that
    // started under the old one must keep the fallback rules it started with.
    @Volatile private var itemEngine = tv.own.owntv.core.player.EnginePreference.MPV_FIRST
    // Per-item engine pins from the gear toggle (VOD counterpart of Live's compatibility mode) —
    // eagerly mirrored so loadUrl can consult them synchronously.
    @Volatile private var vodPinnedMpv: Set<String> = emptySet()
    @Volatile private var vodPinnedExo: Set<String> = emptySet()
    private var surroundMode = SurroundMode.AUTO // see SettingsRepository.surroundMode (#25)
    private var autoPlayNext = true
    // Subtitle appearance (#96). While subStyleOn is false NOTHING here is pushed to mpv, so its own
    // defaults (and any ASS styling a file carries) stay exactly as they are today — and each option
    // left on its own "Default" value is likewise never pushed.
    private var subStyleOn = false
    private var subScale = SubtitleStyle.SCALE_DEFAULT.toDouble()
    private var subFont: AppFontFamily? = null
    private var subColorHex = SubtitleStyle.COLOR_DEFAULT
    private var subPosition = SubtitleStyle.Position.DEFAULT
    private var subBgOpacity = SubtitleStyle.OPACITY_DEFAULT
    private val _audioMixEnabled = MutableStateFlow(false)
    val audioMixEnabled: StateFlow<Boolean> = _audioMixEnabled.asStateFlow()
    private var audioMixOriginalAid: Int? = null
    private var audioMixExternalAid: Int? = null
    private var audioMixWatchdogJob: Job? = null
    private var gstAudioMixEngine: GStreamerAudioMixEngine? = null
    private var audioMixLastPts = Double.NaN
    private var audioMixStallCount = 0

    private var audioDelaySec = 0.0
    private var baseAudioDelayMs = 0 // the Settings audio-delay; each new file resets the in-player nudge to it
    private val _audioDelayMs = MutableStateFlow(0)
    /** Effective audio delay in ms (Settings default + the in-player A/V-sync nudge). */
    val audioDelayMs: StateFlow<Int> = _audioDelayMs.asStateFlow()
    private val _audioDelayRemembered = MutableStateFlow(false)
    /** Whether the current item has its own remembered A/V-sync offset (playback_prefs, DB v35). */
    val audioDelayRemembered: StateFlow<Boolean> = _audioDelayRemembered.asStateFlow()
    private val _subDelayMs = MutableStateFlow(0)
    /** Subtitle-timing offset (ms) for the active subtitle (subtitle plan §8). Positive = shown later. */
    val subDelayMs: StateFlow<Int> = _subDelayMs.asStateFlow()
    private var exoSubDelayJob: Job? = null
    /** Set by the subtitle layer (§8.4): fired when the ACTIVE subtitle changes so its remembered
     *  timing can be applied. Identity: "path:&lt;file&gt;" external, "emb:&lt;ordinal&gt;:&lt;lang&gt;" embedded, null off. */
    var onActiveSubtitleChanged: ((identity: String?) -> Unit)? = null
    /** Set by the subtitle layer: fired after each USER timing change with the value to persist. */
    var onSubtitleDelayUserChange: ((offsetMs: Int) -> Unit)? = null
    private var prefAudioLang = ""
    private var prefSubLang = ""
    private var defaultZoom = ZoomMode.FIT

    /** Settings → the volume a newly picked item starts at, before any per-item override. */
    private var defaultVolume = 100

    /** Settings → Seek step: how far the HUD's rewind/forward and the seek bar's ◀/▶ jump. */
    private val _seekStepMs = MutableStateFlow(
        tv.own.owntv.core.settings.SeekSteps.DEFAULT_SEEK_STEP_SEC * 1000L,
    )
    val seekStepMs: StateFlow<Long> = _seekStepMs.asStateFlow()

    /** Settings → Video player → Auto frame rate. mpv doesn't act on it (the Compose surface does), but
     *  the ExoPlayer handoff engine has its own frame-rate mechanism that must follow the same switch. */
    private var autoFrameRate = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * All mpv commands/property writes run on this single worker thread, never on the UI thread.
     * libmpv calls are synchronous and can block for seconds while the core is stuck in a stalling
     * network read (flaky live streams) — issuing them from the main thread caused ANRs ("Input
     * dispatching timed out"). A single thread keeps the original call order.
     */
    private val mpvExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-cmd").apply { isDaemon = true }
    }

    /**
     * Rule 2 of the class threading contract: the surface entry points must run on the main thread.
     * Debug builds fail loudly; release builds log and carry on rather than killing playback over it.
     */
    private fun assertMainThread(what: String) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        val message = "$what must be called on the main thread, was ${Thread.currentThread().name}"
        if (tv.own.owntv.core.CoreBuildInfo.debug) error(message) else android.util.Log.e(TAG, message)
    }

    private fun mpvAsync(block: MPVLib.() -> Unit) {
        val m = mpv ?: return
        mpvExecutor.execute { runCatching { m.block() } }
    }

    private fun markActiveFile(active: Boolean) {
        mpvHasActiveFile.set(active)
    }

    private fun MPVLib.incrementPendingStopCounter(reason: String): Boolean {
        if (!mpvHasActiveFile.get()) return false
        if (pendingStopEndFiles.credit() == MAX_PENDING_STOP_CREDITS) {
            android.util.Log.w(TAG, "pendingStopEndFiles at cap $MAX_PENDING_STOP_CREDITS ($reason)")
        }
        return true
    }

    private fun MPVLib.rollbackPendingStopCounter(reason: String) {
        pendingStopEndFiles.rollback()
    }

    private fun MPVLib.loadfileWithStopClassification(url: String, reason: String) {
        val counted = incrementPendingStopCounter(reason)
        try {
            // A document has no path for mpv to open, so it is handed an already-open descriptor
            // instead. Everything else passes through untouched.
            command(arrayOf("loadfile", contentFds.playable(url)))
            markActiveFile(true)
        } catch (t: Throwable) {
            if (counted) rollbackPendingStopCounter(reason)
            throw t
        }
    }

    private fun MPVLib.stopWithStopClassification(reason: String) {
        val counted = incrementPendingStopCounter(reason)
        try {
            command(arrayOf("stop"))
            markActiveFile(false)
        } catch (t: Throwable) {
            if (counted) rollbackPendingStopCounter(reason)
            throw t
        }
    }

    private fun consumePendingStopEndFile(): Boolean = pendingStopEndFiles.consume()

    init {
        // Track the HDR setting; apply it live and re-apply on each load via ensureInit.
        settings.hdrEnabled.onEach { enabled ->
            hdrHint = enabled
            if (initialized) mpvAsync { setPropertyString("target-colorspace-hint", if (enabled) "yes" else "no") }
        }.launchIn(scope)
        settings.hwDecoding.onEach { on ->
            hwDecoding = on
            if (initialized) mpvAsync { applyRenderConfig() }
        }.launchIn(scope)
        settings.deinterlace.onEach { on ->
            deinterlace = on
            if (initialized) mpvAsync { applyDeinterlace() }
        }.launchIn(scope)
        settings.surroundMode.onEach { mode ->
            val changed = surroundMode != mode
            surroundMode = mode
            // Touching the setting is the user asking the audio output for another chance.
            if (changed) AudioOutputPolicy.clearLatch()
            // A reload re-inits the audio chain so the new channel layout takes effect on the playing stream.
            if (initialized) {
                mpvAsync {
                    val sur = multichannelAllowed()
                    setPropertyString("audio-channels", audioChannelsValue())
                    setPropertyString("audio-format", if (sur) "s16" else "")
                    setPropertyString("audio-samplerate", if (sur) "48000" else "0")
                }
                reloadCurrentInPlace()
            }
        }.launchIn(scope)
        settings.autoPlayNext.onEach { autoPlayNext = it }.launchIn(scope)
        // Applies from the next VOD load.
        settings.vodEnginePreference.onEach { vodEngine = it }.launchIn(scope)
        settings.measuredStreamStats.onEach { on ->
            measuredStreamStats = on
            if (!on) exoEngine?.setBitrateTrackingEnabled(false) // turning it off stops any in-flight measuring now
        }.launchIn(scope)
        settings.livePrerollSecs.onEach { livePrerollSecs = it } // applies from the next live open
            .launchIn(scope)
        settings.liveBufferSeconds.onEach {
            liveBufferSecs = it
            // Re-apply live to a playing live channel; VOD is untouched. Next-open covers the rest.
            // A playlist override outranks the global value, so a global change is not this tune's to apply.
            if (initialized && isLiveContent && liveBufferOverride == null) {
                val budgetReadahead = playerBudget?.readaheadSecs ?: "30"
                // Keep the pre-roll floor from [applyProbeProfile]: a readahead below `cache-pause-wait`
                // could never satisfy the gate.
                val readahead = effectiveLiveBufferSecs()?.let { secs -> maxOf(secs, effectivePrerollSecs()).toString() }
                    ?: budgetReadahead
                mpvAsync { setPropertyString("demuxer-readahead-secs", readahead) }
            }
        }.launchIn(scope)
        vodEngineStore.mpvUrls.onEach { vodPinnedMpv = it }.launchIn(scope)
        vodEngineStore.exoUrls.onEach { vodPinnedExo = it }.launchIn(scope)
        // Subtitle appearance (#96). Moving anything back to "Default" — or turning the master toggle
        // OFF — has to actively restore mpv's own value: the properties were already set on the
        // running instance, so simply skipping the write would leave the last custom look on screen
        // until the next channel/file load.
        settings.subtitleStyleEnabled.onEach { on ->
            subStyleOn = on
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.subtitleScaleMpv.onEach { s ->
            subScale = s.toDouble()
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.subtitleFont.onEach { font ->
            subFont = font
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.subtitleColor.onEach { hex ->
            subColorHex = hex
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.subtitlePosition.onEach { position ->
            subPosition = position
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.subtitleBgOpacity.onEach { pct ->
            subBgOpacity = pct
            if (initialized) mpvAsync { applySubtitleStyle() }
        }.launchIn(scope)
        settings.audioDelayMs.onEach { ms ->
            baseAudioDelayMs = ms // the Settings default each new file resets to
            applyAudioDelay(ms)
        }.launchIn(scope)
        // Clearing a preferred language has to be written through too. The property is already set on the
        // running core, so skipping the write on a blank value left the old preference in force — turning
        // "Preferred audio language" back to none did nothing until the app restarted. Empty is mpv's own
        // "no preference", which is exactly what a cleared setting means.
        settings.preferredAudioLang.onEach { lang ->
            prefAudioLang = lang
            if (initialized) mpvAsync { setPropertyString("alang", lang) }
        }.launchIn(scope)
        settings.preferredSubLang.onEach { lang ->
            prefSubLang = lang
            if (initialized) mpvAsync {
                setPropertyString("slang", lang)
                setPropertyString("subs-with-matching-audio", if (lang.isBlank()) "no" else "yes")
            }
        }.launchIn(scope)
        settings.defaultZoom.onEach { name ->
            defaultZoom = runCatching { ZoomMode.valueOf(name) }.getOrDefault(ZoomMode.FIT)
        }.launchIn(scope)
        settings.defaultVolume.onEach { defaultVolume = it }.launchIn(scope)
        settings.seekStepSec.onEach { _seekStepMs.value = it * 1000L }.launchIn(scope)
        // Auto frame rate drives the display-mode switch from the Compose surface; ExoPlayer has a
        // SECOND mechanism (Surface.setFrameRate) that must follow the same switch, so the value is
        // tracked here and handed to the handoff engine in [startExo].
        settings.autoFrameRate.onEach { autoFrameRate = it }.launchIn(scope)
        // Subtitle overlay is fed by OBSERVING "sub-text" (see eventProperty) — not polling. The old
        // 250 ms getPropertyString poll logged a "property unavailable" error 4×/sec whenever no line
        // was on screen, flooding logcat and burning a cross-thread call the whole time.
    }
    // Bumped on every load/stop so stale work can tell it's been superseded: the end-of-file error
    // check, and queued loadfile commands (fast preview scrolling queues a burst — only the newest
    // may run, or a slow provider makes the worker grind through dead loads). Volatile: written on
    // the main thread, read on the mpv-cmd worker.
    /** Cleared on every load, and only on a genuinely new item, respectively — see [LoadState] and
     *  [ItemState]. Two assignments in [loadUrl] replace what used to be two lists of hand-written
     *  clears, which is what stops a field being left out of one of them. */
    @Volatile private var load = LoadState()
    @Volatile private var item = ItemState()

    @Volatile private var loadGeneration = 0
    // App-issued loadfile/stop commands can leave a cleanup END_FILE behind. Track those separately
    // so mpv's event thread can classify them as STOP instead of startup failure or reconnect.
    private val pendingStopEndFiles = PendingStopCredits(MAX_PENDING_STOP_CREDITS)
    private val mpvHasActiveFile = AtomicBoolean(false)
    private var errorCheckJob: Job? = null
    private var videoCheckJob: Job? = null
    // Live silent-freeze watchdog (see companion constants): polls time-pos while a live stream is playing
    // and reconnects when it stops advancing. item.liveStallReconnects is the consecutive-failure budget; it
    // resets to 0 once playback is healthy again (or on a genuinely new item).
    private var liveStallJob: Job? = null

    /** The live no-progress watchdog's cadence for THIS device — see the companion constants for the
     *  two tiers and the detection time each produces. `lowSpec` is the same test [PlayerBudget] uses. */
    private val lowSpecDevice: Boolean get() = playerBudget?.lowSpec == true
    private val liveStallPollMs: Long get() = if (lowSpecDevice) LIVE_STALL_POLL_LOW_SPEC_MS else LIVE_STALL_POLL_MS
    private val liveStallLimit: Int get() = if (lowSpecDevice) LIVE_STALL_LIMIT_LOW_SPEC else LIVE_STALL_LIMIT
    // Fast-zap probe trimming (live only). usedTrimmedProbe = this load used a trimmed probe;
    // item.forceFullProbe = a trimmed load came back with no audio, so re-probe fully (the safety net).
    @Volatile private var usedTrimmedProbe = false

    private val _nav = MutableStateFlow(NavState(false, false))
    val nav: StateFlow<NavState> = _nav.asStateFlow()

    // Title of the next queued item (in-season next episode), for the HUD's "Next episode in Ns" card.
    // Null when there's no next item (single movie, or the season's last episode).
    private val _nextUpTitle = MutableStateFlow<String?>(null)
    val nextUpTitle: StateFlow<String?> = _nextUpTitle.asStateFlow()


    /** HUD "Cancel" on the next-episode countdown: skip the automatic advance for the current item. */
    fun cancelAutoNext() { item.autoNextCancelled = true }

    // Emitted when the LAST item of an episode queue finishes naturally and auto-play is on, so the
    // series ViewModel can continue into the next season (it has the full series; the player only has
    // the current season's queue). Within-season advance is handled by the player itself.
    private val _queueEnded = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueEnded: kotlinx.coroutines.flow.SharedFlow<Unit> = _queueEnded

    // Emitted with the new playlist index when the player ITSELF advances within an episode queue
    // (auto-next / HUD prev-next). The series ViewModel initiated neither, but owns per-episode state
    // keyed to the playing item — the subtitle search/restore context (subtitle plan Phase 5) — so it
    // must be told. Index-based on purpose: Stalker queue items mint fresh URLs per load, so the
    // URL-matching used elsewhere can't identify the new episode.
    private val _queueItemChanged = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val queueItemChanged: kotlinx.coroutines.flow.SharedFlow<Int> = _queueItemChanged

    // Emitted when a catch-up ARCHIVE programme plays to its end with no queue behind it and auto-play
    // is on. What comes next lives in the guide, which the player knows nothing about, so LiveViewModel
    // decides. Deliberately NOT [queueEnded]: that one means "a season ran out" and SeriesViewModel acts
    // on it, so borrowing it here would make a finished catch-up programme start an episode.
    private val _archiveEnded = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val archiveEnded: kotlinx.coroutines.flow.SharedFlow<Unit> = _archiveEnded

    var currentTitle: String? = null
        private set
    var currentSubtitle: String? = null
        private set
    var currentYear: String? = null
        private set
    var currentLogoUrl: String? = null
        private set
    var isLiveContent: Boolean = false
        private set

    // Reactive copy of the current item's metadata so Compose recomposes the HUD's title / channel
    // card the instant a new stream loads — channel zapping changes the plain vars above, but a plain
    // var isn't observed, so the "now watching" card showed the previous channel's name.
    private val _currentMeta = MutableStateFlow(MediaMeta())
    val currentMeta: StateFlow<MediaMeta> = _currentMeta.asStateFlow()

    private var preMuteVolume = 100

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    // How far ahead of the playhead the engine holds data, for the HUD's buffer ghost. mpv reports a
    // cache DURATION (seconds ahead of the playhead), Exo an absolute buffered position; both are
    // normalised to an absolute media time here so the bar has one thing to draw.
    private val _bufferedMs = MutableStateFlow(0L)
    val bufferedMs: StateFlow<Long> = _bufferedMs.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _error = MutableStateFlow<PlaybackFailure?>(null)
    val error: StateFlow<PlaybackFailure?> = _error.asStateFlow()
    private val _errorInfo = MutableStateFlow<ErrorInfo?>(null)
    val errorInfo: StateFlow<ErrorInfo?> = _errorInfo.asStateFlow()

    // Captures mpv's own error output (msg-level=warn, set even in release) so the real failure reason is
    // available to show the user. Keeps error/fatal lines, plus warn lines that look like an actual failure
    // (HTTP codes, "failed", "unrecognized", etc.) — ignoring benign per-frame warnings.
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            if (isLiveContent && BROKEN_PTS_RX.containsMatchIn(t)) noteBrokenTimestamp()
            val keep = level <= 20 /* error/fatal */ || (level <= 30 /* warn */ && FAILURE_RX.containsMatchIn(t))
            if (!keep) return
            val safe = HttpClient.redactUrl("${prefix.trim().trimEnd(':')}: $t")
            // The rolling diagnostic file follows a live stream across the ExoPlayer -> mpv fallback.
            // mpv can include the full stream URL here, so sanitize before storing or displaying it.
            LiveDiagnosticsLog.event("mpv level=$level $safe")
            // "Failed to open / loading failed" is the CONSEQUENCE — don't let it overwrite a more specific
            // cause already captured for this load (e.g. "HTTP error 400", an SSL error, a codec message).
            if (load.lastMpvError != null && GENERIC_FAIL_RX.containsMatchIn(t)) return
            load.lastMpvError = safe
        }
    }


    /**
     * mpv complained about this live feed's timestamps. Past [BROKEN_PTS_HITS] that is the pathological
     * feed, not a discontinuity: switch to free-running video timing right away (both properties apply at
     * runtime) and remember the stream so its next open starts that way, `correct-pts` included — that one
     * only takes effect at decoder init, so this load keeps the accurate-PTS decode path.
     */
    private fun noteBrokenTimestamp() {
        if (load.brokenPtsHandled || ++load.brokenPtsHits < BROKEN_PTS_HITS) return
        load.brokenPtsHandled = true
        currentUrl?.let { LiveStreamQuirks.rememberBrokenTimestamps(it) }
        LiveDiagnosticsLog.event("mpv reported ${load.brokenPtsHits} broken video timestamps — switching to free-running video timing")
        mpvAsync {
            setPropertyString("video-sync", "desync")
            setPropertyString("framedrop", "no")
        }
    }

    /** Semantic media details for the playback error renderer. */
    private fun mediaSpec(): MediaSpec? {
        val codec = load.currentVideoCodec?.uppercase()
        val resolution = if (load.currentWidthPx > 0 && load.currentHeightPx > 0) "${load.currentWidthPx}x${load.currentHeightPx}" else null
        val decoder = load.currentHwdec?.let {
            when {
                it.contains("mediacodec", ignoreCase = true) -> DecoderSpec.Hardware(direct = _directRender.value)
                it == "no" -> DecoderSpec.Software(gpu = !_directRender.value)
                else -> DecoderSpec.Named(it, hardware = true, direct = _directRender.value)
            }
        }
        return MediaSpec(codec = codec, resolution = resolution, decoder = decoder)
            .takeIf { it.codec != null || it.resolution != null || it.decoder != null }
    }
    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume.asStateFlow()
    // Audio Mode: video decoder/output stopped, audio kept alive (mpv `vid=no`, or ExoPlayer surface
    // released). Toggled by enterAudioOnly()/exitAudioOnly(); the shell drives it from PlayerMode.AUDIO.
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _audioOnlyMedia = MutableStateFlow(false)
    /** The loaded item has no video track of its own — see [PlaybackEngine.audioOnlyMedia]. Set once the
     *  file is open and audio is genuinely progressing, so a stream that is merely slow to show its first
     *  frame is never labelled audio-only. Cleared by every new load. */
    val audioOnlyMedia: StateFlow<Boolean> = _audioOnlyMedia.asStateFlow()
    private val _videoRes = MutableStateFlow<String?>(null)
    val videoRes: StateFlow<String?> = _videoRes.asStateFlow()

    /** The video's frame rate (e.g. 23.976) — used to ask the display to match it, killing the 3:2
     *  pulldown judder you get playing 24fps content on a fixed 60Hz panel. Null until known. */
    private val _videoFps = MutableStateFlow<Float?>(null)
    val videoFps: StateFlow<Float?> = _videoFps.asStateFlow()

    /** Video aspect ratio (w/h) — the surface view sizes itself with this in direct mode. */
    private val _videoAspect = MutableStateFlow<Float?>(null)
    val videoAspect: StateFlow<Float?> = _videoAspect.asStateFlow()

    /** Native video pixel size (w, h) — the surface view uses it for the Original (1:1) zoom mode. */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    /** Up-to-4 mini stream chips for the player top bar: aspect · resolution · fps · audio. */
    private val _streamChips = MutableStateFlow<List<String>>(emptyList())
    val streamChips: StateFlow<List<String>> = _streamChips.asStateFlow()

    /** First top-bar chip: which engine is decoding right now ("MPV", or "EXO" during an ExoPlayer
     *  handoff/fallback/preferred VOD playback). */
    private val _engineChip = MutableStateFlow<String?>("MPV")
    val engineChip: StateFlow<String?> = _engineChip.asStateFlow()

    /**
     * The renderer's measured output rate right now, or null if it can't be trusted — the core is gone,
     * playback is paused/seeking (both distort the measurement), or mpv has no estimate yet.
     */
    private suspend fun readVfFps(): Float? {
        val out = kotlinx.coroutines.CompletableDeferred<Float?>()
        mpvAsync {
            val usable = getPropertyString("pause") != "yes" && getPropertyString("seeking") != "yes"
            out.complete(if (usable) getPropertyString("estimated-vf-fps")?.toFloatOrNull() else null)
        }
        return kotlinx.coroutines.withTimeoutOrNull(1_000) { out.await() }?.takeIf { it > 1f }
    }

    /**
     * Read from libmpv on [mpvExecutor] and suspend for the answer, instead of blocking the caller's
     * thread across the JNI call (A-F1/A-F2). Same shape as [readVfFps], generalised.
     *
     * Null when the core is gone, the read threw, or it did not come back promptly — a statistic is
     * never worth a stalled UI, and every caller here has a sane "unknown" rendering.
     */
    private suspend fun <T> readOnMpv(block: (MPVLib) -> T): T? {
        val m = mpv ?: return null
        val out = kotlinx.coroutines.CompletableDeferred<T?>()
        mpvExecutor.execute { out.complete(runCatching { block(m) }.getOrNull()) }
        return kotlinx.coroutines.withTimeoutOrNull(MPV_READ_TIMEOUT_MS) { out.await() }
    }

    /** One property, read off the caller's thread. See [readOnMpv]. */
    private suspend fun readProperty(name: String): String? = readOnMpv { it.getPropertyString(name) }

    private fun updateStreamChips() {
        val w = load.currentWidthPx; val h = load.currentHeightPx
        if (w <= 0 || h <= 0) { _streamChips.value = emptyList(); return }
        val base = ArrayList<String>(5)
        aspectLabel(w, h)?.let { base += it }
        _videoRes.value?.let { base += it }
        val knownFps = _videoFps.value
        val m = mpv
        // mpv stays alive (just stopped/surfaceless) during a handoff, so check exoActive, not m == null.
        if (exoActive || m == null) {
            (knownFps ?: exoEngine?.currentFps())?.let { if (it > 0) base += "${Math.round(it)} FPS" }
            exoEngine?.currentBitrateMbps()?.let { base += "%.1f Mbps".format(Locale.ROOT, it) }
            _streamChips.value = base
            return
        }
        // Synchronous libmpv reads can block for seconds while the core is stuck in a stalling network
        // read (same reason all writes go through mpvExecutor) — never issue them from the UI thread.
        // runCatching also covers a rejected execute() after release() shut the executor down.
        runCatching {
            mpvExecutor.execute {
                val chips = ArrayList<String>(5).apply { addAll(base) }
                runCatching {
                    (knownFps ?: m.getPropertyString("container-fps")?.toFloatOrNull())
                        ?.let { if (it > 0) chips += "${Math.round(it)} FPS" }
                    m.getPropertyString("video-bitrate")?.toLongOrNull()
                        ?.let { if (it > 0) chips += "%.1f Mbps".format(Locale.ROOT, it / 1_000_000.0) }
                    when (m.getPropertyInt("audio-params/channel-count")) {
                        1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> null
                    }?.let { chips += it }
                }
                _streamChips.value = chips
            }
        }
    }
    private fun aspectLabel(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val r = w.toFloat() / h
        return when {
            r in 1.72f..1.82f -> "16:9"
            r in 1.28f..1.40f -> "4:3"
            r >= 2.15f -> "21:9"
            r in 1.55f..1.66f -> "16:10"
            else -> "%.2f:1".format(Locale.ROOT, r)
        }
    }

    /** Current subtitle line(s) for the Compose overlay (direct mode only; null = nothing showing). */
    private val _subText = MutableStateFlow<String?>(null)
    val subText: StateFlow<String?> = _subText.asStateFlow()
    private val _audioCount = MutableStateFlow(0)
    val audioCount: StateFlow<Int> = _audioCount.asStateFlow()
    private val _subCount = MutableStateFlow(0)
    val subCount: StateFlow<Int> = _subCount.asStateFlow()
    private val _zoomMode = MutableStateFlow(ZoomMode.FIT)
    val zoomMode: StateFlow<ZoomMode> = _zoomMode.asStateFlow()
    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    val currentMediaUrl: String? get() = currentUrl

    /**
     * Stable identity of the loaded item ([MediaMeta.contentKey]); null when nothing is loaded or the
     * item has no stable key (a row with no `remoteId` — see [tv.own.owntv.core.player.enginePinKey]).
     *
     * Resume-progress owners compare against THIS, not [currentMediaUrl]: a Stalker playback URL is
     * minted per play and never equals the stored cmd, so a URL comparison meant those items never
     * saved a position at all.
     */
    val currentMediaContentKey: String? get() = if (currentUrl != null) currentContentKey else null

    // --- ExoPlayer image-subtitle handoff -----------------------------------------------------
    // ExoPlayer takes over playback ONLY for a VOD with an image subtitle selected. mpv is stopped first
    // (so the provider sees one connection), and ExoPlayer's state is mirrored into the flows above so the
    // HUD is unchanged. All Exo access is on the main scope (its application thread).
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    private var exoEngine: ExoSubtitleEngine? = null
    @Volatile private var exoActive = false
    private var mpvFailureBeforeFallback: PlaybackFailure? = null
    // One-shot: the next loadUrl must recreate the SurfaceView even for ≤1080p content — set by the
    // manual engine toggle, where the outgoing engine's MediaCodec session leaves the surface dirty.
    @Volatile private var forceSurfaceResetNextLoad = false
    private var exoTickJob: Job? = null
    private var pendingImageSub: TrackOption? = null
    // External subs attached during THIS item's playback (either engine). Re-seeded into the incoming
    // engine on a manual engine toggle so they stay listed and the active one stays active (§10).
    private val sessionExternalSubs = ArrayList<ExternalSub>()
    private val freezeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _exoCues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    /** Bitmap/text subtitle cues from the ExoPlayer handoff — drawn by the SubtitleView overlay. */
    val exoCues: StateFlow<List<androidx.media3.common.text.Cue>> = _exoCues.asStateFlow()

    private val _freezeFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    /** A snapshot of the last mpv frame, shown over the surface during the mpv→ExoPlayer swap so the
     *  ~second-long decoder switch doesn't flash black. Cleared on ExoPlayer's first rendered frame. */
    val freezeFrame: StateFlow<android.graphics.Bitmap?> = _freezeFrame.asStateFlow()

    /**
     * Replace the freeze frame, recycling the one it replaces (A-F14).
     *
     * The bitmap is an `ARGB_8888` captured at up to [FREEZE_MAX_W] wide (~2 MB; it used to be captured
     * full-size, ~33 MB at 4K). It was recycled only on the PixelCopy failure branch, so every
     * *successful* handoff left the previous one to the garbage collector, and a session of engine
     * switches on a 4K film walked the heap up a step at a time.
     *
     * Recycling here is safe because the only consumer draws it from `freezeFrame` and is unmounted before
     * the value changes: the swap sets it once, and it is cleared when ExoPlayer renders its first frame
     * or the handoff is torn down.
     */
    private fun setFreezeFrame(next: android.graphics.Bitmap?) {
        val previous = _freezeFrame.value
        _freezeFrame.value = next
        if (previous != null && previous !== next && !previous.isRecycled) {
            runCatching { previous.recycle() }
        }
    }

    private val _surfaceResetToken = MutableStateFlow(0)
    /** Bumped to force the video SurfaceView to be recreated. The Realtek decoder throws 0x80001000 when a
     *  new 4K-class MediaCodec is bound to the SAME Surface a previous 4K-class session used (its VPU
     *  buffer queue stays dirty even after release) — so a back-to-back >1080p load gets a FRESH Surface. */
    val surfaceResetToken: StateFlow<Int> = _surfaceResetToken.asStateFlow()

    /** True while ExoPlayer (not mpv) owns playback for an image-subtitle VOD. */
    val isExoActive: Boolean get() = exoActive

    private val _exoActiveState = MutableStateFlow(false)
    /** Reactive form of [isExoActive]. The UI mounts the SubtitleView overlay ONLY while this is true,
     *  so during normal mpv playback nothing is composited over the video SurfaceView — otherwise the
     *  SurfaceView loses its hardware-overlay / direct scan-out path and 4K stutters to a slideshow. */
    val exoActiveState: StateFlow<Boolean> = _exoActiveState.asStateFlow()

    private val exoCallbacks = object : ExoSubtitleEngine.Callbacks {
        override fun onPlayingChanged(playing: Boolean) { _isPlaying.value = playing }
        override fun onBuffering(buffering: Boolean) { _buffering.value = buffering }
        override fun onVideoSize(width: Int, height: Int) {
            load.currentWidthPx = width; load.currentHeightPx = height
            if (height > 0) consecutiveHardResets = 0 // successful decode — reset thrash guard
            // Remember heaviness like the mpv path does, so the NEXT load (autoplay/toggle back to mpv)
            // knows a >1080p decoder session just ran on this surface and recreates it first.
            if (height > 0) lastVideoHeightPx = height
            updateAspect()
            _videoRes.value = resolutionLabel(height, width)
            updateStreamChips() // onVideoFps may never fire; don't wait on it for resolution/measured fps
            // A measured fps needs a rendered-frame window, so retry once one can have elapsed. Waiting on
            // the shared beat rather than a timer of its own: while Exo is active that beat is already
            // running for the position readout, so this costs no wake-up at all (P-F1). Two beats, not
            // one — the first may arrive immediately from the replay cache.
            val gen = loadGeneration
            scope.launch {
                PlayerHeartbeat.beat.drop(1).first()
                if (gen == loadGeneration && exoActive) updateStreamChips()
            }
        }
        override fun onPositionDuration(positionMs: Long, durationMs: Long, bufferedMs: Long) {
            _position.value = positionMs
            if (durationMs > 0) _duration.value = durationMs
            _bufferedMs.value = bufferedMs
        }
        override fun onFirstFrame() {
            _buffering.value = false; setFreezeFrame(null)
            // Exo owns this VOD as an engine (fallback/preferred): re-list previously downloaded subs
            // (§9), same as mpv's FILE_LOADED hook. Re-fires after each side-load re-prepare, but the
            // restore path no-ops when there's nothing new to attach.
            if (item.exoVodFallback && !isLiveContent) onVodFileLoaded?.invoke()
        }
        override fun onCues(cues: List<androidx.media3.common.text.Cue>) { _exoCues.value = cues }
        override fun onAudioTracks(tracks: List<TrackOption>) {
            _audioTrackList.value = tracks
            _audioCount.value = tracks.size
        }
        override fun onTextTracks(tracks: List<TrackOption>) {
            // Only when Exo owns playback as a VOD ENGINE: mpv never probed the file, so its subtitle
            // list is empty — without this the HUD sub menu shows nothing (image subs included, which
            // ExoPlayer renders natively). During the image-sub handoff mpv's own list stays.
            if (!item.exoVodFallback) return
            _subTrackList.value = tracks
            _subCount.value = tracks.size
        }
        override fun onVideoFps(fps: Float) { _videoFps.value = fps; updateStreamChips() }
        // ExoPlayer knows this straight from the track list, so a music-only VOD played on the Exo engine
        // (preferred-for-VOD, or an mpv fallback) is labelled as fast as one played on mpv.
        override fun onAudioOnlyMedia(audioOnly: Boolean) { if (!_audioOnly.value) _audioOnlyMedia.value = audioOnly }
        override fun onError(failure: PlaybackFailure) {
            // Image-sub handoff → give playback back to mpv. Engine chains: Exo-as-primary falls back to
            // mpv; Exo-as-fallback means mpv already failed this item, so reloading it there would just
            // loop — surface the combined both-engines error.
            when {
                // "Only ExoPlayer": the user ruled mpv out, so a terminal Exo failure is the answer —
                // surface it as the single-engine error it is rather than the both-engines one.
                item.exoVodFallback && item.exoPrimaryThisItem && !itemEngine.allowsHandover ->
                    scope.launch { failExoOnly(failure) }
                item.exoVodFallback && item.exoPrimaryThisItem -> scope.launch { fallbackToMpvVod(failure) }
                item.exoVodFallback -> scope.launch { failBothEngines(failure) }
                else -> scope.launch { revertToMpv(error = failure) }
            }
        }
        override fun onEnded() { scope.launch { onExoEnded() } }
    }

    /** ExoPlayer can decode this VOD's active audio? Always-safe codecs (AAC/AC3/…) pass immediately;
     *  for others (DTS/TrueHD) we check whether THIS device actually has a hardware/software decoder for
     *  it — many TVs do — and only block when it genuinely can't, so we don't fail+bounce. Unknown → try. */
    private fun audioCodecSafeForExo(): Boolean {
        val sel = _audioTrackList.value.firstOrNull { it.selected } ?: _audioTrackList.value.firstOrNull()
        val codec = sel?.codec?.lowercase() ?: return true
        if (EXO_SAFE_AUDIO_CODECS.any { codec.startsWith(it) }) {
            android.util.Log.i(TAG, "Exo codec gate: audio codec='$codec' → safe (allowlist)")
            return true
        }
        val mime = audioMimeFor(codec)
        val ok = mime != null && deviceHasAudioDecoder(mime)
        android.util.Log.i(TAG, "Exo codec gate: audio codec='$codec' mime=$mime deviceDecoder=$ok")
        return ok
    }

    /** Map an mpv/FFmpeg audio codec name to the Android MIME used to look up a device decoder. */
    private fun audioMimeFor(codec: String): String? = when {
        codec.startsWith("ac3") || codec.startsWith("ac-3") -> "audio/ac3"
        codec.startsWith("eac3") || codec.startsWith("e-ac-3") -> "audio/eac3"
        codec.startsWith("dts") -> "audio/vnd.dts"
        codec.startsWith("truehd") || codec.startsWith("mlp") -> "audio/true-hd"
        else -> null
    }

    /** Does this device expose a (hardware or software) MediaCodec decoder for [mime]? */
    private fun deviceHasAudioDecoder(mime: String): Boolean = runCatching {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        list.codecInfos.any { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
    }.getOrDefault(false)

    // --- Video decode capability + rescue ladder (F08/F09/F10) --------------------------------

    /** Map an mpv/FFmpeg video codec name to the Android MIME used to look up a device decoder. */
    private fun videoMimeFor(codec: String): String? = codec.lowercase().let { c ->
        when {
            c.startsWith("h264") || c.startsWith("avc") -> "video/avc"
            c.startsWith("hevc") || c.startsWith("h265") -> "video/hevc"
            c.startsWith("av1") -> "video/av01"
            c.startsWith("vp9") -> "video/x-vnd.on2.vp9"
            c.startsWith("vp8") -> "video/x-vnd.on2.vp8"
            c.startsWith("mpeg2") -> "video/mpeg2"
            c.startsWith("mpeg4") -> "video/mp4v-es"
            c.startsWith("vc1") || c.startsWith("vc-1") -> "video/wvc1"
            else -> null
        }
    }

    /**
     * Does this TV have a HARDWARE decoder that covers [mime] at [w]×[h]? (F10)
     *
     * Many TV SoCs advertise 4K for HEVC/VP9/AV1 only and cap AVC at 1920×1080 — a queryable fact that
     * used to surface as a wrong error message ("ask your provider to re-encode"). `null` = unknown
     * (no codec name yet, or the query threw); only a definite `false` is acted on.
     */
    private fun hardwareCanDecode(mime: String, w: Int, h: Int): Boolean? = runCatching {
        if (w <= 0 || h <= 0) return null
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val isHw = if (android.os.Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated
            } else {
                // Pre-29 has no query; the two Google software decoder prefixes are the reliable tell.
                !info.name.startsWith("OMX.google", true) && !info.name.startsWith("c2.android", true)
            }
            if (!isHw) continue
            val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
            if (caps.isSizeSupported(w, h)) return true
        }
        // Either no hardware decoder for this format at all, or none of them covers this size — both
        // mean the same thing to the ladder: the direct path can't win here.
        false
    }.getOrNull()

    /** Hardware definitely can't decode the current stream's codec at its declared size. */
    private fun hardwareCannotDecodeCurrent(): Boolean {
        val mime = load.currentVideoCodec?.let { videoMimeFor(it) } ?: return false
        val w = load.currentWidthPx
        val h = load.currentHeightPx.takeIf { it > 0 } ?: lastVideoHeightPx
        if (w <= 0 || h <= 0) return false
        return hardwareCanDecode(mime, w, h) == false
    }

    /** mpv/decoder text that means "the video decoder failed", as opposed to a container/network fault. */
    private val decoderFailureRx = Regex(
        "mediacodec|omx\\.|c2\\.|hwdec|hardware decod|could not open codec|decoder|0x8000|0xfffffff",
        RegexOption.IGNORE_CASE,
    )
    private fun looksLikeDecoderFailure(raw: String?): Boolean =
        raw != null && decoderFailureRx.containsMatchIn(raw)

    /** The copy rung is available: hardware decoding is on, GL works, and we haven't used it yet. */
    private fun canTryCopyRescue(): Boolean =
        hwDecodingActive() && !glUnsupported && !item.triedCopyRescue && currentUrl != null

    /** The software rung is available. Kept gated at ≤1080p — [enforceDecodeGuard] would abort above it. */
    private fun canTrySoftwareRescue(): Boolean =
        hwDecodingActive() && !glUnsupported && lastVideoHeightPx <= 1080 && currentUrl != null

    /** Honest terminal state for a decode failure; presentation wording belongs to the HUD. */
    private fun decodeFailureMessage(raw: String?): PlaybackFailure {
        val res = resolutionLabel(load.currentHeightPx.takeIf { it > 0 } ?: lastVideoHeightPx)
        val codec = load.currentVideoCodec?.uppercase()
        if (hardwareCannotDecodeCurrent() && res != null && codec != null) {
            return PlaybackFailure.HardwareFormat(resolution = res, codec = codec)
        }
        return PlayerErrors.visibleFailure(raw, currentUrl, PlaybackFailure.MpvOpenDecode)
    }

    /** Where a rescue resumes. An archive has no Range support — reopening it at an offset fails
     *  outright, so it restarts, and so does a live stream. */
    private fun rescueResumePosition(): Long =
        if (isLiveContent || item.archiveThisItem) 0L else _position.value

    /**
     * The tail every rescue rung shares: reconfigure the render path, then reopen the same item once
     * mpv has applied it. The wait matters — reloading before the new vo/hwdec has landed reopens the
     * item on the path that just failed. [ItemState.forceFullProbe] because a rescue follows a decoder failure,
     * where the trimmed fast-zap probe may have under-read the stream's config.
     */
    private fun reloadAfterRescue(url: String, positionMs: Long) {
        val gen = loadGeneration
        load.expectingPlayback = false
        _buffering.value = true
        item.forceFullProbe = true
        mpvAsync { applyRenderConfig() }
        scope.launch {
            delay(RENDER_RECONFIG_MS)
            if (gen == loadGeneration) {
                loadUrl(url, currentMetaSnapshot(), isLiveContent, positionMs, resetRetries = false)
            }
        }
    }

    /**
     * Rescue rung 2 on its own: reopen this item in pure software decode. Some weak TV decoders reject
     * streams software decoding plays fine.
     *
     * Callers that have already established the copy rung is wrong for their situation — a failure that
     * doesn't look like a decoder failure, or a direct path whose retries are spent — take this directly
     * rather than [tryDecodeRescue], which would try the copy rung first.
     */
    private fun trySoftwareRescue(reason: String): Boolean {
        val url = currentUrl ?: return false
        if (!canTrySoftwareRescue()) return false
        item.forceSoftwareThisLoad = true
        android.util.Log.w(TAG, "$reason — rescue rung 2: software decode")
        // Worth a log line even though playback recovers: a stream that only plays on rung 2 is the
        // exact "it stutters on my box but not yours" report that used to arrive with no evidence (F26).
        PlaybackErrorLog.event(
            context, "mpv", isLiveContent,
            reason = PlayerFailureReason.SOFTWARE_FALLBACK,
            detail = reason,
        )
        reloadAfterRescue(url, rescueResumePosition())
        return true
    }

    /**
     * Step down the decode ladder one rung and reload the same item: copy rescue → software → nothing.
     * Returns true when a rung was taken (the caller must not also surface an error).
     */
    private fun tryDecodeRescue(reason: String): Boolean {
        val url = currentUrl ?: return false
        if (!canTryCopyRescue()) return trySoftwareRescue(reason)
        item.triedCopyRescue = true
        item.forceCopyThisLoad = true
        android.util.Log.w(TAG, "$reason — rescue rung 1: hwdec=mediacodec-copy (GL compositing)")
        PlaybackErrorLog.event(
            context, "mpv", isLiveContent,
            reason = PlayerFailureReason.COPY_MODE_FALLBACK,
            detail = reason,
        )
        reloadAfterRescue(url, rescueResumePosition())
        return true
    }

    /**
     * The mid-GOP rescue: this catch-up archive was opened in hardware and produced no picture, so
     * reopen it in software and remember the panel for the rest of the session
     * ([LiveStreamQuirks.rememberArchiveNeedsSoftware]).
     *
     * This is the counterpart of opening archives in hardware by default. It runs BEFORE the generic
     * decode ladder wherever both could apply: for an archive, mid-GOP is the likely cause, and the
     * generic rungs either burn retries on a path that will fail identically (the copy rung still hands
     * the decoder the same mid-GOP bytes) or refuse above 1080p — where a mid-GOP archive is exactly as
     * broken. Returns true when the rescue was taken, so the caller must not also error/hard-reset.
     */
    private fun tryArchiveSoftwareRescue(reason: String): Boolean {
        val url = currentUrl ?: return false
        if (!item.archiveThisItem || item.forceSoftwareThisLoad || glUnsupported) return false
        LiveStreamQuirks.rememberArchiveNeedsSoftware(url)
        item.forceSoftwareThisLoad = true
        android.util.Log.w(TAG, "$reason — archive rescue: reopening mid-GOP catch-up in software decode")
        PlaybackErrorLog.event(
            context, "mpv", isLiveContent,
            reason = PlayerFailureReason.ARCHIVE_SOFTWARE_FALLBACK,
            detail = reason,
        )
        // Archives are served without Range support: always restart from the beginning.
        reloadAfterRescue(url, 0L)
        return true
    }

    /** Hand playback from mpv to ExoPlayer to show an image subtitle (VOD only). */
    private fun handoffToExo(sub: TrackOption) {
        // A guard, not a value: the handoff deliberately tears this surface down and starts ExoPlayer on
        // a fresh one (see the capture/settle comment below), so there must BE one, but never this one.
        if (attachedSurface == null) return
        val url = currentUrl ?: return
        if (!audioCodecSafeForExo()) {
            toast(toastRenderer.render(PlaybackFailure.ImageSubtitleAudio))
            // The selection is deliberately left alone. The handoff was refused, so mpv keeps playing with
            // whatever subtitle was already on — blanking the list claimed the user's working text subtitle
            // had been switched off while it carried on rendering.
            return
        }
        val pos = _position.value
        pendingImageSub = sub
        _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == sub.mpvId) }
        loadGeneration++ // supersede any mpv retry/watchdog work for this item
        errorCheckJob?.cancel(); videoCheckJob?.cancel()
        load.expectingPlayback = false
        _error.value = null
        _buffering.value = true
        val gen = loadGeneration
        // Capture the current frame to mask the decoder swap, THEN stop mpv + release its surface on the
        // worker (frees the connection + decoder), THEN — after a settle beat, on a FRESH surface — start
        // ExoPlayer. The settle + recreation matter: mpv's MediaCodec takes a moment to release and leaves
        // the surface dirty (Realtek), which intermittently gave Exo "audio plays, no video".
        captureFreezeThen {
            mpvAsync {
                stopWithStopClassification("handoff to exo")
                setPropertyString("vo", "null")
                runCatching { this.detachSurface() } // mpv's detachSurface (the receiver), not OwnTVPlayer's
                scope.launch {
                    delay(DECODER_RELEASE_MS) // let mpv's MediaCodec finish releasing before ExoPlayer claims the decoder
                    if (gen != loadGeneration) return@launch // superseded meanwhile
                    pendingUrl = url
                    load.pendingSeekMs = pos
                    load.pendingStartPaused = false
                    load.pendingExoSub = sub
                    load.pendingExoStart = true
                    _surfaceResetToken.value++ // fresh surface → attachSurface routes it into startExo
                }
            }
        }
    }

    private fun startExo(url: String, pos: Long, surface: Surface, sub: TrackOption?) {
        exoActive = true
        _engineChip.value = "EXO"
        _exoActiveState.value = true // mount the SubtitleView overlay now (only while Exo owns playback)
        _directRender.value = true // ExoPlayer also renders direct-to-surface → the view sizes for zoom
        _subText.value = null // mpv's text overlay is off during the handoff
        val budget = playerBudget ?: PlayerBudget.of(context).also { playerBudget = it }
        val engine = exoEngine ?: ExoSubtitleEngine(context, streamingHttp, budget, exoCallbacks).also { exoEngine = it }
        // Keep the handoff engine on the same audio policy as mpv, and let its watchdog restart the item
        // on a stereo sink — the session latch is already set by the time this fires, so `start` rebuilds.
        engine.surroundMode = surroundMode
        engine.hwDecodingEnabled = hwDecoding
        // Auto frame rate and the preferred languages used to reach mpv only, so an item that landed on
        // ExoPlayer (image-subtitle handoff, "prefer ExoPlayer for VOD", or an mpv fallback) quietly
        // ignored three settings the user had set. Same values, same source of truth.
        engine.autoFrameRateEnabled = autoFrameRate
        engine.prefAudioLang = prefAudioLang
        engine.prefSubLang = prefSubLang
        // Carry this item's request identity across the handoff (F16) — a stream that needs a custom
        // UA/Referer on mpv needs exactly the same on ExoPlayer.
        engine.userAgent = currentUserAgent
        engine.httpHeaders = currentHeaders
        engine.drmConfig = currentDrm
        val restartGen = loadGeneration
        engine.onAudioFallback = {
            toast(toastRenderer.render(PlaybackFailure.Surround))
            // Rebuilding immediately on the same Surface leaves some Realtek/TCL decoders alive-but-
            // blank. Release first, give MediaCodec time to settle, then restart on a fresh Surface.
            if (restartGen == loadGeneration && exoActive) {
                restartExoOnFreshSurface(url, _position.value, sub, restartGen)
            }
        }
        // ExoPlayer's own software rung: this item failed on Exo's hardware decoder in a way a software
        // decoder can plausibly fix, so restart it HERE rather than handing it to mpv. Keeps the ladder
        // symmetric (Exo hardware → Exo software → mpv hardware → mpv copy → mpv software) instead of
        // leaving the user's preferred engine after a single hardware stumble.
        //
        // fromStart: a mid-GOP archive has no Range support, so resuming at an offset would fail
        // outright — it restarts at 0. A normal movie/episode resumes where it stopped.
        engine.onSoftwareRescue = { _, fromStart ->
            if (restartGen == loadGeneration && exoActive) {
                item.forceSoftwareThisLoad = true
                PlaybackErrorLog.event(
                    context, "exoplayer", isLiveContent,
                    reason = PlayerFailureReason.SOFTWARE_FALLBACK,
                    detail = "hardware decoder produced no usable video on ExoPlayer",
                )
                restartExoOnFreshSurface(
                    url = url,
                    pos = if (fromStart) 0L else _position.value,
                    sub = sub,
                    generation = restartGen,
                )
            }
        }
        val extSelect = item.pendingExoExternalSelect
        item.pendingExoExternalSelect = null
        engine.start(
            url, pos, surface, sub?.lang, sub?.typeIndex ?: -1, fallback = item.exoVodFallback,
            // Re-seed this session's external subs so they stay listed across the engine switch (§10);
            // only when Exo owns playback as a VOD engine (the HUD shows Exo's track list then).
            sideloadSubs = if (item.exoVodFallback) sessionExternalSubs.toList() else emptyList(),
            selectExternalLabel = extSelect?.title,
            // Carry this item's decode path across the engine switch: whichever rung mpv ended up on is
            // the one that works for this stream, and a mid-GOP archive fails ExoPlayer's hardware
            // decoder the same way it failed mpv's.
            preferSoftware = item.forceSoftwareThisLoad,
            isArchive = item.archiveThisItem,
        )
        // Re-apply the carried subtitle's remembered timing (§8.4) on the incoming engine.
        if (extSelect != null) onActiveSubtitleChanged?.invoke("path:${extSelect.path}")
        else if (sub != null && !sub.image) onActiveSubtitleChanged?.invoke("emb:${sub.typeIndex}:${sub.lang ?: ""}")
        engine.setVolume(_volume.value) // carry the current HUD volume into ExoPlayer
        startExoTick()
    }

    /**
     * Rebuild Exo after a renderer configuration change without reusing the outgoing decoder's Surface.
     *
     * Both the surround -> stereo fallback and the hardware -> software rescue replace the renderer
     * factory, which means replacing the whole ExoPlayer and its MediaCodec video decoder. Realtek/TCL
     * devices can leave that decoder's buffer queue dirty after release; immediately binding the new
     * decoder to the same Surface then gives audio with a permanently blank picture. Every cross-engine
     * transition already observes [DECODER_RELEASE_MS] and recreates the Surface for this reason.
     *
     * Keep Exo logically active so the UI never exposes or reattaches mpv during the short restart. Once
     * the fresh Surface arrives, [attachSurface] consumes [LoadState.pendingExoStart] and calls [startExo]
     * at the captured position with the new stereo/software policy already latched.
     */
    private fun restartExoOnFreshSurface(
        url: String,
        pos: Long,
        sub: TrackOption?,
        generation: Int,
    ) {
        if (generation != loadGeneration || !exoActive || currentUrl != url) return
        _buffering.value = true
        exoTickJob?.cancel()
        exoEngine?.stop()
        scope.launch {
            delay(DECODER_RELEASE_MS)
            if (generation != loadGeneration || !exoActive || currentUrl != url) return@launch
            pendingUrl = url
            load.pendingSeekMs = pos
            load.pendingStartPaused = false
            load.pendingExoSub = sub
            load.pendingExoStart = true
            _surfaceResetToken.value++
        }
    }

    /**
     * Terminal mpv failure on a VOD → retry the same item ONCE on ExoPlayer before surfacing an error
     * (some devices/streams play on ExoPlayer's MediaCodec path where mpv's can't — the Live TV screen
     * already works this way, just in the other direction). Returns true when the fallback was started,
     * in which case the caller must NOT surface its error.
     *
     * [mpvStuck] = mpv's core may be blocked in a stuck HTTP read (the hard-reset path): the instance is
     * destroyed to abort the connection instead of being politely stopped — an IPTV panel capping VOD at
     * one connection would otherwise refuse ExoPlayer's open while mpv still holds the slot.
     */
    private fun fallbackToExoVod(mpvError: PlaybackFailure, mpvStuck: Boolean): Boolean {
        if (isLiveContent || exoActive || item.triedExoVodFallback) return false
        if (!itemEngine.allowsHandover) {
            android.util.Log.w(TAG, "VOD failed on mpv but the engine setting is mpv-only — no fallback")
            return false
        }
        val url = currentUrl ?: return false
        if (attachedSurface == null) return false
        if (!audioCodecSafeForExo()) {
            android.util.Log.w(TAG, "VOD failed on mpv but audio codec unsafe for ExoPlayer — no fallback")
            return false
        }
        item.triedExoVodFallback = true
        item.exoVodFallback = true
        mpvFailureBeforeFallback = mpvError
        android.util.Log.w(TAG, "VOD terminally failed on mpv ($mpvError) — falling back to ExoPlayer")
        // Resume where mpv got to only if the file actually opened; otherwise _position is stale from the
        // previous item — use the intended start position instead.
        val pos = if (load.fileLoaded && _position.value > 0) _position.value else load.pendingSeekMs
        loadGeneration++ // supersede any mpv retry/watchdog work for this item
        val gen = loadGeneration
        errorCheckJob?.cancel(); videoCheckJob?.cancel()
        load.expectingPlayback = false
        _error.value = null
        _buffering.value = true
        load.currentHwdec = null // keep the mpv decode guard inert while ExoPlayer owns playback
        if (mpvStuck) {
            hardReset()
            // hardReset() destroys mpv on its own thread and forces a fresh Surface; give the core a
            // moment to die (it holds the panel's connection slot), then arm the deferred Exo start and
            // request ANOTHER surface recreate. Grabbing `attachedSurface` here instead would race the
            // recreate hardReset() just triggered: the old surface is already abandoned but not yet
            // reported destroyed, so ExoPlayer configures onto a dead window and dies with
            // `nativeWindowConnect returned an error: Invalid argument (-22)` → "failed on both engines"
            // for an item that plays fine on retry. Routing through load.pendingExoStart guarantees the
            // surface startExo receives was created AFTER this point.
            scope.launch {
                delay(CORE_RESET_SETTLE_MS)
                if (gen != loadGeneration) return@launch // superseded (user zapped/backed out meanwhile)
                pendingUrl = url
                load.pendingSeekMs = pos
                load.pendingExoSub = null
                load.pendingStartPaused = false
                load.pendingExoStart = true
                _surfaceResetToken.value++
            }
        } else {
            // mpv is responsive (END_FILE / decode guard): stop it cleanly to free the connection +
            // decoder, then start ExoPlayer — same single-owner ordering as the image-sub handoff.
            mpvAsync {
                stopWithStopClassification("exo vod fallback")
                setPropertyString("vo", "null")
                runCatching { this.detachSurface() }
                scope.launch {
                    delay(SURFACE_HANDOFF_MS) // let mpv's MediaCodec release — a busy decoder would fail Exo instantly
                    val s = attachedSurface ?: return@launch
                    startExo(url, pos, s, sub = null)
                }
            }
        }
        return true
    }

    /** ExoPlayer-preferred mode: the item started on Exo and Exo failed — retry it on mpv (the reverse
     *  of [fallbackToExoVod]). mpv gets its full retry ladder; a terminal mpv failure after this shows
     *  the combined both-engines error via [vodErrorMessage]. */
    private fun fallbackToMpvVod(exoError: PlaybackFailure) {
        if (!exoActive) return
        val url = currentUrl ?: return
        android.util.Log.w(TAG, "VOD terminally failed on ExoPlayer ($exoError) — falling back to mpv")
        val pos = engineSwitchResumePos()
        item.exoFailureBeforeMpv = exoError
        item.exoPrimaryThisItem = false
        item.triedExoVodFallback = true // never bounce this item back to Exo
        // mpv starts on its OWN hardware rung and walks its own ladder from there (hardware → copy →
        // software). "ExoPlayer couldn't decode this in hardware" says nothing about mpv's hardware
        // path: the two negotiate MediaCodec differently, which is the whole reason both engines
        // exist. Carrying Exo's software verdict over would skip the rung most likely to work.
        item.forceSoftwareThisLoad = false
        item.triedCopyRescue = false
        deactivateExo()
        _buffering.value = true
        loadUrl(url, currentMetaSnapshot(), isLive = false, pos, resetRetries = false)
    }

    /** HUD engine toggle for VOD: switch the CURRENT movie/episode between mpv and ExoPlayer at the same
     *  position, without touching the global "Movies & Series player" setting. Lets the user check which
     *  engine exposes the tracks/behavior they need (e.g. mpv-only subtitle or audio tracks after an
     *  ExoPlayer start, or the reverse). Not persisted — the next item follows the setting again. */
    fun toggleVodEngine() {
        if (isLiveContent) return
        val url = currentUrl ?: return
        // Both directions swap one MediaCodec session for another on TV-class silicon: the outgoing
        // decoder takes a moment to release, and the surface it rendered to stays dirty (Realtek
        // 0x80001000). So each direction waits a settle beat AND starts the incoming engine on a FRESH
        // surface — same recipe as the autoplay >1080p fix — otherwise the incoming engine hits
        // "decoder busy", errors, and (via the auto-fallback) bounces straight back.
        if (exoActive) {
            // → mpv. Manual choice: clear the chain state so this doesn't read as "mpv after Exo failed"
            // (which would turn a later mpv failure into the combined error) and re-arm the auto-fallback.
            android.util.Log.i(TAG, "HUD engine toggle: ExoPlayer → mpv")
            val pos = engineSwitchResumePos()
            // Carry the active subtitle across the switch (§10): an external sub re-attaches + selects
            // after mpv reloads; an embedded pick re-selects by its ordinal among sub tracks.
            val selSub = _subTrackList.value.firstOrNull { it.selected }
            val extSel = selSub?.let { s -> sessionExternalSubs.firstOrNull { it.title == s.label } }
            if (extSel != null) item.pendingExternalAdd = extSel
            else if (selSub != null) item.pendingSelectSubOrdinal = selSub.typeIndex.takeIf { it >= 0 }
            item.exoPrimaryThisItem = false
            item.exoFailureBeforeMpv = null
            deactivateExo() // releases Exo's codec
            item.triedExoVodFallback = false // re-arm the auto-fallback for the manual choice
            // The click is a per-item exception to the global setting, so it also re-opens the handover
            // an "only" mode would otherwise forbid — the user is switching engines by hand precisely
            // because the one the setting names is not working for this item.
            itemEngine = tv.own.owntv.core.player.EnginePreference.MPV_FIRST
            _buffering.value = true
            // Remember the choice for THIS item (like Live's compatibility mode remembers the channel).
            scope.launch { vodEngineStore.pin(currentContentKey ?: url, tv.own.owntv.core.player.VodEnginePin.MPV) }
            scope.launch {
                delay(DECODER_RELEASE_MS) // let ExoPlayer's MediaCodec finish releasing before mpv claims the decoder
                if (currentUrl != url) return@launch // superseded (user zapped/backed out meanwhile)
                forceSurfaceResetNextLoad = true
                loadUrl(url, currentMetaSnapshot(), isLive = false, pos, resetRetries = false)
            }
        } else {
            // → ExoPlayer. Marked primary so an Exo failure falls back to mpv instead of erroring as
            // "both engines failed"; same mpv-first single-connection ordering as the preferred-engine path.
            if (attachedSurface == null) return
            android.util.Log.i(TAG, "HUD engine toggle: mpv → ExoPlayer")
            val pos = if (load.fileLoaded && _position.value > 0) _position.value else load.pendingSeekMs
            // Carry the active subtitle across the switch (§10): session externals are re-seeded into
            // ExoPlayer by startExo — an active external is selected there by label, an embedded pick
            // by its ordinal (via load.pendingExoSub).
            val selSub = _subTrackList.value.firstOrNull { it.selected }
            item.pendingExoExternalSelect = selSub?.let { s -> sessionExternalSubs.firstOrNull { it.title == s.label } }
            val carrySub = if (item.pendingExoExternalSelect == null) selSub else null
            item.exoPrimaryThisItem = true
            item.exoVodFallback = true
            mpvFailureBeforeFallback = null
            item.triedExoVodFallback = false
            itemEngine = tv.own.owntv.core.player.EnginePreference.EXO_FIRST // see the mpv branch above
            loadGeneration++ // supersede mpv retry/watchdog work for this item
            val gen = loadGeneration
            errorCheckJob?.cancel(); videoCheckJob?.cancel()
            load.expectingPlayback = false
            _error.value = null
            _buffering.value = true
            load.currentHwdec = null // keep the mpv decode guard inert while ExoPlayer owns playback
            load.pendingSeekMs = pos
            load.pendingStartPaused = false
            // Remember the choice for THIS item (like Live's compatibility mode remembers the channel).
            scope.launch { vodEngineStore.pin(currentContentKey ?: url, tv.own.owntv.core.player.VodEnginePin.EXO) }
            mpvAsync {
                stopWithStopClassification("manual engine toggle")
                setPropertyString("vo", "null")
                runCatching { this.detachSurface() }
                scope.launch {
                    delay(DECODER_RELEASE_MS) // let mpv's MediaCodec finish releasing before ExoPlayer claims the decoder
                    if (gen != loadGeneration) return@launch // superseded meanwhile
                    // Start Exo on a FRESH surface: attachSurface sees load.pendingExoStart and routes the
                    // recreated surface straight into startExo (mpv never touches it).
                    pendingUrl = url
                    load.pendingExoSub = carrySub
                    load.pendingExoStart = true
                    _surfaceResetToken.value++
                }
            }
        }
    }

    /** Wraps a terminal mpv VOD error message: if ExoPlayer already failed this item first
     *  (ExoPlayer-preferred setting), report that both engines failed rather than just mpv. */
    private fun vodErrorMessage(mpvMsg: PlaybackFailure): PlaybackFailure {
        val exoErr = item.exoFailureBeforeMpv ?: return mpvMsg
        android.util.Log.w(TAG, "VOD failed on BOTH engines — exo first: '$exoErr' / mpv: '$mpvMsg'")
        return PlaybackFailure.BothEnginesExoFirst
    }

    /**
     * Where mpv should resume when an item is handed back to it (engine toggle or Exo fallback).
     *
     * Normally that's the current position. But a catch-up archive ([ItemState.archiveThisItem]) is served
     * by the panel as a plain stream with no Range support, so re-opening it at an offset fails outright:
     * the MOOV-AT-END watchdog aborts ("server lacks Range support") and the user gets a "failed on both
     * engines" error for a programme mpv had been playing happily seconds earlier. Restarting the archive
     * from the beginning actually plays — and for a "Watch from start" programme that's the intended
     * position anyway.
     */
    private fun engineSwitchResumePos(): Long =
        if (item.archiveThisItem) 0L
        else if (_position.value > 0) _position.value
        else load.pendingSeekMs

    /** The engine fallback ALSO failed: stop ExoPlayer and surface one combined error. */
    /** "Only ExoPlayer" and ExoPlayer gave up: mpv is not allowed a turn, so this is the final word.
     *  Reported as ExoPlayer's own error — telling the user both engines failed would be a lie, and it
     *  would hide the fact that the engine setting is what stopped the second attempt. */
    private fun failExoOnly(exoError: PlaybackFailure) {
        android.util.Log.w(TAG, "VOD failed on ExoPlayer ($exoError) and the engine setting is ExoPlayer-only")
        deactivateExo()
        _isPlaying.value = false
        _buffering.value = false
        _error.value = exoError
    }

    private fun failBothEngines(exoError: PlaybackFailure) {
        android.util.Log.w(TAG, "VOD failed on BOTH engines — mpv: '$mpvFailureBeforeFallback' / exo: '$exoError'")
        deactivateExo()
        _isPlaying.value = false
        _buffering.value = false
        _error.value = PlaybackFailure.BothEnginesMpvFirst(exoError)
    }

    /** ExoPlayer reached end-of-file while it owned VOD playback: mirror mpv's END_FILE auto-play-next
     *  (advance the episode queue, or signal the series VM at the end of a season). */
    private fun onExoEnded() {
        if (!exoActive || isLiveContent) return
        if (reachedEnd(_duration.value, _position.value)) advanceAfterNaturalEnd()
    }

    /**
     * A non-live item played all the way to its end. Continue with whatever follows it, if anything:
     *
     *  - the next episode in the queue — the player owns that one;
     *  - the end of a season queue → [queueEnded], for the series view model to roll into the next;
     *  - a catch-up archive with no queue → [archiveEnded], for the live view model to look up the next
     *    programme in the guide. Without this a finished catch-up programme just left a black screen.
     *
     * A single movie (no queue, not an archive) stops, as it always has.
     *
     * Advancing waits a short settle so the ended item's decoder can release; the fresh Surface in
     * loadUrl is what actually prevents the back-to-back >1080p 0x80001000.
     */
    private fun advanceAfterNaturalEnd() {
        if (!autoPlayNext || item.autoNextCancelled) return
        val advance: () -> Unit = when {
            playlist.isEmpty() -> if (item.archiveThisItem) ({ _archiveEnded.tryEmit(Unit) }) else return
            playlistIndex < playlist.size - 1 -> ({ next() })
            else -> ({ _queueEnded.tryEmit(Unit) })
        }
        val gen = loadGeneration
        scope.launch { delay(DECODER_RELEASE_MS); if (gen == loadGeneration) advance() }
    }

    /** PixelCopy the live surface into a bitmap (shown during the swap), then run [block]. Best-effort:
     *  on any failure or after a short timeout it proceeds with no freeze (no worse than a black flash). */
    private fun captureFreezeThen(block: () -> Unit) {
        val surface = attachedSurface
        val w = surfaceW; val h = surfaceH
        if (surface == null || w <= 0 || h <= 0 || android.os.Build.VERSION.SDK_INT < 24) {
            android.util.Log.w(TAG, "freeze-frame skipped: surface=${surface != null} size=${w}x$h sdk=${android.os.Build.VERSION.SDK_INT}")
            block(); return
        }
        // PixelCopy scales into whatever destination it is given, and the frame is drawn with
        // FillBounds over the same geometry, so a reduced capture looks the same. At 4K a full-size
        // ARGB_8888 bitmap is ~33 MB allocated, copied and thrown away for a 250 ms placeholder that
        // sits behind a decoder switch; capped at FREEZE_MAX_W it is under 4 MB.
        val scale = minOf(1f, FREEZE_MAX_W.toFloat() / w)
        val cw = (w * scale).toInt().coerceAtLeast(1)
        val ch = (h * scale).toInt().coerceAtLeast(1)
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bmp == null) { android.util.Log.w(TAG, "freeze-frame skipped: bitmap alloc failed ${cw}x$ch"); block(); return }
        var proceeded = false
        val proceed = { if (!proceeded) { proceeded = true; block() } }
        runCatching {
            android.view.PixelCopy.request(surface, bmp, { result ->
                android.util.Log.i(TAG, "freeze-frame PixelCopy result=$result (SUCCESS=${android.view.PixelCopy.SUCCESS})")
                if (result == android.view.PixelCopy.SUCCESS) setFreezeFrame(bmp) else bmp.recycle()
                proceed()
            }, freezeHandler)
        }.onFailure { runCatching { bmp.recycle() }; proceed() }
        // Safety net: never block the handoff if PixelCopy doesn't call back.
        freezeHandler.postDelayed({ proceed() }, 250)
    }

    /**
     * Publish ExoPlayer's position/duration on the shared cosmetic beat. Was its own 500 ms loop; the
     * readout it feeds is a clock face on a television, where 1 Hz is indistinguishable and the extra
     * wake-up is not (see [PlayerHeartbeat]).
     *
     * **It keeps running while the HUD is hidden, deliberately.** It looks cosmetic and is not: the
     * resume position written by `saveProgressNow` and the end-of-file check in [onExoEnded] both read
     * [position], and a film is watched with the HUD hidden almost the whole time. Suspending this
     * would silently save resume points from wherever the HUD was last open. Only the genuinely
     * cosmetic readers — the stream-info overlay and the chip refresh — are gated on visibility.
     */
    private fun startExoTick() {
        exoTickJob?.cancel()
        exoTickJob = scope.launch {
            PlayerHeartbeat.beat.collect {
                if (!exoActive) return@collect
                exoEngine?.emitPositionDuration()
            }
        }
    }

    /** Tear down the Exo handoff and give the surface back to mpv (does NOT reload — caller decides). */
    private fun deactivateExo() {
        if (!exoActive) return
        exoActive = false
        item.exoVodFallback = false
        mpvFailureBeforeFallback = null
        _engineChip.value = "MPV"
        _exoActiveState.value = false // unmount the SubtitleView overlay → SurfaceView regains direct scan-out
        exoTickJob?.cancel()
        _exoCues.value = emptyList()
        setFreezeFrame(null)
        exoEngine?.stop()
        pendingImageSub = null
        reattachMpvSurface()
    }

    /** Hand playback back to mpv (image sub turned off, a text sub picked, or an Exo failure), resuming
     *  the same item at its current position with subtitles off (or [thenSelectSid] applied after load). */
    private fun revertToMpv(error: PlaybackFailure? = null, thenSelectSid: Int? = null) {
        if (!exoActive) return
        if (item.exoVodFallback) return // mpv already failed this item — never hand it back mid-fallback
        val url = currentUrl ?: return
        val pos = _position.value
        deactivateExo() // releases Exo's codec
        error?.let { toast(toastRenderer.render(it)) }
        item.pendingSelectSid = thenSelectSid
        _subTrackList.value = _subTrackList.value.map { it.copy(selected = thenSelectSid != null && it.mpvId == thenSelectSid) }
        _buffering.value = true
        scope.launch {
            delay(DECODER_RELEASE_MS) // let ExoPlayer's MediaCodec finish releasing before mpv claims the decoder
            if (currentUrl != url) return@launch // superseded (user zapped/backed out meanwhile)
            forceSurfaceResetNextLoad = true // Exo left the surface dirty — mpv gets a fresh one
            loadUrl(url, currentMetaSnapshot(), isLiveContent, pos, resetRetries = false)
        }
    }

    private fun reattachMpvSurface() {
        val surface = attachedSurface ?: return
        mpvAsync {
            runCatching { this.attachSurface(surface) } // mpv's attachSurface (the receiver)
            setOptionString("force-window", "yes")
            setPropertyString("vo", targetVo())
        }
        _directRender.value = useDirect()
    }

    private fun toast(message: String) {
        val epoch = toastEpoch.get()
        scope.launch {
            // A previous item's worker callback may arrive after the user has already opened something
            // else. Never let that stale notice appear over the new video, and replace rather than queue
            // repeated notices from the current item.
            if (epoch != toastEpoch.get()) return@launch
            activeToast?.cancel()
            activeToast = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG)
                .also { it.show() }
        }
    }

    private fun clearToast() {
        toastEpoch.incrementAndGet()
        scope.launch {
            activeToast?.cancel()
            activeToast = null
        }
    }

    /** Expose bundled UI fonts to libass/fontconfig without shipping duplicate assets. */
    private fun prepareSubtitleFontsDir(): java.io.File? = runCatching {
        val dir = java.io.File(context.cacheDir, "subtitle-fonts").apply { mkdirs() }
        AppFontFamily.entries.forEach { font ->
            val resource = SubtitleFontAssets.resourceOf(font)
            if (resource == 0) return@forEach
            val target = java.io.File(dir, "${font.name.lowercase(Locale.US)}.ttf")
            if (!target.isFile) {
                context.resources.openRawResource(resource).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        dir
    }.onFailure {
        android.util.Log.w(TAG, "Could not prepare subtitle fonts: ${it.message}")
    }.getOrNull()

    private fun ensureInit() {
        if (initialized) return
        val budget = PlayerBudget.of(context)
        playerBudget = budget
        android.util.Log.i(TAG, "PlayerBudget: $budget")
        val subtitleFontsDir = prepareSubtitleFontsDir()
        mpv = MPVLib.create(context)?.apply {
            subtitleFontsDir?.let { setOptionString("sub-fonts-dir", it.absolutePath) }
            setOptionString("vo", if (useDirect()) "mediacodec_embed" else "gpu")
            setOptionString("gpu-context", "android")
            setOptionString("hwdec", if (useDirect()) "mediacodec" else "no")
            setOptionString("ao", "audiotrack")
            // Surround sound (opt-in, default off): decode Dolby/DTS to MULTICHANNEL LPCM (5.1/7.1) over HDMI. The
            // AudioTrack stays a normal PCM track, so getTimestamp() keeps mpv's audio clock alive and the
            // zero-copy mediacodec_embed 4K-HDR video path renders smoothly. The sink picks the layout
            // (auto → stereo on a 2.0 TV, 5.1/7.1 on a capable receiver). Off → a plain stereo downmix.
            // (We never bitstream/spdif: on Realtek the passthrough AudioTrack reports no clock, which
            // stalls the direct VO into a ~2fps slideshow on Dolby/DTS content.)
            setOptionString("audio-channels", audioChannelsValue())
            // Compatibility for multichannel: some HALs choke on Float / 44.1 kHz 5.1 PCM (mis-sized buffer
            // → 2× drain, #25). Pin the universally-safe 16-bit/48 kHz output when surround is on.
            val sur = multichannelAllowed()
            setOptionString("audio-format", if (sur) "s16" else "")
            setOptionString("audio-samplerate", if (sur) "48000" else "0")
            setOptionString("force-window", "no")
            setOptionString("idle", "yes")
            setOptionString("ytdl", "no") // IPTV URLs are direct; skip the youtube-dl hook
            // Closed captions (CEA-608/708): US premium channels (HBO/Showtime/Cinemax) and many movies carry
            // captions embedded in the video stream rather than as a subtitle track. mpv (FFmpeg) decodes them
            // into a selectable subtitle track — ExoPlayer doesn't surface undeclared CC, so this is the path
            // that actually shows them. Harmless when there are none (no track is created).
            setOptionString("sub-create-cc-track", "yes")
            // Allow volume boost above 100% (Kodi-style amplification) for quiet streams; mpv soft-limits.
            setOptionString("volume-max", "150")
            // A/V sync on hardware decode: a few movies (high bitrate / 50–60 fps) decode just behind
            // real time, so the picture drifts slightly behind the audio. mpv's default framedrop is "vo",
            // which is a no-op with the direct mediacodec surface (the decoder presents its own frames) — so
            // nothing drops the late frames. "decoder+vo" lets mpv skip decoding late frames at the
            // MediaCodec stage to catch the picture back up to the audio clock. It only drops when actually
            // behind, so content that decodes in time is untouched.
            setOptionString("framedrop", "decoder+vo")
            // Quiet logcat in release; debug builds keep decoder/video-out logs for diagnosing
            // hwdec behavior on real TVs (which decoder engaged, why fallbacks happened).
            setOptionString("msg-level", if (tv.own.owntv.core.CoreBuildInfo.debug) "all=warn,vd=v,vo=v" else "all=warn")
            // Demuxer cache sized to the device (a fixed 256MiB OOM-killed real TVs — see PlayerBudget).
            setOptionString("cache", "yes")
            setOptionString("demuxer-max-bytes", budget.demuxerMaxBytes)
            setOptionString("demuxer-max-back-bytes", budget.demuxerBackBytes)
            setOptionString("demuxer-readahead-secs", budget.readaheadSecs)
            setOptionString("cache-secs", budget.cacheSecs)
            if (budget.lowSpec) {
                // GL diet for TV-class GPUs (e.g. PowerVR BXE on budget 4K panels): mpv's default
                // render path tone-maps 4K HDR in rgba16f with quality scalers — that alone drops
                // a TCL G10 to half-speed video. "fast" = bilinear scalers, no dither/deband.
                setOptionString("profile", "fast")
                setOptionString("fbo-format", "rgba8") // 4K rgba16f intermediates are ~64MB each
                setOptionString("tone-mapping", "clip") // cheapest HDR→SDR
            }
            setOptionString("network-timeout", "60")
            // Strict IPTV panels briefly answer 5xx (e.g. 509 connection-limit right after a channel
            // switch, while the old session still counts). Let FFmpeg retry those itself instead of
            // EOF-ing the stream — the demuxer cache rides over the gap with no visible interruption.
            setOptionString("stream-lavf-o", STREAM_RECONNECT_OPTIONS)
            setOptionString("user-agent", HttpClient.DEFAULT_USER_AGENT)
            setOptionString("sub-scale-with-window", "yes")
            // Subtitle appearance (#96) — applied at init so the very first subtitle of the session
            // already looks right. Each option is skipped entirely while it (or the master toggle)
            // is on "Default", leaving mpv's own value in place.
            if (subStyleOn) {
                if (SubtitleStyle.hasScale(subScale.toFloat())) setOptionString("sub-scale", subScale.toString())
                subFont?.let { setOptionString("sub-font", it.mpvFamilyName) }
                if (SubtitleStyle.hasColor(subColorHex)) setOptionString("sub-color", SubtitleStyle.mpvColor(subColorHex))
                if (SubtitleStyle.hasOpacity(subBgOpacity)) setOptionString("sub-back-color", SubtitleStyle.mpvBackColor(subBgOpacity))
                if (subPosition != SubtitleStyle.Position.DEFAULT) {
                    setOptionString("sub-pos", SubtitleStyle.mpvSubPos(subPosition).toString())
                    setOptionString("sub-align-x", SubtitleStyle.mpvAlignX(subPosition))
                }
                if (subStyleOverridesAss()) setOptionString("sub-ass-override", "force")
            }
            setOptionString("audio-delay", audioDelaySec.toString())
            if (prefAudioLang.isNotBlank()) setOptionString("alang", prefAudioLang)
            if (prefSubLang.isNotBlank()) setOptionString("slang", prefSubLang)
            setOptionString("subs-with-matching-audio", if (prefSubLang.isBlank()) "no" else "yes")
            // HDR passthrough: signal the source colorspace (incl. HDR10/HLG) to the display surface.
            setOptionString("target-colorspace-hint", if (hdrHint) "yes" else "no")
            init()
            // Read back what mpv actually accepted — setOptionString failures are silent, and this
            // line also identifies the running build in logcat captures.
            _directRender.value = useDirect()
            android.util.Log.i(
                TAG,
                "mpv ready: lowSpec=${budget.lowSpec} direct=${useDirect()} hwdec=${getPropertyString("hwdec")} " +
                    "fbo=${getPropertyString("fbo-format")} cache=${getPropertyString("demuxer-max-bytes")}",
            )
            observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("container-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE) // buffer ghost
            // Decode watchdog input: which decoder is actually active ("mediacodec[-copy]" or "no").
            observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING) // for the error screen spec line
            // Current subtitle line for the app-drawn overlay (direct mode); fires only on change.
            observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            addObserver(this@OwnTVPlayer)
            addLogObserver(logObserver) // capture mpv's error output for the on-screen "err: …" detail line
        }
        // Everything below is per-player, not per-mpv-core: run it on the first init only, never again
        // on the re-init that follows a hard reset (see [oneTimeInitDone]).
        if (!oneTimeInitDone) {
            oneTimeInitDone = true
            diagnostics.start() // tail logcat for MediaCodec/AudioTrack errors mpv can't surface
            // When a friendly error is surfaced, expose the real reason beneath it — prefer a system codec/audio
            // error (e.g. MediaCodec 0x80001000) from this stream, else mpv's own last log line.
            scope.launch {
                _error.collect {
                    _errorInfo.value = if (it != null) {
                        val raw = diagnostics.recentError() ?: load.lastMpvError
                        val info = ErrorInfo(reason = raw?.let(PlayerErrors::reasonFor), spec = mediaSpec(), raw = raw)
                        // Persist for Settings → "Playback error log" (users can't pull logcat after the fact).
                        PlaybackErrorLog.log(context, if (exoActive) "exoplayer" else "mpv", isLiveContent, info)
                        info
                    } else null
                }
            }
        }
        initialized = mpv != null
    }

    /** Play a single item (movie / live channel) — clears any queue. [muted] is used by the live preview.
     *  [userAgent] is the per-source custom UA from source settings; null means use the default. */
    fun play(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        year: String? = null,
        logoUrl: String? = null,
        isLive: Boolean = false,
        startPositionMs: Long = 0,
        muted: Boolean = false,
        /** Catch-up / live-rewind archive stream — see [ItemState.archiveThisItem]. */
        isArchive: Boolean = false,
        startPaused: Boolean = false,
        userAgent: String? = null,
        /** Per-channel HTTP headers serialized as `Key: Value` per line (M3U, F16); null for none. */
        httpHeaders: String? = null,
        /** Widevine/ClearKey licence details (#115); non-null pins the item to ExoPlayer. */
        drmConfig: String? = null,
        /** P6 — stable engine-pin identity; null keeps the legacy stream-URL key. */
        contentKey: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        rewindStartMs: Long? = null,
        /** Per-playlist "Pre-buffer" override in seconds; null follows the global setting. */
        livePrerollSecsOverride: Int? = null,
        /** Per-playlist Live latency override; null follows the global setting. */
        liveBufferOverride: LiveBuffer.Override? = null,
        /** Expiring-URL provider for THIS item (Stalker VOD). See [reconnectUrlProvider]. */
        reconnectProvider: tv.own.owntv.core.stalker.ReconnectUrlProvider? = null,
    ) {
        // F12 — the provider belongs to the load. A VOD load with none clears whatever the previous
        // item left behind; live keeps the field as-is when none is passed, because LiveViewModel
        // installs the live provider on BOTH engines just before calling this.
        if (reconnectProvider != null || !isLive) reconnectUrlProvider = reconnectProvider
        currentHeaders = StreamHeaders.decode(httpHeaders)
        currentDrm = tv.own.owntv.core.drm.DrmConfig.decode(drmConfig)
        // The channel's own UA wins over the playlist-wide one (F16): a playlist sets one UA for the
        // whole provider, an EXTVLCOPT line sets it for the one restream that needs it.
        currentUserAgent = StreamHeaders.userAgentOf(currentHeaders) ?: userAgent?.takeIf { it.isNotBlank() }
        tunedUserAgent = userAgent?.takeIf { it.isNotBlank() }
        tunedHttpHeaders = httpHeaders
        prerollOverrideSecs = livePrerollSecsOverride
        this.liveBufferOverride = liveBufferOverride
        playlist = emptyList()
        playlistIndex = 0
        updateNav()
        _zoomMode.value = defaultZoom // start new content at the user's default zoom
        if (!muted) setVolume(defaultVolume) // …and at the default volume; loadUrl re-applies any per-item override
        loadUrl(
            url,
            MediaMeta(title, subtitle, year, logoUrl, contentKey, seasonNumber, episodeNumber, rewindStartMs),
            isLive,
            startPositionMs,
            muted,
            isArchive = isArchive,
            startPaused = startPaused,
        )
    }

    /** Play a queue (a season's episodes) starting at [startIndex] — enables prev/next.
     *  [userAgent] is the per-source custom UA from source settings; null means use the default. */
    fun playEpisodes(items: List<PlaylistItem>, startIndex: Int, startPositionMs: Long = 0, userAgent: String? = null) {
        // Headers are a per-ITEM property in a queue (an M3U episode line can carry its own
        // #EXTVLCOPT), so they're applied in loadItem, not once for the whole queue.
        queueUserAgent = userAgent?.takeIf { it.isNotBlank() }
        playlist = items
        playlistIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val item = items.getOrNull(playlistIndex) ?: return
        _zoomMode.value = defaultZoom
        setVolume(defaultVolume)
        loadItem(item, startPositionMs)
        updateNav()
    }

    fun next() {
        if (playlistIndex < playlist.size - 1) {
            playlistIndex++
            playCurrent()
        }
    }

    fun previous() {
        if (playlistIndex > 0) {
            playlistIndex--
            playCurrent()
        }
    }

    private fun playCurrent() {
        val item = playlist.getOrNull(playlistIndex) ?: return
        loadItem(item, startPositionMs = 0)
        updateNav()
        _queueItemChanged.tryEmit(playlistIndex)
    }

    /** Load a queue item. A plain item loads its stored URL synchronously (unchanged path); an item
     *  with [PlaylistItem.resolveUrl] awaits the fresh URL off-main first, aborting if a newer
     *  load/stop supersedes it meanwhile (same generation guard as the live reconnect resolve). */
    private fun loadItem(item: PlaylistItem, startPositionMs: Long) {
        val resolve = item.resolveUrl
        // Per-item headers replace (never merge with) the previous item's, so a queue that mixes
        // header-carrying and plain episodes can't leak one item's Referer onto the next.
        currentHeaders = StreamHeaders.decode(item.httpHeaders)
        currentDrm = tv.own.owntv.core.drm.DrmConfig.decode(item.drmConfig)
        currentUserAgent = StreamHeaders.userAgentOf(currentHeaders) ?: queueUserAgent
        tunedUserAgent = queueUserAgent
        tunedHttpHeaders = item.httpHeaders
        // F12 — a queue item that mints its URL (Stalker episode) reuses that same resolver as its
        // reconnect provider, so a retry after the short-lived link expires asks the portal again
        // instead of replaying a dead URL. An item with a stable URL clears the previous item's
        // provider: the lifetime is the load, never the player.
        reconnectUrlProvider = resolve?.let { tv.own.owntv.core.stalker.ReconnectUrlProvider { it() } }
        if (resolve == null) {
            loadUrl(item.url, item.meta, isLive = false, startPositionMs)
            return
        }
        val gen = loadGeneration
        _buffering.value = true // resolving counts as loading — keep the spinner up instead of a dead frame
        scope.launch {
            val url = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { resolve() }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "queue item resolve failed: ${e.message}")
                if (gen == loadGeneration) {
                    _buffering.value = false
                    _error.value = PlaybackFailure.StreamLink
                }
                return@launch
            }
            if (gen != loadGeneration) return@launch // superseded by another load/stop meanwhile
            loadUrl(url, item.meta, isLive = false, startPositionMs)
        }
    }

    private fun updateNav() {
        _nav.value = NavState(playlistIndex > 0, playlistIndex < playlist.size - 1)
        _nextUpTitle.value = playlist.getOrNull(playlistIndex + 1)?.meta?.title
    }

    /** The metadata of the item currently loaded — every reload (retry, reconnect, engine switch,
     *  background restore) re-passes this rather than rebuilding it field by field, so a field added
     *  to [MediaMeta] can't be dropped on the way (P6: [MediaMeta.contentKey] is one such field). */
    private fun currentMetaSnapshot() =
        MediaMeta(currentTitle, currentSubtitle, currentYear, currentLogoUrl, currentContentKey, currentSeasonNumber, currentEpisodeNumber, currentRewindStartMs)

    /**
     * The `.ts` ⇄ `.m3u8` sibling of the item's ORIGINAL URL, or null when there is nothing to swap
     * (no extension, or it is already what we're playing).
     *
     * Derived from [ItemState.altFormatBaseUrl] rather than `currentUrl` on purpose: by the time this runs the
     * loaded URL may be the extensionless `timeshift.php` alternate, which has no extension to flip.
     *
     * The swap itself lives in [LiveStreamQuirks.alternateFormatUrl], shared with the ExoPlayer rung so
     * a channel gets the same sibling whichever engine asks for it.
     */
    private fun alternateFormatUrl(): String? {
        val base = item.altFormatBaseUrl ?: return null
        // A refusal — 403, 429, a session limit — is the panel answering the *account*, and the same
        // answer waits at every URL on it. Swapping the extension can only invent a URL that 404s, so
        // this rung stays out of the way and lets the real refusal reach the user's screen.
        val refused = load.lastMpvError?.let { PlayerErrors.httpStatusIn(it) }
            ?.let { LiveStreamQuirks.isRequestRefusal(it) } == true
        if (refused) return null
        return LiveStreamQuirks.alternateFormatUrl(base)?.takeIf { it != currentUrl }
    }

    /**
     * Reopen this stream with FFmpeg's error tolerance on — the rung for a feed mpv's strict demuxer
     * defaults reject outright, and the one that closes most of the "VLC plays it, we don't" gap.
     * Remembered for the session so the same channel doesn't pay the failed strict attempt again.
     */
    private fun noteNeedsTolerantDemux(url: String, why: String) {
        if (item.triedTolerantDemux) return
        item.triedTolerantDemux = true
        item.tolerantDemuxThisLoad = true
        LiveStreamQuirks.rememberNeedsTolerantDemux(url)
        LiveDiagnosticsLog.event("mpv $why — reopening with tolerant demuxing (discardcorrupt/genpts/ignore_err)")
    }

    /** P6 — move a legacy URL-keyed VOD pin onto the stable content key (no-op without one). */
    private fun migrateVodPin(url: String, stableKey: String?) {
        if (stableKey == null) return
        scope.launch { vodEngineStore.migrateKey(url, stableKey) }
    }

    private fun loadUrl(
        url: String,
        meta: MediaMeta,
        isLive: Boolean,
        startPositionMs: Long,
        muted: Boolean = false,
        resetRetries: Boolean = true,
        isArchive: Boolean = false,
        startPaused: Boolean = false,
    ) {
        // Internal retries keep the current item's notice; a new item must never inherit it.
        if (resetRetries) clearToast()
        ensureInit()
        // A new item must never inherit an external AudioMix track from the previous channel.
        // Same-item retries keep it intact so recovery does not silently drop commentary.
        if (resetRetries && _audioMixEnabled.value) mpv?.audioMixDisableInternal()
        // THE per-load reset. Everything this load must not inherit from the previous one is in
        // [LoadState], so forgetting it is one assignment that cannot be partially done. The four values
        // that are not simply cleared come from this call's own arguments.
        load = LoadState(
            loadStartTime = System.currentTimeMillis(),
            pendingStartPaused = startPaused,
            expectingPlayback = true,
            pendingSeekMs = startPositionMs,
        )
        if (resetRetries) deactivateExo() // a brand-new item always plays on mpv (drops any Exo handoff)
        currentTitle = meta.title
        currentSubtitle = meta.subtitle
        currentYear = meta.year
        currentLogoUrl = meta.logoUrl
        currentSeasonNumber = meta.seasonNumber
        currentEpisodeNumber = meta.episodeNumber
        currentRewindStartMs = meta.rewindStartMs
        _currentMeta.value = meta // reactive — refreshes the HUD title / "now watching" card on every load
        isLiveContent = isLive
        currentUrl = url
        currentContentKey = meta.contentKey
        LiveDiagnosticsLog.event(
            "mpv_load live=$isLive url=${HttpClient.redactUrl(url)} " +
                "ua=${if (currentUserAgent.isNullOrBlank()) "default" else "custom"}",
        )
        loadGeneration++
        errorCheckJob?.cancel()
        videoCheckJob?.cancel()
        liveStallJob?.cancel()
        _error.value = null
        diagnostics.markLoad() // scope captured codec/audio errors to this stream
        _videoRes.value = null
        _videoFps.value = null
        _audioOnlyMedia.value = false // re-decided per load, once this item's tracks are known
        // A genuinely new item resets the failure budget; an auto-retry / software-fallback reload of
        // the SAME item passes resetRetries=false to keep that state — see [ItemState].
        if (resetRetries) {
            // Position and duration are pushed by the engines and were never cleared, so a new item
            // inherited the outgoing one's readout until its own first report arrived. For a second
            // or two after an auto-advance the HUD therefore still saw "8 seconds from the end" and
            // popped the next-episode card for the episode AFTER the one just started. Seeding them
            // from this load's own arguments closes that window. Same-item reloads (retry, engine
            // switch, reconnect) keep their live values — they pass resetRetries=false.
            _position.value = startPositionMs
            _duration.value = 0L
            _bufferedMs.value = 0L
            // A/V-sync starts at the Settings default and unremembered, until applyRememberedPrefs
            // below says otherwise. Inside the new-item branch on purpose: a retry, a software
            // fallback or a reconnect is the SAME stream, and resetting there dropped a remembered
            // delay to the global value until the Room read came back (an audible sync jump), and
            // lost an un-remembered nudge outright. Zoom and volume already survive a retry
            // untouched; this is the same rule.
            applyAudioDelay(baseAudioDelayMs)
            _audioDelayRemembered.value = false
            // Decode path for this item, decided BEFORE the reset because it is a compare-then-set: a
            // catch-up archive starts mid-GOP, which SOME hardware decoders can't recover from (audio
            // plays, no video frame ever arrives) — but most cope, and pinning every archive to software
            // cost all of them hardware decoding. So an archive opens in hardware like everything else
            // and only starts in software on a panel already caught failing this session
            // ([tryArchiveSoftwareRescue] does the catching). Everything else follows the user's
            // hardware-decoding setting. (Software renders via GL, broken on the emulator, so skip it there.)
            val wantSoftware = isArchive && !glUnsupported && LiveStreamQuirks.archiveNeedsSoftware(url)
            val needReconfig = item.forceSoftwareThisLoad != wantSoftware || item.ccSoftwareOverride || item.forceCopyThisLoad
            // THE reset. Everything a new item must not inherit is in [ItemState]; the three values that
            // are not simply cleared are handed to the constructor, so the whole thing stays one statement.
            item = ItemState(
                altFormatBaseUrl = url,
                archiveThisItem = isArchive,
                forceSoftwareThisLoad = wantSoftware,
            )
            sessionExternalSubs.clear() // genuinely new item → its own external-sub session
            applySubtitleDelay(0) // timing never carries onto another item/subtitle (§8.4)
            if (needReconfig) mpvAsync { applyRenderConfig() }
        }
        // AFTER the per-load defaults above, never before: this reads the item's own remembered zoom /
        // volume / audio delay and applies them over those defaults, so a reset that ran later would
        // undo it. It is asynchronous and generation-guarded, so it still cannot delay the load.
        applyRememberedPrefs(meta.contentKey ?: url)
        // The decode watchdog's own state rode in [LoadState] above; the StateFlows it feeds are here.
        _videoAspect.value = null
        _videoSize.value = null
        _streamChips.value = emptyList()
        _subText.value = null
        // ExoPlayer-preferred mode (Settings → Video Player): Movies & Series start on ExoPlayer, with
        // mpv as the automatic fallback (the reverse of the default chain). A per-item gear-toggle pin
        // overrides the setting in either direction. Catch-up stays on mpv (an archive needs mpv's
        // mid-GOP handling and its software rescue rung — timeshift recordings are damaged often enough
        // that starting them on ExoPlayer trades a fast start for a support queue), and same-item mpv
        // retries (resetRetries=false, incl. the Exo→mpv fallback itself) never reroute. The HUD gear
        // toggle still switches a playing archive by hand; only the automatic start is pinned.
        // A back-to-back >1080p (4K-class) load on the SAME reused Surface throws Realtek 0x80001000 / a
        // frame-drop "slideshow" (the VPU buffer queue stays dirty after a heavy session), so such a load
        // gets a freshly recreated SurfaceView. Read and clear the flag HERE, above the engine split: the
        // hardware doesn't care which engine draws, and clearing it below the Exo-primary early return
        // left it set — so it leaked into whatever mpv load came next and forced a pointless recreate
        // there, while the Exo load that actually needed one never got it.
        val forceSurfaceReset = forceSurfaceResetNextLoad
        forceSurfaceResetNextLoad = false
        val needsFreshSurface = (lastVideoHeightPx > 1080 || forceSurfaceReset) && surfaceAttached
        // P6 — read the stable key first, then the legacy URL key that older builds wrote; a legacy
        // hit is rewritten under the stable key so it survives the next re-sync/Stalker resolve.
        //
        // Only a pin the USER made with the HUD engine toggle may override the setting. Nothing the
        // player learns by itself does: the setting means "start here every time", so a run of failures
        // — which on a public playlist is usually dead links, not this TV — can never quietly retire the
        // chosen engine. The cost is that a genuinely undecodable file pays its fallback on every open;
        // the user's remedy is the toggle, which is one click and visible.
        val pinKey = meta.contentKey
        val pinnedToExo: Boolean? = when {
            pinKey != null && pinKey in vodPinnedMpv -> false
            pinKey != null && pinKey in vodPinnedExo -> true
            url in vodPinnedMpv -> { migrateVodPin(url, pinKey); false }
            url in vodPinnedExo -> { migrateVodPin(url, pinKey); true }
            else -> null
        }
        // #115 — a protected item can only play on ExoPlayer, which has the CDM; mpv has none. That
        // outranks both the setting and a per-item pin, because the alternative is not a slower route
        // but no route at all.
        val drmProtected = currentDrm != null
        val startOnExo = if (drmProtected) true else pinnedToExo ?: !vodEngine.startsOnMpv
        // A pin that contradicts an "only" setting re-opens the handover for this one item: the user
        // named an engine for it *against* the global rule, so locking that item to the engine they
        // overrode would leave it with no way to reach the one that plays it. Everything else keeps the
        // setting's own rules, including its refusal to hand over at all.
        itemEngine = when {
            drmProtected -> tv.own.owntv.core.player.EnginePreference.EXO_ONLY
            vodEngine.allowsHandover || startOnExo == vodEngine.startsOnMpv ->
                tv.own.owntv.core.player.EnginePreference.firstOn(onMpv = !startOnExo)
            else -> vodEngine
        }
        if (!isLive && startOnExo && resetRetries && !isArchive) {
            item.exoPrimaryThisItem = true
            item.exoVodFallback = true // Exo owns VOD playback as an ENGINE — same HUD interception as fallback
            android.util.Log.i(TAG, "VOD starting on ExoPlayer (preferred engine setting)")
            _buffering.value = true
            val surface = attachedSurface
            if (surface == null) {
                // First open: the player screen's SurfaceView isn't composed yet — attachSurface flushes.
                pendingUrl = url
                load.pendingExoStart = true
            } else if (initialized && mpvHasActiveFile.get()) {
                // A previous item may still be playing on mpv: stop it and free its connection/decoder
                // BEFORE Exo opens (one-connection panels), same ordering as the image-sub handoff.
                val gen = loadGeneration
                mpvAsync {
                    stopWithStopClassification("exo primary vod")
                    setPropertyString("vo", "null")
                    runCatching { this.detachSurface() }
                    scope.launch {
                        if (gen != loadGeneration) return@launch
                        // Wait for mpv's MediaCodec to finish releasing before Exo claims the decoder —
                        // the same wait every other engine transition already does. Without it, a TV box
                        // with a single hardware decoder hands Exo a codec the outgoing engine still
                        // holds, which fails instantly (Realtek 0x80001000 / a black surface).
                        delay(DECODER_RELEASE_MS)
                        if (gen != loadGeneration) return@launch
                        if (needsFreshSurface) {
                            // 4K-class item on a surface the previous heavy session left dirty: recreate the
                            // SurfaceView and let attachSurface route it straight into startExo, exactly as
                            // the manual engine toggle does. Reusing the surface here is what produces the
                            // black picture / slideshow this gate exists to prevent.
                            pendingUrl = url
                            load.pendingExoStart = true
                            _surfaceResetToken.value++
                            return@launch
                        }
                        val s = attachedSurface ?: return@launch
                        startExo(url, startPositionMs, s, sub = null)
                        if (startPaused) exoEngine?.pause()
                    }
                }
            } else if (needsFreshSurface) {
                // Same 4K-class gate with no mpv file to hand off (e.g. Exo→Exo back to back).
                pendingUrl = url
                load.pendingExoStart = true
                _surfaceResetToken.value++
            } else {
                startExo(url, startPositionMs, surface, sub = null)
                if (startPaused) exoEngine?.pause()
            }
            return
        }
        // Mute is a global mpv property, so set it now (applies whenever the file actually loads). The
        // live preview mutes; everything else plays with sound.
        mpvAsync { setPropertyBoolean("mute", muted) }
        // Defer the actual loadfile until a surface exists, otherwise mpv inits video output with no
        // surface and falls back to audio-only. attachSurface() flushes the pending load.
        // A back-to-back >1080p (4K-class) load on the SAME reused Surface throws Realtek 0x80001000 / a
        // frame-drop "slideshow" (the VPU buffer queue stays dirty after a heavy session) — so recreate the
        // SurfaceView first and let the fresh surface's attachSurface() flush this load. Covers BOTH VOD
        // auto-play AND live channel zapping (4K→next 4K via D-pad/CH±, which otherwise hangs until you back
        // out and re-enter — a manual surface recreate). Only when the PREVIOUS item was >1080p, so normal
        // playback and the first 4K load are untouched.
        mpvLoadCount++
        usedFreshSurface = needsFreshSurface
        if (needsFreshSurface) {
            pendingUrl = url
            _surfaceResetToken.value++
        } else if (surfaceAttached) {
            startLoad(url)
        } else {
            pendingUrl = url
        }

        // Catch-up/VOD video watchdog: some archive (timeshift) segments start mid-GOP — audio plays
        // but no H.264 frame ever decodes ("non-existing PPS" → blank, no error). If we're clearly
        // playing (time advancing) yet have no video after a grace window, retry once in software
        // (which recovers at the next keyframe) and only then surface a clear error. Live is excluded:
        // it recovers on its own, and audio-only radio channels are legitimate.
        // Three stages, each measured from load.loadStartTime and each with its own recovery:
        //   Stage 1 (no FILE_LOADED after 10s) — the demuxer never opened the file, likely a malformed
        //           MP4 where avformat_find_stream_info hangs.
        //   Stage 2 (loaded, no height and no bitrate after 6s) — moov-at-end: FILE_LOADED fired but the
        //           metadata that follows it never arrived.
        //   Stage 3 (loaded, still no frame after 7s) — the demuxer finished but the decoder stalled.
        // Consecutive hard-reset guard: 3 in a row = error instead of endless destroy/recreate.
        if (!isLive) {
            val gen = loadGeneration
            videoCheckJob = scope.launch {
                // Check every 1s until we fire or are cancelled
                while (gen == loadGeneration) {
                    delay(1000)
                    if (gen != loadGeneration || isLiveContent) return@launch
                    if (load.currentHeightPx > 0) return@launch // playing normally — cancel watchdog
                    val elapsed = System.currentTimeMillis() - load.loadStartTime
                    // Nothing below can judge an item that has no video track at all. An audio-only VOD
                    // (a radio station filed under Movies, a music-only MP4) loads with no height and no
                    // video bitrate — precisely stage 2's and stage 3's signature — so healthy audio was
                    // declared broken at ~6s. mpv sets `video-codec` as soon as track selection happens,
                    // so this is the same test the live no-video watchdog uses one branch down, with
                    // Audio Mode exempt for the same reason: `vid=no` is the app turning the picture off
                    // on purpose. Only stand down once audio is demonstrably progressing.
                    if (load.fileLoaded && elapsed > 5_000 && _position.value > 0 &&
                        (_audioOnly.value || load.currentVideoCodec == null)
                    ) {
                        android.util.Log.d(TAG, "watchdog — item has no video track and audio is playing; standing down")
                        // Tell the UI, so the black screen carries an explanation instead of looking broken.
                        // Audio Mode is the user's own doing and already has its own presentation.
                        if (!_audioOnly.value) _audioOnlyMedia.value = true
                        return@launch
                    }
                    if (!load.fileLoaded && elapsed > 10_000) {
                        // Stage 1: demuxer hung during probe — stuck, not just slow.
                        // First strike: reset silently and reload the same item once. The common
                        // trigger is auto-play advancing while the provider still holds the finished
                        // episode's connection slot — destroying mpv aborts the stuck request and the
                        // retry then opens cleanly. Only a second hang shows the error.
                        if (!item.triedOpenReset) {
                            item.triedOpenReset = true
                            android.util.Log.w(TAG, "watchdog T_OPEN — no FILE_LOADED after ${elapsed}ms, silent hard-reset + retry")
                            val url = currentUrl
                            val seekMs = load.pendingSeekMs
                            load.expectingPlayback = false
                            _buffering.value = true
                            hardReset()
                            if (url != null) {
                                delay(CORE_RESET_SETTLE_MS) // let the fresh mpv core + recreated surface settle
                                loadUrl(url, currentMetaSnapshot(), isLiveContent, seekMs, resetRetries = false)
                            }
                            return@launch
                        }
                        // L6 — the archive ladder's missing rung. The decode-failure branch below already
                        // hands a failing archive to ExoPlayer, but a demuxer that never opens at all only
                        // ever hard-reset mpv, three times, and then showed an error: an archive mpv simply
                        // cannot open never reached the engine that might have played it. Measured on the
                        // owner's panel, a healthy archive opens in 1.5–2.1 s, so ten seconds without
                        // FILE_LOADED is not slowness, it is a dead end.
                        //
                        // `mpvStuck = true` because that is exactly the situation: the core may be blocked
                        // in an HTTP read the panel is never going to answer, and a one-connection provider
                        // would refuse ExoPlayer's open while mpv still holds the slot.
                        if (item.archiveThisItem && fallbackToExoVod(PlaybackFailure.MpvOpenDecode, mpvStuck = true)) {
                            android.util.Log.w(TAG, "watchdog T_OPEN — archive never opened on mpv after ${elapsed}ms, handing to ExoPlayer")
                            return@launch
                        }
                        android.util.Log.w(TAG, "watchdog T_OPEN — no FILE_LOADED after ${elapsed}ms, HARD-RESETTING mpv")
                        triggerHardReset()
                        return@launch
                    }
                    // Stage 2: moov-at-end detection — FILE_LOADED fired but metadata is missing.
                    // video-bitrate=null means the demuxer can't parse container headers (moov atom
                    // is at end of file + server doesn't support Range requests). This will never fix
                    // itself by retrying.
                    // Read off-thread (A-F1): this loop runs on the main scope, and a blocking JNI read
                    // per tick is exactly what the threading rule at the top of this file forbids.
                    val bitrateKnown = readProperty("video-bitrate")?.toLongOrNull()?.let { it > 0 } ?: false
                    if (load.fileLoaded && load.currentHeightPx == 0 && !bitrateKnown && elapsed > 6_000) {
                        // "Loaded but no height and no bitrate" is ALSO what a failed video DECODER looks
                        // like (MediaCodec err 0xfffffff4 / 0x80001000). Blaming the file there is wrong —
                        // and it dead-ends, while the same screen shows the codec error contradicting it.
                        // So classify first: a decoder signature goes down the decode ladder instead. (F08)
                        val decoderErr = load.lastMpvError ?: diagnostics.recentError()
                        if (looksLikeDecoderFailure(decoderErr) || hardwareCannotDecodeCurrent()) {
                            videoCheckJob?.cancel()
                            if (tryArchiveSoftwareRescue("watchdog: archive decoder failed ('$decoderErr')")) return@launch
                            if (tryDecodeRescue("watchdog MOOV-AT-END but decoder failed ('$decoderErr')")) return@launch
                            android.util.Log.w(TAG, "decoder failure with no rescue rung left — surfacing decode error")
                            load.expectingPlayback = false; _buffering.value = false
                            if (!isLiveContent && fallbackToExoVod(PlaybackFailure.MpvOpenDecode, mpvStuck = false)) return@launch
                            _error.value = vodErrorMessage(decodeFailureMessage(decoderErr))
                            _errorInfo.value = ErrorInfo(decoderErr?.let { PlayerErrors.reasonFor(it) }, mediaSpec(), decoderErr)
                            return@launch
                        }
                        // An archive gets the software rescue even with no decoder text to go on: the
                        // hardware failure this catches is SILENT (the decoder accepts the format, audio
                        // plays, no frame ever arrives), and "not formatted for streaming" would be a
                        // dead end for a stream that plays fine one rung down.
                        if (tryArchiveSoftwareRescue("watchdog: archive loaded but produced no video")) {
                            videoCheckJob?.cancel()
                            return@launch
                        }
                        android.util.Log.w(TAG, "watchdog MOOV-AT-END — FILE_LOADED but no bitrate/height after ${elapsed}ms, aborting (server lacks Range support)")
                                    _error.value = vodErrorMessage(PlaybackFailure.NotStreaming)
                        load.expectingPlayback = false; _buffering.value = false
                        videoCheckJob?.cancel()
                        return@launch
                    }
                    if (load.fileLoaded && elapsed > 7_000) {
                        // Stage 3: demuxer finished but no video frame — decoder stalled. For a catch-up
                        // archive that is the mid-GOP signature, and a hard reset only reproduces it:
                        // reopen in software instead (and teach the panel).
                        if (tryArchiveSoftwareRescue("watchdog T_DECODE — archive produced no frame after ${elapsed}ms")) return@launch
                        android.util.Log.w(TAG, "watchdog T_DECODE — FILE_LOADED but no frame after ${elapsed}ms, HARD-RESETTING mpv")
                        triggerHardReset()
                        return@launch
                    }
                }
            }
        } else {
            // Live silent-freeze watchdog. A live feed can wedge with the socket still open: mpv keeps
            // pause=false / paused-for-cache=false and emits no END_FILE, but time-pos stops advancing — a
            // frozen channel with no spinner, no retry, no error. paused-for-cache (the buffering spinner) and
            // END_FILE (the reconnect path) only cover the cases mpv actually signals; this covers the silent
            // one. Mirrors the ExoPlayer live engine's progress watchdog so both backends behave the same.
            val gen = loadGeneration
            liveStallJob = scope.launch {
                var lastPos = -1L
                var stalls = 0
                // Audio-plays-no-video watchdog: position (audio clock) keeps advancing so the freeze
                // check above never trips, yet mpv selected a video track (load.currentVideoCodec != null,
                // set as soon as track selection happens) and never decoded a single frame
                // (load.currentHeightPx stays 0). Legitimate audio-only radio channels have no video track
                // at all, so they never enter this branch. Reuses the same bounded reconnect budget/UX
                // as the freeze watchdog above.
                var noVideoStalls = 0
                while (gen == loadGeneration) {
                    delay(liveStallPollMs)
                    if (gen != loadGeneration || !isLiveContent) return@launch
                    if (load.expectingPlayback) {
                        val openingMs = System.currentTimeMillis() - load.loadStartTime
                        if (openingMs >= LIVE_OPEN_TIMEOUT_MS) {
                            // Only bound the silent hang here. The `.ts`↔`.m3u8` switch belongs to the
                            // END_FILE ladder, which already owns `item.triedAltFormat`; flipping the format
                            // here too made the two paths take turns and doubled the requests a refusing
                            // panel saw.
                            LiveDiagnosticsLog.event("mpv live open timed out after ${openingMs}ms — surfacing error")
                            load.expectingPlayback = false
                            _isPlaying.value = false
                            _buffering.value = false
                            val raw = load.lastMpvError ?: "No playable data received before timeout"
                            _error.value = PlayerErrors.visibleFailure(raw, currentUrl, PlaybackFailure.Channel)
                            _errorInfo.value = ErrorInfo(PlayerErrors.reasonFor(raw), mediaSpec(), raw)
                            mpvAsync { stopWithStopClassification("live open timeout") }
                            return@launch
                        }
                        continue
                    }
                    // Only a genuinely-playing mpv live stream can "freeze". Skip while still opening
                    // (load.expectingPlayback), while an error is shown, while paused/handed-off to ExoPlayer, or
                    // while mpv itself is buffering (paused-for-cache already drives the spinner there).
                    if (exoActive || load.expectingPlayback || _error.value != null || !_isPlaying.value) {
                        stalls = 0; lastPos = -1L; noVideoStalls = 0
                        continue
                    }
                    val pos = _position.value
                    if (pos > 0 && pos == lastPos) {
                        // No progress since the last poll.
                        if (++stalls < liveStallLimit) continue
                        val frozenMs = liveStallLimit * liveStallPollMs
                        if (!connectivity.isOnlineNow()) {
                            // Offline: keep the spinner up and wait for the network rather than burning the
                            // reconnect budget on a dead connection (it resumes once connectivity returns).
                            android.util.Log.w(TAG, "live stall (mpv, Live) — frozen ~${frozenMs}ms but offline; showing spinner, waiting for network")
                            _buffering.value = true
                            stalls = 0
                            continue
                        }
                        if (item.liveStallReconnects < MAX_LIVE_RECONNECTS) {
                            item.liveStallReconnects++
                            android.util.Log.w(TAG, "live stall (mpv, Live) — no progress for ~${frozenMs}ms, reconnect attempt ${item.liveStallReconnects}/$MAX_LIVE_RECONNECTS")
                            _buffering.value = true // spinner while we re-fetch the live edge
                            // Re-fetch in place, preserving the decoder retry budget; reloadLive bumps the
                            // generation, ending this loop, and starts a fresh watchdog for the new load.
                            val stalledUrl = currentUrl ?: return@launch
                            // A channel that opens and then repeatedly dies mid-stream is the OTHER shape of
                            // the strict-demuxer problem (the END_FILE ladder only sees the ones that never
                            // start). Halfway through the reconnect budget, stop re-fetching the same way and
                            // reopen with error tolerance — reconnecting identically has already failed twice.
                            if (item.liveStallReconnects >= TOLERANT_DEMUX_AFTER_RECONNECTS) {
                                noteNeedsTolerantDemux(stalledUrl, "live stall after ${item.liveStallReconnects} reconnects")
                            }
                            reloadLive(stalledUrl, resetRetries = false)
                            return@launch
                        } else {
                            android.util.Log.w(TAG, "live stall (mpv, Live) — reconnect budget exhausted after $MAX_LIVE_RECONNECTS attempts, surfacing error")
                            _buffering.value = false
                            _error.value = PlaybackFailure.LostConnection
                            return@launch
                        }
                    } else {
                        // Progress (or not yet started) → healthy on the position check. Clear any stall
                        // state and, if we'd been reconnecting, log the recovery and reset the budget.
                        if (stalls > 0 || item.liveStallReconnects > 0) {
                            android.util.Log.i(TAG, "live playback resumed (mpv, Live) after stall/reconnect")
                        }
                        stalls = 0
                        item.liveStallReconnects = 0
                        lastPos = pos
                        // Audio is advancing, but a video track was selected and still hasn't produced a
                        // single decoded frame — the "audio plays, no picture" case position-only checks
                        // above can't see. Audio Mode is exempt: `vid=no` is the app deliberately turning
                        // the picture off, and reporting "no video frame" for it would reconnect a healthy
                        // channel and eventually show a decode error over working audio.
                        if (!_audioOnly.value && load.currentVideoCodec != null && load.currentHeightPx == 0) {
                            if (++noVideoStalls < liveStallLimit) continue
                            val elapsedMs = liveStallLimit * liveStallPollMs
                            LiveDiagnosticsLog.event("no-video (mpv, Live) — audio progressing but no video frame after ~${elapsedMs}ms")
                            if (item.liveStallReconnects < MAX_LIVE_RECONNECTS) {
                                item.liveStallReconnects++
                                noVideoStalls = 0
                                android.util.Log.w(TAG, "no-video (mpv, Live) — reconnect attempt ${item.liveStallReconnects}/$MAX_LIVE_RECONNECTS")
                                _buffering.value = true
                                val stalledUrl = currentUrl ?: return@launch
                                reloadLive(stalledUrl, resetRetries = false)
                                return@launch
                            } else {
                                android.util.Log.w(TAG, "no-video (mpv, Live) — reconnect budget exhausted, surfacing error")
                                _buffering.value = false
                                _error.value = PlaybackFailure.AudioNoVideo
                                return@launch
                            }
                        } else {
                            noVideoStalls = 0
                        }
                    }
                }
            }
        }
    }

    private fun startLoad(url: String) {
        pendingUrl = null
        val gen = loadGeneration
        mpvAsync {
            // Superseded by a newer load or a stop while waiting in the queue? Skip the dead load —
            // this keeps fast preview-scrolling from grinding through every channel it passed.
            if (gen != loadGeneration) return@mpvAsync
            // SAFETY: restore the video output before every loadfile. A previous playback's stop/EOF can
            // leave vo="null" (detachSurface, or the end-file handler), and if we loadfile without
            // restoring it, mpv opens audio-only with a blank screen — and EVERY subsequent load inherits
            // the broken state (a single failed episode poisons all later playback until app restart).
            // This is the "played before, now nothing plays" regression: once the VO goes null it stays null.
            if (surfaceAttached) {
                setPropertyString("vo", targetVo())
                _directRender.value = useDirect()
            }
            applyProbeProfile(url) // trim the demuxer probe for live (faster zap); full probe for VOD
            // Apply the effective User-Agent for this stream. Per-load so a fallback-UA retry or a
            // newly-configured source UA takes effect without restarting the player. With no user
            // setting, a panel already caught refusing the default identity starts on the fallback one,
            // so only the channel that discovered the block ever pays for the retry.
            setPropertyString(
                "user-agent",
                currentUserAgent
                    ?: HttpClient.FALLBACK_USER_AGENT.takeIf { LiveStreamQuirks.blocksDefaultUserAgent(url) }
                    ?: HttpClient.DEFAULT_USER_AGENT,
            )
            // Per-channel headers (F16). Always written, so a channel that carries none clears whatever
            // the previous item set — mpv keeps the property across loads otherwise.
            setPropertyString("http-header-fields", StreamHeaders.toMpvHeaderFields(currentHeaders))
            // reconnect_streamed helps one long-lived raw-TS response, but breaks HLS: EOF is the
            // normal end of a manifest response, so FFmpeg reconnects the same tiny playlist forever.
            setPropertyString(
                "stream-lavf-o",
                streamLavfOptionsFor(url, isLiveContent, LiveStreamQuirks.isHlsUrl(url) && isLiveContent),
            )
            // Global proxy (Approach 1): route mpv's own FFmpeg networking through the configured HTTP
            // proxy, or clear it when disabled. Applied per-load so toggling the setting takes effect on
            // the next stream. The URL may embed proxy credentials — it is NEVER logged here.
            setPropertyString("http-proxy", proxyHolder.mpvProxyUrl() ?: "")
            loadfileWithStopClassification(url, "replacement loadfile")
            setPropertyBoolean("pause", false)
        }
        // mpv only fires the "pause" observer on a *change*; at startup pause is already false, so the
        // observer never fires and nothing would move this off its initial value. Seed it here —
        // otherwise the HUD sits on a stale paused state while the stream is actually running.
        _isPlaying.value = true
    }

    /** Mute/unmute without reloading — lets preview → fullscreen reuse the same stream connection. */
    fun setMuted(muted: Boolean) {
        if (initialized) mpvAsync { setPropertyBoolean("mute", muted) }
    }

    fun togglePlayPause() {
        if (exoActive) { exoEngine?.togglePlayPause(); return }
        if (initialized) mpvAsync { command(arrayOf("cycle", "pause")) }
    }

    fun seekBy(deltaMs: Long) {
        if (exoActive) { exoEngine?.seekBy(deltaMs); return }
        if (initialized) mpvAsync { command(arrayOf("seek", (deltaMs / 1000).toString(), "relative")) }
    }

    fun setSpeed(speed: Double) {
        if (exoActive) exoEngine?.setSpeed(speed) else if (initialized) mpvAsync { setPropertyDouble("speed", speed) }
        _speed.value = speed
    }

    /**
     * True when the user's picks can only be honoured by discarding a file's own ASS styling —
     * mpv's default "yes" already lets [sub-color] through for plain SRT, but ASS wins otherwise.
     * A file left entirely on "Default" options keeps its authored styling untouched.
     */
    private fun subStyleOverridesAss(): Boolean = subStyleOn && (
        subFont != null ||
        SubtitleStyle.hasColor(subColorHex) ||
            SubtitleStyle.hasOpacity(subBgOpacity) ||
            subPosition != SubtitleStyle.Position.DEFAULT
        )

    /**
     * Push the custom subtitle look (#96) onto the running mpv instance. Every option resolves to
     * mpv's own value when it's on "Default" (or the master toggle is off). Must run on the mpv
     * thread (call inside [mpvAsync]).
     *
     * Writing the defaults back is not optional: these properties persist on the instance, so
     * restoring them explicitly is the only thing that makes turning an option back to "Default"
     * take effect on a file that is already playing.
     */
    private fun MPVLib.applySubtitleStyle() {
        val on = subStyleOn
        setPropertyDouble(
            "sub-scale",
            if (on && SubtitleStyle.hasScale(subScale.toFloat())) subScale else MPV_DEFAULT_SUB_SCALE,
        )
        setPropertyString("sub-font", if (on) subFont?.mpvFamilyName ?: MPV_DEFAULT_SUB_FONT else MPV_DEFAULT_SUB_FONT)
        setPropertyString(
            "sub-color",
            if (on && SubtitleStyle.hasColor(subColorHex)) SubtitleStyle.mpvColor(subColorHex) else MPV_DEFAULT_SUB_COLOR,
        )
        setPropertyString(
            "sub-back-color",
            if (on && SubtitleStyle.hasOpacity(subBgOpacity)) SubtitleStyle.mpvBackColor(subBgOpacity) else MPV_DEFAULT_SUB_BACK_COLOR,
        )
        val position = if (on) subPosition else SubtitleStyle.Position.DEFAULT
        setPropertyInt("sub-pos", SubtitleStyle.mpvSubPos(position))
        // Horizontal alignment is a newer mpv option than the rest — never let a build without it
        // take down the whole style update.
        runCatching { setPropertyString("sub-align-x", SubtitleStyle.mpvAlignX(position)) }
        setPropertyString("sub-ass-override", if (subStyleOverridesAss()) "force" else "yes")
    }

    private fun applyAudioDelay(ms: Int) {
        _audioDelayMs.value = ms
        audioDelaySec = ms / 1000.0
        if (initialized) mpvAsync { setPropertyDouble("audio-delay", audioDelaySec) }
    }

    /** In-player A/V-sync nudge for a badly-muxed file (positive = delay audio). Per-file: resets to the
     *  Settings default on the next item, so it never carries a wrong offset onto a good file. */
    fun adjustAudioDelay(deltaMs: Int) {
        applyAudioDelay((_audioDelayMs.value + deltaMs).coerceIn(-10_000, 10_000))
        // Already remembering this item? Then the stored number follows the one on screen, or the
        // toggle would keep claiming to remember a value the user has since nudged away from.
        if (_audioDelayRemembered.value) {
            val key = currentContentKey ?: currentUrl ?: return
            val ms = _audioDelayMs.value
            scope.launch { playbackPrefs.rememberAudioDelay(key, ms) }
        }
    }

    // --- Subtitle timing (subtitle plan §8): offset for the ACTIVE subtitle. Positive = shown later. ---

    /** True when timing adjustment applies to the active subtitle on this engine (§8.1): any text sub
     *  on mpv (`sub-delay`); side-loaded external subs only on ExoPlayer (shifted copy at load). */
    fun subtitleTimingAvailable(): Boolean {
        if (isLiveContent) return false
        val sel = _subTrackList.value.firstOrNull { it.selected } ?: return false
        if (sel.image) return false
        return if (exoActive) sessionExternalSubs.any { it.title == sel.label } else true
    }

    fun adjustSubtitleDelay(deltaMs: Int) {
        setSubtitleDelay((_subDelayMs.value + deltaMs).coerceIn(-30_000, 30_000), byUser = true)
    }

    fun resetSubtitleDelay() = setSubtitleDelay(0, byUser = true)

    /** Apply a remembered offset (from the subtitle layer, §8.4) without re-persisting it. */
    fun applySubtitleDelay(offsetMs: Int) = setSubtitleDelay(offsetMs, byUser = false)

    private fun setSubtitleDelay(ms: Int, byUser: Boolean) {
        _subDelayMs.value = ms
        if (exoActive) {
            // Each Exo change re-prepares the stream (shifted-file side-load) — debounce so holding a
            // step button doesn't restart playback per 100 ms press.
            exoSubDelayJob?.cancel()
            exoSubDelayJob = scope.launch {
                delay(EXO_SUB_DELAY_DEBOUNCE_MS)
                val sel = _subTrackList.value.firstOrNull { it.selected } ?: return@launch
                if (sessionExternalSubs.any { it.title == sel.label }) exoEngine?.setSubtitleDelayMs(ms, sel.label)
            }
        } else if (initialized) {
            mpvAsync { setPropertyDouble("sub-delay", ms / 1000.0) }
        }
        if (byUser) onSubtitleDelayUserChange?.invoke(ms)
    }

    /** Tell the subtitle layer which subtitle is active ("path:&lt;file&gt;" external, "emb:…" embedded,
     *  null off) so it can apply that subtitle's remembered timing (§8.4). */
    private fun notifyActiveSubtitle(track: TrackOption?) {
        val identity = track?.let { t ->
            sessionExternalSubs.firstOrNull { it.title == t.label }?.let { "path:${it.path}" }
                ?: "emb:${t.typeIndex}:${t.lang ?: ""}"
        }
        onActiveSubtitleChanged?.invoke(identity)
    }

    // --- Per-item zoom / volume the user asked us to remember (playback_prefs, DB v32) ---

    /**
     * Apply whatever the user last chose for THIS item, over the global defaults already in place.
     *
     * Only what was actually remembered is applied: an item with no zoom row keeps the default zoom,
     * one with no volume row keeps the volume carried over from the previous item (which is how the
     * player has always behaved inside a season). The read is a two-column lookup on a tiny table,
     * but it still can't block the load, so a late answer is discarded — [gen] pins it to this load.
     */
    private fun applyRememberedPrefs(key: String) {
        val gen = loadGeneration // loadUrl has already incremented it for this load by the time we run
        scope.launch {
            val row = playbackPrefs.prefsFor(key) ?: return@launch
            if (gen != loadGeneration) return@launch // a newer item started while we were reading
            row.zoomMode?.let { name ->
                runCatching { ZoomMode.valueOf(name) }.getOrNull()?.let { setZoomMode(it) }
            }
            row.volumeBoost?.let { setVolume(it) }
            row.audioDelayMs?.let {
                applyAudioDelay(it)
                _audioDelayRemembered.value = true
            }
        }
    }

    /** Deliberate zoom choice from the HUD — applied now and remembered for this item. */
    fun setZoomModeByUser(mode: ZoomMode) {
        setZoomMode(mode)
        val key = currentContentKey ?: currentUrl ?: return
        scope.launch { playbackPrefs.rememberZoom(key, mode.name) }
    }

    /** Deliberate volume change from the HUD — applied now and remembered for this item. Mute is
     *  deliberately NOT remembered: it is a momentary action, not a preference for the title. */
    fun adjustVolumeByUser(delta: Int) {
        adjustVolume(delta)
        val key = currentContentKey ?: currentUrl ?: return
        val level = _volume.value
        scope.launch { playbackPrefs.rememberVolume(key, level) }
    }

    /**
     * HUD "Remember this delay": store the current A/V-sync offset for this item, or forget it again.
     *
     * Forgetting also returns playback to the global Settings value straight away, so the toggle is
     * honest — leaving the stream on an offset it no longer remembers would look like it had failed.
     */
    fun toggleRememberAudioDelay() {
        val key = currentContentKey ?: currentUrl ?: return
        val remember = !_audioDelayRemembered.value
        _audioDelayRemembered.value = remember
        val ms = if (remember) _audioDelayMs.value else null
        if (!remember) applyAudioDelay(baseAudioDelayMs)
        scope.launch { playbackPrefs.rememberAudioDelay(key, ms) }
    }

    // --- Volume (mpv software volume, independent of the system/hardware volume) ---
    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 150)
        if (exoActive) exoEngine?.setVolume(v) else if (initialized) mpvAsync { setPropertyDouble("volume", v.toDouble()) }
        _volume.value = v
        if (v > 0) preMuteVolume = v
    }

    fun adjustVolume(delta: Int) = setVolume(_volume.value + delta)

    fun toggleMute() {
        if (_volume.value > 0) { preMuteVolume = _volume.value; setVolume(0) } else setVolume(preMuteVolume.coerceAtLeast(10))
    }

    // --- Zoom / aspect ---
    fun setZoomMode(mode: ZoomMode) {
        _zoomMode.value = mode
        android.util.Log.i(TAG, "setZoomMode: mode=$mode direct=${_directRender.value} aspect=${_videoAspect.value} live=$isLiveContent")
        // The surface VIEW resizes/crops itself per mode (see MpvVideoSurface, which observes zoomMode)
        // for every render path. Direct mode already fills the surface edge-to-edge on its own. GL mode
        // (software rescue) must be told to do the same — mpv's own keepaspect/panscan/aspect-override
        // are legacy properties that modern vo=gpu(-next) mostly ignores anyway, so rather than lean on
        // them we just disable mpv's internal scaling entirely and let the view be the single source of
        // truth for aspect/zoom in both render paths.
        if (_directRender.value) return
        mpvAsync {
            setPropertyString("video-unscaled", "no")
            setPropertyDouble("panscan", 0.0)
            setPropertyString("keepaspect", "no")
            setPropertyString("video-aspect-override", "no")
        }
    }

    /**
     * HUD "Retry" on an error. The HUD offers it for *any* failed playback, so this must not assume
     * live: reloading a movie through the live path restarted it at 00:00 with live demuxer settings
     * and the live watchdogs attached, and — with a stale Stalker [reconnectUrlProvider] left over
     * from a previous channel — could even mint and play a live URL instead of the movie (F11).
     */
    fun retry() {
        val url = currentUrl ?: return
        reload(url, isLive = isLiveContent, resetRetries = true)
    }

    /**
     * Re-fetch the live edge — used by the stall-reconnect watchdogs. Always reloads as live from
     * the edge; see [reload] for the shared body.
     */
    private fun reloadLive(url: String, resetRetries: Boolean) = reload(url, isLive = true, resetRetries = resetRetries)

    /**
     * Reload the current item. For an expiring-URL source (Stalker, [reconnectUrlProvider] set) it
     * mints a fresh URL first (a network call on IO); otherwise it reloads [url] directly.
     * `loadGeneration` is captured so a zap/stop during the resolve aborts the reload.
     *
     * Live restarts at the edge; VOD resumes where it stopped, which is the whole point of retrying
     * a movie 40 minutes in.
     */
    private fun reload(url: String, isLive: Boolean, resetRetries: Boolean) {
        val startAt = if (isLive) 0L else _position.value
        val provider = reconnectUrlProvider
        if (provider == null) {
            loadUrl(url, currentMetaSnapshot(), isLive = isLive, startPositionMs = startAt, resetRetries = resetRetries)
            return
        }
        val gen = loadGeneration
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { provider.freshUrl() }.getOrNull()
            }
            if (gen != loadGeneration || currentUrl == null) return@launch // zapped/stopped during resolve
            loadUrl(fresh ?: url, currentMetaSnapshot(), isLive = isLive, startPositionMs = startAt, resetRetries = resetRetries)
        }
    }

    fun stop() {
        clearToast()
        deactivateExo() // give the surface back to mpv before tearing down
        load.pendingExoStart = false
        loadGeneration++ // cancels any queued-but-not-yet-executed load
        load.expectingPlayback = false
        errorCheckJob?.cancel()
        videoCheckJob?.cancel()
        liveStallJob?.cancel()
        if (initialized) mpvAsync { stopWithStopClassification("stop") }
        currentUrl = null
        pendingUrl = null
        _isPlaying.value = false
        _buffering.value = false
        _audioOnlyMedia.value = false
    }

    /** Live TV's fallback ladder has given up on the channel mpv is holding — no rung left, or the whole
     *  tune ran out of time. Stop the stream and put the failure on screen: mpv can sit on a stream that
     *  never arrives without ever reporting an error, so the spinner would otherwise never clear. */
    fun abandonLive(reason: String) {
        val url = currentUrl
        stop()
        _error.value = PlayerErrors.visibleFailure(reason, url, PlaybackFailure.Channel)
    }

    /** True while mpv owns a stream (loaded or loading) — i.e. while it may still hold a provider session. */
    val hasActiveStream: Boolean get() = currentUrl != null || pendingUrl != null

    private val _active = MutableStateFlow(false)

    /**
     * [hasActiveStream] as a flow, for UI that has to appear and disappear with the stream rather than
     * ask about it — the mobile app's docked mini player, which is on screen exactly while something is
     * playing and cannot poll a getter.
     */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private fun refreshActive() {
        _active.value = currentUrlField != null || pendingUrlField != null
    }

    /**
     * [stop], then wait until mpv has really finished tearing the stream down.
     *
     * [stop] only *queues* the stop on mpv's single-threaded executor, so a caller that starts another
     * engine straight afterwards races FFmpeg's socket. Queueing a barrier behind the stop and waiting
     * for it is exact: when the barrier runs, mpv has returned from the stop command. That matters on
     * panels that allow one session per account ([LiveStreamQuirks.isSingleSession]) — there the second
     * engine is refused outright while the first is still connected.
     *
     * Bounded: a wedged mpv core must not freeze an engine switch. Returns false if the wait timed out.
     */
    suspend fun stopAndAwaitRelease(timeoutMs: Long = MPV_RELEASE_TIMEOUT_MS): Boolean {
        stop()
        if (!initialized) return true
        val stopped = CountDownLatch(1)
        val queued = runCatching { mpvExecutor.execute { stopped.countDown() } }.isSuccess
        if (!queued) return true // executor gone: nothing is holding a stream either
        return withContext(Dispatchers.IO) { stopped.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Nuclear option for a stuck demuxer: when mpv's core thread is BLOCKED inside a multi-GB HTTP seek
     * (a malformed MP4 with broken UDTA atoms), `stop`/`loadfile` can't help — they queue behind the
     * blocked thread and never execute. The ONLY way to abort the stuck connection is to DESTROY the mpv
     * instance entirely and create a fresh one. `mpv.destroy()` aborts all pending I/O immediately.
     *
     * Runs on a DEDICATED thread (not mpvExecutor — that's the one that's BLOCKED). A fresh `ensureInit()`
     * on the next load recreates the instance from scratch. The surface is force-recreated so the new mpv
     * gets a clean decoder binding (the old MediaCodec was left in a dirty state by the abort).
     */
    private fun triggerHardReset() {
        load.expectingPlayback = false; _buffering.value = false // prevent END_FILE re-trigger loop
        consecutiveHardResets++
        // Before surfacing an error, retry the item once on ExoPlayer — a "malformed" verdict from mpv's
        // demuxer/decoder is often device-specific, and Exo's MediaCodec path may play it fine (mpvStuck:
        // the core may be blocked in the stuck HTTP read, so the fallback destroys it, as hardReset would).
        if (fallbackToExoVod(PlaybackFailure.MpvOpenDecode, mpvStuck = true)) return
        // Surface the error immediately — the user sees "can't play this video" rather than a blank screen.
        _error.value = vodErrorMessage(PlaybackFailure.FileCorrupt)
        if (consecutiveHardResets >= 3) {
            _error.value = vodErrorMessage(PlaybackFailure.MultipleVideos)
            videoCheckJob?.cancel()
            android.util.Log.w(TAG, "hardReset thrash guard — $consecutiveHardResets consecutive resets, aborting")
            return
        }
        hardReset()
    }

    private fun hardReset() {
        val oldMpv = mpv
        mpv = null
        initialized = false
        surfaceAttached = false
        loadGeneration++
        pendingUrl = null
        // The core about to be destroyed owes these END_FILEs; the fresh one must not inherit them, or
        // its first genuine failure would be misclassified as our own cleanup.
        pendingStopEndFiles.reset()
        // Destroy on a dedicated thread — mpvExecutor is blocked, so we CAN'T use mpvAsync here.
        // destroy() aborts the stuck HTTP read synchronously, freeing the core.
        Thread({
            runCatching {
                oldMpv?.let {
                    it.removeObserver(this)
                    it.destroy()
                }
            }
            android.util.Log.i(TAG, "hardReset: old mpv instance destroyed — core unblocked")
        }, "owntv-mpv-destroy").start()
        // Force the UI to recreate the SurfaceView, so the fresh mpv instance gets a clean decoder binding
        // (the old MediaCodec was left dirty by the abort — a back-to-back 4K load on the same Surface
        // would throw Realtek 0x80001000).
        _surfaceResetToken.value++
    }

    /**
     * The app moved to the background (Home / another app). An IPTV player has no background
     * playback — stop the stream so the demuxer cache and decoder buffers are freed immediately.
     * Holding them got the process LMK-killed at 490–620 MB PSS while invisible ("empty" state).
     */
    fun onAppBackgrounded() {
        val url = currentUrl ?: pendingUrl
        if (url != null) {
            // Remember a non-live item so the screensaver/Home → return can restore it paused at its
            // position. (Live just re-tunes; the archive/VOD stream is freed for memory while invisible.)
            backgroundRestore = if (!isLiveContent) {
                BackgroundRestore(
                    url, currentMetaSnapshot(), _position.value, _isPlaying.value,
                    tunedUserAgent, tunedHttpHeaders, reconnectUrlProvider,
                )
            } else null
            stop()
        }
    }

    /** Paired with [onAppBackgrounded]: restore a VOD that was freed while the app was in the background
     *  (e.g. the TV screensaver), so pressing Play just works instead of doing nothing on a freed stream. */
    fun onAppForegrounded() {
        val r = backgroundRestore ?: return
        backgroundRestore = null
        if (currentUrl != null) return // already playing something else
        play(
            r.url,
            title = r.meta.title,
            subtitle = r.meta.subtitle,
            year = r.meta.year,
            logoUrl = r.meta.logoUrl,
            isLive = false,
            startPositionMs = r.positionMs,
            startPaused = !r.wasPlaying,
            userAgent = r.userAgent,
            httpHeaders = r.httpHeaders,
            contentKey = r.meta.contentKey,
            seasonNumber = r.meta.seasonNumber,
            episodeNumber = r.meta.episodeNumber,
            rewindStartMs = r.meta.rewindStartMs,
            // Stalker VOD: without its provider the restore replays a create_link URL that has very
            // likely expired, with no way to mint a fresh one.
            reconnectProvider = r.reconnectProvider,
        )
    }

    /** Drop any pending restore (e.g. on profile switch — don't bring back the previous user's item). */
    fun discardBackgroundRestore() { backgroundRestore = null }

    /**
     * The OS signaled serious memory pressure while we're alive: yield before the kernel takes.
     * Shrinks the demuxer cache live (it prunes already-buffered data too).
     */
    fun onTrimMemory() {
        if (!initialized) return
        mpvAsync {
            setPropertyString("demuxer-max-bytes", PlayerBudget.TRIM_DEMUXER_BYTES)
            setPropertyString("demuxer-max-back-bytes", "8MiB")
        }
    }

    fun release() {
        clearToast()
        contentFds.release()
        // Queued freeze-frame/PixelCopy callbacks must never fire after teardown (released surface/bitmap).
        freezeHandler.removeCallbacksAndMessages(null)
        errorCheckJob?.cancel()
        exoTickJob?.cancel()
        exoActive = false
        exoEngine?.release()
        exoEngine = null
        scope.cancel()
        if (initialized) {
            val m = mpv
            mpv = null
            initialized = false
            // Destroy on the command thread so queued commands drain first (and never block the UI).
            mpvExecutor.execute {
                runCatching {
                    m?.removeObserver(this)
                    m?.destroy()
                }
            }
        }
        mpvExecutor.shutdown()
    }

    // --- Surface (driven by the MpvVideoSurface view) ---
    fun attachSurface(surface: Surface) {
        assertMainThread("attachSurface")
        ensureInit()
        attachedSurface = surface
        surfaceAttached = true
        // A first Exo load, or an Exo renderer rebuild deliberately kept logically active while its old
        // decoder settled, is waiting for this Surface. Consume it before the ordinary exoActive branch.
        if (load.pendingExoStart) {
            load.pendingExoStart = false
            val url = pendingUrl
            val sub = load.pendingExoSub
            pendingUrl = null
            load.pendingExoSub = null
            if (url != null) {
                startExo(url, load.pendingSeekMs, surface, sub = sub)
                if (load.pendingStartPaused) exoEngine?.pause()
            }
            return
        }
        // ExoPlayer owns playback right now (ordinary Surface recreation) → give it the new surface.
        if (exoActive) { exoEngine?.setSurface(surface); return }
        mpv?.attachSurface(surface)
        mpv?.setOptionString("force-window", "yes")
        mpv?.setOptionString("vo", targetVo())
        // Flush a load that was waiting for the surface (so video output inits correctly the first time).
        pendingUrl?.let { startLoad(it) }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        assertMainThread("setSurfaceSize") // surfaceW/H are plain fields, published to main-thread readers
        surfaceW = width; surfaceH = height // remembered for the freeze-frame PixelCopy at handoff time
        if (exoActive) return // ExoPlayer scales to the surface itself; nothing to tell mpv
        if (initialized) mpvAsync { setPropertyString("android-surface-size", "${width}x$height") }
    }

    /**
     * Detach [surface], but only while it is still the one being rendered into.
     *
     * A view handing the picture over to another view — the phone's channel screen going full
     * screen, or the full screen player docking into the mini player — is torn down *after* its
     * replacement has attached, so an unconditional detach at that moment blanks the view that just
     * took over. The TV app has one player view and calls the plain [detachSurface].
     */
    fun detachSurface(surface: Surface) {
        assertMainThread("detachSurface")
        if (attachedSurface !== surface) return
        detachSurface()
    }

    fun detachSurface() {
        assertMainThread("detachSurface")
        surfaceAttached = false
        attachedSurface = null
        if (exoActive) { exoEngine?.setSurface(null); return }
        if (!initialized) return
        mpv?.setPropertyString("vo", "null")
        mpv?.setOptionString("force-window", "no")
        mpv?.detachSurface()
    }

    // --- Audio Mode (Audio Mode plan §5) ---
    // Drop video output but keep audio playing at position. mpv `vid=no` stops the video decoder live
    // (no reload, audio uninterrupted); ExoPlayer (image-sub handoff path) just releases its surface.
    // Restored by exitAudioOnly() before the video surface remounts on return to fullscreen/mini.
    fun enterAudioOnly() {
        if (_audioOnly.value) return
        _audioOnly.value = true
        // Deselect the video track as well as dropping the surface: without it ExoPlayer keeps decoding
        // frames into nothing, so Audio Mode cost the same power as watching (and its first-frame
        // watchdog would eventually declare the device unable to render video).
        if (exoActive) { exoEngine?.setVideoTrackDisabled(true); exoEngine?.setSurface(null); return }
        if (!initialized) return
        mpvAsync { setPropertyString("vid", "no") }
    }

    fun exitAudioOnly() {
        if (!_audioOnly.value) return
        _audioOnly.value = false
        if (exoActive) {
            // Surface before track, always: leaving Audio Mode from the now-playing bar runs while the
            // full-screen SurfaceView is still unmounted, and re-enabling video with no surface makes
            // ExoPlayer build its decoder against a placeholder. The engine defers the re-enable to
            // whichever of these two arrives with a real surface (here, or the later attachSurface).
            attachedSurface?.let { exoEngine?.setSurface(it) }
            exoEngine?.setVideoTrackDisabled(false)
            return
        }
        if (!initialized) return
        mpvAsync { setPropertyString("vid", "auto") }
    }

    // --- Tracks ---
    // Track lists are queried once per loaded file (on mpv's event thread) and cached, so the HUD
    // never issues synchronous mpv reads from the UI thread (those block during network stalls → ANR).
    private val _audioTrackList = MutableStateFlow<List<TrackOption>>(emptyList())
    private val _subTrackList = MutableStateFlow<List<TrackOption>>(emptyList())

    fun audioTracks(): List<TrackOption> = _audioTrackList.value
    fun textTracks(): List<TrackOption> = _subTrackList.value

    fun setBitrateTrackingEnabled(enabled: Boolean) {
        // Gated by the escape-hatch toggle: with it off, no throughput measuring ever starts.
        exoEngine?.setBitrateTrackingEnabled(enabled && measuredStreamStats)
    }

    fun refreshStreamChips() = updateStreamChips()

    /** Technical readout for the stream-info overlay, read live from whichever engine owns playback —
     *  mpv (libmpv get_property is thread-safe) or ExoPlayer (image-sub handoff / engine fallback /
     *  ExoPlayer-preferred; the overlay polls from composition, i.e. the main thread Exo requires). */
    suspend fun streamInfo(): List<StreamInfoRow> {
        if (exoActive) {
            val mode = when {
                item.exoPrimaryThisItem -> StreamEngineMode.PREFERRED
                item.exoVodFallback -> StreamEngineMode.FALLBACK
                else -> StreamEngineMode.IMAGE_SUBTITLE_HANDOFF
            }
            val out = ArrayList<StreamInfoRow>()
            out += StreamInfoRow(StreamInfoLabel.ENGINE, StreamInfoValue.Engine(StreamEngine.EXOPLAYER, mode))
            out += exoEngine?.streamInfo().orEmpty()
            currentUrl?.let { out += StreamInfoRow(StreamInfoLabel.SOURCE, StreamInfoValue.Source(HttpClient.redactUrl(it))) }
            return out
        }
        return readOnMpv { mpvStreamInfo(it) } ?: emptyList()
    }

    /** The mpv half of [streamInfo]: ~25 property reads, which is why it runs on [mpvExecutor] and never
     *  on the caller's thread (A-F2 — the overlay polls this once a second from composition). */
    private fun mpvStreamInfo(m: MPVLib): List<StreamInfoRow> {
        fun str(p: String) = m.getPropertyString(p)?.takeIf { it.isNotBlank() }
        val out = ArrayList<StreamInfoRow>()
        out += StreamInfoRow(StreamInfoLabel.ENGINE, StreamInfoValue.Engine(StreamEngine.MPV))
        (str("file-format") ?: str("demuxer"))?.lowercase()?.let { d ->
            val fmt = when {
                d.contains("hls") -> "HLS"
                d.contains("mpegts") -> "MPEG-TS"
                else -> d.uppercase()
            }
            out += StreamInfoRow(StreamInfoLabel.FORMAT, StreamInfoValue.Format(fmt))
        }
        // Video
        val vw = m.getPropertyInt("video-params/w") ?: m.getPropertyInt("width")
        val vh = m.getPropertyInt("video-params/h") ?: m.getPropertyInt("height")
        val pix = str("video-params/pixelformat").orEmpty()
        val depth = when { "10" in pix -> 10; "12" in pix -> 12; pix.isNotEmpty() -> 8; else -> null }
        if (load.currentVideoCodec != null || str("video-codec") != null || vw != null || vh != null || str("container-fps") != null) {
            out += StreamInfoRow(
                StreamInfoLabel.VIDEO,
                StreamInfoValue.Video(
                    codec = load.currentVideoCodec ?: str("video-codec"),
                    width = vw?.takeIf { it > 0 },
                    height = vh?.takeIf { it > 0 },
                    fps = str("container-fps")?.toDoubleOrNull(),
                    bitDepth = depth,
                ),
            )
        }
        when (str("video-params/gamma")?.lowercase()) {
            "pq" -> StreamHdrMode.HDR10_PQ
            "hlg" -> StreamHdrMode.HLG
            null -> null
            else -> StreamHdrMode.SDR
        }?.let { out += StreamInfoRow(StreamInfoLabel.HDR, StreamInfoValue.Hdr(it)) }
        str("video-bitrate")?.toLongOrNull()?.takeIf { it > 0 }?.let {
            out += StreamInfoRow(StreamInfoLabel.BITRATE, StreamInfoValue.Bitrate(it))
        }
        val hw = str("hwdec-current")
        out += StreamInfoRow(
            StreamInfoLabel.DECODER,
            if (hw != null && hw != "no") {
                StreamInfoValue.Decoder(DecoderKind.NAMED, name = hw, direct = _directRender.value, hardware = true)
            } else {
                StreamInfoValue.Decoder(
                    kind = DecoderKind.SOFTWARE,
                    direct = false,
                    gpu = !_directRender.value,
                )
            },
        )
        val channelCount = m.getPropertyInt("audio-params/channel-count")
        val sampleRate = m.getPropertyInt("audio-params/samplerate")
        val audioBitrate = str("audio-bitrate")?.toLongOrNull()?.takeIf { it > 0 }
        if (str("audio-codec-name") != null || channelCount != null || sampleRate != null || audioBitrate != null) {
            out += StreamInfoRow(
                StreamInfoLabel.AUDIO,
                StreamInfoValue.Audio(
                    codec = str("audio-codec-name")?.uppercase(),
                    channelCount = channelCount,
                    sampleRateHz = sampleRate,
                    bitsPerSecond = audioBitrate,
                ),
            )
        }
        val buffered = str("demuxer-cache-duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val drops = str("frame-drop-count")?.toLongOrNull()
        if (buffered != null || drops != null) {
            out += StreamInfoRow(StreamInfoLabel.BUFFER, StreamInfoValue.Buffer(buffered, drops))
        }
        // What actually left the device, versus what the file contains. mpv never bitstreams (see
        // audio-channels), so the interesting part is the layout the sink accepted and whether the
        // session's stereo safety net has already fired.
        out += StreamInfoRow(
            StreamInfoLabel.AUDIO_OUTPUT,
            StreamInfoValue.AudioOutput(
                kind = AudioOutputKind.PCM,
                channelCount = m.getPropertyInt("audio-out-params/channel-count"),
                multichannelAllowed = multichannelAllowed(),
                fallbackReason = AudioOutputPolicy.latchReason,
            ),
        )
        // What the live buffering settings resolved to for THIS stream, read back from mpv itself — proof
        // that "Pre-buffer" reached the engine, independent of Logcat. Worded as an amount of video, never
        // as a wait — "start after 10s" read as a countdown and made a working setting look broken.
        if (isLiveContent) {
            out += StreamInfoRow(
                StreamInfoLabel.LIVE_BUFFER,
                StreamInfoValue.LiveBuffer(
                    prerollEnabled = str("cache-pause-initial") == "yes",
                    prerollSeconds = str("cache-pause-wait")?.toDoubleOrNull(),
                    readaheadSeconds = str("demuxer-readahead-secs")?.toDoubleOrNull(),
                    playlistOverride = prerollOverrideSecs != null,
                ),
            )
        }
        currentUrl?.let { out += StreamInfoRow(StreamInfoLabel.SOURCE, StreamInfoValue.Source(HttpClient.redactUrl(it))) }
        return out
    }

    /** Synchronous mpv read — only call off the main thread (mpv event thread / mpv-cmd worker). */
    private fun queryTracks(type: String): List<TrackOption> {
        if (!initialized) return emptyList()
        val m = mpv ?: return emptyList()
        val count = m.getPropertyInt("track-list/count") ?: 0
        val out = ArrayList<TrackOption>()
        var typeIndex = 0
        for (i in 0 until count) {
            if (m.getPropertyString("track-list/$i/type") != type) continue
            val id = m.getPropertyInt("track-list/$i/id") ?: continue
            val title = m.getPropertyString("track-list/$i/title")
            val lang = m.getPropertyString("track-list/$i/lang")
            val codec = m.getPropertyString("track-list/$i/codec")
            val selected = m.getPropertyBoolean("track-list/$i/selected") ?: false
            // Image-based subtitle (PGS/VOBSUB/DVB): mpv's direct path can't draw it — on VOD, selecting
            // it hands playback to ExoPlayer. typeIndex lines the pick up with ExoPlayer's track order.
            val image = type == "sub" && codec?.lowercase() in BITMAP_SUB_CODECS
            out.add(
                TrackOption(
                    label = title.orEmpty(),
                    mpvId = id,
                    selected = selected,
                    image = image,
                    codec = codec,
                    lang = lang,
                    typeIndex = typeIndex,
                    labelKind = if (type == "sub") TrackLabelKind.SUBTITLE else TrackLabelKind.AUDIO,
                ),
            )
            typeIndex++
        }
        return out
    }

    fun audioMixEnable(url: String, headers: String? = null, userAgent: String? = null) {
        if (url.isBlank()) return
        mpvAsync {
            if (exoActive || !initialized) return@mpvAsync
            val surface = attachedSurface
            val videoUrl = currentUrl
            if (surface == null || videoUrl.isNullOrBlank()) {
                LiveDiagnosticsLog.event("audio_mix gst failed: no active video surface/url")
                return@mpvAsync
            }

            audioMixWatchdogJob?.cancel()
            gstAudioMixEngine?.stop()
            gstAudioMixEngine = GStreamerAudioMixEngine(context)

            // MPV is stopped only as a handoff. Do not mutate its VO after the stop:
            // the GStreamer backend takes ownership of the already-attached Android Surface.
            stopWithStopClassification("handoff to gstreamer audiomix")

            val started = gstAudioMixEngine?.start(
                surface = surface,
                videoUrl = videoUrl,
                audioUrl = url,
                userAgent = userAgent,
            ) == true

            if (started) {
                audioMixOriginalAid = getPropertyInt("aid")
                audioMixExternalAid = null
                _audioMixEnabled.value = true
                LiveDiagnosticsLog.event(
                    "audio_mix gst active video=${HttpClient.redactUrl(videoUrl)} audio=${HttpClient.redactUrl(url)}",
                )
            } else {
                LiveDiagnosticsLog.event("audio_mix gst failed to start; restoring MPV")
                gstAudioMixEngine?.stop()
                gstAudioMixEngine = null
                _audioMixEnabled.value = false
                loadUrl(videoUrl, currentMetaSnapshot(), isLiveContent, resetRetries = false)
            }
        }
    }

    /**
     * Recovers a live external audio track without restarting the video stream.
     * A track can remain selected while its network/decode path has stopped advancing.
     */
    private fun startAudioMixWatchdog() {
        audioMixWatchdogJob?.cancel()
        audioMixLastPts = Double.NaN
        audioMixStallCount = 0
        val generation = loadGeneration
        audioMixWatchdogJob = scope.launch {
            delay(1_500L)
            while (generation == loadGeneration && _audioMixEnabled.value) {
                delay(1_000L)
                val aid = audioMixExternalAid ?: break
                val snapshot = readOnMpv { m ->
                    val selected = m.getPropertyInt("aid") == aid
                    val pts = m.getPropertyString("audio-pts")?.toDoubleOrNull()
                    selected to pts
                } ?: continue
                val pts = snapshot.second
                if (!snapshot.first || pts == null || !pts.isFinite()) {
                    audioMixStallCount++
                } else if (audioMixLastPts.isFinite() && pts <= audioMixLastPts + 0.05) {
                    audioMixStallCount++
                } else {
                    audioMixStallCount = 0
                }
                if (pts != null && pts.isFinite()) audioMixLastPts = pts
                if (audioMixStallCount >= 4) {
                    audioMixStallCount = 0
                    LiveDiagnosticsLog.event("audio_mix watchdog stalled track=$aid; reloading audio only")
                    mpvAsync {
                        if (_audioMixEnabled.value && audioMixExternalAid == aid) {
                            command(arrayOf("audio-reload", aid.toString()))
                        }
                    }
                }
            }
        }
    }

    fun audioMixDisable() {
        mpvAsync {
            val videoUrl = currentUrl
            val gst = gstAudioMixEngine
            if (gst?.isRunning() == true) {
                gst.stop()
                gstAudioMixEngine = null
                audioMixExternalAid = null
                audioMixOriginalAid = null
                _audioMixEnabled.value = false
                if (!videoUrl.isNullOrBlank()) {
                    loadUrl(videoUrl, currentMetaSnapshot(), isLiveContent, resetRetries = false)
                }
            } else {
                audioMixDisableInternal()
            }
        }
    }

    private fun MPVLib.audioMixDisableInternal() {
        if (!initialized) return
        gstAudioMixEngine?.stop()
        gstAudioMixEngine = null
        audioMixWatchdogJob?.cancel()
        val externalAid = audioMixExternalAid
        if (externalAid != null) command(arrayOf("audio-remove", externalAid.toString()))
        val originalAid = audioMixOriginalAid
        if (originalAid != null) setPropertyInt("aid", originalAid)
        else setPropertyString("aid", "no")
        audioMixExternalAid = null
        audioMixOriginalAid = null
        _audioMixEnabled.value = false
    }

    fun selectAudio(mpvId: Int) {
        if (exoActive) exoEngine?.selectAudio(mpvId) else if (initialized) mpvAsync { setPropertyInt("aid", mpvId) }
        _audioTrackList.value = _audioTrackList.value.map { it.copy(selected = it.mpvId == mpvId) }
    }

    fun selectSubtitle(mpvId: Int) {
        val track = _subTrackList.value.find { it.mpvId == mpvId }
        // Engine-fallback playback: mpv already failed this item, so subtitle picks (text AND image —
        // ExoPlayer renders both natively) are applied on ExoPlayer instead of reverting to mpv.
        if (exoActive && item.exoVodFallback) {
            track?.let { exoEngine?.selectTextTrack(it.typeIndex, it.lang) }
            _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == mpvId) }
            notifyActiveSubtitle(track)
            return
        }
        // Image subtitle on a VOD → hand playback to ExoPlayer (it draws bitmap subs on its own layer).
        if (track?.image == true) {
            if (!isLiveContent) {
                handoffToExo(track)
            } else {
                // Live has no ExoPlayer handoff, so a bitmap subtitle cannot be drawn at all. Marking it
                // selected told the user it was on while nothing ever appeared on screen; say so instead
                // and leave whatever was selected before in place.
                toast(toastRenderer.render(PlaybackFailure.ImageFormat))
            }
            return
        }
        // Text subtitle: mpv's direct path + app overlay. If we're mid-handoff, return to mpv first and
        // apply this sub once it reloads.
        if (exoActive) { revertToMpv(thenSelectSid = mpvId); return }
        if (initialized) mpvAsync {
            setPropertyInt("sid", mpvId)
            setPropertyString("sub-visibility", "yes") // ensure subs aren't hidden
        }
        // CEA-608/708 CC: text only flows from the SOFTWARE decoder's side data (#57), so decode in
        // software while this track is selected — but only where that's viable (≤1080p, GL works).
        val isCc = track?.codec?.lowercase()?.startsWith("eia") == true
        if (isCc && !glUnsupported && load.currentHeightPx in 1..1080) {
            setCcSoftwareOverride(true)
        } else {
            if (isCc) android.util.Log.w(TAG, "CC track selected but software decode not viable (height=${load.currentHeightPx}px, gl=${!glUnsupported}) — captions may stay empty")
            setCcSoftwareOverride(false)
        }
        _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == mpvId) }
        notifyActiveSubtitle(track)
    }

    fun disableSubtitles() {
        if (exoActive && item.exoVodFallback) { // fallback playback stays on Exo — just turn its text off
            exoEngine?.disableTextTracks()
            _subTrackList.value = _subTrackList.value.map { it.copy(selected = false) }
            notifyActiveSubtitle(null)
            return
        }
        if (exoActive) { revertToMpv(); return } // turning subs off ends the image-sub handoff
        setCcSoftwareOverride(false) // CC off → back to the configured (hardware) decode path
        if (initialized) mpvAsync { setPropertyString("sid", "no") }
        _subTrackList.value = _subTrackList.value.map { it.copy(selected = false) }
        notifyActiveSubtitle(null)
    }

    /**
     * Attach an external subtitle file (OpenSubtitles download or local pick) and select it
     * immediately (subtitle plan §6.5). mpv's `sub-add … select` attaches it live with no playback
     * interruption. [title] labels it in the track list; [lang] is the ISO code when known.
     *
     * When ExoPlayer owns VOD playback (engine fallback / preferred engine) the sub is side-loaded
     * natively via a position-preserving re-prepare (§10). During an image-sub handoff, picking an
     * external TEXT sub returns playback to mpv and attaches it once mpv reloads.
     */
    /** One external subtitle to (re)attach — see [restoreExternalSubtitles]. */
    data class ExternalSub(
        val path: String,
        /** Stable/raw engine label used for matching; presentation adds the localized source label. */
        val title: String,
        val lang: String?,
        val source: ExternalSubtitleSource = ExternalSubtitleSource.OPENSUBTITLES,
    )

    /** Set by the subtitle layer; fired after a VOD file finishes loading so previously downloaded
     *  subtitles can be re-listed (subtitle plan §9). Runs on the mpv event thread. */
    var onVodFileLoaded: (() -> Unit)? = null

    /**
     * Re-attach previously downloaded subtitles WITHOUT changing the current selection (they show in
     * the Subtitles list; the user re-picks — owner decision). No-op on ExoPlayer/live.
     */
    fun restoreExternalSubtitles(subs: List<ExternalSub>) {
        if (subs.isEmpty()) return
        subs.forEach { s -> if (sessionExternalSubs.none { it.path == s.path }) sessionExternalSubs.add(s) }
        if (exoActive) {
            // Only when Exo owns playback as a VOD engine; during an image-sub handoff mpv re-lists
            // them itself after the handoff ends (its FILE_LOADED re-fires the restore hook).
            if (item.exoVodFallback) exoEngine?.restoreExternalSubtitles(subs)
            return
        }
        if (!initialized) return
        mpvAsync {
            val originalSid = getPropertyString("sid") ?: "no" // preserve the user's current choice
            // Skip files already in the track list (a toggle carry-over may have re-attached one first).
            val existing = _subTrackList.value.map { it.label }.toSet()
            val toAdd = subs.filter { it.title !in existing }
            if (toAdd.isEmpty()) return@mpvAsync
            toAdd.forEach { command(arrayOf("sub-add", it.path, "auto", it.title, "")) }
            setPropertyString("sid", originalSid) // "auto" may have selected one — undo that
            val sid = getPropertyInt("sid") ?: -1
            val list = queryTracks("sub").map { it.copy(selected = it.mpvId == sid) }
            _subTrackList.value = list
            _subCount.value = list.size
        }
    }

    fun addExternalSubtitle(
        path: String,
        title: String,
        lang: String? = null,
        source: ExternalSubtitleSource = ExternalSubtitleSource.OPENSUBTITLES,
    ) {
        if (sessionExternalSubs.none { it.path == path }) sessionExternalSubs.add(ExternalSub(path, title, lang, source))
        if (exoActive) {
            if (item.exoVodFallback) {
                // Exo owns VOD playback (engine fallback / preferred engine): side-load natively (§10).
                exoEngine?.addExternalSubtitle(path, title, lang, source)
                onActiveSubtitleChanged?.invoke("path:$path")
            } else {
                // Image-sub handoff: an external TEXT sub means the image sub is being replaced — return
                // to mpv and attach the sub once its file reloads (mirrors the item.pendingSelectSid path).
                item.pendingExternalAdd = ExternalSub(path, title, lang, source)
                revertToMpv()
            }
            return
        }
        if (!initialized) return
        mpvAsync {
            // Already attached (e.g. re-applied after an engine toggle raced the §9 restore): don't add a
            // duplicate row — just select the existing track.
            _subTrackList.value.firstOrNull { it.label == title }?.let { existing ->
                setPropertyInt("sid", existing.mpvId)
                setPropertyString("sub-visibility", "yes")
                _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == existing.mpvId) }
                onActiveSubtitleChanged?.invoke("path:$path")
                return@mpvAsync
            }
            // Empty mpv lang so the HUD label is exactly [title] ("Bengali — OpenSubtitles") rather than
            // title + a duplicated language name from queryTracks' label().
            command(arrayOf("sub-add", path, "select", title, ""))
            setPropertyString("sub-visibility", "yes")
            // sub-add is synchronous, so track-list now includes the new sub — refresh the HUD list so it
            // shows (labelled and selected). Runs on the mpv-cmd worker, off the main thread (queryTracks' rule).
            // Mark "selected" from mpv's actual current sid so the Subtitles menu opens focused on the
            // just-added track (queryTracks' own selected flag can lag right after sub-add).
            val sid = getPropertyInt("sid") ?: -1
            val subs = queryTracks("sub").map { it.copy(selected = it.mpvId == sid) }
            _subTrackList.value = subs
            _subCount.value = subs.size
            onActiveSubtitleChanged?.invoke("path:$path")
        }
    }


    // --- mpv event callbacks (called off the main thread) ---
    override fun eventProperty(property: String) {
        // A string property went unavailable/null. For sub-text that means "no line on screen now".
        if (property == "sub-text") _subText.value = null
    }

    override fun eventProperty(property: String, value: Long) {
        // mpv's stop() during a handoff to Exo fires stale events afterward that would overwrite state Exo already set.
        if (exoActive) return
        when (property) {
            "time-pos" -> {
                _position.value = value * 1000
                if (value > 0) load.expectingPlayback = false // playback actually started
            }
            "duration" -> _duration.value = value * 1000
            "width" -> {
                load.currentWidthPx = value.toInt()
                updateAspect()
            }
            "height" -> {
                _videoRes.value = resolutionLabel(value.toInt())
                load.currentHeightPx = value.toInt()
                if (value > 0) {
                    lastVideoHeightPx = value.toInt() // remember for recovery decisions on a later failed load
                    videoCheckJob?.cancel() // video is decoding → watchdog not needed
                    // A real frame decoded → playback genuinely works. Dismiss any error the watchdog raised
                    // prematurely while a slow hardware decoder (e.g. Realtek setPortMode negotiation) was
                    // still producing its first frame — otherwise the popup stays stuck over playing video.
                    if (_error.value != null) _error.value = null
                }
                updateAspect()
                enforceDecodeGuard()
            }
        }
    }

    private fun updateAspect() {
        val w = load.currentWidthPx
        val h = load.currentHeightPx
        _videoAspect.value = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
        _videoSize.value = if (w > 0 && h > 0) w to h else null
        updateStreamChips()
    }

    /**
     * Abort playback when a >1080p video lands on the SOFTWARE decoder (hwdec-current == "no"):
     * TV CPUs can't sustain it — it stutters for a few seconds, then the memory/thermal pressure
     * gets the whole app killed. ≤1080p software decoding stays allowed (viable, and the rescue
     * path for streams the hardware decoder mangles).
     */
    private fun enforceDecodeGuard() {
        if (load.decodeGuardTripped) return
        val hw = load.currentHwdec ?: return
        val h = load.currentHeightPx
        if (h <= 1080 || (hw != "no" && hw.isNotEmpty())) return
        android.util.Log.w(TAG, "Decode guard TRIPPED: ${h}px on software decoder")
        load.decodeGuardTripped = true
        val res = resolutionLabel(h) ?: "${h}p"
        val msg: PlaybackFailure = if (hwDecoding) {
            PlaybackFailure.HardwareFallback(res)
        } else {
            PlaybackFailure.HardwareDisabled(res)
        }
        // A VOD that mpv can only software-decode may still hardware-decode on ExoPlayer's MediaCodec
        // path (different codec selection/negotiation) — retry it there before giving up. The fallback
        // stops mpv itself; if Exo also lands without hardware decode it fails → combined error.
        if (!isLiveContent && fallbackToExoVod(msg, mpvStuck = false)) return
        // Halt decoding but KEEP currentUrl so the HUD's Retry works (e.g. after the user flips the
        // hardware-decoding setting, which applies live).
        loadGeneration++
        load.expectingPlayback = false
        errorCheckJob?.cancel()
        pendingUrl = null
        mpvAsync { stopWithStopClassification("decodeGuard") }
        scope.launch {
            _isPlaying.value = false
            _buffering.value = false
            _error.value = vodErrorMessage(msg)
        }
    }

    /**
     * Shares [classifyResolution] with the live preview engine so the same channel never shows one
     * quality fullscreen and another in the preview pane. Width defaults to the last reported frame
     * width, which is what lets a vertically-cropped cinemascope stream classify correctly.
     */
    private fun resolutionLabel(height: Int, width: Int = load.currentWidthPx): String? =
        classifyResolution(width, height)

    override fun eventProperty(property: String, value: Boolean) {
        if (exoActive) return
        when (property) {
            "pause" -> _isPlaying.value = !value
            "paused-for-cache" -> _buffering.value = value
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "hwdec-current" -> {
                android.util.Log.i(TAG, "hwdec-current='$value' (height=${load.currentHeightPx}px, setting=${if (hwDecoding) "on" else "off"})")
                load.currentHwdec = value
                enforceDecodeGuard()
            }
            "video-codec" -> load.currentVideoCodec = value.takeIf { it.isNotBlank() }
            // Active subtitle line for the app-drawn overlay (direct mode only; GL mode draws its own).
            "sub-text" -> {
                val line = value.trim().takeIf { it.isNotEmpty() }
                _subText.value = if (_directRender.value) line else null
                // Diagnostic: confirms caption/subtitle text is actually flowing (e.g. CEA-608 CC). DEBUG only.
                if (tv.own.owntv.core.CoreBuildInfo.debug && line != null) {
                    android.util.Log.i(TAG, "sub-text (direct=${_directRender.value}): $line")
                }
            }
        }
    }
    override fun eventProperty(property: String, value: Double) {
        if (exoActive) return
        if (property == "speed") _speed.value = value
        if (property == "container-fps" && value > 0) _videoFps.value = value.toFloat()
        // Seconds of demuxed data ahead of the playhead → an absolute media time, so the HUD's ghost
        // means the same thing on both engines.
        if (property == "demuxer-cache-duration") _bufferedMs.value = _position.value + (value * 1000).toLong()
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                // Stale FILE_LOADED from mpv's own stop() during a handoff to Exo, not a real load.
                if (exoActive) return
                load.fileLoaded = true
                // Observation only — do NOT clear the counter here. Each app-issued loadfile/stop credits
                // exactly one cleanup END_FILE, and that END_FILE can legitimately arrive *after* the new
                // file's FILE_LOADED. The old `getAndSet(0)` threw those credits away, so the late END_FILE
                // was misread as a playback failure and triggered a spurious retry/hard reset. Credits are
                // consumed only by consumePendingStopEndFile(), one event each; the increment side is
                // bounded so an unmatched credit can't accumulate.
                val pendingStops = pendingStopEndFiles.peek()
                if (pendingStops > 0) {
                    android.util.Log.i(
                        TAG,
                        "FILE_LOADED with pendingStopEndFiles=$pendingStops generation=$loadGeneration " +
                            "live=$isLiveContent; awaiting their END_FILEs",
                    )
                }
                markActiveFile(true)
                // The new file opened successfully — cancel any pending error and clear a stale one.
                // This callback runs on mpv's event thread (not main), so sync reads are safe here.
                load.expectingPlayback = false
                errorCheckJob?.cancel()
                _error.value = null
                _buffering.value = false // a reconnect's spinner ends when the new file loads
                // A load that only succeeded once the fallback identity was tried is the proof that this
                // panel blocklists the default User-Agent. Record it panel-wide (session-only) so every
                // other channel here — either engine — opens first time instead of repeating the failure.
                if (item.uaFallbackPending) {
                    item.uaFallbackPending = false
                    currentUrl?.let { LiveStreamQuirks.rememberBlocksDefaultUserAgent(it) }
                }
                _audioTrackList.value = queryTracks("audio")
                _subTrackList.value = queryTracks("sub")
                _audioCount.value = _audioTrackList.value.size
                _subCount.value = _subTrackList.value.size
                // Re-list previously downloaded subtitles for a VOD item (subtitle plan §9). Fires after
                // the fresh track list is built so restoreExternalSubtitles appends onto it.
                if (!isLiveContent) onVodFileLoaded?.invoke()
                // Fast-zap safety net: a trimmed probe can miss the audio PMT on a sparse stream, leaving
                // a video-only load. If that happens, re-probe fully (once) so the channel plays with sound.
                if (usedTrimmedProbe && !item.forceFullProbe && _audioTrackList.value.isEmpty()) {
                    android.util.Log.w(TAG, "trimmed probe found no audio — re-probing fully")
                    item.forceFullProbe = true
                    reloadCurrentInPlace()
                    return
                }
                // A text subtitle the user picked while ExoPlayer was handling an image sub: apply it now
                // that mpv has reloaded and re-enumerated its tracks.
                item.pendingSelectSid?.let { sid ->
                    item.pendingSelectSid = null
                    mpv?.setPropertyInt("sid", sid)
                    mpv?.setPropertyString("sub-visibility", "yes")
                    _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == sid) }
                    notifyActiveSubtitle(_subTrackList.value.firstOrNull { it.mpvId == sid })
                }
                // An external subtitle added during an Exo handoff (or active across an Exo→mpv engine
                // toggle): attach + select it now that mpv is back.
                item.pendingExternalAdd?.let { s ->
                    item.pendingExternalAdd = null
                    addExternalSubtitle(s.path, s.title, s.lang, s.source)
                }
                // Embedded sub carried across an Exo→mpv engine toggle: re-select it by ordinal.
                item.pendingSelectSubOrdinal?.let { ord ->
                    item.pendingSelectSubOrdinal = null
                    _subTrackList.value.getOrNull(ord)?.let { t ->
                        mpv?.setPropertyInt("sid", t.mpvId)
                        mpv?.setPropertyString("sub-visibility", "yes")
                        _subTrackList.value = _subTrackList.value.map { it.copy(selected = it.mpvId == t.mpvId) }
                        notifyActiveSubtitle(t)
                    }
                }
                mpv?.getPropertyBoolean("pause")?.let { _isPlaying.value = !it }
                mpv?.getPropertyInt("height")?.let {
                    _videoRes.value = resolutionLabel(it, mpv?.getPropertyInt("width") ?: load.currentWidthPx)
                }
                setZoomMode(_zoomMode.value) // re-apply zoom on the new track
                if (load.pendingSeekMs > 0) {
                    val seekMs = load.pendingSeekMs
                    load.pendingSeekMs = 0
                    mpvAsync { command(arrayOf("seek", (seekMs / 1000).toString(), "absolute")) }
                }
                if (load.pendingStartPaused) { // restored a backgrounded VOD — hold it paused at the resume point
                    load.pendingStartPaused = false
                    mpvAsync { setPropertyBoolean("pause", true) }
                    _isPlaying.value = false
                }
                // Surround-output failsafe (#25): some sinks claim multichannel PCM support but mis-play it —
                // the audio drains ~2× fast, so mpv's audio-master clock (and the video) runs ~2× and the
                // sound is silent. mpv sees the output as fine (audio-params == audio-out-params, avsync ≈ 0),
                // so the only tell is the video running away: estimated-vf-fps ≈ 2× the file's container-fps.
                // Checked in the 5–15 s window (past the start-up burst, before long drift) and skipped while
                // seeking (a seek bursts frames to catch up and would false-trip). On a hit, latch surround off
                // for the session and reload this item in stereo.
                run {
                    val sgen = loadGeneration
                    scope.launch {
                        delay(SURROUND_CHECK_MS)
                        if (sgen != loadGeneration || !multichannelAllowed()) return@launch
                        // Two independent tells, because the two ways an output fails look nothing alike:
                        //
                        //  1. RUNAWAY — the sink drains multichannel PCM ~2× fast, so mpv's audio-master
                        //     clock (and with it the video) runs away while the room stays silent.
                        //     estimated-vf-fps ≈ 2× container-fps is the only visible symptom. Skipped
                        //     while seeking (a seek bursts frames to catch up and would false-trip), and
                        //     skipped entirely when container-fps is unknown — common on live TS, and a
                        //     guess there would be a false positive on a perfectly good channel.
                        //
                        //  2. SILENCE — the sink accepts the format and simply never plays it. The clock
                        //     keeps time (mpv falls back to the video clock), so nothing is "wrong" except
                        //     that audio-pts is frozen while time-pos advances. This is the live failure
                        //     the runaway check cannot see, and the one reported as "picture, no sound".
                        val baseline = kotlinx.coroutines.CompletableDeferred<Pair<Double?, Double?>>()
                        mpvAsync {
                            baseline.complete(
                                getPropertyString("audio-pts")?.toDoubleOrNull() to
                                    getPropertyString("time-pos")?.toDoubleOrNull(),
                            )
                        }
                        val (startAudioPts, startTimePos) =
                            kotlinx.coroutines.withTimeoutOrNull(1_000) { baseline.await() } ?: (null to null)
                        delay(SURROUND_SILENCE_CHECK_MS)
                        if (sgen != loadGeneration || !multichannelAllowed()) return@launch
                        mpvAsync {
                            if (getPropertyString("seeking") == "yes") return@mpvAsync // catching up — not a real runaway
                            if (getPropertyString("pause") == "yes") return@mpvAsync    // paused audio is not stalled audio
                            val cfps = getPropertyString("container-fps")?.toDoubleOrNull() ?: 0.0
                            val vfps = getPropertyString("estimated-vf-fps")?.toDoubleOrNull() ?: 0.0
                            val runaway = cfps > 1.0 && vfps > cfps * 1.5

                            val nowAudioPts = getPropertyString("audio-pts")?.toDoubleOrNull()
                            val nowTimePos = getPropertyString("time-pos")?.toDoubleOrNull()
                            // Only meaningful when an audio track is actually selected and the clock moved.
                            val hasAudio = getPropertyString("aid").let { it != null && it != "no" && it != "false" }
                            val videoMoved = startTimePos != null && nowTimePos != null &&
                                nowTimePos - startTimePos > SURROUND_SILENCE_CHECK_MS / 2000.0
                            val audioFrozen = hasAudio && videoMoved && startAudioPts != null &&
                                nowAudioPts != null && kotlin.math.abs(nowAudioPts - startAudioPts) < 0.25

                            val reason = when {
                                runaway -> "audio drained ${"%.1f".format(vfps / cfps)}× too fast"
                                audioFrozen -> "audio output produced no sound"
                                else -> return@mpvAsync
                            }
                            android.util.Log.w(TAG, "surround failsafe: $reason (est-vf-fps=$vfps container-fps=$cfps) — falling back to stereo")
                            AudioOutputPolicy.latchStereo("mpv: $reason")
                            PlaybackErrorLog.event(context, "mpv", isLiveContent, PlayerFailureReason.STEREO_FALLBACK, reason)
                            setPropertyString("audio-channels", "stereo")
                            setPropertyString("audio-format", "")
                            setPropertyString("audio-samplerate", "0")
                            toast(toastRenderer.render(PlaybackFailure.Surround))
                            val stereoUrl = currentUrl
                            if (sgen == loadGeneration && stereoUrl != null) {
                                loadUrl(stereoUrl, currentMetaSnapshot(), isLiveContent, _position.value, resetRetries = false)
                            }
                        }
                    }
                }
                // F14: frame rate for a stream that never declares one. Live MPEG-TS usually has no
                // `container-fps`, which is the ONLY thing that feeds [_videoFps] — so on mpv live the
                // fps stayed null, AutoFrameRateEffect got null, and Auto frame rate silently did
                // nothing on exactly the content it exists for (25fps broadcast on a 60Hz panel).
                // Measure it instead: once playback has settled, sample the renderer's own rate twice a
                // second apart and only publish it if both samples agree and land on a broadcast rate.
                // Two agreeing samples matter because the start-up burst and any cache stall skew a
                // single reading, and a wrong fps here would ask the TV for the wrong display mode.
                // Only when something consumes the result: Auto frame rate (the display-mode switch) or
                // the measured-stats escape hatch (the fps chip). With both off nobody reads _videoFps,
                // and the probe was still waking the mpv worker three times per open for nothing.
                if (autoFrameRate || measuredStreamStats) {
                    val fgen = loadGeneration
                    scope.launch {
                        delay(LIVE_FPS_PROBE_MS)
                        if (fgen != loadGeneration || _videoFps.value != null) return@launch
                        val first = readVfFps() ?: return@launch
                        delay(1_000)
                        if (fgen != loadGeneration || _videoFps.value != null) return@launch
                        val second = readVfFps() ?: return@launch
                        if (kotlin.math.abs(first - second) > 0.5f) return@launch
                        val snapped = STANDARD_FPS.minByOrNull { kotlin.math.abs(it - second) }
                            ?.takeIf { kotlin.math.abs(it - second) <= 0.6f } ?: return@launch
                        android.util.Log.i(TAG, "measured fps: est-vf-fps=$second -> ${snapped}fps (no container-fps)")
                        _videoFps.value = snapped
                        updateStreamChips()
                    }
                }

                // Decode watchdog, polled: the decoder is chosen a few seconds AFTER the file loads,
                // so read it directly once it has settled (the observed event also runs enforceDecodeGuard).
                val gen = loadGeneration
                scope.launch {
                    delay(DECODE_CHECK_MS)
                    if (gen != loadGeneration) return@launch
                    mpvAsync {
                        val hw = getPropertyString("hwdec-current") ?: ""
                        val h = getPropertyInt("height") ?: 0
                        android.util.Log.i(TAG, "decode check: hwdec-current='$hw' height=${h}px direct=${_directRender.value}")
                        // Diagnostics for "video plays like a slideshow": is mpv dropping frames (timing),
                        // is the decoder dropping (too slow), or is the network cache underrunning?
                        android.util.Log.i(
                            TAG,
                            "playback stats: container-fps=${getPropertyString("container-fps")} " +
                                "est-vf-fps=${getPropertyString("estimated-vf-fps")} " +
                                "frame-drops=${getPropertyString("frame-drop-count")} " +
                                "decoder-drops=${getPropertyString("decoder-frame-drop-count")} " +
                                "cache=${getPropertyString("demuxer-cache-duration")}s " +
                                "paused-for-cache=${getPropertyString("paused-for-cache")} " +
                                "video-bitrate=${getPropertyString("video-bitrate")}",
                        )
                        // Display-timing readout. The mpv core is REUSED across channels while
                        // FrameRateController switches the panel's refresh rate underneath it (60↔30Hz),
                        // so a stale display-fps belief here would explain judder that only appears from
                        // the SECOND mpv load onward. Compared against what Android reports right now.
                        android.util.Log.i(
                            TAG,
                            "display timing: mpv display-fps=${getPropertyString("display-fps")} " +
                                "estimated-display-fps=${getPropertyString("estimated-display-fps")} " +
                                "vsync-jitter=${getPropertyString("vsync-jitter")} " +
                                "video-sync=${getPropertyString("video-sync")} " +
                            "vo=${getPropertyString("vo")} " +
                            "mistimed=${getPropertyString("mistimed-frame-count")} " +
                            "vo-delayed=${getPropertyString("vo-delayed-frame-count")} " +
                                "android-display=${androidDisplayHz()} " +
                                "load#=$mpvLoadCount freshSurface=$usedFreshSurface",
                        )
                        // The direct surface can only display hardware frames. If the direct decoder
                        // didn't engage (cold-boot decoder-busy, etc.), retry direct a few times (it
                        // usually frees within seconds), then fall back to software decode, then error.
                        if (_directRender.value && (hw.isEmpty() || hw == "no")) {
                            val pos = if (isLiveContent) 0L else _position.value
                            // If the codec list says no hardware decoder covers this codec at this size,
                            // retrying the direct path is guaranteed to fail again — go straight to the
                            // rescue ladder and say so, instead of burning retries on a known-no. (F10)
                            // A catch-up archive skips the retry ladder: the hardware decoder not engaging
                            // on a mid-GOP stream repeats identically on every retry.
                            if (tryArchiveSoftwareRescue("direct decoder never engaged on archive")) return@mpvAsync
                            if (hardwareCannotDecodeCurrent() &&
                                tryDecodeRescue("hardware can't decode ${load.currentVideoCodec} at ${load.currentWidthPx}x${load.currentHeightPx}")
                            ) {
                                val resolution = resolutionLabel(load.currentHeightPx, load.currentWidthPx) ?: "${load.currentWidthPx}x${load.currentHeightPx}"
                                toast(toastRenderer.render(PlaybackFailure.HardwareFallback(resolution)))
                                return@mpvAsync
                            }
                            if (item.autoRetries < maxRetries()) {
                                item.autoRetries++
                                // The trimmed fast-zap probe is only for the FIRST attempt. If the hardware
                                // decoder failed to engage, the probe may have under-read this stream's
                                // config (e.g. a 4K HEVC/HDR channel needs more than 1 MB to get its VPS/SPS
                                // + HDR metadata, or MediaCodec errors 0x80001000) — so re-probe in FULL.
                                item.forceFullProbe = true
                                android.util.Log.w(TAG, "direct failed — retry ${item.autoRetries}/${maxRetries()} (full probe)")
                                _buffering.value = true
                                scope.launch {
                                    delay(backoffMs(item.autoRetries))
                                    if (gen == loadGeneration) loadUrl(currentUrl ?: return@launch, currentMetaSnapshot(), isLiveContent, pos, resetRetries = false)
                                }
                            } else if (tryDecodeRescue("direct failed after retries")) {
                                // The shared ladder (F7) picks the rung: the copy rescue between direct and
                                // software (F09) — still hardware decoding, GL compositing, and with no
                                // resolution gate the ONLY rescue a 4K file the direct path can't open ever
                                // gets — then pure software decode for weak decoders that mangle the stream.
                                // Both are skipped on emulators (translated GL crashes) and software above
                                // 1080p (it can't sustain it — the guard would trip; a clean error is better).
                            } else {
                                android.util.Log.w(TAG, "direct failed — retries exhausted, showing error")
                                scope.launch { _buffering.value = false; _error.value = PlaybackFailure.DecoderBusy }
                            }
                            return@mpvAsync
                        }
                        load.currentHwdec = hw.ifEmpty { null }
                        if (h > 0) {
                            load.currentHeightPx = h
                            // A frame decoded on mpv is as much proof the core is healthy as one decoded on
                            // ExoPlayer, which is the only place this counter was ever cleared. Left
                            // uncleared, three resets spread across an evening's zapping eventually tripped
                            // the thrash guard on a channel that was playing perfectly.
                            consecutiveHardResets = 0
                        }
                        enforceDecodeGuard()
                    }
                }
            }
            // mpv's Kotlin wrapper does not expose mpv_event_end_file.reason, so classify the cases we
            // know the app caused (replacement loadfile, manual stop, decode guard) before treating an
            // END_FILE as a possible playback failure.
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (consumePendingStopEndFile()) return
                // Dev 2/3 instant-catch: if the file ended before FILE_LOADED ever fired, the demuxer
                // rejected it outright (malformed MP4) — hard-reset immediately. VOD only: for a live
                // stream the same symptom is routine (a provider 5xx on zap, a stalled edge, a `.ts`
                // channel the panel only serves as HLS), and resetting here returns before the live
                // retry ladder below ever runs, so the channel dies on the first hiccup.
                if (shouldHardResetOnEarlyEndFile(load.fileLoaded, load.expectingPlayback, isLiveContent)) {
                    android.util.Log.w(TAG, "END_FILE before FILE_LOADED — demuxer rejected file, hard-resetting")
                    load.expectingPlayback = false; _buffering.value = false
                    triggerHardReset()
                    return
                }
                markActiveFile(false)
                if (load.expectingPlayback) {
                    val gen = loadGeneration
                    errorCheckJob?.cancel()
                    errorCheckJob = scope.launch {
                        // Unclassified END_FILE is not always a final failure: slow or flaky live
                        // streams can still report FILE_LOADED shortly after. Give mpv a short grace
                        // window; FILE_LOADED clears load.expectingPlayback/cancels this path.
                        delay(EOF_GRACE_MS)
                        if (!load.expectingPlayback || gen != loadGeneration) return@launch
                        // No internet → don't burn the retry budget on a dead connection; surface the
                        // offline error straight away.
                        if (!connectivity.isOnlineNow()) {
                            android.util.Log.w(TAG, "playback didn't start — offline, skipping retries")
                            _buffering.value = false
                            _error.value = PlaybackFailure.NoInternet
                            return@launch
                        }
                        // A LIVE stream that retried once and still won't start may be on a panel that only
                        // serves HLS — try the `.m3u8` variant of the same channel before erroring (we
                        // default to `.ts` since it's more widely supported, with this as the safety net).
                        //
                        // Live only, on purpose. An archive is never worth swapping to `.m3u8`: the
                        // timeshift server has no HLS repackager, so the swap can only ever waste a rung
                        // and several seconds in front of the user. Catch-up's alternate is the PHP query
                        // form below, which is a genuinely different endpoint rather than a format guess.
                        val altFormat = if (isLiveContent && !item.triedAltFormat && item.autoRetries >= 1) alternateFormatUrl() else null
                        val catchupAlt =
                            if (!isLiveContent && !item.triedCatchupPhpForm) {
                                tv.own.owntv.core.epg.CatchupUrl.timeshiftPhpAlternate(currentUrl)
                            } else {
                                null
                            }
                        if (altFormat != null) {
                            item.triedAltFormat = true
                            item.autoRetries = 0
                            android.util.Log.w(TAG, "live stream didn't start — trying format fallback -> ${altFormat.substringAfterLast('.')}")
                            _buffering.value = true
                            delay(FALLBACK_RETRY_DELAY_MS)
                            if (gen == loadGeneration) {
                                loadUrl(altFormat, currentMetaSnapshot(), isLiveContent, 0L, resetRetries = false)
                            }
                        } else if (catchupAlt != null) {
                            // Some Xtream panels reject the path-style catch-up URL (they return an HTML
                            // error page → "unrecognized format") but accept the PHP query form. Try it.
                            item.triedCatchupPhpForm = true
                            item.autoRetries = 0
                            android.util.Log.w(TAG, "catch-up path URL rejected — trying timeshift.php fallback")
                            _buffering.value = true
                            delay(FALLBACK_RETRY_DELAY_MS)
                            if (gen == loadGeneration) {
                                loadUrl(catchupAlt, currentMetaSnapshot(), isLiveContent, 0L, resetRetries = false)
                            }
                        }
                        // The stream didn't start. Silently retry a few times with exponential backoff
                        // before surfacing the error — handles transient failures (cold-boot decoder-busy,
                        // a provider 5xx, the first-play surface race) so the user rarely sees an error.
                        // A live panel that answered with an HTTP refusal will answer the identical request
                        // the same way; cut the repeats short so the format/UA fallbacks (which DO change
                        // the request) run instead of a storm. A *busy* answer (458/429/408) is not a
                        // refusal and keeps its full ladder — see [httpRefusalKind].
                        else if (
                            item.autoRetries < maxRetries() && currentUrl != null &&
                            !(isLiveContent && isHardHttpRefusal(load.lastMpvError) && item.autoRetries >= HARD_REFUSAL_MAX_RETRIES)
                        ) {
                            // F29 — a one-session panel answering mpv teaches the same panel-wide quirk an
                            // ExoPlayer 458 does, so the NEXT tune knows the two engines must not overlap.
                            if (isLiveContent && httpStatusOf(load.lastMpvError)?.let { LiveStreamQuirks.isSessionLimit(it) } == true) {
                                currentUrl?.let { LiveStreamQuirks.rememberSessionLimit(it) }
                                PlaybackErrorLog.event(
                                    context, "mpv", live = true,
                                    reason = PlayerFailureReason.ONE_SESSION_PROVIDER,
                                    detail = "panel answered HTTP 458; this panel is now opened one stream at a time",
                                )
                            }
                            item.autoRetries++
                            // Re-probe in FULL on retry: the trimmed fast-zap probe is first-attempt only, and
                            // an under-read 4K/HDR stream is a common reason a load fails to start.
                            item.forceFullProbe = true
                            android.util.Log.w(TAG, "playback didn't start — auto-retry ${item.autoRetries}/${maxRetries()} (full probe)")
                            _buffering.value = true
                            delay(backoffMs(item.autoRetries))
                            // Read the url ONCE, after the delay: re-reading the mutable field between the
                            // null check and the use is how a stop during the backoff becomes an NPE.
                            val retryUrl = currentUrl
                            if (gen == loadGeneration && retryUrl != null) {
                                loadUrl(
                                    retryUrl, currentMetaSnapshot(),
                                    isLiveContent, if (isLiveContent) 0L else _position.value, resetRetries = false,
                                )
                            }
                        } else if (!item.triedTolerantDemux && currentUrl != null) {
                            // Retries and the format alternates are spent and the stream still produced no
                            // playable data. Before the decoder rescues below — which assume the container
                            // was fine and only the decode failed — give the demuxer one pass with error
                            // tolerance on. A damaged mux is the far likelier cause on a re-streamed feed,
                            // and this is the cheapest rung on the ladder.
                            currentUrl?.let { noteNeedsTolerantDemux(it, "playback didn't start after retries") }
                            item.forceFullProbe = true
                            _buffering.value = true
                            delay(FALLBACK_RETRY_DELAY_MS)
                            val demuxUrl = currentUrl
                            if (gen == loadGeneration && demuxUrl != null) {
                                // resetRetries=false keeps the exhausted budget, so this is exactly one attempt.
                                loadUrl(
                                    demuxUrl, currentMetaSnapshot(),
                                    isLiveContent, if (isLiveContent) 0L else _position.value, resetRetries = false,
                                )
                            }
                        } else if (tryArchiveSoftwareRescue("archive didn't start on hardware")) {
                            // (handled — the item is reopening in software)
                        } else if (canTryCopyRescue() && looksLikeDecoderFailure(load.lastMpvError ?: diagnostics.recentError())) {
                            // Decoder (not network/container) failure and the direct path is exhausted:
                            // take the copy rung before the ≤1080p software one, so 4K files that only
                            // fail on the direct surface still get a rescue. (F09)
                            tryDecodeRescue("playback didn't start — decoder failure")
                        } else if (trySoftwareRescue("playback didn't start on hardware")) {
                            // Hardware decoding never got it going — some weak TV decoders reject streams
                            // that software decoding plays fine, so the ladder's software rung takes one
                            // attempt before we error. The copy rung was already considered above and
                            // declined (this failure doesn't read as a decoder failure).
                        } else if (currentUserAgent == null && !item.triedUaFallback &&
                            currentUrl?.let { !LiveStreamQuirks.blocksDefaultUserAgent(it) } == true
                        ) {
                            // All standard retries exhausted. If the user left the source User-Agent blank,
                            // retry once under a neutral identity — some panels sit behind a WAF that
                            // blocklists player User-Agents by name and answers them with a challenge page
                            // regardless of the URL. Skipped when this load already started on the fallback
                            // because the panel is a known offender: that attempt has just been made.
                            item.triedUaFallback = true
                            currentUserAgent = HttpClient.FALLBACK_USER_AGENT
                            item.uaFallbackPending = true
                            item.forceFullProbe = true
                            android.util.Log.w(TAG, "playback failed — retrying once as ${HttpClient.FALLBACK_USER_AGENT}")
                            _buffering.value = true
                            delay(FALLBACK_RETRY_DELAY_MS)
                            val uaUrl = currentUrl
                            if (gen == loadGeneration && uaUrl != null) {
                                loadUrl(
                                    uaUrl, currentMetaSnapshot(),
                                    isLiveContent, if (isLiveContent) 0L else _position.value, resetRetries = false,
                                )
                            }
                        } else {
                            // mpv's whole retry ladder is exhausted. For a VOD, retry once on ExoPlayer
                            // before erroring — it may play what mpv can't on this device/provider.
                            if (!isLiveContent && fallbackToExoVod(PlaybackFailure.MpvStreamNeverStarted, mpvStuck = false)) return@launch
                            _buffering.value = false
                            _error.value = vodErrorMessage(
                                PlayerErrors.visibleFailure(
                                    load.lastMpvError,
                                    currentUrl,
                                    PlaybackFailure.StreamUnavailable(item.triedUaFallback),
                                ),
                            )
                        }
                    }
                } else if (isLiveContent && currentUrl != null) {
                    // A live stream died mid-play (provider hiccup / connection limit → HTTP 509, OR a
                    // hardware-decoder error like Realtek's 0x80001000 on 4K HEVC): mpv goes idle and the
                    // screen would stay blank. Reconnect after a pause LONG ENOUGH for the hardware decoder
                    // to finish releasing — a 4K decoder on TV-class silicon takes ~3 s, and re-initializing
                    // it sooner throws 0x80001000 and churns forever. Once it releases cleanly the reconnect
                    // succeeds, so the loop ends in playback rather than an endless re-init storm.
                    if (!connectivity.isOnlineNow()) {
                        _buffering.value = false
                        _error.value = PlaybackFailure.NoInternet
                    } else if (item.liveStallReconnects >= MAX_LIVE_RECONNECTS) {
                        // Bounded, like every other live recovery path. This one used to call retry(),
                        // which reloads with resetRetries = true — so a channel dying immediately after
                        // each reconnect reset its own budget and looped forever behind a spinner, with
                        // no message and no way out but Back. The stall watchdog clears the counter as
                        // soon as the picture is genuinely progressing again, so this only ever fires on
                        // consecutive failures.
                        android.util.Log.w(TAG, "live died mid-play — reconnect budget exhausted after $MAX_LIVE_RECONNECTS attempts, surfacing error")
                        LiveDiagnosticsLog.event("mpv live mid-play reconnects exhausted ($MAX_LIVE_RECONNECTS) — surfacing error")
                        load.expectingPlayback = false
                        _isPlaying.value = false
                        _buffering.value = false
                        _error.value = PlaybackFailure.LostConnection
                    } else {
                        item.liveStallReconnects++
                        android.util.Log.w(TAG, "live died mid-play — reconnect attempt ${item.liveStallReconnects}/$MAX_LIVE_RECONNECTS")
                        _buffering.value = true
                        val gen = loadGeneration
                        scope.launch {
                            delay(LIVE_RECONNECT_DELAY_MS)
                            if (gen == loadGeneration && currentUrl != null) retry() else _buffering.value = false
                        }
                    }
                } else if (!isLiveContent && currentUrl != null) {
                    // A VOD finished. If it reached the end (position is at/near the duration — not a
                    // mid-stream drop), continue with whatever follows it — see [advanceAfterNaturalEnd].
                    val dur = _duration.value
                    val pos = _position.value
                    val reachedEnd = reachedEnd(dur, pos)
                    if (reachedEnd) {
                        advanceAfterNaturalEnd()
                    } else {
                        // The item did NOT reach its end: the provider dropped the connection mid-film.
                        // Nothing used to handle this — mpv went idle, the last frame stayed on screen and
                        // the app said nothing, so a cut-off movie was indistinguishable from a frozen one.
                        // One silent reload from the current position (the common cause is a transient
                        // drop), then an honest error. Live's equivalent is the branch above.
                        // A catch-up archive is exempt from the reload: the panel serves it with no Range
                        // support, so reopening at an offset fails outright ("not formatted for streaming")
                        // — the very trap [resumePositionForHandoff] exists for. It goes straight to the
                        // error, which is still better than the silent freeze it used to get.
                        if (!item.triedMidStreamReload && !item.archiveThisItem && currentUrl != null) {
                            item.triedMidStreamReload = true
                            android.util.Log.w(TAG, "VOD ended mid-stream at ${pos}ms of ${dur}ms — reloading once from position")
                            _buffering.value = true
                            val gen = loadGeneration
                            scope.launch {
                                delay(DECODER_RELEASE_MS)
                                val reloadUrl = currentUrl
                                if (gen == loadGeneration && reloadUrl != null) {
                                    reload(reloadUrl, isLive = false, resetRetries = false)
                                } else {
                                    _buffering.value = false
                                }
                            }
                        } else {
                            android.util.Log.w(TAG, "VOD ended mid-stream again at ${pos}ms of ${dur}ms — surfacing error")
                            _isPlaying.value = false
                            _buffering.value = false
                            _error.value = vodErrorMessage(PlaybackFailure.LostConnection)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The two groups of per-playback state in [OwnTVPlayer], split exactly as the player's own reset was
 * already split - because that split is load-bearing, not incidental.
 *
 * [LoadState] is cleared on **every** load, including a silent retry or a software-fallback reload of
 * the same item. [ItemState] is cleared only when a **genuinely new** item starts (`resetRetries`),
 * because a retry of the same film must keep what it has already tried: an item whose retry budget and
 * "already tried software / the other extension / the fallback UA" flags reset on each attempt would
 * retry forever.
 *
 * They exist for the same reason as `TuneState` next door. The reset used to be two hand-written lists
 * of three dozen assignments, and a field added later but not added to the right list bleeds into the
 * next film - or, worse, into the next retry, where a spent rung looks unspent. One assignment each
 * cannot be partially done.
 *
 * **Deliberately NOT here:** the item's identity (title, url, contentKey and friends, assigned
 * unconditionally on every load, so they cannot go stale), the StateFlows the HUD collects (they must
 * keep their identity and are reset by value in `loadUrl`), the collections with their own clear
 * (`sessionExternalSubs`), and everything the user's settings own. Absence from these classes is a
 * statement that a field legitimately outlives a load.
 *
 * Mutable `var`s, like `TuneState`: these are written from mpv's event and log threads as well as the
 * main thread, exactly as before, and the fields that were `@Volatile` keep that individually.
 */
internal data class LoadState(
    /** Set when mpv fires EVENT_FILE_LOADED; with [loadStartTime] it decides which stage of the
     *  VOD/archive load watchdog applies - open timeout at 10s, moov-at-end at 6s, decode at 7s. */
    var fileLoaded: Boolean = false,
    /** When loadUrl started, for the watchdog stages above. */
    var loadStartTime: Long = 0L,
    /** Load this item paused (restoring a backgrounded VOD). */
    @field:Volatile var pendingStartPaused: Boolean = false,
    /** A VOD load that wants to start on ExoPlayer but arrived before the surface exists (first open):
     *  attachSurface flushes pendingUrl into startExo instead of mpv's startLoad. */
    @field:Volatile var pendingExoStart: Boolean = false,
    /** The image subtitle a deferred Exo start ([pendingExoStart]) must select once its surface arrives. */
    var pendingExoSub: TrackOption? = null,
    /** Last warn/error-level mpv log line (e.g. "ffmpeg: http: HTTP error 400"). Snapshotted into the
     *  error info when one surfaces, so the HUD can show the real cause. */
    @field:Volatile var lastMpvError: String? = null,
    /** Broken-timestamp detection. Touched from mpv's log thread. */
    @field:Volatile var brokenPtsHits: Int = 0,
    @field:Volatile var brokenPtsHandled: Boolean = false,
    var expectingPlayback: Boolean = false,
    var pendingSeekMs: Long = 0L,
    /** Decode watchdog: if a >1080p video ends up on the software decoder, playback is aborted with a
     *  friendly error - CPU-decoding 4K on a TV chip stutters, overheats and OOM-kills the app (observed
     *  on a TCL G10). These arrive on mpv's event thread per loaded file. */
    @field:Volatile var currentHwdec: String? = null,
    /** For the error screen's media spec line. */
    @field:Volatile var currentVideoCodec: String? = null,
    @field:Volatile var currentHeightPx: Int = 0,
    @field:Volatile var currentWidthPx: Int = 0,
    @field:Volatile var decodeGuardTripped: Boolean = false,
)

internal data class ItemState(
    /** Silent auto-retry budget for a load that fails to start (cold-boot decoder-busy, a provider 5xx,
     *  the surface-timing race). Counts up across retries with backoff, then the error UI takes over. */
    @field:Volatile var autoRetries: Int = 0,
    /** Set when the user hits "Cancel" on the next-episode countdown card - suppresses the automatic
     *  end-of-file advance for the CURRENT item only. */
    var autoNextCancelled: Boolean = false,
    var liveStallReconnects: Int = 0,
    /** A stream that will not start on the extension it was asked for is retried once on the other one -
     *  `.ts` or `.m3u8` - before erroring. For live that covers the panel which only serves HLS; for a
     *  catch-up archive the opposite, a panel whose live edge remuxes to HLS while its timeshift server
     *  only ever has `.ts` on disk. */
    @field:Volatile var triedAltFormat: Boolean = false,
    /** The catch-up `timeshift.php` query form - a SEPARATE alternate from the extension swap, for the
     *  panels that reject the path-style archive URL outright. Sharing one flag with [triedAltFormat]
     *  meant whichever alternate ran first consumed the other's turn. */
    @field:Volatile var triedCatchupPhpForm: Boolean = false,
    /** The URL the extension swap is derived from: the item's ORIGINAL url, not the live `currentUrl`.
     *  Once the `timeshift.php` alternate has loaded there is no extension left to swap. */
    @field:Volatile var altFormatBaseUrl: String? = null,
    /** FFmpeg error tolerance for this load - the retry rung for a stream mpv's strict demuxer defaults
     *  reject. [triedTolerantDemux] keeps it to a single attempt. */
    @field:Volatile var tolerantDemuxThisLoad: Boolean = false,
    @field:Volatile var triedTolerantDemux: Boolean = false,
    /** Catch-up/VOD streams that start mid-GOP (no H.264 SPS/PPS yet) can play audio with a blank
     *  video; one software-decode reload is tried before surfacing an error. */
    @field:Volatile var triedSoftwareForVideo: Boolean = false,
    /** With no custom User-Agent, a failed load is retried once under the neutral FALLBACK_USER_AGENT -
     *  some panels sit behind a WAF that blocklists player identities by name. */
    @field:Volatile var triedUaFallback: Boolean = false,
    /** That retry is in flight: if it loads, the panel really was refusing the default identity, and the
     *  lesson is recorded panel-wide in `LiveStreamQuirks`. */
    @field:Volatile var uaFallbackPending: Boolean = false,
    /** T_OPEN first-strike: a hung open gets ONE silent hard-reset + reload of the same item before any
     *  error is shown. Covers auto-play advancing while the provider still holds the previous episode's
     *  connection slot. */
    var triedOpenReset: Boolean = false,
    /** A VOD that dies mid-stream gets ONE silent reload from the current position before an error. */
    var triedMidStreamReload: Boolean = false,
    @field:Volatile var triedExoVodFallback: Boolean = false,
    /** A text subtitle picked while an Exo handoff is active: applied after mpv reloads (FILE_LOADED). */
    @field:Volatile var pendingSelectSid: Int? = null,
    /** An external subtitle added while an Exo image-sub handoff was active: attached after mpv reloads. */
    @field:Volatile var pendingExternalAdd: OwnTVPlayer.ExternalSub? = null,
    /** Embedded sub to re-select after an Exo to mpv toggle reload (0-based ordinal among sub tracks). */
    @field:Volatile var pendingSelectSubOrdinal: Int? = null,
    /** The external sub a deferred Exo start must select (engine toggle with an external sub active). */
    @field:Volatile var pendingExoExternalSelect: OwnTVPlayer.ExternalSub? = null,
    /** ExoPlayer-preferred mode: the item STARTED on Exo, so an Exo failure falls back to mpv (the
     *  reverse chain); a later terminal mpv failure shows the combined error. */
    @field:Volatile var exoPrimaryThisItem: Boolean = false,
    @field:Volatile var exoFailureBeforeMpv: PlaybackFailure? = null,
    /** Engine-fallback state: a VOD that terminally failed on mpv is retried once on ExoPlayer. While
     *  this is set, Exo owns playback as a *player* (not an image-sub handoff). */
    @field:Volatile var exoVodFallback: Boolean = false,
    @field:Volatile var forceFullProbe: Boolean = false,
    /** This item is a catch-up / live-rewind ARCHIVE (timeshift) stream rather than a normal VOD file.
     *  Archives start mid-GOP and are served without Range support, which changes three things: they
     *  never start on the Exo-primary route, they resume from 0 across an engine switch, and a decode
     *  failure gets the software rescue. */
    @field:Volatile var archiveThisItem: Boolean = false,
    /** A stream the hardware decoder cannot start (weak TV decoders reject some otherwise-fine files) is
     *  retried ONCE in pure software, per item, before the error shows. Never changes the user setting. */
    @field:Volatile var forceSoftwareThisLoad: Boolean = false,
    /** The RESCUE rung between "direct hardware" and "software 1080p or less" (F09): hwdec=mediacodec-copy
     *  + vo=gpu - still hardware decoding, but frames are copied out and composited by GL. Never a
     *  default; it exists only for the 4K file the direct path cannot open at all. */
    @field:Volatile var forceCopyThisLoad: Boolean = false,
    @field:Volatile var triedCopyRescue: Boolean = false,
    /** CEA-608/708 CC text is decoder side data the hardware decoder never surfaces, so the synthetic CC
     *  track stays empty under hwdec (#57). While a CC track is selected we decode in software. */
    @field:Volatile var ccSoftwareOverride: Boolean = false,
)
