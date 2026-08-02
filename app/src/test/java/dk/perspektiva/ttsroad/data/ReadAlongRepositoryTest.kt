package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/** In-memory [SessionStore] for the read-along calls; named apart from the other fakes in this package. */
private class FakeReaderSessionStore(private var state: SessionState) : SessionStore {
    var clearTokenCalls = 0
        private set

    override suspend fun current(): SessionState = state

    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) {
        state = SessionState(
            serverUrl = normalizeBaseUrl(baseUrl),
            token = response.token,
            username = response.user.username,
        )
    }

    override suspend fun clearToken() {
        clearTokenCalls++
        state = state.copy(token = null)
    }
}

/** A [ReadAlongStore] that keeps entries in a map, standing in for the on-disk one. */
private class FakeReadAlongStore(
    private val entries: MutableMap<Int, CachedReadAlong> = mutableMapOf(),
) : ReadAlongStore {
    var writes = 0
        private set

    override fun read(chapterId: Int): CachedReadAlong? = entries[chapterId]

    override fun write(chapterId: Int, entry: CachedReadAlong) {
        writes++
        entries[chapterId] = entry
    }

    override fun clear() = entries.clear()

    fun seed(chapterId: Int, entry: CachedReadAlong) {
        entries[chapterId] = entry
    }

    val size: Int get() = entries.size
}

private const val ChapterBody = """
{
  "api_version": 1,
  "chapter": {"id":10,"fiction_id":1,"title":"Chapter 1","chapter_number":1,
              "audio_duration":1420.5,"has_timings":true,"timing_version":1},
  "text": "The knight rode north.\n\nSnow fell on the pass.",
  "paragraphs": [[0,22],[24,45]],
  "cues": [[0,3,0.0],[4,10,0.42],[11,15,0.98]]
}
"""

private const val RevisedBody = """
{
  "api_version": 1,
  "chapter": {"id":10,"fiction_id":1,"title":"Chapter 1","audio_duration":1500.0,"has_timings":true},
  "text": "The knight rode south.",
  "paragraphs": [[0,22]],
  "cues": [[0,3,0.0],[4,10,0.50]]
}
"""

/**
 * The read-along fetch: conditional requests, the 304 path, and the outcomes that are not errors.
 *
 * Chapter text never changes after conversion, so the ETag should hold approximately forever — which
 * makes the 304 path the *normal* one for any chapter re-opened, not an edge case.
 */
class ReadAlongRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    private fun store() = FakeReaderSessionStore(
        SessionState(serverUrl = server.url("/").toString(), token = "session-token", username = "admin"),
    )

    private fun repository(
        sessionStore: SessionStore = store(),
        readAlongStore: ReadAlongStore = FakeReadAlongStore(),
    ) = TtsRoadRepository(sessionStore, readAlongStore = readAlongStore)

    @Test
    fun `a read-along document is fetched and parsed`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))

        val document = repository.readAlong(chapterId = 10)

        assertNotNull(document)
        assertEquals(10, document!!.chapterId)
        assertEquals(3, document.cues.size)
        assertTrue(document.hasTimings)
        assertEquals("/api/mobile/chapters/10/readalong", server.takeRequest().path)
    }

    @Test
    fun `the request goes to the signed-in host with the session token`() = runTest {
        // Never built from a server-supplied base URL: that is the mistake that made every cover
        // render as a black tile in 0.7.0, and it would 404 the reader the same way.
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))

        repository.readAlong(chapterId = 10)

        val request = server.takeRequest()
        assertEquals("Bearer session-token", request.getHeader("Authorization"))
        assertEquals(server.hostName, request.requestUrl!!.host)
        assertEquals(server.port, request.requestUrl!!.port)
    }

    @Test
    fun `the first fetch stores the ETag and the next one revalidates with it`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        repository.readAlong(chapterId = 10)

        server.enqueue(MockResponse().setResponseCode(304))
        repository.readAlong(chapterId = 10)

        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a 304 reuses the cached document without re-parsing it`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        val first = repository.readAlong(chapterId = 10)

        server.enqueue(MockResponse().setResponseCode(304))
        val second = repository.readAlong(chapterId = 10)

        assertSame("a 304 must hand back the very same document, not an equal copy", first, second)
    }

    @Test
    fun `a changed ETag replaces the cached document`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        val first = repository.readAlong(chapterId = 10)

        server.enqueue(MockResponse().setBody(RevisedBody).setHeader("ETag", "\"def\""))
        val second = repository.readAlong(chapterId = 10)

        assertEquals("The knight rode north.\n\nSnow fell on the pass.", first!!.text)
        assertEquals("The knight rode south.", second!!.text)

        // And the new ETag, not the old one, is what the next request revalidates with.
        server.enqueue(MockResponse().setResponseCode(304))
        repository.readAlong(chapterId = 10)
        server.takeRequest()
        server.takeRequest()
        assertEquals("\"def\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a 404 means this chapter has no read-along, which is not an error`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(404))

        val document = repository.readAlong(chapterId = 10)

        assertNull(document)
        assertFalse("a missing read-along must not look like an expired session", repository.sessionExpired.value)
    }

    @Test
    fun `a 401 still expires the session`() = runTest {
        val sessionStore = store()
        val repository = repository(sessionStore)
        server.enqueue(MockResponse().setResponseCode(401))

        val thrown = runCatching { repository.readAlong(chapterId = 10) }.exceptionOrNull()

        assertTrue(thrown is HttpException)
        assertEquals(401, (thrown as HttpException).code())
        assertEquals(1, sessionStore.clearTokenCalls)
        assertTrue(repository.sessionExpired.value)
    }

    @Test
    fun `a server error surfaces when there is nothing cached to fall back to`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = runCatching { repository.readAlong(chapterId = 10) }.exceptionOrNull()

        assertTrue("the reader has nothing to show, so it must say so", thrown != null)
    }

    @Test
    fun `a chapter read once still reads with no network`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        val online = repository.readAlong(chapterId = 10)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val offline = repository.readAlong(chapterId = 10)

        assertSame(online, offline)
    }

    @Test
    fun `a server error falls back to the cached document rather than blanking the reader`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        val online = repository.readAlong(chapterId = 10)

        server.enqueue(MockResponse().setResponseCode(503))
        assertSame(online, repository.readAlong(chapterId = 10))
    }

    @Test
    fun `a fetched document is persisted for the next launch`() = runTest {
        val disk = FakeReadAlongStore()
        val repository = repository(readAlongStore = disk)
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))

        repository.readAlong(chapterId = 10)

        assertEquals(1, disk.writes)
        assertEquals("\"abc\"", disk.read(10)!!.etag)
    }

    @Test
    fun `a document persisted by a previous launch is read back and revalidated`() = runTest {
        val disk = FakeReadAlongStore()
        val seeded = TtsRoadRepository(store(), readAlongStore = disk)
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        seeded.readAlong(chapterId = 10)

        // A fresh repository, as after a process restart: nothing in memory, everything on disk.
        val restarted = TtsRoadRepository(store(), readAlongStore = disk)
        server.enqueue(MockResponse().setResponseCode(304))
        val document = restarted.readAlong(chapterId = 10)

        assertNotNull(document)
        assertEquals(3, document!!.cues.size)
        server.takeRequest()
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `a persisted document reads offline with no request possible at all`() = runTest {
        val disk = FakeReadAlongStore()
        val seeded = TtsRoadRepository(store(), readAlongStore = disk)
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        seeded.readAlong(chapterId = 10)

        val restarted = TtsRoadRepository(store(), readAlongStore = disk)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val document = restarted.readAlong(chapterId = 10)

        assertNotNull("a chapter already read must not need the network again", document)
        assertEquals("The knight rode north.\n\nSnow fell on the pass.", document!!.text)
    }

    @Test
    fun `different chapters are cached separately, each with its own ETag`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        val ten = repository.readAlong(chapterId = 10)
        server.enqueue(MockResponse().setBody(RevisedBody).setHeader("ETag", "\"xyz\""))
        val eleven = repository.readAlong(chapterId = 11)

        assertEquals("The knight rode north.\n\nSnow fell on the pass.", ten!!.text)
        assertEquals("The knight rode south.", eleven!!.text)

        // Revalidating chapter 11 must present chapter 11's tag, not the one cached for chapter 10.
        server.enqueue(MockResponse().setResponseCode(304))
        assertSame(eleven, repository.readAlong(chapterId = 11))

        server.takeRequest()
        assertEquals("/api/mobile/chapters/11/readalong", server.takeRequest().path)
        val revalidation = server.takeRequest()
        assertEquals("/api/mobile/chapters/11/readalong", revalidation.path)
        assertEquals("\"xyz\"", revalidation.getHeader("If-None-Match"))
    }

    @Test
    fun `signing out drops every cached chapter, on disk and in memory`() = runTest {
        val disk = FakeReadAlongStore()
        val repository = repository(readAlongStore = disk)
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        repository.readAlong(chapterId = 10)
        assertEquals(1, disk.size)

        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))
        repository.logout()

        assertEquals("the next account must not read the previous one's chapters", 0, disk.size)
    }

    @Test
    fun `a chapter cached under a previous ETag is not served after signing out`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setBody(ChapterBody).setHeader("ETag", "\"abc\""))
        repository.readAlong(chapterId = 10)
        server.enqueue(MockResponse().setBody("""{"status":"ok","revoked":true}"""))
        repository.logout()

        // Signed out, there is no session to authorise with, so the call cannot silently succeed
        // from a stale cache.
        val thrown = runCatching { repository.readAlong(chapterId = 10) }.exceptionOrNull()
        assertNotNull(thrown)
    }
}
