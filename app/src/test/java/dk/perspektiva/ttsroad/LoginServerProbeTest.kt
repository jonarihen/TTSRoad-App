package dk.perspektiva.ttsroad

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Dns
import okhttp3.OkHttpClient
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

private const val Unreachable = "CAN'T REACH THIS ADDRESS YET"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class LoginServerProbeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(503)
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `failed probe is quiet, debounced and does not block sign in`() {
        val address = server.url("/").toString()
        compose.setContent {
            TtsRoadTheme {
                LoginScreen(TtsRoadRepository(FakeSessionStore(SessionState())), SessionState())
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("SERVER ADDRESS").performTextReplacement(address)
        compose.mainClock.advanceTimeBy(799)
        compose.waitForIdle()
        compose.onNodeWithText(Unreachable, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, requests.size)

        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(Unreachable), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val message = compose.onNodeWithText(Unreachable, useUnmergedTree = true)
        assertEquals(AarisColor.Muted, message.textLayout().layoutInput.style.color)
        assertEquals("/api/mobile/capabilities", requests.single().path)

        compose.onNodeWithText("USERNAME").performTextInput("reader")
        compose.onNodeWithText("PASSWORD").performTextInput("secret")
        compose.onNode(hasText("SIGN IN") and hasClickAction()).assertIsEnabled()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("SERVER ADDRESS").performTextInput("x")
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithText(Unreachable, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, requests.size)
    }

    @Test
    fun `half typed single label host is debounced before probing`() {
        val lookups = CopyOnWriteArrayList<String>()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups += hostname
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val repository = TtsRoadRepository(
            FakeSessionStore(SessionState()),
            httpClient = OkHttpClient.Builder().dns(dns).build(),
        )
        compose.setContent { TtsRoadTheme { LoginScreen(repository, SessionState()) } }
        compose.mainClock.autoAdvance = false
        val field = compose.onNodeWithText("SERVER ADDRESS")
        for (typed in listOf("http://n", "http://na", "http://nas")) {
            field.performTextReplacement(typed)
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        field.performTextReplacement("")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertTrue(lookups.isEmpty())
        assertEquals(0, requests.size)
        compose.onNodeWithText(Unreachable, useUnmergedTree = true).assertDoesNotExist()

        field.performTextReplacement("http://nas:${server.port}")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(Unreachable), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("nas"), lookups)
        assertEquals("/api/mobile/capabilities", requests.single().path)
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("no text layout", action?.invoke(results) == true)
        return results.single()
    }
}
