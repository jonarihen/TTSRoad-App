package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `GET /api/mobile/logs` — the pipeline's own log, on the phone (#124).
 *
 * Three things here are load-bearing beyond "the JSON parses".
 *
 * The first is that a server without the endpoint answers **null** rather than throwing or showing
 * an empty list. On this screen an empty list means *nothing has gone wrong*, which is the one
 * answer that must never be invented — and an old server saying nothing is not the same statement.
 *
 * The second is the level filter. The server answers **400** to a level it does not recognise,
 * deliberately, for the same reason; so this client must never send one. A stray value is dropped
 * here rather than turned into an error dialog.
 *
 * The third is paging. `before_id` is a cursor over a monotonic primary key, not an offset over a
 * table that only grows — the difference shows up as the same failure appearing twice.
 */
class ServerLogsRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private suspend fun repository(logs: Boolean = true): TtsRoadRepository {
        val store = FakeSessionStore(SessionState())
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "admin", isAdmin = true),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """
                {"api_version": 1, "server": {"name": "TTSRoad"},
                 "capabilities": {"logs": $logs}, "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    /** Shaped exactly as `app/routers/mobile.py` builds it. */
    private val page = """
        {"api_version": 1,
         "logs": [
           {"id": 4120, "level": "ERROR", "message": "Synthesis failed for chapter 88",
            "fiction_id": 1, "chapter_id": 88, "created_at": "2026-08-26T09:14:02Z"},
           {"id": 4119, "level": "INFO", "message": "Poll finished: 0 new chapters",
            "fiction_id": null, "chapter_id": null, "created_at": "2026-08-26T09:00:00Z"}
         ],
         "has_more": true,
         "next_before_id": 4119}
    """

    @Test
    fun `a server without the endpoint answers null and is never asked`() = runTest {
        val repository = repository(logs = false)

        assertNull(repository.serverLogs())

        // Only the capability probe. Asking anyway would 404, and the screen would report a
        // failure where the honest answer is "this server does not publish its log".
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `every field of a log line survives the wire`() = runTest {
        val repository = repository()
        server.enqueue(json(page))

        val response = repository.serverLogs()

        server.takeRequest()
        assertEquals("/api/mobile/logs?limit=50", server.takeRequest().path)
        assertNotNull(response)
        assertTrue(response!!.hasMore)
        assertEquals(4119, response.nextBeforeId)

        val error = response.logs.first()
        assertEquals(4120, error.id)
        assertEquals("ERROR", error.level)
        assertEquals("Synthesis failed for chapter 88", error.message)
        assertEquals(1, error.fictionId)
        assertEquals(88, error.chapterId)
        assertEquals("2026-08-26T09:14:02Z", error.createdAt)

        // A line about the install rather than about a book. Most of a quiet log looks like this,
        // and a client that assumed a fiction id would drop the poller's own heartbeat.
        val poll = response.logs.last()
        assertNull(poll.fictionId)
        assertNull(poll.chapterId)
    }

    @Test
    fun `the level filter is upper-cased into the server's own vocabulary`() = runTest {
        val repository = repository()
        server.enqueue(json(page))

        repository.serverLogs(level = "error")

        server.takeRequest()
        assertEquals("/api/mobile/logs?limit=50&level=ERROR", server.takeRequest().path)
    }

    /**
     * The server answers 400 rather than an empty list to a level it does not know, on purpose: an
     * empty log means "nothing has gone wrong", and a typo must not be able to say that. So a value
     * this client cannot vouch for is dropped rather than forwarded.
     */
    @Test
    fun `a level the server would refuse is dropped rather than sent`() = runTest {
        val repository = repository()
        server.enqueue(json(page))

        repository.serverLogs(level = "CRITICAL")

        server.takeRequest()
        assertEquals("/api/mobile/logs?limit=50", server.takeRequest().path)
    }

    @Test
    fun `a fiction filter and a cursor travel as the server names them`() = runTest {
        val repository = repository()
        server.enqueue(json(page))

        repository.serverLogs(level = "WARNING", fictionId = 7, beforeId = 4119)

        server.takeRequest()
        assertEquals(
            "/api/mobile/logs?limit=50&level=WARNING&fiction_id=7&before_id=4119",
            server.takeRequest().path,
        )
    }

    /** 200 is the server's cap; asking for more is answered with 200, so the ask says 200. */
    @Test
    fun `the page size is clamped to the range the server honours`() = runTest {
        val repository = repository()
        server.enqueue(json(page))
        server.enqueue(json(page))

        repository.serverLogs(limit = 5_000)
        repository.serverLogs(limit = 0)

        server.takeRequest()
        assertEquals("/api/mobile/logs?limit=200", server.takeRequest().path)
        assertEquals("/api/mobile/logs?limit=1", server.takeRequest().path)
    }

    /**
     * Paging walks `next_before_id` until it goes away. The last page carries null there even though
     * it has rows, which is what stops a client counting pages or trusting `has_more` alone.
     */
    @Test
    fun `the last page reports no cursor even though it has rows`() = runTest {
        val repository = repository()
        server.enqueue(json(page))
        server.enqueue(
            json(
                """
                {"api_version": 1,
                 "logs": [{"id": 4118, "level": "INFO", "message": "Scheduler started",
                           "created_at": "2026-08-26T08:00:00Z"}],
                 "has_more": false,
                 "next_before_id": null}
                """,
            ),
        )

        val first = repository.serverLogs()
        val second = repository.serverLogs(beforeId = first?.nextBeforeId)

        server.takeRequest()
        server.takeRequest()
        assertEquals("/api/mobile/logs?limit=50&before_id=4119", server.takeRequest().path)
        assertFalse(second!!.hasMore)
        assertNull(second.nextBeforeId)
        assertEquals(1, second.logs.size)
    }

    /**
     * A non-admin account is refused server-side. The client hides the screen rather than asking —
     * see `canReadServerLogs` — but the refusal must still surface as a failure and not as an empty
     * log if the session loses admin between sign-in and the request.
     */
    @Test
    fun `a 403 throws rather than looking like a quiet server`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(403))

        val thrown = runCatching { repository.serverLogs() }.exceptionOrNull()

        assertNotNull(thrown)
    }
}
