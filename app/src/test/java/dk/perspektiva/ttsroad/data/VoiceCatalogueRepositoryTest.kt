package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class FakeVoiceSessionStore(
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

/** Capability gating and the exact authenticated request for `GET /api/mobile/voices`. */
class VoiceCatalogueRepositoryTest {
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

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend fun repository(voiceCatalogue: Boolean): TtsRoadRepository {
        val store = FakeVoiceSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "admin", isAdmin = true),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """{"api_version":1,"capabilities":{"voice_catalogue":$voiceCatalogue}}""",
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `an advertised catalogue is fetched with the bearer token and decoded`() = runTest {
        val repository = repository(voiceCatalogue = true)
        server.enqueue(
            json(
                """{"api_version":1,"voices":[
                    {"name":"en-US-BrianNeural","locale":"en-US","gender":"Male"}
                ]}""",
            ),
        )

        val voices = repository.voices()

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/voices", request.path)
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))
        assertEquals("en-US-BrianNeural", voices?.single()?.name)
    }

    @Test
    fun `a server without the capability is never probed for the route`() = runTest {
        val repository = repository(voiceCatalogue = false)

        assertNull(repository.voices())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an empty published catalogue is distinct from no catalogue`() = runTest {
        val repository = repository(voiceCatalogue = true)
        server.enqueue(json("""{"api_version":1,"voices":[]}"""))

        assertEquals(emptyList<MobileVoice>(), repository.voices())
        assertEquals(2, server.requestCount)
    }
}
