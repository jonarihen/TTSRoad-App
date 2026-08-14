package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeFictionSessionStore(
    private var state: SessionState = SessionState(),
) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
            isAdmin = response.user.isAdmin,
        )
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

/**
 * Adding and deleting fictions over the mobile mirror of `/api/fictions`.
 *
 * Two things carry most of the weight here. First, that a refusal keeps the server's own words: it
 * is the half that knows which sites have adapters, and "already tracked" is a different instruction
 * to the user than "that is not a URL I can read". Second, that a server without the capability is
 * never called at all — these routes destroy shared data, and probing for them is not free.
 */
class FictionManagementRepositoryTest {
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

    private fun capabilities(fictionManagement: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.6.0"},
      "capabilities": {"fiction_management": $fictionManagement},
      "limits": {}
    }
    """

    private suspend fun repository(fictionManagement: Boolean = true): TtsRoadRepository {
        val store = FakeFictionSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "admin", isAdmin = true),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(json(capabilities(fictionManagement)))
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `adding posts the url to the mobile route and reports the tracked fiction`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "status": "ok",
                    "fiction": {"id": 7, "title": "Ashfall", "total_chapters": 0}}""",
                code = 201,
            ),
        )

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"fiction_url":"https://www.royalroad.com/fiction/12345"}""",
            request.body.readUtf8(),
        )
        assertTrue(result is FictionAddResult.Added)
        assertEquals("Ashfall", (result as FictionAddResult.Added).fiction?.title)
    }

    @Test
    fun `a surrounding space in a pasted url is trimmed rather than rejected`() = runTest {
        // Pasting from a browser share sheet routinely brings whitespace with it, and the server's
        // URL validator would refuse the untrimmed string.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}""", 201))

        repository.addFiction("  https://www.royalroad.com/fiction/12345\n")

        server.takeRequest()
        assertEquals(
            """{"fiction_url":"https://www.royalroad.com/fiction/12345"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `the server's reason for refusing a url is what the caller gets`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Not a supported fiction site"}""", code = 400))

        val result = repository.addFiction("https://example.com/not-a-fiction")

        assertTrue(result is FictionAddResult.Refused)
        assertEquals("Not a supported fiction site", (result as FictionAddResult.Refused).message)
    }

    @Test
    fun `a fiction already tracked is a refusal with the server's words, not a crash`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Fiction already tracked"}""", code = 409))

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertEquals("Fiction already tracked", (result as FictionAddResult.Refused).message)
    }

    @Test
    fun `a refusal with an unreadable body still says something useful`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>nope</html>"))

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertTrue(result is FictionAddResult.Refused)
        assertTrue((result as FictionAddResult.Refused).message.isNotBlank())
    }

    @Test
    fun `a blank url never reaches the server`() = runTest {
        val repository = repository()

        val result = repository.addFiction("   ")

        assertTrue(result is FictionAddResult.Refused)
        // Only the capabilities call was made.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server without fiction management is not asked to add`() = runTest {
        val repository = repository(fictionManagement = false)

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertEquals(FictionAddResult.Unsupported, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `deleting sends DELETE to the mobile route and believes the body`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"api_version": 1, "status": "ok", "fiction_id": 7, "deleted": true}"""),
        )

        val deleted = repository.deleteFiction(7)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/7", request.path)
        assertEquals("DELETE", request.method)
        assertEquals(true, deleted)
    }

    @Test
    fun `deleting something already gone counts as deleted`() = runTest {
        // From the browser, or a second tap on a list that has not refreshed. The caller wanted the
        // fiction gone and it is gone; reporting an error would be reporting the wrong thing.
        val repository = repository()
        server.enqueue(json("""{"detail": "Fiction not found"}""", code = 404))

        assertEquals(true, repository.deleteFiction(7))
    }

    @Test
    fun `a server without fiction management is not asked to delete`() = runTest {
        val repository = repository(fictionManagement = false)

        assertNull(repository.deleteFiction(7))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the capability is off by default so an older server hides the controls`() {
        assertEquals(false, ServerCapabilities.Baseline.fictionManagement)
    }

    @Test
    fun `only a literal true turns fiction management on`() {
        val response = CapabilitiesResponse(capabilities = mapOf("fiction_management" to "yes"))
        assertEquals(false, ServerCapabilities.from(response).fictionManagement)
    }
}
