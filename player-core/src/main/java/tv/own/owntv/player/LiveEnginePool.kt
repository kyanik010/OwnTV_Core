package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engines behind a Multiview grid: one [LivePreviewEngine] per tile, created on demand and
 * released together.
 *
 * Two rules live here rather than in either app, because getting them wrong is the difference
 * between a grid and a mess, and both apps would otherwise implement them separately:
 *
 * 1. **Exactly one tile has sound.** Giving a tile the sound mutes whichever tile had it. Four
 *    engines each grabbing audio focus is precisely what must not happen.
 * 2. **The tiles without sound are asked for less video.** The focused tile keeps the full picture;
 *    the rest are capped, which is one of the only two mitigations D5 leaves for a device that runs
 *    out of hardware decoders (the other is the tile saying so — see
 *    [PlaybackFailure.DecoderExhausted]).
 *
 * The pool does not tune anything and knows nothing about channels: the screen still drives each
 * engine. It owns their lifetime and their volume, nothing else.
 *
 * Main thread only, like the engines themselves.
 */
@UnstableApi
class LiveEnginePool(private val newEngine: () -> LivePreviewEngine) {

    private val engines = LinkedHashMap<Int, LivePreviewEngine>()
    private val _audibleTile = MutableStateFlow<Int?>(null)

    /** The tile whose sound is playing, or null while every tile is muted. */
    val audibleTile: StateFlow<Int?> = _audibleTile.asStateFlow()

    /** How many engines exist right now — tiles that have been asked for, filled or not. */
    val size: Int get() = engines.size

    /**
     * The engine for [tile], building it the first time. A new engine starts muted and capped: it is
     * given the sound only by an explicit [giveSoundTo], so a tile can never steal audio by opening.
     */
    fun engineFor(tile: Int): LivePreviewEngine = engines.getOrPut(tile) {
        newEngine().also {
            it.setMuted(true)
            it.setMaxVideoHeight(BACKGROUND_TILE_HEIGHT)
        }
    }

    /** The engine for [tile] if it has one, without building one. */
    fun peek(tile: Int): LivePreviewEngine? = engines[tile]

    /**
     * Move the sound to [tile] — or, with null, mute everything. The tile that gains the sound also
     * gains the full picture, and the one that loses it goes back to the capped one.
     */
    fun giveSoundTo(tile: Int?) {
        if (_audibleTile.value == tile) return
        _audibleTile.value = tile
        engines.forEach { (index, engine) ->
            val audible = index == tile
            engine.setMuted(!audible)
            engine.setMaxVideoHeight(if (audible) null else BACKGROUND_TILE_HEIGHT)
        }
    }

    /**
     * The owner's background-audio case: one tile keeps the picture, another gives up its picture and
     * plays only sound.
     *
     * A sound-only tile takes the sound, because sound is all it has left. It still costs a provider
     * connection — dropping the video track does not close the stream — which is why the tile says so
     * rather than looking free.
     */
    fun setSoundOnly(tile: Int, soundOnly: Boolean) {
        val engine = engines[tile] ?: return
        if (soundOnly) {
            engine.enterAudioOnly()
            giveSoundTo(tile)
        } else {
            engine.exitAudioOnly()
        }
    }

    /**
     * Explicit source separation:
     * [audioTile] supplies audio only; [videoTile] remains visible at full resolution and muted.
     * Other tiles stay muted and capped for decoder/bandwidth protection.
     */
    fun setAudioSource(audioTile: Int, videoTile: Int?) {
        val audioEngine = engines[audioTile] ?: return
        if (videoTile == audioTile) return

        audioEngine.enterAudioOnly()
        _audibleTile.value = audioTile

        engines.forEach { (index, engine) ->
            when {
                index == audioTile -> {
                    engine.setMuted(false)
                    engine.setMaxVideoHeight(BACKGROUND_TILE_HEIGHT)
                }
                index == videoTile -> {
                    engine.setMuted(true)
                    engine.setMaxVideoHeight(null)
                }
                else -> {
                    engine.setMuted(true)
                    engine.setMaxVideoHeight(BACKGROUND_TILE_HEIGHT)
                }
            }
        }
    }

    /** Free one tile's engine — the user emptied it, or the grid shrank. */
    fun release(tile: Int) {
        engines.remove(tile)?.release()
        if (_audibleTile.value == tile) _audibleTile.value = null
    }

    /** Free every engine. Leaving Multiview, and the only thing a caller must not forget. */
    fun releaseAll() {
        engines.values.forEach { it.release() }
        engines.clear()
        _audibleTile.value = null
    }

    companion object {
        /**
         * The video ceiling for a tile without the sound. 720p is a quarter of a 4K panel, which is
         * roughly the size such a tile is drawn at anyway, and it is the difference between four
         * decoder instances a mid-range box can serve and four it cannot.
         */
        const val BACKGROUND_TILE_HEIGHT = 720
    }
}
