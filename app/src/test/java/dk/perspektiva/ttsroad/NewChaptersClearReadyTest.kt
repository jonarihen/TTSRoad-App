package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Clear N ready" used to launch an unguarded coroutine, so a dropped connection or a 500 was an
 * uncaught exception and the process died (#345). Both now surface as an inline error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NewChaptersClearReadyTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private var dismissResponse: () -> MockResponse = { MockResponse().setResponseCode(204) }
    private var dismissCalls = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/mobile/capabilities" -> json("""{"capabilities":{"notifications":true,"follows":true}}""")
                    "/api/mobile/notifications/dismiss-read" -> {
                        dismissCalls++
                        dismissResponse()
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `server error on clear is shown inline and the list is reloaded`() {
        dismissResponse = { MockResponse().setResponseCode(500) }
        val state = show()

        compose.onNodeWithText("CLEAR 1 READY").performClick()
        compose.waitUntil(5_000) { state.refreshRequest == 1 }

        assertEquals(1, dismissCalls)
        compose.onNodeWithText("HTTP 500", substring = true).assertIsDisplayed()
        compose.onNodeWithText("RETRY").assertIsDisplayed()
        // The rows are untouched until the poller's reload says otherwise.
        compose.onNodeWithText("The Arrival").assertIsDisplayed()
        compose.onNodeWithText("CLEAR 1 READY").assertIsDisplayed()
    }

    @Test
    fun `dropped connection on clear is shown inline rather than crashing`() {
        dismissResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val state = show()

        compose.onNodeWithText("CLEAR 1 READY").performClick()
        compose.waitUntil(15_000) { state.refreshRequest == 1 }

        assertTrue(dismissCalls >= 1)
        compose.onNodeWithText("RETRY").assertExists()
        compose.onNodeWithText("CLEAR 1 READY").assertExists()
    }

    @Test
    fun `successful clear asks the poller to reload without an error`() {
        val state = show()

        compose.onNodeWithText("CLEAR 1 READY").performClick()
        compose.waitUntil(5_000) { state.refreshRequest == 1 }

        assertEquals(1, dismissCalls)
        compose.onNodeWithText("RETRY").assertDoesNotExist()
    }

    private fun show(): NewChaptersState {
        val session = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
        val repository = TtsRoadRepository(FakeSessionStore(session))
        runBlocking { repository.refreshCurrentCapabilities() }
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            ready = 1
            unread = 1
            notifications = listOf(
                ChapterNotificationEntry(
                    id = 10,
                    state = "ready",
                    playable = true,
                    dismissible = true,
                    fiction = ChapterNotificationFiction(id = 7, title = "Test Serial"),
                    chapter = ChapterNotificationChapter(id = 101, title = "The Arrival", chapterNumber = 1.0),
                ),
            )
        }
        compose.setContent {
            TtsRoadTheme {
                NewChaptersScreen(
                    padding = PaddingValues(0.dp),
                    state = state,
                    repository = repository,
                    onPlay = {},
                    onOpenFiction = {},
                )
            }
        }
        return state
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")
}
