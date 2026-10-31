package dk.perspektiva.ttsroad

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.nav.AppRoot
import dk.perspektiva.ttsroad.nav.AppScreen
import dk.perspektiva.ttsroad.player.ControllerConnector
import dk.perspektiva.ttsroad.player.FakePlayer
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.SleepTimerStop
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class JumpOfferPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var index = 1
    private var position = 55_000L
    private var playing = false
    val seeks = mutableListOf<Pair<Int, Long>>()

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlaylist(listOf(10, 11).map {
            FakePlayer.item("chapter:$it", "Chapter $it").buildUpon().setIsSeekable(true).build()
        })
        .setCurrentMediaItemIndex(index)
        .setContentPositionMs(position)
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        seeks += mediaItemIndex to positionMs
        index = mediaItemIndex
        position = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp", application = Application::class)
class JumpBackOfferUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var player: JumpOfferPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var controller: PlaybackController
    private val stopped = System.currentTimeMillis() - 60 * 60_000L
    private val awake = stopped - 30 * 60_000L

    @Before
    fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(when (request.path) {
                    "/api/mobile/capabilities" -> """{"capabilities":{}}"""
                    "/api/mobile/library" -> """{"fictions":[],"continue_listening":[],"recent_chapters":[]}"""
                    else -> "{}"
                })
        }
        server.start()
        runBlocking {
            ServiceLocator.tokenStore(context).saveLogin(server.url("/").toString(),
                LoginResponse(token = "test", user = MobileUser(id = 1, username = "Listener")))
        }
        ServiceLocator.libraryCache(context).clear()
        ServiceLocator.playbackHistory(context).apply {
            clear()
            record(awake, "chapter:10", 7, 10, "Fell asleep here", "Book", 12_000L)
            record(stopped, "chapter:11", 7, 11, "Morning chapter", "Book", 55_000L)
        }
        ServiceLocator.sleepTimerStops(context).record(SleepTimerStop(stopped, awake))
        player = JumpOfferPlayer()
        mediaSession = MediaSession.Builder(context, player).build()
        controller = PlaybackController(context, ServiceLocator.tokenStore(context),
            ServiceLocator.playbackPreferences(context), ServiceLocator.fictionSpeedPreferences(context),
            object : ControllerConnector {
                override fun connect(context: Context, token: SessionToken, listener: MediaController.Listener): ListenableFuture<MediaController> =
                    MediaController.Builder(context, mediaSession.token).setListener(listener).buildAsync()
            }, scope)
        controller.connect()
    }

    @After
    fun cleanup() {
        compose.runOnIdle {
            controller.release()
            mediaSession.release()
            player.release()
            ServiceLocator.libraryCache(context).clear()
            ServiceLocator.playbackHistory(context).clear()
        }
        scope.cancel()
        runBlocking { ServiceLocator.tokenStore(context).clearAll() }
        server.shutdown()
    }

    private fun show(initial: AppScreen) {
        val session = runBlocking { ServiceLocator.tokenStore(context).current() }
        compose.setContent {
            var screen by remember { mutableStateOf(initial) }
            TtsRoadTheme {
                MainScaffold(session, screen, AppRoot.Home, false, { screen = it }, {}, {}, {}, {},
                    ServiceLocator.repository(context), controller, remember { NewChaptersState() })
            }
        }
        compose.waitUntil(10_000) { controller.state.value.hasMedia }
    }

    private fun assertOffer() {
        compose.onNodeWithText("MARK AS UNPLAYED?").assertIsDisplayed()
        compose.onNodeWithText("Playback carried on through 2 chapters", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(0 to 12_000L), player.seeks)
            assertTrue(player.playWhenReady)
        }
        compose.onNodeWithText("KEEP").performClick()
        compose.onNodeWithText("MARK AS UNPLAYED?").assertDoesNotExist()
    }

    @Test
    fun `banner resume there opens the player with the unmark offer`() {
        show(AppScreen.Library)
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("RESUME THERE")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("RESUME THERE").performClick()
        assertOffer()
    }

    @Test
    fun `jump-back sheet offers the same chapters after a successful jump`() {
        show(AppScreen.Player)
        compose.onNodeWithContentDescription("Jump back to where you were").performClick()
        compose.onNodeWithText("Fell asleep here", substring = true, ignoreCase = true).performClick()
        assertOffer()
    }
}
