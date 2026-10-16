package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.nav.AppRoot
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The badge and the Listening row count the server's totals, not the rows: the list is one capped
 * page (the server's MAX_NOTIFICATIONS is 200) while `ready`/`unread` are counted over every open
 * notice. Counting rows under-reported exactly when the backlog was largest (#346 follow-up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NewChaptersCountsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `counts beyond the page size reach the badge and the title`() {
        val pageSize = 200
        val rows = (1..pageSize).joinToString(",") { id ->
            """{"id":$id,"state":"ready","playable":true,"fiction":{"id":7},"chapter":{"id":${1000 + id}}}"""
        }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.encodedPath) {
                    "/api/mobile/capabilities" -> """{"capabilities":{"notifications":true,"follows":true}}"""
                    "/api/mobile/notifications" -> """{"notifications":[$rows],"unread":350,"ready":240}"""
                    else -> """{"fictions":[]}"""
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        try {
            val session = SessionState(serverUrl = server.url("/").toString(), token = "test-token")
            val repository = TtsRoadRepository(FakeSessionStore(session))
            runBlocking { repository.refreshCurrentCapabilities() }
            lateinit var state: NewChaptersState
            compose.setContent {
                state = rememberNewChapters(
                    repository, session, ServerCapabilities(notifications = true, follows = true),
                    { session }, true, ChapterNotificationLifetime(),
                )
                TtsRoadTheme {
                    PrimaryNavigationBar(
                        selected = AppRoot.Home,
                        onSelect = {},
                        badges = mapOf(AppRoot.Listening to state.ready),
                    )
                }
            }
            compose.waitUntil(5_000) { state.loadedOnce && !state.isLoading }

            assertEquals(pageSize, state.rows.size)
            assertEquals(240, state.ready)
            assertEquals("New chapters (240 ready · 110 waiting)", newChaptersRowTitle(state.unread, state.ready))
            compose.onNodeWithTag("nav-badge", useUnmergedTree = true).assertTextEquals("99+")
        } finally {
            server.shutdown()
        }
    }
}
