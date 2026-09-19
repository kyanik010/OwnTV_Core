package tv.own.owntv.player

import android.content.Context
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental AudioMix backend.
 *
 * Unlike the legacy MPV audio-add path, this backend owns ONE GStreamer pipeline containing
 * both the picture source and the commentary source. Both branches therefore share the
 * pipeline clock/base-time and GStreamer's normal timestamp/segment synchronisation.
 *
 * The class intentionally contains no IPTV URL construction: callers pass the already-resolved
 * Xtream playback URLs so this engine remains transport-agnostic.
 */
internal class GStreamerAudioMixEngine(context: Context) {
    private val started = AtomicBoolean(false)

    init {
        System.loadLibrary("gstreamer_android")
        System.loadLibrary("owntv_audiomix_gst")
        nativeInit()
    }

    fun start(surface: Surface, videoUrl: String, audioUrl: String): Boolean {
        if (videoUrl.isBlank() || audioUrl.isBlank()) return false
        stop()
        val ok = nativeStart(surface, videoUrl, audioUrl)
        started.set(ok)
        return ok
    }

    fun stop() {
        if (started.getAndSet(false)) nativeStop()
    }

    fun setAudioRate(rate: Double) {
        if (started.get()) nativeSetAudioRate(rate.coerceIn(0.997, 1.003))
    }

    fun setAudioDelayMs(delayMs: Long) {
        if (started.get()) nativeSetAudioDelay(delayMs.coerceIn(-5000L, 5000L))
    }

    fun isRunning(): Boolean = started.get()

    private external fun nativeInit()
    private external fun nativeStart(surface: Surface, videoUrl: String, audioUrl: String): Boolean
    private external fun nativeStop()
    private external fun nativeSetAudioRate(rate: Double)
    private external fun nativeSetAudioDelay(delayMs: Long)
}
