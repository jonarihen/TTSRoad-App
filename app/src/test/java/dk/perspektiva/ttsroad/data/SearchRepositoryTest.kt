package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSearchSessionStore(
    private var state: SessionState = SessionState(),
) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
        )
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

/**
 * Server-side search.
 *
 * The thing the local filter structurally cannot do is match narration text, so the cases here
 * centre on the grouped payload surviving contact with hits that carry very different fields.
 */
class SearchRepositoryTest {
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

    private fun capabilities(search: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.5.0"},
      "capabilities": {"search": $search},
      "limits": {}
    }
    """

    private suspend fun repository(search: Boolean): TtsRoadRepository {
        val store = FakeSearchSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilities(search))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `a query reaches the search endpoint`() = runTest {
        val repository = repository(search = true)
        server.enqueue(
            json(
                """
                {"query": "lighthouse", "total": 1, "indexed": true,
                 "fictions": {"items": [], "total": 0, "capped": false, "has_more": false},
                 "chapters": {"items": [], "total": 0, "capped": false, "has_more": false},
                 "text": {"items": [{"kind": "text", "fiction_id": 1, "fiction_title": "Ashes",
                                     "chapter_id": 42, "chapter_title": "The Gate",
                                     "snippet": "past the lighthouse", "playable": true}],
                          "total": 1, "capped": false, "has_more": false}}
                """,
            ),
        )

        val results = repository.search("lighthouse")

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path!!, request.path!!.startsWith("/api/mobile/search"))
        assertTrue(request.path!!, "q=lighthouse" in request.path!!)
        assertEquals(1, results?.total)
        // The narration-text hit is the whole point: the local filter cannot reach it.
        assertEquals(42, results?.text?.items?.first()?.chapterId)
        assertEquals("past the lighthouse", results?.text?.items?.first()?.snippet)
    }

    @Test
    fun `a query is trimmed before it is sent`() = runTest {
        val repository = repository(search = true)
        server.enqueue(json("""{"query": "ashes", "total": 0}"""))

        repository.search("  ashes  ")

        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("q=ashes"))
    }

    @Test
    fun `a blank query is never sent`() = runTest {
        val repository = repository(search = true)

        assertNull(repository.search("   "))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an older server is never asked`() = runTest {
        // Null here is not "no results" — the caller falls back to the local filter, which is
        // still the instant path and the only one that works offline.
        val repository = repository(search = false)

        assertNull(repository.search("lighthouse"))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a fiction-scoped search narrows to one book`() = runTest {
        val repository = repository(search = true)
        server.enqueue(json("""{"query": "gate", "total": 0}"""))

        repository.search("gate", fictionId = 7)

        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("fiction_id=7"))
    }

    @Test
    fun `groups are present even with nothing to find`() = runTest {
        // The server always sends all three; a client that assumed otherwise would NPE on an
        // empty search rather than showing "nothing found".
        val repository = repository(search = true)
        server.enqueue(json("""{"query": "zzz", "total": 0, "indexed": true}"""))

        val results = repository.search("zzz")

        assertEquals(0, results?.total)
        assertTrue(results?.fictions?.items?.isEmpty() == true)
        assertTrue(results?.chapters?.items?.isEmpty() == true)
        assertTrue(results?.text?.items?.isEmpty() == true)
    }

    @Test
    fun `a fiction hit with no chapter fields still parses`() = runTest {
        // Fiction hits carry no chapter id or snippet, text hits carry no tags. One strict field
        // would fail the whole response.
        val repository = repository(search = true)
        server.enqueue(
            json(
                """
                {"query": "ashes", "total": 1,
                 "fictions": {"items": [{"kind": "fiction", "fiction_id": 1,
                                         "fiction_title": "Ashes of Aether",
                                         "chapter_id": null, "chapter_title": null,
                                         "snippet": null}],
                              "total": 1, "capped": false, "has_more": false}}
                """,
            ),
        )

        val hit = repository.search("ashes")?.fictions?.items?.first()

        assertEquals(SearchKindFiction, hit?.kind)
        assertNull(hit?.chapterId)
        assertEquals("Ashes of Aether", hit?.resolvedTitle)
    }

    @Test
    fun `a capped total is reported as capped`() = runTest {
        val repository = repository(search = true)
        server.enqueue(
            json(
                """
                {"query": "the", "total": 500,
                 "text": {"items": [], "total": 500, "capped": true, "has_more": true}}
                """,
            ),
        )

        val group = repository.search("the")?.text

        // The server stops counting at a cap, so the UI must render "500+" rather than an
        // exact-looking 500.
        assertTrue(group?.capped == true)
        assertTrue(group?.hasMore == true)
    }

    @Test
    fun `an unavailable full-text index is reported rather than hidden`() = runTest {
        val repository = repository(search = true)
        server.enqueue(json("""{"query": "x", "total": 0, "indexed": false}"""))

        assertFalse(repository.search("x")?.indexed ?: true)
    }
}

/** The row title fallback, which is what stops a hit rendering blank. */
class SearchHitTitleTest {

    @Test
    fun `a chapter hit shows the chapter`() {
        val hit = SearchHit(kind = SearchKindText, chapterTitle = "The Gate", fictionTitle = "Ashes")
        assertEquals("The Gate", hit.resolvedTitle)
    }

    @Test
    fun `a fiction hit shows the fiction`() {
        val hit = SearchHit(kind = SearchKindFiction, fictionTitle = "Ashes")
        assertEquals("Ashes", hit.resolvedTitle)
    }

    @Test
    fun `a blank title is treated as absent`() {
        val hit = SearchHit(kind = SearchKindText, chapterTitle = "  ", fictionTitle = "Ashes")
        assertEquals("Ashes", hit.resolvedTitle)
    }

    @Test
    fun `there is always something to show`() {
        assertEquals("Untitled", SearchHit(kind = SearchKindText).resolvedTitle)
    }
}
