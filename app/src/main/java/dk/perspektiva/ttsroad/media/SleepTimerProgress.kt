package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerMode

/**
 * Which chapter's automatic "played" mark is held back because a sleep timer, not the listener,
 * ends its playback (#485).
 *
 * With an end-of-chapter timer the player stops at the boundary whether or not anyone heard the
 * end of the chapter, and a duration or ceiling that expires inside a chapter's tail stops there
 * too. Either way the next save lands inside the played threshold. This keeps the automatic mark
 * deferred while a timer is set to stop inside the current chapter, and from then until the
 * listener plays it on — a stop the timer made is a position, not a finish.
 *
 * Playing at a boundary afterwards advances into the next chapter, which marks this one played
 * through the departing-chapter save, exactly as a natural completion did before.
 */
internal class SleepTimerProgress(
    private val player: Player,
    private val timer: SleepTimerController,
) : Player.Listener {
    /** The chapter whose end a sleep timer stopped on, until the listener plays again. */
    private var stoppedItem: MediaItem? = null

    /** The service has applied [dk.perspektiva.ttsroad.player.SleepTimerAction.Expire]. */
    fun expired() {
        stoppedItem = player.currentMediaItem
    }

    fun deferPlayedMark(item: MediaItem): Boolean {
        if (item == stoppedItem) return true
        if (item != player.currentMediaItem) return false
        val state = timer.state.value
        if (!state.isArmed) return false
        if (state.mode == SleepTimerMode.EndOfChapter) return true
        // A plain countdown stops at a wall-clock moment. The played threshold only matters when it
        // lands inside the current chapter's tail, and there it is the timer, not the listener,
        // that is about to cut playback short.
        val duration = player.duration.takeIf { it > 0L } ?: return false
        val chapterRemainingMs = dk.perspektiva.ttsroad.player.remainingMsAtSpeed(
            positionMs = player.currentPosition,
            durationMs = duration,
            speed = player.playbackParameters.speed,
        )
        return chapterRemainingMs != null && state.remainingMs <= chapterRemainingMs
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            stoppedItem = player.currentMediaItem
        }
        if (playWhenReady) stoppedItem = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem != stoppedItem) stoppedItem = null
    }
}
