package tv.own.owntv.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the state of an external IPTV audio source for AudioMix.
 *
 * The actual libmpv commands are handled by OwnTVPlayer so that all
 * MPV operations continue to run through its existing mpvAsync executor.
 */
class AudioMixController {

    private val active = AtomicBoolean(false)

    @Volatile
    private var audioUrl: String? = null

    @Volatile
    private var delayMs: Int = 0

    fun isActive(): Boolean = active.get()

    fun currentUrl(): String? = audioUrl

    fun currentDelayMs(): Int = delayMs

    fun setSource(url: String?) {
        audioUrl = url
        active.set(!url.isNullOrBlank())
    }

    fun clearSource() {
        audioUrl = null
        delayMs = 0
        active.set(false)
    }

    fun setDelay(delayMs: Int) {
        this.delayMs = delayMs.coerceIn(-10_000, 10_000)
    }
}
