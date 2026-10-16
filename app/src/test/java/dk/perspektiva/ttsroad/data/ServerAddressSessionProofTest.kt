package dk.perspektiva.ttsroad.data

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #437: checking another address by the server's `instance_id` and by session proof, before the
 * token goes anywhere.
 *
 * Every candidate here is a MockWebServer whose recorded requests are inspected afterwards: the
 * property under test is that a candidate failing any check has received no `Authorization` header
 * at all — not on discovery, not on the proof request.
 *
 * Robolectric only for `org.json`, which parses the proof request's body the way a server would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAddressSessionProofTest {
    private lateinit var home: MockWebServer
    private lateinit var away: MockWebServer

    private val homeUrl get() = home.url("/").toString()
    private val awayUrl get() = away.url("/").toString()

    private val instance = "3f0c9a52-7d1e-4f63-9a0b-2c4e8d6b1f70"
    private val token = "secret-token"

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

    private fun signedIn(
        identity: String? = "tts.example",
        instanceId: String? = instance,
        deviceId: Int? = 42,
    ) = FakeSessionStore(
        SessionState(
            serverUrl = homeUrl,
            token = token,
            username = "admin",
            serverIdentity = identity,
            serverInstanceId = instanceId,
            deviceId = deviceId,
            userId = 1,
        ),
    )

    private fun capabilities(
        baseUrl: String? = "https://tts.example",
        instanceId: String? = instance,
        sessionProof: Boolean = true,
    ): MockResponse {
        val server = buildList {
            add("\"name\":\"TTSRoad\",\"version\":\"1.5.7\"")
            baseUrl?.let { add("\"base_url\":\"$it\"") }
            instanceId?.let { add("\"instance_id\":\"$it\"") }
        }.joinToString(",")
        return MockResponse().setBody(
            """{"api_version":1,"server":{$server},"capabilities":{"session_proof":$sessionProof}}""",
        )
    }

    private fun me() = MockResponse().setBody("""{"user":{"id":1,"username":"admin","is_admin":true}}""")

    /** What a server holding [heldToken] for device 42 answers: the proof over the nonce it was sent. */
    private fun proofFor(request: RecordedRequest, heldToken: String = token, instanceId: String = instance): MockResponse {
        val body = JSONObject(request.body.readUtf8())
        val nonce = Base64.getUrlDecoder().decode(body.getString("nonce"))
        val proof = if (body.getInt("device_id") == 42) {
            SessionProof.expected(heldToken, nonce)
        } else {
            SessionProof.expected("not-a-session", nonce)
        }
        return MockResponse().setBody("""{"proof":"$proof","instance_id":"$instanceId"}""")
    }

    /** A candidate that answers discovery, then the proof via [prove], then `/me`, recording every request. */
    private fun candidate(
        discovery: MockResponse = capabilities(),
        prove: (RecordedRequest) -> MockResponse = { proofFor(it) },
    ) {
        away.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/capabilities" -> discovery
                "/api/mobile/session-proof" -> prove(request)
                "/api/mobile/me" -> me()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun recorded(server: MockWebServer): List<RecordedRequest> =
        List(server.requestCount) { server.takeRequest(5, TimeUnit.SECONDS) ?: error("expected a request") }

    private fun assertNoTokenSent(requests: List<RecordedRequest>) {
        requests.forEach { assertNull("${it.path} carried a token", it.getHeader("Authorization")) }
        assertFalse(requests.any { it.path == "/api/mobile/me" })
    }

    @Test
    fun `a candidate that proves the session is sent the token only after the proof`() = runTest {
        val store = signedIn()
        candidate()

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        val requests = recorded(away)
        assertEquals(
            listOf("/api/mobile/capabilities", "/api/mobile/session-proof", "/api/mobile/me"),
            requests.map { it.path },
        )
        assertNull(requests[0].getHeader("Authorization"))
        val proofRequest = requests[1]
        assertNull("the proof request is unauthenticated", proofRequest.getHeader("Authorization"))
        assertEquals("POST", proofRequest.method)
        assertEquals("Bearer $token", requests[2].getHeader("Authorization"))
        assertEquals(listOf(awayUrl), store.current().alternateUrls)
        assertEquals(instance, store.current().serverInstanceId)
        assertEquals(0, home.requestCount)
    }

    @Test
    fun `the proof request carries the device id and a fresh 32-byte nonce, and never the token`() = runTest {
        val bodies = mutableListOf<String>()
        candidate(prove = { request ->
            val copy = request.body.clone().readUtf8()
            bodies += copy
            proofFor(request)
        })
        val repository = TtsRoadRepository(signedIn())

        repository.verifyServerAddress(awayUrl, ServerAddressAction.Remember)
        repository.verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(2, bodies.size)
        val nonces = bodies.map { body ->
            val json = JSONObject(body)
            assertEquals(42, json.getInt("device_id"))
            assertFalse("the token never appears in the request", body.contains(token))
            json.getString("nonce").also { assertFalse(it.contains("=")) }
        }
        nonces.forEach { assertEquals(32, Base64.getUrlDecoder().decode(it).size) }
        assertTrue("each check chooses its own nonce", nonces[0] != nonces[1])
    }

    @Test
    fun `a wrong proof is refused without a single Authorization header reaching the candidate`() = runTest {
        val store = signedIn()
        // Knows the real server's instance id (it is public) but not the session.
        candidate(prove = { proofFor(it, heldToken = "a-guess") })

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue(result is ServerAddressResult.Refused)
        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        val requests = recorded(away)
        assertEquals(listOf("/api/mobile/capabilities", "/api/mobile/session-proof"), requests.map { it.path })
        assertNoTokenSent(requests)
        assertEquals(homeUrl, store.current().serverUrl)
        assertEquals(emptyList<String>(), store.current().alternateUrls)
    }

    @Test
    fun `a missing or malformed proof is refused without the token`() = runTest {
        val store = signedIn()
        candidate(prove = { MockResponse().setBody("""{"instance_id":"$instance"}""") })

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertNoTokenSent(recorded(away))
    }

    @Test
    fun `a right proof under another instance id is refused`() = runTest {
        val store = signedIn()
        candidate(prove = { proofFor(it, instanceId = "00000000-0000-4000-8000-000000000000") })

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertNoTokenSent(recorded(away))
        assertEquals(homeUrl, store.current().serverUrl)
    }

    @Test
    fun `a different instance id is refused at discovery, before the proof is even asked`() = runTest {
        val store = signedIn()
        candidate(discovery = capabilities(instanceId = "00000000-0000-4000-8000-000000000000"))

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertTrue((result as ServerAddressResult.Refused).message.contains("different server"))
        val requests = recorded(away)
        assertEquals(listOf("/api/mobile/capabilities"), requests.map { it.path })
        assertNoTokenSent(requests)
    }

    @Test
    fun `with an instance id recorded, a candidate that leaves it out is not this server`() = runTest {
        val store = signedIn()
        // Same base_url, no instance id: dropping the field must not downgrade to the weaker check.
        candidate(discovery = capabilities(instanceId = null, sessionProof = false))

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertNoTokenSent(recorded(away))
    }

    @Test
    fun `instance ids compare regardless of case`() = runTest {
        val store = signedIn()
        candidate(discovery = capabilities(instanceId = instance.uppercase()))

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
    }

    @Test
    fun `an instance id makes a placeholder base_url irrelevant`() = runTest {
        // Every unconfigured install says http://localhost:8787; the instance id still tells them apart.
        val store = signedIn(identity = "localhost:8787")
        candidate(discovery = capabilities(baseUrl = "http://localhost:8787"))

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
    }

    @Test
    fun `a 429 from the proof endpoint is a refusal, with the wait, and no token`() = runTest {
        val store = signedIn()
        candidate(prove = {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "600").setBody(
                """{"detail":{"message":"Too many attempts","reason":"rate_limited","retry_after":600}}""",
            )
        })

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertTrue(result.message, result.message.contains("10 minutes"))
        assertNoTokenSent(recorded(away))
        assertTrue(store.current().isLoggedIn)
        assertEquals(homeUrl, store.current().serverUrl)
    }

    @Test
    fun `a session with no device id falls back to the instance id comparison`() = runTest {
        val store = signedIn(deviceId = null)
        candidate()

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        assertEquals(listOf("/api/mobile/capabilities", "/api/mobile/me"), recorded(away).map { it.path })
    }

    @Test
    fun `an older server with neither instance id nor proof keeps the base_url check`() = runTest {
        val store = signedIn(instanceId = null)
        candidate(discovery = capabilities(instanceId = null, sessionProof = false))

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        assertEquals(listOf("/api/mobile/capabilities", "/api/mobile/me"), recorded(away).map { it.path })
        assertNull(store.current().serverInstanceId)
    }

    @Test
    fun `a session recorded before instance ids takes it from the address in use's discovery`() = runTest {
        val store = signedIn(instanceId = null)
        val repository = TtsRoadRepository(store)
        home.enqueue(capabilities())
        repository.refreshCurrentCapabilities()
        home.takeRequest(5, TimeUnit.SECONDS)
        // Same base_url as the session's, but another install: only the instance id can tell.
        candidate(discovery = capabilities(instanceId = "00000000-0000-4000-8000-000000000000"))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertNoTokenSent(recorded(away))
        assertEquals(1, home.requestCount)
    }

    @Test
    fun `a candidate that stops offering the proof the address in use offers is refused`() = runTest {
        val store = signedIn()
        val repository = TtsRoadRepository(store)
        home.enqueue(capabilities())
        repository.refreshCurrentCapabilities()
        // An impostor repeating the public instance id, and hoping the proof is simply skipped.
        candidate(discovery = capabilities(sessionProof = false))

        val result = repository.verifyServerAddress(awayUrl, ServerAddressAction.Use)

        assertFalse((result as ServerAddressResult.Refused).tokenSent)
        assertNoTokenSent(recorded(away))
    }

    @Test
    fun `a legacy session's backfill and a newer candidate record the instance id once confirmed`() = runTest {
        val store = signedIn(identity = null, instanceId = null)
        home.enqueue(capabilities(sessionProof = false))
        candidate()

        val result = TtsRoadRepository(store).verifyServerAddress(awayUrl, ServerAddressAction.Remember)

        assertEquals(ServerAddressResult.Done, result)
        assertNull(home.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals(instance, store.current().serverInstanceId)
        assertEquals("tts.example", store.current().serverIdentity)
    }
}
