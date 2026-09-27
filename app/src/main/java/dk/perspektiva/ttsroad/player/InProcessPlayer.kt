package dk.perspektiva.ttsroad.player

import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/**
 * The service's own player, published for the one reader that cannot live with a controller's
 * estimate of it.
 *
 * A [androidx.media3.session.MediaController] only hears the real position every few seconds and
 * extrapolates from wall time in between, then snaps back to the truth on the next update. That
 * snap is a visible jump in the read-along highlight, forwards or backwards, and skip-silence makes
 * it larger because media time then outruns the wall clock. The UI and the service share one
 * process and one main looper, so the highlight reads the player itself instead.
 *
 * Everything else still goes through the controller: this is read-only, and only for sampling.
 */
internal object InProcessPlayer {
    private var player: Player? = null
    private var discontinuityGeneration = 0L

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            discontinuityGeneration++
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            discontinuityGeneration++
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            discontinuityGeneration++
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            discontinuityGeneration++
        }
    }

    @MainThread
    fun attach(player: Player) {
        this.player?.let { detach(it) }
        this.player = player
        discontinuityGeneration++
        player.addListener(listener)
    }

    @MainThread
    fun detach(player: Player) {
        if (this.player !== player) return
        player.removeListener(listener)
        this.player = null
        discontinuityGeneration++
    }

    @MainThread
    fun sample(): ReadAlongPlaybackSample? {
        val player = player ?: return null
        val looper = Looper.myLooper() ?: return null
        if (player.applicationLooper != looper) return null
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        return ReadAlongPlaybackSample(
            mediaId = mediaId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            speed = player.playbackParameters.speed,
            discontinuityGeneration = discontinuityGeneration,
            exact = true,
        )
    }
}
