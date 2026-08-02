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
 * The structured 401 the server sends when a bearer token can no longer be used, and what the app
 * is expected to do about each documented reason.
 */
class TtsRoadRepositorySessionEndTest {
    private lateinit var server: MockWebServer

    private fun loggedInStore() = FakeSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin"),
    )

    private fun expiredBody(reason: String, message: String) =
        MockResponse()
            .setResponseCode(401)
            .setBody("""{"detail":{"message":"$message","reason":"$reason"}}""")

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `an expired token signs out and says so in the server's words`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(expiredBody("token_expired", "This device session expired. Sign in again."))

        runCatching { repository.library() }

        assertEquals(1, store.clearTokenCalls)
        assertNull(store.current().token)
        val end = repository.sessionEnd.value
        assertNotNull(end)
        assertEquals(SessionEndReason.Expired, end?.reason)
        assertEquals("This device session expired. Sign in again.", end?.message)
    }

    @Test
    fun `a revoked token signs out and is told apart from an expiry`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(expiredBody("token_revoked", "This device was signed out from another device."))

        runCatching { repository.library() }

        assertEquals(1, store.clearTokenCalls)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
        assertEquals(
            "This device was signed out from another device.",
            repository.sessionEnd.value?.message,
        )
    }

    @Test
    fun `an invalid token signs out`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(expiredBody("invalid_token", "Unknown token."))

        runCatching { repository.library() }

        assertEquals(1, store.clearTokenCalls)
        assertEquals(SessionEndReason.Invalid, repository.sessionEnd.value?.reason)
        assertEquals("Unknown token.", repository.sessionEnd.value?.message)
    }

    /**
     * The point of not retrying: the server has said the credential is dead, and asking again with
     * the same one can only ever get the same answer.
     */
    @Test
    fun `a rejected token is not retried`() = runTest {
        for (reason in listOf("token_expired", "token_revoked", "invalid_token")) {
            val single = MockWebServer()
            single.start()
            try {
                val store = FakeSessionStore(
                    SessionState(serverUrl = single.url("/").toString(), token = "stale", username = "admin"),
                )
                val repository = TtsRoadRepository(store)
                single.enqueue(
                    MockResponse().setResponseCode(401).setBody("""{"detail":{"reason":"$reason"}}"""),
                )

                runCatching { repository.library() }

                assertEquals("reason: $reason", 1, single.requestCount)
            } finally {
                single.shutdown()
            }
        }
    }

    /** An older server, or one behind a proxy that answers 401 itself. */
    @Test
    fun `a 401 with no recognisable reason still signs out safely`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token"}"""))

        runCatching { repository.library() }

        assertEquals(1, store.clearTokenCalls)
        assertFalse(store.current().isLoggedIn)
        assertEquals(SessionEndReason.Unknown, repository.sessionEnd.value?.reason)
        assertTrue(repository.sessionEnd.value?.message?.isNotBlank() == true)
    }

    @Test
    fun `a 401 with an empty body still signs out safely`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))

        runCatching { repository.library() }

        assertEquals(1, store.clearTokenCalls)
        assertEquals(SessionEndReason.Unknown, repository.sessionEnd.value?.reason)
        assertTrue(repository.sessionEnd.value?.message?.isNotBlank() == true)
    }

    @Test
    fun `a 500 keeps the session so the user can retry`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        runCatching { repository.library() }

        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    /** A dead connection is the drive-through-a-tunnel case, not a credential problem. */
    @Test
    fun `a network failure keeps the session`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.shutdown()

        val thrown = runCatching { repository.library() }.exceptionOrNull()

        assertNotNull(thrown)
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    /**
     * The audio path reaches the same conclusion from a 401 on a stream, and has to land on the
     * same login screen with the same explanation.
     */
    @Test
    fun `ending the session from the audio path clears the token and keeps the reason`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)

        repository.endSession(
            parseSessionEnd("""{"detail":{"message":"Audio said no.","reason":"token_revoked"}}"""),
        )

        assertEquals(1, store.clearTokenCalls)
        assertFalse(store.current().isLoggedIn)
        assertEquals(SessionEndReason.Revoked, repository.sessionEnd.value?.reason)
        assertEquals("Audio said no.", repository.sessionEnd.value?.message)
    }

    @Test
    fun `signing in again clears the reason`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(expiredBody("token_expired", "Gone."))
        runCatching { repository.library() }
        assertNotNull(repository.sessionEnd.value)

        server.enqueue(
            MockResponse().setBody(
                """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
            ),
        )
        repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )

        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `login stores the device id and expiry the server hands back`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setBody(
                """{"token":"ttsr_x","token_type":"bearer","device_id":42,
                   |"expires_at":"2026-10-26T12:00:00Z","user":{"id":1,"username":"admin"}}"""
                    .trimMargin(),
            ),
        )

        assertEquals(
            LoginResult.Success,
            repository.login(
                baseUrl = server.url("/").toString(),
                username = "admin",
                password = "correct",
                deviceName = "Pixel",
            ),
        )
        assertEquals(42, store.current().deviceId)
        assertEquals("2026-10-26T12:00:00Z", store.current().expiresAt)
    }

    /** Older servers send neither field, and that must not stop anyone signing in. */
    @Test
    fun `a login response without the new fields still signs in`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setBody(
                """{"token":"ttsr_x","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
            ),
        )

        assertEquals(
            LoginResult.Success,
            repository.login(
                baseUrl = server.url("/").toString(),
                username = "admin",
                password = "correct",
                deviceName = "Pixel",
            ),
        )
        assertTrue(store.current().isLoggedIn)
        assertNull(store.current().deviceId)
        assertNull(store.current().expiresAt)
    }
}
