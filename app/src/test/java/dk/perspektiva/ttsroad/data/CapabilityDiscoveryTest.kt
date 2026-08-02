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

/** In-memory [SessionStore] so discovery can be tested without DataStore or an Android context. */
private class FakeDiscoverySessionStore(private var state: SessionState = SessionState()) : SessionStore {
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

private const val FullPayload = """
{
  "api_version": 1,
  "server": {"name": "TTSRoad", "version": "1.4.0", "base_url": "https://ttsroad.example.com"},
  "capabilities": {
    "readalong": true,
    "search": false,
    "bookmarks": false,
    "delta_sync": false,
    "batch_progress": false,
    "audio_content_hash": false,
    "device_management": true
  },
  "limits": {"max_chapters_per_page": 200}
}
"""

class CapabilityDiscoveryTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString()

    @Test
    fun `discovery reads the advertised capabilities`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))

        val capabilities = repository.capabilities(baseUrl())

        assertTrue(capabilities.readAlong)
        assertTrue(capabilities.deviceManagement)
        assertFalse(capabilities.search)
        assertEquals("1.4.0", capabilities.serverVersion)
        assertEquals(200, capabilities.maxChaptersPerPage)
        assertEquals("/api/mobile/capabilities", server.takeRequest().path)
    }

    @Test
    fun `a 404 means an older server, not an error`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setResponseCode(404))

        val capabilities = repository.capabilities(baseUrl())

        assertEquals(ServerCapabilities.Baseline, capabilities)
        assertFalse(capabilities.readAlong)
        assertNull(capabilities.serverVersion)
    }

    @Test
    fun `an unreachable server falls back to the baseline so login still works`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(ServerCapabilities.Baseline, repository.capabilities(baseUrl()))
    }

    @Test
    fun `malformed JSON falls back to the baseline`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody("not json at all"))

        assertEquals(ServerCapabilities.Baseline, repository.capabilities(baseUrl()))
    }

    @Test
    fun `discovery is unauthenticated so it can validate a URL before login`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))

        repository.capabilities(baseUrl())

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a stale token is never attached to the public discovery call`() = runTest {
        val store = FakeDiscoverySessionStore(
            SessionState(serverUrl = baseUrl(), token = "stale-token", username = "admin"),
        )
        val repository = TtsRoadRepository(store)
        // Prime the shared client's auth header the way any authenticated call would.
        server.enqueue(MockResponse().setResponseCode(500))
        runCatching { repository.library() }

        server.enqueue(MockResponse().setBody(FullPayload))
        repository.capabilities(baseUrl(), forceRefresh = true)

        server.takeRequest() // the library call
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the result is cached per base URL so repeat calls cost nothing`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))

        val first = repository.capabilities(baseUrl())
        val second = repository.capabilities(baseUrl())

        assertEquals(first, second)
        assertEquals("only the first call should reach the server", 1, server.requestCount)
    }

    @Test
    fun `a trailing slash difference hits the same cache entry`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))

        repository.capabilities(baseUrl())
        repository.capabilities(baseUrl().trimEnd('/'))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `forcing a refresh refetches and replaces the cached value`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))
        assertTrue(repository.capabilities(baseUrl()).readAlong)

        server.enqueue(
            MockResponse().setBody(
                """{"api_version":1,"server":{"name":"TTSRoad","version":"1.5.0"},
                    "capabilities":{"readalong":false,"search":true}}""",
            ),
        )
        val refreshed = repository.capabilities(baseUrl(), forceRefresh = true)

        assertFalse(refreshed.readAlong)
        assertTrue(refreshed.search)
        assertEquals("1.5.0", refreshed.serverVersion)
        assertEquals(2, server.requestCount)
        // The replacement must stick, not be overwritten by the stale entry.
        assertTrue(repository.capabilities(baseUrl()).search)
    }

    @Test
    fun `a failed refresh keeps the last good capabilities instead of downgrading`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))
        assertTrue(repository.capabilities(baseUrl()).readAlong)

        server.enqueue(MockResponse().setResponseCode(500))
        val afterOutage = repository.capabilities(baseUrl(), forceRefresh = true)

        assertTrue("a blip must not silently disable read-along", afterOutage.readAlong)
    }

    @Test
    fun `the cache expires so a server upgraded under a running app is noticed`() = runTest {
        var now = 0L
        val repository = TtsRoadRepository(FakeDiscoverySessionStore(), clock = { now })
        server.enqueue(MockResponse().setBody(FullPayload))
        assertFalse(repository.capabilities(baseUrl()).search)

        now += java.util.concurrent.TimeUnit.HOURS.toMillis(7)
        server.enqueue(
            MockResponse().setBody("""{"api_version":1,"capabilities":{"search":true}}"""),
        )

        assertTrue(repository.capabilities(baseUrl()).search)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a fresh cache entry is not refetched before it expires`() = runTest {
        var now = 0L
        val repository = TtsRoadRepository(FakeDiscoverySessionStore(), clock = { now })
        server.enqueue(MockResponse().setBody(FullPayload))
        repository.capabilities(baseUrl())

        now += java.util.concurrent.TimeUnit.HOURS.toMillis(5)
        repository.capabilities(baseUrl())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 404 is remembered so an old server is not re-asked on every screen`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setResponseCode(404))

        repository.capabilities(baseUrl())
        repository.capabilities(baseUrl())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the session capabilities start at the baseline before anything is discovered`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())

        assertEquals(ServerCapabilities.Baseline, repository.currentCapabilities.value)
    }

    @Test
    fun `refreshing publishes the signed-in server's capabilities`() = runTest {
        val store = FakeDiscoverySessionStore(
            SessionState(serverUrl = baseUrl(), token = "t", username = "admin"),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody(FullPayload))

        repository.refreshCurrentCapabilities()

        assertTrue(repository.currentCapabilities.value.readAlong)
        assertTrue(repository.currentCapabilities.value.deviceManagement)
    }

    @Test
    fun `refreshing while signed out stays at the baseline and makes no request`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())

        repository.refreshCurrentCapabilities()

        assertEquals(ServerCapabilities.Baseline, repository.currentCapabilities.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signing out drops the previous server's capabilities`() = runTest {
        val store = FakeDiscoverySessionStore(
            SessionState(serverUrl = baseUrl(), token = "t", username = "admin"),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody(FullPayload))
        repository.refreshCurrentCapabilities()
        assertTrue(repository.currentCapabilities.value.readAlong)

        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))
        repository.logout()

        assertEquals(
            "a new account must not inherit the old server's feature flags",
            ServerCapabilities.Baseline,
            repository.currentCapabilities.value,
        )
    }

    @Test
    fun `signing out of a server clears its cached capabilities`() = runTest {
        val repository = TtsRoadRepository(FakeDiscoverySessionStore())
        server.enqueue(MockResponse().setBody(FullPayload))
        repository.capabilities(baseUrl())

        repository.forgetCapabilities(baseUrl())
        server.enqueue(MockResponse().setBody(FullPayload))
        repository.capabilities(baseUrl())

        assertEquals(2, server.requestCount)
    }
}
