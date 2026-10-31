package dk.perspektiva.ttsroad.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #505: the address in use is unreachable, so a verified saved address takes over. */
class ServerAddressFailoverTest {
    private lateinit var home: MockWebServer
    private lateinit var away: MockWebServer

    @Before
    fun start() {
        home = MockWebServer().apply { start() }
        away = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        runCatching { home.shutdown() }
        away.shutdown()
    }

    private fun capabilities() = MockResponse().setBody(
        """{"api_version":1,"server":{"name":"TTSRoad","version":"1.4.0","base_url":"https://tts.example"},"capabilities":{}}""",
    )

    private fun me() = MockResponse().setBody("""{"user":{"id":1,"username":"admin","is_admin":true}}""")

    private fun store(homeUrl: String, awayUrl: String) = FakeSessionStore(
        SessionState(
            serverUrl = homeUrl,
            token = "secret-token",
            username = "admin",
            serverIdentity = "tts.example",
            userId = 1,
            alternateUrls = listOf(awayUrl),
        ),
    )

    /** Polls up to 20s of real time; the condition is normally met within milliseconds. */
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition() && System.nanoTime() < deadline) delay(25)
        assertTrue("condition not met within 20s", condition())
    }

    @Test
    fun `an unreachable address in use fails over to a verified saved one`() {
        val homeUrl = home.url("/").toString()
        val awayUrl = away.url("/").toString()
        home.shutdown()
        val sessions = store(homeUrl, awayUrl)
        val repository = TtsRoadRepository(sessions)
        away.enqueue(capabilities())
        away.enqueue(me())
        away.enqueue(capabilities())

        runCatching { runBlocking { repository.library() } }
        waitFor { repository.addressFailover.value != null }

        val session = runBlocking { sessions.current() }
        assertEquals(awayUrl, session.serverUrl)
        assertEquals("secret-token", session.token)
        assertEquals(homeUrl, repository.addressFailover.value?.from)
        assertEquals(awayUrl, repository.addressFailover.value?.to)
        assertNotNull(away.takeRequest().also { assertEquals(null, it.getHeader("Authorization")) })
    }

    @Test
    fun `an alternate that is a different server is never switched to or sent the token`() {
        val homeUrl = home.url("/").toString()
        val awayUrl = away.url("/").toString()
        home.shutdown()
        val sessions = store(homeUrl, awayUrl)
        val repository = TtsRoadRepository(sessions)
        away.enqueue(
            MockResponse().setBody(
                """{"api_version":1,"server":{"name":"X","version":"1","base_url":"https://other.example"},"capabilities":{}}""",
            ),
        )

        runCatching { runBlocking { repository.library() } }
        waitFor { away.requestCount > 0 }

        assertEquals(homeUrl, runBlocking { sessions.current().serverUrl })
        assertEquals(null, repository.addressFailover.value)
        assertEquals(1, away.requestCount)
        assertEquals(null, away.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `only failures to reach the host count`() {
        assertTrue(isHostUnreachable(UnknownHostException("x")))
        assertTrue(isHostUnreachable(ConnectException("refused")))
        assertTrue(isHostUnreachable(SocketTimeoutException("Connect timed out")))
        assertFalse(isHostUnreachable(SocketTimeoutException("timeout")))
        assertFalse(isHostUnreachable(SSLHandshakeException("bad cert")))
    }
}
