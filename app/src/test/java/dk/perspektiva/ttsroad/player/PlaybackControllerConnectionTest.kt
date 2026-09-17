package dk.perspektiva.ttsroad.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dk.perspektiva.ttsroad.core.ServiceLocator
import java.util.concurrent.Future
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingConnector : ControllerConnector {
    val listeners = mutableListOf<MediaController.Listener>()
    val futures = mutableListOf<SettableFuture<MediaController>>()
    val releasedFutures = mutableListOf<Future<MediaController>>()

    override fun connect(
        context: Context,
        token: SessionToken,
        listener: MediaController.Listener,
    ): ListenableFuture<MediaController> {
        val future = SettableFuture.create<MediaController>()
        listeners += listener
        futures += future
        return future
    }

    override fun releaseFuture(future: Future<MediaController>) {
        releasedFutures += future
        super.releaseFuture(future)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player).build()
        scope = CoroutineScope(Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        scope.cancel()
        session.release()
        player.release()
    }

    private fun createRealController(): MediaController =
        MediaController.Builder(context, session.token).buildAsync().get()

    private fun controller(connector: ControllerConnector): PlaybackController =
        PlaybackController(
            context = context,
            tokenStore = ServiceLocator.tokenStore(context),
            preferences = ServiceLocator.playbackPreferences(context),
            fictionSpeeds = ServiceLocator.fictionSpeedPreferences(context),
            connector = connector,
            scope = scope,
        )

    @Test
    fun `releasing while connection is in flight cancels it and releases the future`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()

        assertEquals(1, connector.futures.size)
        val pendingFuture = connector.futures.first()

        controller.release()

        assertTrue("in-flight future must be handed to releaseFuture", connector.releasedFutures.contains(pendingFuture))
        assertTrue("pending future must have been cancelled", pendingFuture.isCancelled)
        assertNull(controller.reportedPositionMs())
    }

    @Test
    fun `completed future before release is released by release and never leaves controller installed`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()
        val pendingFuture = connector.futures.first()
        val realController = createRealController()
        assertTrue(realController.isConnected)

        // Complete the future, then release
        pendingFuture.set(realController)
        controller.release()

        assertNull("controller must not be installed on released PlaybackController", controller.reportedPositionMs())
        assertEquals("Nothing playing", controller.state.value.title)
        assertFalse("completed controller must have been released by releaseFuture or release", realController.isConnected)
    }

    @Test
    fun `prior generation connection does not overwrite subsequent connection`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()
        val firstFuture = connector.futures[0]
        val firstController = createRealController()
        firstFuture.set(firstController)

        // Release generation 1
        controller.release()
        assertFalse("first controller was released on release", firstController.isConnected)
        assertNull(controller.reportedPositionMs())

        // Start generation 2
        controller.connect()
        assertEquals(2, connector.futures.size)
        val secondFuture = connector.futures[1]
        val secondController = createRealController()
        secondFuture.set(secondController)

        assertTrue("second controller is current and must stay connected", secondController.isConnected)
        assertNotNull(controller.reportedPositionMs())

        controller.release()
        assertFalse("releasing must disconnect the active controller", secondController.isConnected)
        assertNull(controller.reportedPositionMs())
    }

    @Test
    fun `releasing an active controller disconnects it and resets state`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()
        val realController = createRealController()
        connector.futures.first().set(realController)

        assertTrue(realController.isConnected)
        assertNotNull(controller.reportedPositionMs())

        controller.release()

        assertFalse("controller must be released", realController.isConnected)
        assertNull(controller.reportedPositionMs())
        assertEquals("Nothing playing", controller.state.value.title)
    }

    @Test
    fun `disconnect resets state and reconnects only on demand ignoring stale callbacks`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)
        controller.connect()
        val first = createRealController()
        connector.futures[0].set(first)
        first.release()
        connector.listeners[0].onDisconnected(first)

        assertNull(controller.reportedPositionMs())
        assertEquals(PlayerUiState(), controller.state.value)
        assertEquals(1, connector.futures.size)

        controller.connect()
        assertEquals(2, connector.futures.size)
        val second = createRealController()
        connector.futures[1].set(second)
        connector.listeners[0].onDisconnected(first)
        assertNotNull(controller.reportedPositionMs())
        assertTrue(second.isConnected)
        controller.release()
        connector.listeners[1].onDisconnected(second)
        assertEquals(2, connector.futures.size)
        assertNull(controller.reportedPositionMs())
    }

    @Test
    fun `failed connection resets connecting and permits retry`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()
        connector.futures[0].setException(IllegalStateException("failed connection"))

        assertNull(controller.reportedPositionMs())

        // Next connection attempt should be able to try again
        controller.connect()
        assertEquals(2, connector.futures.size)

        val realController = createRealController()
        connector.futures[1].set(realController)

        assertTrue(realController.isConnected)
        assertNotNull(controller.reportedPositionMs())

        controller.release()
    }
}
