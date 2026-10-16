package dk.perspektiva.ttsroad

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failing library request used to leave `followsAnything` null, so every 60s poll asked for the
 * whole library again for as long as the app was open (#322).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewChaptersFollowsCheckTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a failing library is asked once per load, not on every poll`() {
        val polls = AtomicInteger()
        val libraryCalls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/mobile/capabilities" ->
                        json("""{"capabilities":{"notifications":true,"follows":true}}""")
                    "/api/mobile/notifications" -> {
                        polls.incrementAndGet()
                        json("""{"notifications":[],"unread":0,"ready":0}""")
                    }
                    "/api/mobile/library" -> {
                        libraryCalls.incrementAndGet()
                        MockResponse().setResponseCode(500)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
        try {
            val session = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
            val repository = TtsRoadRepository(FakeSessionStore(session))
            runBlocking { repository.refreshCurrentCapabilities() }
            val capabilities = ServerCapabilities(notifications = true, follows = true)
            lateinit var state: NewChaptersState
            compose.setContent {
                state = rememberNewChapters(
                    repository, session, capabilities, { session }, true, ChapterNotificationLifetime(),
                )
            }
            compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }
            assertEquals(1, libraryCalls.get())

            repeat(3) { round ->
                compose.mainClock.advanceTimeBy(61_000)
                compose.waitUntil(5_000) { polls.get() >= round + 2 && !state.isLoading }
            }
            assertTrue(polls.get() >= 4)
            assertEquals(1, libraryCalls.get())
            assertNull(state.followsAnything)

            // An explicit refresh is allowed to try again.
            val before = polls.get()
            compose.runOnIdle { state.refreshRequest++ }
            compose.waitForIdle()
            compose.waitUntil(5_000) { polls.get() > before && libraryCalls.get() == 2 }
        } finally {
            server.shutdown()
        }
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")
}
