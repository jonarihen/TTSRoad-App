package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.normalizeBaseUrl
import dk.perspektiva.ttsroad.player.PendingProgressStore
import dk.perspektiva.ttsroad.player.ProgressSync
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cold-start resume must not read the server position before the offline backlog is flushed (#314).
 *
 * The server here is a tiny stateful fake: the progress endpoint moves the chapter's position, and
 * the library reports whatever position it holds — which is what makes the order observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeAfterPendingProgressTest {
    private class MemorySessionStore(private var state: SessionState = SessionState()) : SessionStore {
        override suspend fun current(): SessionState = state
        override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
            state = SessionState(normalizeBaseUrl(baseUrl), response.token, response.user.username)
        }
        override suspend fun clearToken() {
            state = state.copy(token = null)
        }
    }

    private lateinit var server: MockWebServer

    /** What the fake server holds for chapter 7: ten minutes in, older than the phone's backlog. */
    @Volatile
    private var serverPositionSeconds = 600.0
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        File(RuntimeEnvironment.getApplication().filesDir, "pending_progress.json").delete()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requests) { requests += "${request.method} ${path.substringBefore('?')}" }
                return when {
                    path.startsWith("/api/mobile/capabilities") -> json(
                        """{"api_version": 1, "server": {"name": "TTSRoad", "version": "1.0"}, "capabilities": {}}""",
                    )
                    path.startsWith("/api/mobile/playback/progress") -> {
                        serverPositionSeconds = JSONObject(request.body.readUtf8()).getDouble("position_seconds")
                        json("""{"status": "ok", "chapter_id": 7}""")
                    }
                    path.startsWith("/api/mobile/library") -> json(
                        """{"continue_listening": [{"chapter_id": 7, "fiction_id": 3,
                            "resume_seconds": $serverPositionSeconds}]}""",
                    )
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

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `a queued position newer than the server's is what resume starts from`() = runTest {
        val sessions = MemorySessionStore()
        sessions.saveLogin(server.url("/").toString(), LoginResponse(token = "t", user = MobileUser(id = 1, username = "r")))
        val repository = TtsRoadRepository(sessions)
        repository.refreshCurrentCapabilities()
        val pending = PendingProgressStore(RuntimeEnvironment.getApplication())
        // Thirty minutes in, recorded offline before the process died.
        pending.record(fictionId = 3, chapterId = 7, positionSeconds = 1_800.0, isPlayed = false, owner = sessions.current())
        val sync = ProgressSync(repository, pending)

        val library = afterPendingProgress(flush = { sync.flush() }, read = { repository.library() })

        assertEquals(1_800.0, library.continueListening.single().resolvedPositionSeconds, 0.0)
        val order = synchronized(requests) { requests.filter { "capabilities" !in it } }
        assertEquals(listOf("POST /api/mobile/playback/progress", "GET /api/mobile/library"), order)
    }
}
