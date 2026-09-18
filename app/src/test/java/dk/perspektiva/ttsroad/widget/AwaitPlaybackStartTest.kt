package dk.perspektiva.ttsroad.widget

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.player.FakePlayer
import kotlinx.coroutines.test.runTest
import org.junit.After
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
}
