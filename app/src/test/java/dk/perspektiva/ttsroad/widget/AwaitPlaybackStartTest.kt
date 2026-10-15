package dk.perspektiva.ttsroad.widget

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.player.FakePlayer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwaitPlaybackStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.release()
        if (::session.isInitialized) session.release()
    }

    private fun playingController(): MediaController {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1", title = "Chapter 1")),
            playing = true,
        )
        session = MediaSession.Builder(context, player).build()
        controller = MediaController.Builder(context, session.token).buildAsync().get()
        return controller
    }

    @Test
    fun `already playing returns immediately`() = runTest {
        val start = System.currentTimeMillis()

        playingController().awaitPlaybackStart()

        assertTrue(System.currentTimeMillis() - start < 1_000L)
    }

    @Test
    fun `idle controller returns after a bounded timeout`() = runTest {
        val player = FakePlayer()
        session = MediaSession.Builder(context, player).build()
        controller = MediaController.Builder(context, session.token).buildAsync().get()

        controller.awaitPlaybackStart(timeoutMs = 100L)
    }

    @Test
    fun `resumed playback completes before the timeout`() = runTest {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1", title = "Chapter 1")),
            playing = false,
        )
        session = MediaSession.Builder(context, player).build()
        controller = MediaController.Builder(context, session.token).buildAsync().get()

        controller.play()
        controller.awaitPlaybackStart(timeoutMs = 5_000L)

        assertTrue(controller.isPlaying)
    }

    /** Hands the test the listener the awaiter registers, so it can fire events at will. */
    private class ListenerCapturingPlayer : ForwardingPlayer(FakePlayer()) {
        val listeners = mutableListOf<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }
    }

    @Test
    fun `a second start event after resuming does not resume again`() = runTest {
        val player = ListenerCapturingPlayer()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            player.awaitPlaybackStart(timeoutMs = 5_000L)
        }
        val listener = player.listeners.single()

        // Both arrive before the resumed coroutine gets to run — the case that used to throw
        // "Already resumed" from inside a Media3 listener (#320).
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        listener.onIsPlayingChanged(true)
        waiting.join()

        assertTrue(waiting.isCompleted)
        assertEquals(emptyList<Player.Listener>(), player.listeners)
    }

    @Test
    fun `the listener is detached once playback starts`() = runTest {
        val player = ListenerCapturingPlayer()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            player.awaitPlaybackStart(timeoutMs = 5_000L)
        }

        player.listeners.single().onIsPlayingChanged(true)
        waiting.join()

        assertTrue(player.listeners.isEmpty())
    }
}
