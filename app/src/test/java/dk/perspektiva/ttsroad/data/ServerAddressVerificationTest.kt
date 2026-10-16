package dk.perspektiva.ttsroad.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #372: switching to another address of the signed-in server without signing out.
 *
 * Two MockWebServers on different ports are two origins, which is exactly the distinction the
 * per-origin token rule draws. The property under test, above all others: the token reaches the
 * candidate address only after it has identified itself as the same server without it.
 */
class ServerAddressVerificationTest {
    private lateinit var home: MockWebServer
    private lateinit var away: MockWebServer

    private val homeUrl get() = home.url("/").toString()
    private val awayUrl get() = away.url("/").toString()

    @Before
    fun start() {
        home = MockWebServer().apply { start() }
        away = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        home.shutdown()
        away.shutdown()
    }

    private fun signedIn(identity: String? = "tts.example", userId: Int? = 1) = FakeSessionStore(
        SessionState(
            serverUrl = homeUrl,
            token = "secret-token",
            username = "admin",
            serverIdentity = identity,
            userId = userId,
        ),
    )

    private fun capabilities(baseUrl: String?) = MockResponse().setBody(
        """{"api_version":1,"server":{"name":"TTSRoad","version":"1.4.0"${baseUrl?.let { ",\"base_url\":\"$it\"" } ?: ""}},"capabilities":{}}""",
    )

    private fun me(username: String = "admin", id: Int = 1) =
        MockResponse().setBody("""{"user":{"id":$id,"username":"$username","is_admin":true}}""")

    private fun MockWebServer.next(): RecordedRequest =
        takeRequest(5, TimeUnit.SECONDS) ?: error("expected a request")

    @Test
    fun `an address that names the same server and accepts the token is switched to, still signed in`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(me())
        away.enqueue(capabilities("https://tts.example")) // the refresh after the switch

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertEquals(ServerAddressResult.Done, result)
        val session = store.current()
        assertEquals(awayUrl, session.serverUrl)
        assertEquals("secret-token", session.token)
        assertTrue(session.isLoggedIn)
        assertEquals(listOf(homeUrl), session.alternateUrls)
        assertEquals("tts.example", session.serverIdentity)

        val identity = away.next()
        assertEquals("/api/mobile/capabilities", identity.path)
        assertNull("identity is checked without the token", identity.getHeader("Authorization"))
        val account = away.next()
        assertEquals("/api/mobile/me", account.path)
        assertEquals("Bearer secret-token", account.getHeader("Authorization"))
        assertEquals("the address being left is not contacted at all", 0, home.requestCount)
    }

    @Test
    fun `after switching, API calls are authenticated on the new address and never on the old one`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(me())
        away.enqueue(capabilities("https://tts.example"))
        repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)
        repeat(3) { away.next() }

        away.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        repository.library()

        val library = away.next()
        assertTrue(library.path!!.startsWith("/api/mobile/library"))
        assertEquals("Bearer secret-token", library.getHeader("Authorization"))
        assertEquals(0, home.requestCount)
    }

    @Test
    fun `saving an address keeps the one in use`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example/"))
        away.enqueue(me())

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        assertEquals(homeUrl, store.current().serverUrl)
        assertEquals(listOf(awayUrl), store.current().alternateUrls)
        assertEquals(2, away.requestCount)
    }

    @Test
    fun `a different server is refused before the token is sent`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://someone-else.example"))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertTrue(result.message, result.message.contains("different server"))
        assertEquals(1, away.requestCount)
        assertNull(away.next().getHeader("Authorization"))
        assertEquals(homeUrl, store.current().serverUrl)
        assertEquals(emptyList<String>(), store.current().alternateUrls)
    }

    @Test
    fun `a server that does not say which server it is gets no token`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities(baseUrl = null))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertEquals(1, away.requestCount)
        assertNull(away.next().getHeader("Authorization"))
        assertEquals(emptyList<String>(), store.current().alternateUrls)
    }

    @Test
    fun `a server too old to have discovery gets no token`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(MockResponse().setResponseCode(404))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertEquals(1, away.requestCount)
        assertNull(away.next().getHeader("Authorization"))
        assertEquals(homeUrl, store.current().serverUrl)
    }

    @Test
    fun `an unreachable address is refused and the session is untouched`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        val gone = awayUrl
        away.shutdown()

        val result = repository.verifyServerAddress(gone, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertEquals(homeUrl, store.current().serverUrl)
        assertTrue(store.current().isLoggedIn)
    }

    @Test
    fun `a session signed in before identities were recorded asks the address in use, without the token`() = runTest {
        val store = signedIn(identity = null)
        val repository = TtsRoadRepository(store)
        home.enqueue(capabilities("https://tts.example"))
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(me())

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        val reference = home.next()
        assertEquals("/api/mobile/capabilities", reference.path)
        assertNull(reference.getHeader("Authorization"))
        assertEquals("tts.example", store.current().serverIdentity)
    }

    @Test
    fun `with no recorded identity and the address in use unreachable, the candidate is never contacted`() = runTest {
        val store = signedIn(identity = null)
        val repository = TtsRoadRepository(store)
        home.enqueue(MockResponse().setResponseCode(503))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertEquals(0, away.requestCount)
    }

    @Test
    fun `a 401 at the candidate is not the session ending`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token"}"""))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue((result as ServerAddressResult.Refused).tokenSent)
        assertTrue(store.current().isLoggedIn)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
        assertEquals(homeUrl, store.current().serverUrl)
    }

    @Test
    fun `a different account at the candidate is refused`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(me(username = "guest", id = 2))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertEquals(homeUrl, store.current().serverUrl)
        assertEquals(emptyList<String>(), store.current().alternateUrls)
    }

    @Test
    fun `the same username under another account id is refused`() = runTest {
        val store = signedIn(userId = 1)
        val repository = TtsRoadRepository(store)
        away.enqueue(capabilities("https://tts.example"))
        away.enqueue(me(username = "admin", id = 7))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertEquals(homeUrl, store.current().serverUrl)
    }

    @Test
    fun `a sign-out during the check wins`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        away.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/capabilities" -> capabilities("https://tts.example")
                else -> {
                    store.replace(store.currentBlocking().copy(token = null))
                    me()
                }
            }
        }

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertEquals(homeUrl, store.currentBlocking().serverUrl)
        assertEquals(emptyList<String>(), store.currentBlocking().alternateUrls)
    }

    @Test
    fun `the address in use and a malformed address are refused without any request`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)

        assertTrue(repository.verifyServerAddress(homeUrl.uppercase(), ServerAddressAction.Use) is ServerAddressResult.Refused)
        assertTrue(repository.verifyServerAddress("ftp://nope", ServerAddressAction.Use) is ServerAddressResult.Refused)
        assertEquals(0, home.requestCount)
        assertEquals(0, away.requestCount)
    }

    @Test
    fun `signed out, nothing is checked`() = runTest {
        val repository = TtsRoadRepository(FakeSessionStore(SessionState(serverUrl = homeUrl)))

        assertTrue(repository.verifyServerAddress(awayUrl, ServerAddressAction.Use) is ServerAddressResult.Refused)
        assertEquals(0, away.requestCount)
    }

    @Test
    fun `forgetting a saved address leaves the one in use`() = runTest {
        val store = FakeSessionStore(
            SessionState(serverUrl = homeUrl, token = "t", username = "admin", alternateUrls = listOf(awayUrl)),
        )
        val repository = TtsRoadRepository(store)

        repository.forgetServerAddress(awayUrl)

        assertEquals(homeUrl, store.current().serverUrl)
        assertEquals(emptyList<String>(), store.current().alternateUrls)
    }
}
