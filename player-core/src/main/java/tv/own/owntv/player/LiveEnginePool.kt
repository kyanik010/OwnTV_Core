package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engines behind a Multiview grid: one [LivePreviewEngine] per tile, created on demand and
 * released together.
 *
 * Normal Multiview has exactly one audible tile and gives that tile the full picture. The
 * background-audio mode is different: it deliberately separates the two roles so the video source
 * can remain full resolution while a different tile supplies the audio.
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

    /** The engine for [tile], building it the first time. New engines start muted and capped. */
    fun engineFor(tile: Int): LivePreviewEngine = engines.getOrPut(tile) {
        newEngine().also {
            it.setMuted(true)
            it.setMaxVideoHeight(BACKGROUND_TILE_HEIGHT)
        }
    }

    /** The engine for [tile] if it has one, without building one. */
    fun peek(tile: Int): LivePreviewEngine? = engines[tile]

    /**
     * Normal Multiview audio selection. The audible tile gets the full picture and all other tiles
     * are muted/capped for decoder and bandwidth protection.
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
     * True source separation:
     *
     * [audioTile] supplies audio only.
     * [videoTile] remains visible, muted, and FULL resolution.
     * Other tiles remain muted/capped.
     *
     * This is intentionally separate from [giveSoundTo]. Using giveSoundTo for this mode would cap
     * the 4K video source to BACKGROUND_TILE_HEIGHT, defeating the feature.
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
                    // Audio-only mode owns the video suppression.
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

    /**
     * Restore a sound-only tile to a normal picture tile. The caller then decides which tile should
     * own the sound.
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
        /** Background video ceiling for secondary Multiview tiles. */
        const val BACKGROUND_TILE_HEIGHT = 720
    }
}
