package tv.own.owntv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the player HUD needs from "whichever engine is currently playing" — mpv ([OwnTVPlayer], via
 * [MpvPlaybackEngine]) or the ExoPlayer live engine ([LivePreviewEngine] when a Live preview is promoted to
 * full-screen). VOD-only controls (seek/speed/prev-next/position) have no-op defaults so a live engine need
 * only implement the live-relevant members.
 */
interface PlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    val buffering: StateFlow<Boolean>
    val error: StateFlow<PlaybackFailure?>
    /** The structured underlying failure (plain reason • media spec • raw engine text), shown under the
     *  friendly message so users can report the real cause without adb/logcat. Null when none. */
    val errorInfo: StateFlow<ErrorInfo?> get() = NULL_ERROR
    /** Set while the engine is sitting out a wait the provider asked for (HTTP 429 + `Retry-After`) and
     *  will re-ask for the identical stream by itself. The HUD shows it as a spinner with a live countdown
     *  instead of an error screen. Null when nothing is pending. */
    val providerBackOff: StateFlow<ProviderBackOff?> get() = NO_BACKOFF
    val videoRes: StateFlow<String?>
    /** Up-to-4 mini stream chips (aspect · resolution · fps · audio) for the player top bar. */
    val streamChips: StateFlow<List<String>> get() = NO_CHIPS
    /** Re-check [streamChips] now. */
    fun refreshStreamChips() {}
    /** Bitrate is only ever displayed in the debug overlay — enable tracking only while it's open. */
    fun setBitrateTrackingEnabled(enabled: Boolean) {}
    /** Short label of the engine currently decoding ("MPV" / "EXO"), shown as the first top-bar chip.
     *  Null = don't show one. */
    val engineChip: StateFlow<String?> get() = NULL_STRING
    val volume: StateFlow<Int>
    val zoomMode: StateFlow<ZoomMode>
    val audioCount: StateFlow<Int>
    val subCount: StateFlow<Int>
    val currentMeta: StateFlow<MediaMeta>
    val isLiveContent: Boolean

    /**
     * Whether the sound comes out of THIS device.
     *
     * True for every decoder that runs here, which is why it defaults to true. False only for an
     * engine that has handed the stream to something else — a Chromecast — where the phone's audio
     * focus and its headphone-unplug rule would be acting on sound it is not making. Pulling the
     * headphones out should not pause a film playing in another room.
     */
    val playsLocally: Boolean get() = true

    /** True while the engine decodes audio only (video output stopped to save power) — Audio Mode. */
    val audioOnly: StateFlow<Boolean> get() = FALSE_FLOW

    /**
     * True when the ITEM itself carries no video track at all — a radio channel in a TV playlist, a
     * music-only file filed under Movies.
     *
     * Distinct from [audioOnly], which is the app switching video off because the user asked. This one is
     * a property of the stream, and the UI needs it: sound over a black screen is indistinguishable from
     * a broken player, so the player says so on screen instead of leaving the user to guess.
     */
    val audioOnlyMedia: StateFlow<Boolean> get() = FALSE_FLOW
    /** Stop the video decoder/output but keep audio playing at position (Audio Mode enter). No-op if
     *  already audio-only. Audio is uninterrupted — mpv drops the video track (`vid=no`), ExoPlayer
     *  releases its surface. */
    fun enterAudioOnly() {}
    /** Resume video output (Audio Mode exit → back to fullscreen/mini). No-op if not audio-only. */
    fun exitAudioOnly() {}

    fun togglePlayPause()
    fun setZoomMode(mode: ZoomMode)
    fun adjustVolume(delta: Int)

    /**
     * The two above, but as a DELIBERATE user choice: the engine applies the change and remembers it
     * for the item currently playing (`playback_prefs`), so the same film/channel comes back at that
     * zoom and volume. Everything else that moves zoom or volume — audio-focus ducking, an engine
     * handoff carrying the level across, re-applying zoom on a new video track — must keep calling
     * the plain setters, or the player would teach itself preferences the user never expressed.
     */
    fun setZoomModeByUser(mode: ZoomMode) = setZoomMode(mode)
    fun adjustVolumeByUser(delta: Int) = adjustVolume(delta)
    fun toggleMute()
    fun retry()
    val audioMixEnabled: StateFlow<Boolean> get() = FALSE_FLOW
    fun audioMixEnable(url: String, headers: String? = null, userAgent: String? = null) {}
    fun audioMixDisable() {}
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)
    fun disableSubtitles()
    /** Attach + select an external subtitle file (OpenSubtitles/local). VOD only (mpv sub-add, or an
     *  ExoPlayer side-load re-prepare); a live engine ignores it (subtitle plan §3.4). */
    fun addExternalSubtitle(path: String, title: String, lang: String?) {}
    fun audioTracks(): List<TrackOption>
    fun textTracks(): List<TrackOption>

    /** Live technical readout (label → value) for the stream-info overlay — codec, resolution, fps, HDR,
     *  bitrate, decoder, audio, buffer, source. A snapshot; the overlay re-reads it periodically.
     *
     *  Suspending because the mpv implementation reads ~25 properties and does so on its own executor
     *  rather than on the caller's thread; the ExoPlayer implementations stay on the main thread, which
     *  is what Media3 requires, and simply never suspend. */
    suspend fun streamInfo(): List<StreamInfoRow> = emptyList()

    // VOD-only — sensible no-op / empty defaults for a live engine.
    val position: StateFlow<Long> get() = ZERO_LONG
    val duration: StateFlow<Long> get() = ZERO_LONG
    /** How far ahead of [position] the engine has data, for the scrub bar's buffer ghost. 0 = unknown,
     *  which simply draws no ghost — an engine that cannot report it costs nothing. */
    val bufferedMs: StateFlow<Long> get() = ZERO_LONG
    val speed: StateFlow<Double> get() = ONE_DOUBLE
    val nav: StateFlow<NavState> get() = NO_NAV
    /** Title of the next queued item (in-season next episode), for the HUD next-episode countdown card.
     *  Null when there is no next item — a live engine leaves it null. */
    val nextUpTitle: StateFlow<String?> get() = NULL_STRING
    /** In-player A/V-sync nudge (ms) — mpv only; an ExoPlayer engine leaves it at 0. */
    val audioDelayMs: StateFlow<Int> get() = ZERO_INT
    /** True when this engine can shift audio against video (mpv's `audio-delay`). ExoPlayer cannot, so
     *  the HUD hides the nudge there. mpv supports it on live too — provider A/V drift is real (F19e). */
    fun audioDelayAvailable(): Boolean = false
    /** Whether the current item has its own remembered A/V-sync offset (mpv only). */
    val audioDelayRemembered: StateFlow<Boolean> get() = FALSE_FLOW
    /** Remember the current A/V-sync offset for this item, or forget it again (mpv only). */
    fun toggleRememberAudioDelay() {}
    /** Subtitle-timing offset (ms) for the ACTIVE subtitle — VOD only (subtitle plan §8). */
    val subDelayMs: StateFlow<Int> get() = ZERO_INT
    /** Settings → Seek step: how far one press of rewind/forward moves. VOD only; a live engine never
     *  seeks (stepping through a live archive is Live TV's own rewind, with its own setting). */
    val seekStepMs: StateFlow<Long> get() = DEFAULT_SEEK_STEP
    fun setSpeed(speed: Double) {}
    fun adjustAudioDelay(deltaMs: Int) {}
    fun adjustSubtitleDelay(deltaMs: Int) {}
    fun resetSubtitleDelay() {}
    /** True when timing adjustment applies to the active subtitle on this engine (plan §8.1). */
    fun subtitleTimingAvailable(): Boolean = false
    fun previous() {}
    fun next() {}
    fun seekBy(deltaMs: Long) {}
    /** HUD "Cancel" on the next-episode countdown — suppress the automatic advance for the current item. */
    fun cancelAutoNext() {}

    companion object {
        private val ZERO_INT: StateFlow<Int> = MutableStateFlow(0)
        private val ZERO_LONG: StateFlow<Long> = MutableStateFlow(0L)
        private val ONE_DOUBLE: StateFlow<Double> = MutableStateFlow(1.0)
        private val NO_NAV: StateFlow<NavState> = MutableStateFlow(NavState(hasPrev = false, hasNext = false))
        private val NULL_ERROR: StateFlow<ErrorInfo?> = MutableStateFlow(null)
        private val NO_BACKOFF: StateFlow<ProviderBackOff?> = MutableStateFlow(null)
        private val NO_CHIPS: StateFlow<List<String>> = MutableStateFlow(emptyList())
        private val NULL_STRING: StateFlow<String?> = MutableStateFlow(null)
        private val FALSE_FLOW: StateFlow<Boolean> = MutableStateFlow(false)
        private val DEFAULT_SEEK_STEP: StateFlow<Long> =
            MutableStateFlow(tv.own.owntv.core.settings.SeekSteps.DEFAULT_SEEK_STEP_SEC * 1000L)
    }
}

/** Adapts the full mpv player to [PlaybackEngine] (delegation only — keeps [OwnTVPlayer] untouched). */
class MpvPlaybackEngine(private val p: OwnTVPlayer) : PlaybackEngine {
    override val isPlaying get() = p.isPlaying
    override val buffering get() = p.buffering
    override val error get() = p.error
    override val errorInfo get() = p.errorInfo
    override val videoRes get() = p.videoRes
    override val streamChips get() = p.streamChips
    override val engineChip get() = p.engineChip
    override val volume get() = p.volume
    override val zoomMode get() = p.zoomMode
    override val audioCount get() = p.audioCount
    override val subCount get() = p.subCount
    override val currentMeta get() = p.currentMeta
    override val isLiveContent get() = p.isLiveContent
    override val audioOnly get() = p.audioOnly
    override val audioOnlyMedia get() = p.audioOnlyMedia
    override fun enterAudioOnly() = p.enterAudioOnly()
    override fun exitAudioOnly() = p.exitAudioOnly()
    override val position get() = p.position
    override val duration get() = p.duration
    override val bufferedMs get() = p.bufferedMs
    override val speed get() = p.speed
    override val nav get() = p.nav
    override val nextUpTitle get() = p.nextUpTitle
    override val audioDelayMs get() = p.audioDelayMs
    override fun audioDelayAvailable() = true
    override val subDelayMs get() = p.subDelayMs
    override fun adjustSubtitleDelay(deltaMs: Int) = p.adjustSubtitleDelay(deltaMs)
    override fun resetSubtitleDelay() = p.resetSubtitleDelay()
    override fun subtitleTimingAvailable() = p.subtitleTimingAvailable()
    override fun togglePlayPause() = p.togglePlayPause()
    override fun setZoomMode(mode: ZoomMode) = p.setZoomMode(mode)
    override fun adjustVolume(delta: Int) = p.adjustVolume(delta)
    override fun setZoomModeByUser(mode: ZoomMode) = p.setZoomModeByUser(mode)
    override fun adjustVolumeByUser(delta: Int) = p.adjustVolumeByUser(delta)
    override fun toggleMute() = p.toggleMute()
    override fun retry() = p.retry()
    override val audioMixEnabled get() = p.audioMixEnabled
    override fun audioMixEnable(url: String, headers: String?, userAgent: String?) = p.audioMixEnable(url, headers, userAgent)
    override fun audioMixDisable() = p.audioMixDisable()
    override fun selectAudio(id: Int) = p.selectAudio(id)
    override fun selectSubtitle(id: Int) = p.selectSubtitle(id)
    override fun disableSubtitles() = p.disableSubtitles()
    override fun addExternalSubtitle(path: String, title: String, lang: String?) = p.addExternalSubtitle(path, title, lang)
    override fun audioTracks() = p.audioTracks()
    override fun textTracks() = p.textTracks()
    override suspend fun streamInfo() = p.streamInfo()
    override fun setBitrateTrackingEnabled(enabled: Boolean) = p.setBitrateTrackingEnabled(enabled)
    override fun refreshStreamChips() = p.refreshStreamChips()
    override fun setSpeed(speed: Double) = p.setSpeed(speed)
    override fun adjustAudioDelay(deltaMs: Int) = p.adjustAudioDelay(deltaMs)
    override val audioDelayRemembered get() = p.audioDelayRemembered
    override fun toggleRememberAudioDelay() = p.toggleRememberAudioDelay()
    override fun previous() = p.previous()
    override fun next() = p.next()
    override fun seekBy(deltaMs: Long) = p.seekBy(deltaMs)
    override val seekStepMs get() = p.seekStepMs
    override fun cancelAutoNext() = p.cancelAutoNext()
}
