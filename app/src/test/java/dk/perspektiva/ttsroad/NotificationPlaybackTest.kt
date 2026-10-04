package dk.perspektiva.ttsroad

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import dk.perspektiva.ttsroad.media.TtsRoadSessionCommands
import dk.perspektiva.ttsroad.media.SessionBoundQueue
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.ChaptersResponse
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.player.ControllerConnector
import dk.perspektiva.ttsroad.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class NotificationQueuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var items = emptyList<MediaItem>()
    private var index = 0
    private var playing = false

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() })
        .setCurrentMediaItemIndex(index)
        .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems
        index = startIndex
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        items = items.filterIndexed { index, _ -> index !in fromIndex until toIndex }
        index = 0
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPlaybackTest {
    private val session = SessionState(serverUrl = "https://ttsroad.example.com/", token = "test-token")
    private val route = NotificationRoute.Play(7, 102)
    private val response = ChaptersResponse(
        fiction = FictionSummary(id = 7),
        chapters = listOf(
            ChapterSummary(id = 101, audio = AudioInfo(url = "/audio/one.mp3")),
            ChapterSummary(id = 102, audio = AudioInfo(url = "/audio/two.mp3")),
            ChapterSummary(id = 103),
            ChapterSummary(id = 104, audio = AudioInfo(url = "/audio/four.mp3")),
        ),
    )

    @Test
    fun `Play loads fiction queue and starts exact chapter with subsequent chapters retained`() = runTest {
        var queued: ChaptersResponse? = null
        var start = 0
        assertTrue(playNotificationChapter(route, session,
            load = { assertEquals(7, it); response }, stillAllowed = { true },
            playQueue = { queue, id -> queued = queue; start = id; true }))
        assertEquals(102, start)
        assertEquals(listOf(101, 102, 104), queued?.chapters?.map { it.resolvedChapterId })
        assertEquals(response.fiction, queued?.fiction)
    }

    @Test
    fun `stale missing unplayable or mismatched target never falls back to first queue entry`() = runTest {
        for (stale in listOf(
            response.copy(chapters = response.chapters.filterNot { it.id == 102 }),
            response.copy(chapters = response.chapters.map { if (it.id == 102) it.copy(audio = null) else it }),
            response.copy(chapters = response.chapters.map { if (it.id == 102) it.copy(audio = AudioInfo(url = "")) else it }),
            response.copy(fiction = FictionSummary(id = 8)),
        )) {
            assertFalse(playNotificationChapter(route, session, { stale }, { true }, { _, _ -> error("Must not play") }))
        }
    }

    @Test
    fun `notification queue reaches shared PlaybackController and media session at exact chapter`() =
        verifyPlayback(refuseQueue = false)

    @Test
    fun `session rejecting queue installation returns failure despite optimistic controller updates`() =
        verifyPlayback(refuseQueue = true)

    @Test
    fun `account switching during connection never installs old chapter IDs`() =
        verifyPlayback(refuseQueue = false, switchAt = "connect")

    @Test
    fun `account switching before service installation rejects old queue`() =
        verifyPlayback(refuseQueue = false, switchAt = "install")

    @Test
    fun `queue result after account switch cannot claim old playback success`() =
        verifyPlayback(refuseQueue = false, switchAt = "result")

    private fun verifyPlayback(refuseQueue: Boolean, switchAt: String? = null) {
        val context = RuntimeEnvironment.getApplication()
        val store = ServiceLocator.tokenStore(context)
        runBlocking { store.saveLogin(session.serverUrl, LoginResponse(token = "test-token", user = MobileUser(id = 1, username = "listener"))) }
        val player = NotificationQueuePlayer()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val mediaSession = MediaSession.Builder(context, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(TtsRoadSessionCommands.installQueueCommand).build(),
                ).build()

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = scope.future {
                if (switchAt == "install") runBlocking { store.saveLogin("https://other.example.com/",
                    LoginResponse(token = "next-token", user = MobileUser(id = 2, username = "next"))) }
                if (refuseQueue) return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                val result = SessionBoundQueue.install(args.getString("ticket"), player, store) {}
                if (switchAt == "result") {
                    runBlocking { store.saveLogin("https://other.example.com/",
                        LoginResponse(token = "next-token", user = MobileUser(id = 2, username = "next"))) }
                    player.clearMediaItems()
                }
                result
            }
        }).build()
        val controller = PlaybackController(
            context, store, ServiceLocator.playbackPreferences(context), ServiceLocator.fictionSpeedPreferences(context),
            object : ControllerConnector {
                override fun connect(context: Context, token: SessionToken, listener: MediaController.Listener): ListenableFuture<MediaController> {
                    if (switchAt == "connect") runBlocking {
                        store.saveLogin("https://other.example.com/",
                            LoginResponse(token = "next-token", user = MobileUser(id = 2, username = "next")))
                    }
                    return MediaController.Builder(context, mediaSession.token).setListener(listener).buildAsync()
                }
            }, scope,
        )
        try {
            var installed = false
            val operation = scope.launch {
                installed = playNotificationChapter(route, session, { response }, { true }) { queue, id ->
                    controller.playQueue(queue.chapters, id, queue.fiction, expectedSession = session)
                }
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!operation.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                Thread.yield()
            }
            assertTrue(operation.isCompleted)
            assertEquals(!refuseQueue && switchAt == null, installed)
            if (refuseQueue || switchAt != null) {
                assertTrue(player.items.isEmpty())
            } else {
                assertEquals(listOf("chapter:101", "chapter:102", "chapter:104"), player.items.map { it.mediaId })
                assertEquals("chapter:102", player.currentMediaItem?.mediaId)
                assertTrue(player.playWhenReady)
            }
        } finally {
            controller.release()
            scope.cancel()
            mediaSession.release()
            player.release()
            runBlocking { store.clearToken() }
        }
    }

    @Test
    fun `failed controller connection is propagated instead of optimistic queue success`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = ServiceLocator.tokenStore(context)
        store.saveLogin(session.serverUrl, LoginResponse(token = "test-token", user = MobileUser(id = 1, username = "listener")))
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val controller = PlaybackController(
            context, store, ServiceLocator.playbackPreferences(context), ServiceLocator.fictionSpeedPreferences(context),
            object : ControllerConnector {
                override fun connect(context: Context, token: SessionToken, listener: MediaController.Listener): ListenableFuture<MediaController> =
                    Futures.immediateFailedFuture(SecurityException("Session rejected connection"))
            }, scope,
        )
        try {
            assertFalse(playNotificationChapter(route, session, { response }, { true }) { queue, id ->
                controller.playQueue(queue.chapters, id, queue.fiction, expectedSession = session)
            })
        } finally {
            controller.release()
            scope.cancel()
            store.clearToken()
        }
    }

    @Test
    fun `queue verification rejects server refusal or different chapter despite optimistic controller state`() = runTest {
        assertFalse(playNotificationChapter(route, session, { response }, { true }, { _, _ -> false }))
        val player = NotificationQueuePlayer()
        try {
            val args = Bundle().apply {
                putStringArrayList("media_ids", arrayListOf("chapter:102"))
                putString("start_media_id", "chapter:102")
            }
            assertEquals(SessionResult.RESULT_ERROR_INVALID_STATE, TtsRoadSessionCommands.verifyQueue(player, args).resultCode)
        } finally {
            player.release()
        }
    }

    @Test
    fun `queue installation ticket is single use and discarded tickets cannot mutate player`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = ServiceLocator.tokenStore(context)
        store.saveLogin(session.serverUrl, LoginResponse(token = session.token!!, user = MobileUser(id = 1, username = "listener")))
        val player = NotificationQueuePlayer()
        val items = listOf(MediaItem.Builder().setMediaId("chapter:102").build())
        try {
            val discarded = SessionBoundQueue.issue(session, items, 0, 0)
            SessionBoundQueue.discard(discarded)
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, SessionBoundQueue.install(discarded, player, store) {}.resultCode)
            assertTrue(player.items.isEmpty())
            val valid = SessionBoundQueue.issue(session, items, 0, 0)
            assertEquals(SessionResult.RESULT_SUCCESS, SessionBoundQueue.install(valid, player, store) {}.resultCode)
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, SessionBoundQueue.install(valid, player, store) {}.resultCode)
        } finally {
            player.release()
            store.clearToken()
        }
    }

    @Test
    fun `session or capability loss while fetching prevents playback`() = runTest {
        var allowed = true
        assertFalse(playNotificationChapter(route, session,
            load = { allowed = false; response }, stillAllowed = { allowed },
            playQueue = { _, _ -> error("Must not play") }))
        assertFalse(playNotificationChapter(route, session.copy(token = null),
            load = { error("Must not fetch") }, stillAllowed = { true },
            playQueue = { _, _ -> error("Must not play") }))
    }
}
