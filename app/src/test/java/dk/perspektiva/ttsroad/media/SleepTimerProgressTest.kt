package dk.perspektiva.ttsroad.media

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.player.FakePlayer
import dk.perspektiva.ttsroad.player.PlayedMarks
import dk.perspektiva.ttsroad.player.PlayedThreshold
import dk.perspektiva.ttsroad.player.SleepTimerAction
import dk.perspektiva.ttsroad.player.SleepTimerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class TimerStopPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var position = 280_000L
    private var ready = true
    private var reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var ended = false
    private var index = 0
    var seeks = 0

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(10, 11).map {
            FakePlayer.item("chapter:$it", durationMs = 300_000L).buildUpon().setIsSeekable(true).build()
        })
        .setCurrentMediaItemIndex(index)
        .setContentPositionMs(position)
        .setPlayWhenReady(ready, reason)
        .setPlaybackState(if (ended) Player.STATE_ENDED else Player.STATE_READY)
        .build()

    /** What pauseAtEndOfMediaItems looks like on the app thread: paused at the chapter boundary. */
    fun boundary(lastItem: Boolean) {
        position = 300_000L
        ready = false
        reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
        ended = lastItem
        invalidateState()
    }

    /** A chapter finishing on its own, with no pauseAtEndOfMediaItems involved. */
    fun naturalEnd() {
        position = 300_000L
        ended = true
        invalidateState()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        index = mediaItemIndex
        position = positionMs
        ended = false
        seeks++
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        ready = playWhenReady
        ended = false
        reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        return Futures.immediateVoidFuture()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerProgressTest {
    private fun reached(player: TimerStopPlayer, progress: SleepTimerProgress, queueEnded: Boolean = false): Boolean =
        PlayedThreshold.reached(
            player.currentPosition, player.duration, true, queueEnded,
            progress.deferPlayedMark(player.currentMediaItem!!),
        )

    @Test
    fun `boundary pause stays unplayed before and after timer expiry, STATE_ENDED included`() {
        for (lastItem in listOf(false, true)) {
            val player = TimerStopPlayer()
            val timer = SleepTimerController().apply { armEndOfChapter(20_000L) }
            val progress = SleepTimerProgress(player, timer)
            player.addListener(progress)
            val saves = mutableListOf<Pair<Long, Boolean>>()
            val marks = PlayedMarks()
            fun save(queueEnded: Boolean) {
                saves += player.currentPosition to marks.resolve(10, false, reached(player, progress, queueEnded))
            }
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) save(false)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) save(true)
                }
            })
            save(false)
            timer.tick(0L, true, 20_000L)
            player.boundary(lastItem)
            assertEquals(SleepTimerAction.Expire, timer.tick(20_000L, false, 0L))
            progress.expired()
            player.pause()
            save(lastItem)
            assertTrue(saves.any { it.first == 300_000L })
            assertTrue("a timer stop never marks played", saves.all { !it.second })
            assertFalse(timer.state.value.isArmed)
            assertFalse(reached(player, progress, lastItem))
        }
    }

    @Test
    fun `playing on clears the deferral so the next chapter start counts the stop as natural`() {
        val player = TimerStopPlayer()
        val timer = SleepTimerController().apply { armEndOfChapter(20_000L) }
        val progress = SleepTimerProgress(player, timer)
        player.addListener(progress)
        player.boundary(false)
        assertTrue(reached(player, progress) == false)
        player.play()
        timer.cancel()
        assertFalse(progress.deferPlayedMark(player.currentMediaItem!!))
    }

    @Test
    fun `duration timer expiry is recorded before pause and holds after disarm`() {
        val player = TimerStopPlayer()
        val timer = SleepTimerController().apply { armDuration(500L) }
        val progress = SleepTimerProgress(player, timer)
        player.addListener(progress)
        timer.tick(0L, true, 20_000L)
        assertFalse(reached(player, progress))
        assertEquals(SleepTimerAction.Expire, timer.tick(500L, true, 20_000L))
        progress.expired()
        player.pause()
        assertFalse(reached(player, progress))
        player.seekTo(0, 100_000L)
        player.play()
        assertEquals(100_000L, player.currentPosition)
        assertFalse(progress.deferPlayedMark(player.currentMediaItem!!))
    }

    @Test
    fun `natural completions remain played even when a duration timer is armed`() {
        val player = TimerStopPlayer()
        val timer = SleepTimerController().apply { armDuration(60_000L) }
        val progress = SleepTimerProgress(player, timer)
        assertTrue(reached(player, progress))
        player.naturalEnd()
        assertTrue(reached(player, progress, queueEnded = true))
        timer.cancel()
        assertTrue(reached(player, progress))
    }

    @Test
    fun `timer does not unmark loaded played chapters or explicit played marks`() {
        val player = TimerStopPlayer()
        val timer = SleepTimerController().apply { armEndOfChapter(20_000L) }
        val progress = SleepTimerProgress(player, timer)
        val marks = PlayedMarks()
        assertTrue(marks.resolve(10, true, reached(player, progress)))
        marks.remember(listOf(10), true)
        assertTrue(marks.resolve(10, false, reached(player, progress)))
    }
}
