package dk.perspektiva.ttsroad.media

import android.os.Looper
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.player.FakePlayer
import dk.perspektiva.ttsroad.player.SleepTimerController
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProbeTest {
    private class P : SimpleBasePlayer(Looper.getMainLooper()) {
        var position = 280_000L
        override fun getState(): State = State.Builder()
            .setAvailableCommands(androidx.media3.common.Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(FakePlayer.item("chapter:10", durationMs = 300_000L)))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setPlayWhenReady(true, androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(androidx.media3.common.Player.STATE_READY)
            .build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Test
    fun probe() {
        val p = P()
        val timer = SleepTimerController().apply { armDuration(60_000L) }
        val progress = SleepTimerProgress(p, timer)
        println("PROBE duration=${p.duration} pos=${p.currentPosition} speed=${p.playbackParameters.speed}")
        println("PROBE state=${timer.state.value} defer=${progress.deferPlayedMark(p.currentMediaItem!!)}")
        val remaining = dk.perspektiva.ttsroad.player.remainingMsAtSpeed(p.currentPosition, p.duration, p.playbackParameters.speed)
        println("PROBE remaining=$remaining")
    }
}
