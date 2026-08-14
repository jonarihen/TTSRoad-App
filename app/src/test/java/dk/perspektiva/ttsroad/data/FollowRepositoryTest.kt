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

private class FakeFollowSessionStore(
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
 * Follow, unfollow, and the library scope that makes browsing-to-follow possible.
 *
 * The case that matters most is the older server: without per-user libraries `/library` is still
 * the whole shared list, so offering a follow control there would be offering something the server
 * cannot honour.
 */
class FollowRepositoryTest {
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

    private fun capabilities(follows: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.5.0"},
      "capabilities": {"follows": $follows},
      "limits": {}
    }
    """

    private suspend fun repository(follows: Boolean): TtsRoadRepository {
        val store = FakeFollowSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilities(follows))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `the library asks for the shelf by default`() = runTest {
        val repository = repository(follows = true)
        server.enqueue(json("""{"scope": "followed", "fictions": [], "following_ids": []}"""))

        repository.library()

        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("scope=followed"))
    }

    @Test
    fun `browse asks for everything`() = runTest {
        val repository = repository(follows = true)
        server.enqueue(
            json(
                """
                {"scope": "all", "following_ids": [1],
                 "fictions": [{"id": 1, "title": "Ashes", "following": true},
                              {"id": 2, "title": "Embers", "following": false}]}
                """,
            ),
        )

        val response = repository.library(LibraryScopeAll)

        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("scope=all"))
        assertEquals(LibraryScopeAll, response.scope)
        assertEquals(listOf(1), response.followingIds)
        assertTrue(response.fictions.first { it.id == 1 }.following)
        assertFalse(response.fictions.first { it.id == 2 }.following)
    }

    @Test
    fun `a server without follows is taken at its word about the scope it applied`() = runTest {
        // It ignores the parameter and answers the shared list. The response says `followed`, and
        // believing the request instead would have the browse screen claim to show everything.
        val repository = repository(follows = false)
        server.enqueue(json("""{"fictions": [{"id": 1, "title": "Ashes"}]}"""))

        val response = repository.library(LibraryScopeAll)

        assertEquals(LibraryScopeFollowed, response.scope)
        // Absent `following` defaults to true: on such a server every fiction is on the one list.
        assertTrue(response.fictions.first().following)
    }

    @Test
    fun `following a fiction posts and reports the result`() = runTest {
        val repository = repository(follows = true)
        server.enqueue(json("""{"status": "ok", "fiction_id": 5, "following": true}"""))

        assertEquals(true, repository.setFollowing(5, following = true))

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/fictions/5/follow", request.path)
    }

    @Test
    fun `unfollowing deletes`() = runTest {
        val repository = repository(follows = true)
        server.enqueue(json("""{"status": "ok", "fiction_id": 5, "following": false}"""))

        assertEquals(false, repository.setFollowing(5, following = false))

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/fictions/5/follow", request.path)
    }

    @Test
    fun `the server's answer wins over what was asked for`() = runTest {
        // Not hypothetical: the endpoint reports the resulting state, and trusting the request
        // would leave the toggle showing something the server disagrees with.
        val repository = repository(follows = true)
        server.enqueue(json("""{"status": "ok", "fiction_id": 5, "following": false}"""))

        assertEquals(false, repository.setFollowing(5, following = true))
    }

    @Test
    fun `following a fiction that no longer exists is not followed, not an error`() = runTest {
        // A 404 over mobile means the fiction is gone from the server. It cannot be followed, and
        // saying so is more useful than an error the user cannot act on.
        val repository = repository(follows = true)
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(false, repository.setFollowing(5, following = true))
    }

    @Test
    fun `an older server is never asked to follow anything`() = runTest {
        val repository = repository(follows = false)

        assertNull(repository.setFollowing(5, following = true))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}

/** The `follows` capability flag itself. */
class FollowCapabilityTest {

    @Test
    fun `follows is off at the baseline`() {
        assertFalse(ServerCapabilities.Baseline.follows)
    }

    @Test
    fun `only a literal true enables it`() {
        // Same rule as every other flag: a string or a number means the server is saying something
        // this client does not understand, and guessing would light up a control it cannot honour.
        assertTrue(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("follows" to true)),
            ).follows,
        )
        assertFalse(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("follows" to "true")),
            ).follows,
        )
        assertFalse(
            ServerCapabilities.from(CapabilitiesResponse(capabilities = emptyMap())).follows,
        )
    }
}
