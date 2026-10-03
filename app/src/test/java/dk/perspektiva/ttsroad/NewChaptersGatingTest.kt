package dk.perspektiva.ttsroad

import android.app.NotificationManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NewChaptersGatingTest {
    @get:Rule val compose = createComposeRule()
    private val supported = ServerCapabilities(notifications = true, follows = true)

    @Test
    fun `poller and Listening entry require both capabilities and valid session and reset on changes`() {
        val polls = AtomicInteger()
        var ready = false
        val manager = shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.encodedPath) {
                    "/api/mobile/capabilities" -> """{"capabilities":{"notifications":true,"follows":true}}"""
                    "/api/mobile/notifications" -> {
                        polls.incrementAndGet()
                        """{"notifications":[{"id":1,"state":"${if (ready) "ready" else "pulled"}","playable":$ready,"fiction":{"id":7},"chapter":{"id":102}}],"unread":1,"ready":${if (ready) 1 else 0}}"""
                    }
                    else -> """{"fictions":[]}"""
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        try {
            val original = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
            val repository = TtsRoadRepository(FakeSessionStore(original))
            runBlocking { repository.refreshCurrentCapabilities() }
            var session by mutableStateOf<SessionState?>(null)
            var capabilities by mutableStateOf(supported)
            lateinit var state: NewChaptersState
            compose.setContent {
                state = rememberNewChapters(repository, session, capabilities, currentSession = { session ?: SessionState() })
                TtsRoadTheme {
                    ListeningScreenBody(
                        padding = PaddingValues(), hasMedia = false, hasHistory = false,
                        canOpenQueue = false, canOpenBookmarks = false, canOpenPronunciationReports = false,
                        canOpenLogs = false, canOpenNewChapters = canUseChapterNotifications(capabilities, session),
                        unreadNewChapters = state.unread,
                    )
                }
            }
            compose.waitForIdle()
            assertEquals(0, polls.get())
            compose.onNodeWithText("New chapters").assertDoesNotExist()
            val initial = state
            compose.runOnIdle { session = original }
            compose.waitForIdle()
            compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }
            assertNotSame(initial, state)
            assertEquals(1, polls.get())
            compose.onNodeWithText("New chapters (1)").assertExists()
            assertEquals(0, manager.allNotifications.size)
            ready = true
            compose.runOnIdle { state.refreshRequest++ }
            compose.waitForIdle()
            compose.waitUntil(5_000) { state.ready == 1 && !state.isLoading }
            assertEquals(1, manager.allNotifications.size)
            compose.runOnIdle { state.refreshRequest++ }
            compose.waitForIdle()
            compose.waitUntil(5_000) { polls.get() == 3 && !state.isLoading }
            assertEquals(1, manager.allNotifications.size)

            var previous = state
            for (change in listOf<() -> Unit>(
                { capabilities = supported.copy(follows = false) },
                { capabilities = supported.copy(notifications = false) },
                { capabilities = supported; session = original.copy(token = null) },
                { session = original.copy(serverUrl = " ") },
            )) {
                compose.runOnIdle { change() }
                compose.waitForIdle()
                assertNotSame(previous, state)
                assertEquals(0, state.unread)
                assertFalse(state.loadedOnce)
                compose.onNodeWithText("New chapters").assertDoesNotExist()
                compose.mainClock.advanceTimeBy(120_000)
                compose.waitForIdle()
                assertEquals(3, polls.get())
                assertEquals(0, manager.allNotifications.size)
                previous = state
            }
            compose.runOnIdle { session = original.copy(token = "other-account") }
            compose.waitForIdle()
            compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }
            assertEquals(4, polls.get())
            assertEquals(0, manager.allNotifications.size)
            val account = state
            compose.runOnIdle { session = original.copy(serverUrl = server.url("/other/").toString()) }
            compose.waitForIdle()
            compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }
            assertNotSame(account, state)
            assertEquals(5, polls.get())
            assertEquals(0, manager.allNotifications.size)
        } finally {
            server.shutdown()
        }
    }
}
