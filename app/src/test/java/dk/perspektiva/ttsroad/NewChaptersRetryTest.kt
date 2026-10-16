package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.canRetryConversion
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed conversion can be retried from the New chapters list itself (#350). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NewChaptersRetryTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private var maintenance = true
    private var retryResponse: () -> MockResponse = {
        json("""{"api_version":1,"status":"queued","chapter_id":101}""")
    }

    private val failed = ChapterNotificationEntry(
        id = 10,
        state = "stalled",
        dismissible = true,
        fiction = ChapterNotificationFiction(id = 7, title = "Test Serial"),
        chapter = ChapterNotificationChapter(id = 101, title = "The Arrival", chapterNumber = 1.0, status = "error"),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                requests += "${request.method} $path"
                return when (path) {
                    "/api/mobile/capabilities" ->
                        json("""{"capabilities":{"notifications":true,"follows":true,"chapter_maintenance":$maintenance}}""")
                    "/api/mobile/chapters/101/retry" -> retryResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `only a row whose conversion failed is retryable`() {
        assertTrue(failed.canRetryConversion)
        // Excluded chapters are stalled too, and the server refuses to retry them.
        assertFalse(failed.copy(chapter = failed.chapter.copy(status = "done")).canRetryConversion)
        assertFalse(failed.copy(state = "pulled", chapter = failed.chapter.copy(status = "processing")).canRetryConversion)
        assertFalse(failed.copy(state = "ready", playable = true).canRetryConversion)
    }

    @Test
    fun `convert again posts the chapter retry and reloads the list`() {
        val state = show()

        compose.onNodeWithText("CONVERT AGAIN").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { state.refreshRequest == 1 }

        assertTrue(requests.contains("POST /api/mobile/chapters/101/retry"))
        compose.onNodeWithText("RETRY").assertDoesNotExist()
    }

    @Test
    fun `a refused retry is explained inline`() {
        retryResponse = { MockResponse().setResponseCode(409) }
        val state = show()

        compose.onNodeWithText("CONVERT AGAIN").performClick()
        // Wait for the answer, not the request: MockWebServer records the request before OkHttp has
        // handed the 409 back, and waitForIdle does not wait for that I/O.
        val refusal = "That chapter is already converting, or is excluded"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(refusal).fetchSemanticsNodes().isNotEmpty() }

        assertTrue(requests.any { it.endsWith("/retry") })
        compose.onNodeWithText(refusal).assertIsDisplayed()
        assertEquals(0, state.refreshRequest)
    }

    @Test
    fun `no retry is offered on a server without chapter maintenance`() {
        maintenance = false
        show()

        compose.onNodeWithText("The Arrival").assertIsDisplayed()
        compose.onNodeWithText("CONVERT AGAIN").assertDoesNotExist()
    }

    private fun show(): NewChaptersState {
        val session = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
        val repository = TtsRoadRepository(FakeSessionStore(session))
        runBlocking { repository.refreshCurrentCapabilities() }
        val state = NewChaptersState().apply {
            isLoading = false
            loadedOnce = true
            unread = 1
            notifications = listOf(failed)
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
