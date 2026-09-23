package dk.perspektiva.ttsroad.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dk.perspektiva.ttsroad.core.ServiceLocator
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.Shadows.shadowOf
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

private class ReadAlongSessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var entries = listOf(
        FakePlayer.item("chapter:10").buildUpon().setIsSeekable(true).build(),
        FakePlayer.item("chapter:11").buildUpon().setIsSeekable(true).build(),
    )
    var index = 0
    var position = 3_004L
    var positionSupplier: PositionSupplier? = null
    var ready = true
    var playback = Player.STATE_READY
    var loading = false
    var speed = 1f
    private var discontinuityReason: Int? = null

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(entries)
        .setCurrentMediaItemIndex(index)
        .setContentPositionMs(positionSupplier ?: PositionSupplier.getConstant(position))
        .setPlayWhenReady(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else playback)
        .setIsLoading(loading)
        .setPlaybackParameters(PlaybackParameters(speed))
        .apply { discontinuityReason?.let { setPositionDiscontinuity(it, position) } }
        .build()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        index = mediaItemIndex
        position = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        ready = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun publish() = invalidateState()

    fun jump(positionMs: Long, reason: Int) {
        position = positionMs
        discontinuityReason = reason
        invalidateState()
        discontinuityReason = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var player: ReadAlongSessionPlayer
    private lateinit var session: MediaSession
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        player = ReadAlongSessionPlayer()
        session = MediaSession.Builder(context, player).build()
        scope = CoroutineScope(Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        scope.cancel()
        session.release()
        player.release()
    }

    private fun createRealController(listener: MediaController.Listener? = null): MediaController =
        MediaController.Builder(context, session.token)
            .apply { listener?.let { setListener(it) } }
            .buildAsync()
            .get()

    private fun connectedController(): PlaybackController {
        val connector = RecordingConnector()
        val controller = controller(connector)
        controller.connect()
        connector.futures.first().set(createRealController(connector.listeners.first()))
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

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
        assertNull(controller.readAlongSample())
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
        val firstSample = controller.readAlongSample()!!

        // Release generation 1
        controller.release()
        assertFalse("first controller was released on release", firstController.isConnected)
        assertNull(controller.reportedPositionMs())
        assertNull(controller.readAlongSample())

        // Start generation 2
        controller.connect()
        assertEquals(2, connector.futures.size)
        val secondFuture = connector.futures[1]
        val secondController = createRealController()
        secondFuture.set(secondController)

        assertTrue("second controller is current and must stay connected", secondController.isConnected)
        assertNotNull(controller.reportedPositionMs())
        val secondSample = controller.readAlongSample()!!
        assertEquals(firstSample.mediaId, secondSample.mediaId)
        assertTrue(secondSample.discontinuityGeneration > firstSample.discontinuityGeneration)

        controller.release()
        assertFalse("releasing must disconnect the active controller", secondController.isConnected)
        assertNull(controller.reportedPositionMs())
        assertNull(controller.readAlongSample())
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
        assertNull(controller.readAlongSample())
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
        assertNull(controller.readAlongSample())
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
        assertNull(controller.readAlongSample())
    }

    @Test
    fun `sampling is null without a connection or current media item`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)
        assertNull(controller.readAlongSample())
        controller.connect()
        assertNull(controller.readAlongSample())
        player.entries = emptyList()
        player.publish()
        connector.futures.first().set(createRealController())

        assertNull(controller.readAlongSample())
        assertNotNull(controller.reportedPositionMs())
        controller.release()
    }

    @Test
    fun `sample reads identity position playing and speed together from the live controller`() = runTest {
        player.speed = 2.5f
        player.publish()
        val controller = connectedController()
        try {
            val sample = controller.readAlongSample()!!
            assertEquals("chapter:10", sample.mediaId)
            assertEquals(controller.reportedPositionMs(), sample.positionMs)
            assertTrue(sample.isPlaying)
            assertEquals(2.5f, sample.speed, 0f)

            player.seekTo(1, 712L)
            shadowOf(Looper.getMainLooper()).idle()
            val changed = controller.readAlongSample()!!
            assertEquals("chapter:11", changed.mediaId)
            assertEquals(712L, changed.positionMs)
            assertTrue(changed.discontinuityGeneration > sample.discontinuityGeneration)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `periodic anchors correct extrapolation without events or a discontinuity generation change`() = runTest {
        player.positionSupplier = SimpleBasePlayer.PositionSupplier { player.position }
        player.publish()
        shadowOf(Looper.getMainLooper()).idle()
        val connector = RecordingConnector()
        val controller = controller(connector)
        controller.connect()
        val real = createRealController(connector.listeners.first())
        connector.futures.first().set(real)
        shadowOf(Looper.getMainLooper()).idle()
        var events = 0
        real.addListener(object : Player.Listener {
            override fun onEvents(player: Player, playerEvents: Player.Events) {
                events++
            }
        })
        try {
            val initial = controller.readAlongSample()!!
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999L))
            val extrapolated = controller.readAlongSample()!!
            assertEquals(initial.positionMs + 2_999L, extrapolated.positionMs)
            val tracker = ReadAlongPlaybackPosition()
            tracker.positionMs(extrapolated, initial.mediaId, 2_999L)
            player.position = extrapolated.positionMs - 12L

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))

            val corrected = controller.readAlongSample()!!
            assertEquals(player.position, corrected.positionMs)
            assertEquals(initial.discontinuityGeneration, corrected.discontinuityGeneration)
            assertEquals(0, events)
            assertEquals(extrapolated.positionMs, tracker.positionMs(corrected, initial.mediaId, 3_000L))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16L))
            val advancing = controller.readAlongSample()!!
            assertEquals(corrected.positionMs + 16L, advancing.positionMs)
            assertEquals(advancing.positionMs, tracker.positionMs(advancing, initial.mediaId, 3_016L))
        } finally {
            controller.release()
        }
    }

    @Test
    fun `paused loading still receives periodic position anchors without events`() = runTest {
        player.ready = false
        player.loading = true
        player.positionSupplier = SimpleBasePlayer.PositionSupplier { player.position }
        player.publish()
        shadowOf(Looper.getMainLooper()).idle()
        val connector = RecordingConnector()
        val controller = controller(connector)
        controller.connect()
        val real = createRealController(connector.listeners.first())
        connector.futures.first().set(real)
        shadowOf(Looper.getMainLooper()).idle()
        var events = 0
        real.addListener(object : Player.Listener {
            override fun onEvents(player: Player, playerEvents: Player.Events) {
                events++
            }
        })
        try {
            val before = controller.readAlongSample()!!
            val tracker = ReadAlongPlaybackPosition()
            tracker.positionMs(before, before.mediaId, 0L)
            player.position = before.positionMs - 12L

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000L))

            val after = controller.readAlongSample()!!
            assertFalse(after.isPlaying)
            assertEquals(before.positionMs - 12L, after.positionMs)
            assertEquals(before.discontinuityGeneration, after.discontinuityGeneration)
            assertEquals(0, events)
            assertEquals(after.positionMs, tracker.positionMs(after, before.mediaId, 3_000L))
        } finally {
            controller.release()
        }
    }

    @Test
    fun `a local twelve millisecond backward seek synchronously changes the sample generation`() = runTest {
        val controller = connectedController()
        try {
            val tracker = ReadAlongPlaybackPosition()
            val before = controller.readAlongSample()!!
            tracker.positionMs(before, before.mediaId, 0L)

            controller.seekTo(before.positionMs - 12L)

            val after = controller.readAlongSample()!!
            assertEquals(before.mediaId, after.mediaId)
            assertEquals(before.positionMs - 12L, after.positionMs)
            assertTrue(after.discontinuityGeneration > before.discontinuityGeneration)
            assertEquals(after.positionMs, tracker.positionMs(after, before.mediaId, 16L))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(after.positionMs, player.currentPosition)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `a remote twelve millisecond backward seek changes generation through the session`() = runTest {
        val controller = connectedController()
        val remote = createRealController()
        try {
            val before = controller.readAlongSample()!!
            val tracker = ReadAlongPlaybackPosition()
            tracker.positionMs(before, before.mediaId, 0L)

            remote.seekTo(before.positionMs - 12L)
            shadowOf(Looper.getMainLooper()).idle()

            val after = controller.readAlongSample()!!
            assertEquals(before.mediaId, after.mediaId)
            assertEquals(before.positionMs - 12L, after.positionMs)
            assertTrue(after.discontinuityGeneration > before.discontinuityGeneration)
            assertEquals(after.positionMs, tracker.positionMs(after, before.mediaId, 16L))
        } finally {
            remote.release()
            controller.release()
        }
    }

    @Test
    fun `internal service discontinuities also invalidate the read along sample`() = runTest {
        val controller = connectedController()
        try {
            val before = controller.readAlongSample()!!
            player.jump(before.positionMs - 12L, Player.DISCONTINUITY_REASON_INTERNAL)
            shadowOf(Looper.getMainLooper()).idle()

            val after = controller.readAlongSample()!!
            assertEquals(before.positionMs - 12L, after.positionMs)
            assertTrue(after.discontinuityGeneration > before.discontinuityGeneration)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `pause buffering resume and speed callbacks reset continuity even between polls`() = runTest {
        val controller = connectedController()
        try {
            var before = controller.readAlongSample()!!
            player.pause()
            shadowOf(Looper.getMainLooper()).idle()
            val paused = controller.readAlongSample()!!
            assertFalse(paused.isPlaying)
            assertTrue(paused.discontinuityGeneration > before.discontinuityGeneration)
            player.play()
            shadowOf(Looper.getMainLooper()).idle()
            val resumed = controller.readAlongSample()!!
            assertTrue(resumed.isPlaying)
            assertTrue(resumed.discontinuityGeneration > paused.discontinuityGeneration)

            before = resumed
            player.playback = Player.STATE_BUFFERING
            player.publish()
            shadowOf(Looper.getMainLooper()).idle()
            val buffering = controller.readAlongSample()!!
            assertFalse(buffering.isPlaying)
            assertTrue(player.playWhenReady)
            assertTrue(buffering.discontinuityGeneration > before.discontinuityGeneration)
            player.playback = Player.STATE_READY
            player.publish()
            shadowOf(Looper.getMainLooper()).idle()
            val ready = controller.readAlongSample()!!
            assertTrue(ready.isPlaying)
            assertTrue(ready.discontinuityGeneration > buffering.discontinuityGeneration)

            before = ready
            player.pause()
            shadowOf(Looper.getMainLooper()).idle()
            player.play()
            shadowOf(Looper.getMainLooper()).idle()
            val missedPause = controller.readAlongSample()!!
            assertTrue(missedPause.isPlaying)
            assertTrue(missedPause.discontinuityGeneration > before.discontinuityGeneration)

            player.setPlaybackSpeed(3f)
            shadowOf(Looper.getMainLooper()).idle()
            val faster = controller.readAlongSample()!!
            assertEquals(3f, faster.speed, 0f)
            assertTrue(faster.discontinuityGeneration > missedPause.discontinuityGeneration)
            player.setPlaybackSpeed(1f)
            shadowOf(Looper.getMainLooper()).idle()
            val restored = controller.readAlongSample()!!
            assertEquals(before.speed, restored.speed, 0f)
            assertTrue(restored.discontinuityGeneration > before.discontinuityGeneration)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `session disconnect clears the sample and same media reconnect changes generation`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)
        controller.connect()
        connector.futures.first().set(createRealController(connector.listeners.first()))
        val before = controller.readAlongSample()!!
        session.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(controller.readAlongSample())
        assertNull(controller.reportedPositionMs())

        session = MediaSession.Builder(context, player).build()
        controller.connect()
        connector.futures.last().set(createRealController(connector.listeners.last()))
        try {
            val after = controller.readAlongSample()!!
            assertEquals(before.mediaId, after.mediaId)
            assertTrue(after.discontinuityGeneration > before.discontinuityGeneration)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `sampling rejects a worker thread rather than returning torn player state`() = runTest {
        val controller = connectedController()
        try {
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                failure.set(runCatching { controller.readAlongSample() }.exceptionOrNull())
            }
            worker.start()
            worker.join()
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `failed connection resets connecting and permits retry`() = runTest {
        val connector = RecordingConnector()
        val controller = controller(connector)

        controller.connect()
        connector.futures[0].setException(IllegalStateException("failed connection"))

        assertNull(controller.reportedPositionMs())
        assertNull(controller.readAlongSample())

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
