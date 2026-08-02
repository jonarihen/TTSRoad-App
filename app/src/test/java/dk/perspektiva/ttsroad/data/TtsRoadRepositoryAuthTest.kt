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
import retrofit2.HttpException

class TtsRoadRepositoryAuthTest {
    private lateinit var server: MockWebServer

    private fun loggedInStore() = FakeSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "stale-token", username = "admin"),
    )

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
    fun `401 on an authenticated call clears the token and flags the session as expired`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token"}"""))

        val thrown = runCatching { repository.library() }.exceptionOrNull()

        assertTrue("expected the 401 to propagate", thrown is HttpException)
        assertEquals(401, (thrown as HttpException).code())
        assertEquals(1, store.clearTokenCalls)
        assertNull(store.current().token)
        assertFalse(store.current().isLoggedIn)
        assertNotNull(repository.sessionEnd.value)
    }

    @Test
    fun `a successful authenticated call leaves the session alone`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))

        assertEquals(0, repository.library().fictions.size)
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a non-401 failure keeps the token so the user can retry`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        val thrown = runCatching { repository.chapters(fictionId = 7) }.exceptionOrNull()

        assertEquals(500, (thrown as HttpException).code())
        assertEquals(0, store.clearTokenCalls)
        assertTrue(store.current().isLoggedIn)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `401 on a background progress save also expires the session`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))

        runCatching {
            repository.saveProgress(fictionId = 1, chapterId = 2, positionSeconds = 30.0, isPlayed = false)
        }

        assertEquals(1, store.clearTokenCalls)
        assertNotNull(repository.sessionEnd.value)
    }

    @Test
    fun `a wrong password still reports a plain failure`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Incorrect username or password"}"""),
        )

        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "wrong",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.Failure("Incorrect username or password"), result)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `a totp_required login is not treated as an expired session`() = runTest {
        val store = FakeSessionStore(SessionState())
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":{"code":"totp_required"}}"""),
        )

        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.TotpRequired, result)
        assertEquals(0, store.clearTokenCalls)
        assertNull(repository.sessionEnd.value)
    }

    @Test
    fun `signing in again clears the expired flag`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.library() }
        assertNotNull(repository.sessionEnd.value)

        server.enqueue(
            MockResponse().setBody(
                """{"token":"fresh","token_type":"bearer","user":{"id":1,"username":"admin"}}""",
            ),
        )
        val result = repository.login(
            baseUrl = server.url("/").toString(),
            username = "admin",
            password = "correct",
            deviceName = "Pixel",
        )

        assertEquals(LoginResult.Success, result)
        assertNull(repository.sessionEnd.value)
        assertEquals("fresh", store.current().token)
    }

    @Test
    fun `the recovered session sends the new token`() = runTest {
        val store = loggedInStore()
        val repository = TtsRoadRepository(store)
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.library() }

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
        server.enqueue(MockResponse().setBody("""{"api_version":1,"fictions":[]}"""))
        repository.library()

        server.takeRequest() // the 401'd library call
        server.takeRequest() // login
        val recovered = server.takeRequest()
        assertNotNull(recovered.getHeader("Authorization"))
        assertEquals("Bearer fresh", recovered.getHeader("Authorization"))
    }
}
