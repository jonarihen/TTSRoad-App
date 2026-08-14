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

/** In-memory [SessionStore] for the preference calls; named apart from the other fakes here. */
private class FakePreferenceSessionStore(
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

private fun capabilitiesPayload(playerPreferences: Boolean) = """
{
  "api_version": 1,
  "server": {"name": "TTSRoad", "version": "1.5.0"},
  "capabilities": {"player_preferences": $playerPreferences},
  "limits": {}
}
"""

/**
 * The preferences calls over the wire.
 *
 * The parts worth pinning are the ones a reader cannot check: that the client asks `/api/me/...`
 * and not `/api/mobile/...`, that PATCH carries only the changed key, and that the capability gate
 * stops the call from being made at all rather than merely ignoring its result.
 */
class AccountPreferencesRepositoryTest {
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

    private fun baseUrl(): String = server.url("/").toString()

    /** Signs in and discovers capabilities, leaving the repository ready for a preferences call. */
    private suspend fun signedInRepository(playerPreferences: Boolean): TtsRoadRepository {
        val store = FakePreferenceSessionStore()
        store.saveLogin(
            baseUrl(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilitiesPayload(playerPreferences))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `preferences are read from the shared account route, not a mobile one`() = runTest {
        val repository = signedInRepository(playerPreferences = true)
        server.enqueue(
            MockResponse()
                .setBody("""{"preferences": {"hide_played": true, "reader_theme": "sepia"}}""")
                .setHeader("Content-Type", "application/json"),
        )

        val prefs = repository.accountPreferences()

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        // The point of syncing these is that they are the same rows the browser reads.
        assertEquals("/api/me/preferences", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))
        assertEquals(true, prefs?.preferenceBool("hide_played"))
        assertEquals("sepia", prefs?.preferenceString("reader_theme"))
    }

    @Test
    fun `a patch sends only the key that changed`() = runTest {
        val repository = signedInRepository(playerPreferences = true)
        server.enqueue(
            MockResponse()
                .setBody("""{"preferences": {"hide_played": true}}""")
                .setHeader("Content-Type", "application/json"),
        )

        repository.updateAccountPreferences(chapterFilterPatch(ChapterFilter.Unplayed))

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/me/preferences", request.path)
        val body = request.body.readUtf8()
        assertEquals("""{"hide_played":true}""", body)
        // A phone back from a spell offline holds stale copies of everything it did not touch.
        assertTrue(body, "reader_theme" !in body)
        assertTrue(body, "playback_speed" !in body)
    }

    @Test
    fun `an older server is never asked at all`() = runTest {
        val repository = signedInRepository(playerPreferences = false)

        assertNull(repository.accountPreferences())
        assertNull(repository.updateAccountPreferences(chapterFilterPatch(ChapterFilter.Unplayed)))

        server.takeRequest() // capabilities
        // Not merely ignored: an older server answers /api/me/preferences happily and silently
        // drops every key it does not know, so a call would look like it worked.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an empty patch is not sent`() = runTest {
        val repository = signedInRepository(playerPreferences = true)

        assertNull(repository.updateAccountPreferences(emptyMap()))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failing preferences call answers null instead of throwing`() = runTest {
        // Every one of these settings has a working local value, so a failure here is not an error
        // the user can act on — and it must not take the screen reading it down.
        val repository = signedInRepository(playerPreferences = true)
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(repository.accountPreferences())
    }

    @Test
    fun `a 404 from a version skew is tolerated rather than crashing`() = runTest {
        val repository = signedInRepository(playerPreferences = true)
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(repository.accountPreferences())
    }
}
