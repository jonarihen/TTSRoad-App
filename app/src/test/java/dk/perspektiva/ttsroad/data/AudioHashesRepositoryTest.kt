package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class FakeAudioHashSessionStore(
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
 * `GET /api/mobile/fictions/{id}/audio-hashes` over the wire (#109).
 *
 * The parts worth pinning are the ones a reader cannot check: that the capability gate stops the
 * request being made at all, that a failure is an ordinary null rather than an exception on a
 * screen the user opened to read a book, and that a null `audio_sha256` survives parsing as a null
 * — because everything downstream treats that as "unknown" and treating it as "" would make it a
 * difference from every recorded hash.
 */
class AudioHashesRepositoryTest {
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

    private suspend fun repository(audioContentHash: Boolean): TtsRoadRepository {
        val store = FakeAudioHashSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {"api_version": 1, "server": {"name": "TTSRoad"},
                     "capabilities": {"audio_content_hash": $audioContentHash}, "limits": {}}
                    """,
                )
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `hashes are read off the fiction's own endpoint`() = runTest {
        val repository = repository(audioContentHash = true)
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {"api_version": 1, "fiction_id": 12, "total": 2, "chapters": [
                      {"chapter_id": 5, "audio_sha256": "aaa", "audio_filesize": 1024},
                      {"chapter_id": 6, "audio_sha256": null, "audio_filesize": 0}
                    ]}
                    """,
                )
                .setHeader("Content-Type", "application/json"),
        )

        val response = repository.audioHashes(12)

        server.takeRequest() // capabilities
        assertEquals(
            "/api/mobile/fictions/12/audio-hashes",
            server.takeRequest().path,
        )
        assertEquals(2, response?.chapters?.size)
        assertEquals("aaa", response?.chapters?.first()?.audioSha256)
        // The distinction the whole feature rests on: unknown, not "".
        assertNull(response?.chapters?.last()?.audioSha256)
    }

    @Test
    fun `a server without the capability is never asked`() = runTest {
        val repository = repository(audioContentHash = false)

        assertNull(repository.audioHashes(12))

        server.takeRequest() // capabilities
        assertEquals(
            "the route does not exist on this server, so asking is a 404 and a wasted round trip",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `a failed request is an ordinary null, not an exception`() = runTest {
        val repository = repository(audioContentHash = true)
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(repository.audioHashes(12))
    }
}
