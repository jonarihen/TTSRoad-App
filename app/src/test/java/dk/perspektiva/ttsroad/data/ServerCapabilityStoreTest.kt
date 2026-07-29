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
import retrofit2.HttpException

/** In-memory [SessionStore] so these tests need no Android context or DataStore. */
private class FakeStore(private var state: SessionState) : SessionStore {
    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(serverUrl = normalizeBaseUrl(baseUrl), token = response.token)
    }

    override suspend fun clearToken() {
        state = state.copy(token = null)
    }
}

private const val CapabilitiesBody = """
{
  "api_version": 1,
  "server": {"name": "TTSRoad", "version": "1.4.0"},
  "capabilities": {"readalong": true, "device_management": true},
  "limits": {"max_chapters_per_page": 200}
}
"""

class ServerCapabilityStoreTest {
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

    private fun repository() = TtsRoadRepository(FakeStore(SessionState()))

    private fun store(repository: TtsRoadRepository = repository(), now: () -> Long = { 0L }) =
        ServerCapabilityStore(repository, now)

    @Test
    fun `discovery reads the advertised flags`() = runTest {
        server.enqueue(MockResponse().setBody(CapabilitiesBody))

        val capabilities = store().activate(server.url("/").toString())

        assertEquals("/api/mobile/capabilities", server.takeRequest().path)
        assertEquals(setOf(Capability.ReadAlong, Capability.DeviceManagement), capabilities.enabled)
        assertEquals("1.4.0", capabilities.serverVersion)
    }

    @Test
    fun `a 404 is an old server, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val capabilities = store().activate(server.url("/").toString())

        assertEquals(ServerCapabilities.Baseline, capabilities)
        assertTrue(capabilities.discovered)
        Capability.entries.forEach { assertFalse(it in capabilities) }
    }

    @Test
    fun `a server error still propagates so the caller can tell it apart from an old server`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            val thrown = runCatching {
                store().activate(server.url("/").toString())
            }.exceptionOrNull()

            assertEquals(500, (thrown as HttpException).code())
        }

    @Test
    fun `discovery never sends the session bearer token`() = runTest {
        // A live session, and a probe of some entirely different URL the user is typing.
        val repository = repository()
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        val signedIn = TtsRoadRepository(
            FakeStore(SessionState(serverUrl = server.url("/").toString(), token = "secret")),
        )
        signedIn.library()
        server.takeRequest()

        server.enqueue(MockResponse().setBody(CapabilitiesBody))
        store(repository).probe(server.url("/").toString())

        val probe = server.takeRequest()
        assertNull("the probe must not carry a token", probe.getHeader("Authorization"))
        assertNull("the opt-out marker is internal", probe.getHeader("X-TtsRoad-No-Auth"))
    }

    @Test
    fun `a cached answer is reused until the refresh interval passes`() = runTest {
        var now = 0L
        val store = store(now = { now })
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody(CapabilitiesBody))

        store.activate(url)
        now += 60 * 60 * 1000 // an hour later
        assertEquals(setOf(Capability.ReadAlong, Capability.DeviceManagement), store.probe(url).enabled)
        assertEquals("one request served both calls", 1, server.requestCount)

        // Past the interval the server is asked again, which is also how a version bump is noticed.
        now += 6 * 60 * 60 * 1000
        server.enqueue(
            MockResponse().setBody(
                """{"server":{"name":"TTSRoad","version":"1.5.0"},"capabilities":{"search":true}}""",
            ),
        )
        val refreshed = store.activate(url)

        assertEquals(2, server.requestCount)
        assertEquals("1.5.0", refreshed.serverVersion)
        assertEquals(setOf(Capability.Search), refreshed.enabled)
    }

    @Test
    fun `each server is cached separately`() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            val store = store()
            server.enqueue(MockResponse().setBody(CapabilitiesBody))
            other.enqueue(MockResponse().setResponseCode(404))

            assertEquals(setOf(Capability.ReadAlong, Capability.DeviceManagement), store.activate(server.url("/").toString()).enabled)
            assertEquals(ServerCapabilities.Baseline, store.probe(other.url("/").toString()))
            assertEquals(1, server.requestCount)
            assertEquals(1, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `probing does not publish over the signed-in server's capabilities`() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            val store = store()
            server.enqueue(MockResponse().setBody(CapabilitiesBody))
            store.activate(server.url("/").toString())

            other.enqueue(MockResponse().setResponseCode(404))
            store.probe(other.url("/").toString())

            assertEquals("1.4.0", store.current.value.serverVersion)
            assertTrue(Capability.ReadAlong in store.current.value)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `a half-typed URL is not a request`() = runTest {
        val store = store()

        assertEquals(ServerCapabilities.Unknown, store.probe("ttsroad.example"))
        assertEquals(ServerCapabilities.Unknown, store.probe(""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signing out drops the cache and the published answer`() = runTest {
        val store = store()
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody(CapabilitiesBody))
        store.activate(url)

        store.clear()

        assertEquals(ServerCapabilities.Unknown, store.current.value)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ServerCapabilities.Baseline, store.probe(url))
        assertEquals("the cleared cache had to ask again", 2, server.requestCount)
    }
}
