package dk.perspektiva.ttsroad.data

import java.io.ByteArrayInputStream
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
import retrofit2.HttpException

/**
 * Importing a book from the phone, over `POST /api/mobile/fictions/upload-epub`.
 *
 * Most of the weight is on what does *not* happen. A server that does not advertise `epub_upload`
 * is never asked — the route is admin-only and multipart, and probing for it is not free. A book
 * past the advertised ceiling never reaches the wire at all, which is the entire reason
 * `max_epub_bytes` is published: the alternative is pushing a hundred megabytes up a mobile
 * connection to be told 413.
 *
 * The rest is the envelope. The part has to be called `file` or the server answers 422 with nothing
 * a user could act on, and a refusal has to keep the server's own words — "This EPUB has already
 * been uploaded" is the server recognising the book by content hash, which is an answer rather than
 * an error to retry.
 */
class EpubUploadRepositoryTest {
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

    private fun capabilities(epubUpload: Boolean, maxEpubBytes: Long? = null): String {
        val limits = maxEpubBytes?.let { """"max_epub_bytes": $it""" }.orEmpty()
        return """
        {
          "api_version": 1,
          "server": {"name": "TTSRoad", "version": "1.9.0"},
          "capabilities": {"epub_upload": $epubUpload},
          "limits": {$limits}
        }
        """
    }

    private suspend fun repository(
        epubUpload: Boolean = true,
        maxEpubBytes: Long? = null,
    ): TtsRoadRepository {
        val store = FakeSessionStore(SessionState())
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "admin", isAdmin = true),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(json(capabilities(epubUpload, maxEpubBytes)))
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setBody(body)
        .setHeader("Content-Type", "application/json")

    private fun book(
        filename: String = "Ashfall.epub",
        sizeBytes: Long? = 2048L,
        bytes: ByteArray = ByteArray(2048) { it.toByte() },
    ) = PickedEpub.Ready(filename, sizeBytes) { ByteArrayInputStream(bytes) }

    @Test
    fun `the book is posted to the upload route as multipart, in a part named file`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "status": "ok",
                    "fiction": {"id": 12, "title": "Ashfall", "source_type": "epub"}}""",
                code = 201,
            ),
        )

        val result = repository.uploadEpub(book())

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/upload-epub", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        // The server looks for this exact part name; anything else is a 422 it cannot explain.
        assertTrue(body.contains("""name="file""""))
        // And this exact extension: `upload_epub` validates the filename and never the type.
        assertTrue(body.contains("""filename="Ashfall.epub""""))
        assertTrue(body.contains("Content-Type: application/epub+zip"))
        assertEquals("Ashfall", (result as FictionAddResult.Added).fiction?.title)
    }

    @Test
    fun `the header that buys the upload a longer clock never reaches the server`() = runTest {
        // It is a note to this app's own interceptor — the client's 60-second read timeout is sized
        // for JSON, and the server parses a whole book before it answers.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 12, "title": "Ashfall"}}""", 201))

        repository.uploadEpub(book())

        server.takeRequest()
        assertNull(server.takeRequest().getHeader(SlowUploadHeader))
    }

    @Test
    fun `a book over the server's advertised ceiling never leaves the phone`() = runTest {
        // The whole point of `max_epub_bytes`: the server would answer 413, but only after the
        // bytes had been pushed up a mobile connection.
        val repository = repository(maxEpubBytes = 4L * 1024 * 1024)

        val result = repository.uploadEpub(book(sizeBytes = 8L * 1024 * 1024))

        assertTrue(result.toString(), result is FictionAddResult.Refused)
        assertTrue((result as FictionAddResult.Refused).message.contains("4 MB"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the advertised ceiling wins over this client's assumed one`() = runTest {
        // A file that fits the 100 MB default and not the 4 MB this server published has to be
        // refused against the server's own number.
        val repository = repository(maxEpubBytes = 4L * 1024 * 1024)

        val result = repository.uploadEpub(book(sizeBytes = 40L * 1024 * 1024))

        assertTrue(result is FictionAddResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server that publishes no ceiling still gets the documented one applied`() = runTest {
        val repository = repository(maxEpubBytes = null)

        val result = repository.uploadEpub(book(sizeBytes = DefaultMaxEpubBytes + 1))

        assertTrue(result is FictionAddResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a file the server would refuse on its name is refused before the request`() = runTest {
        val repository = repository()

        val result = repository.uploadEpub(book(filename = "Ashfall.mobi"))

        assertTrue(result is FictionAddResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server without the capability is never asked`() = runTest {
        val repository = repository(epubUpload = false)

        assertEquals(FictionAddResult.Unsupported, repository.uploadEpub(book()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a duplicate book keeps the server's own explanation`() = runTest {
        // 409, from the content hash: the server has seen these exact bytes before. Telling someone
        // "already uploaded" is a different instruction to "that failed, try again".
        val repository = repository()
        server.enqueue(json("""{"detail": "This EPUB has already been uploaded"}""", code = 409))

        val result = repository.uploadEpub(book())

        assertEquals(
            "This EPUB has already been uploaded",
            (result as FictionAddResult.Refused).message,
        )
    }

    @Test
    fun `a book the server cannot parse keeps its explanation too`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Could not parse EPUB: bad zip"}""", code = 400))

        val result = repository.uploadEpub(book())

        assertEquals("Could not parse EPUB: bad zip", (result as FictionAddResult.Refused).message)
    }

    @Test
    fun `a server that advertised the route and then 404s is reported as unable`() = runTest {
        // Advertised but absent means the flag and the routes disagree — a proxy in front, or a
        // server downgraded under a running app. Not something the user can correct by retrying.
        val repository = repository()
        server.enqueue(json("""{"detail": "Not Found"}""", code = 404))

        assertEquals(FictionAddResult.Unsupported, repository.uploadEpub(book()))
    }

    @Test
    fun `an expired session is rethrown rather than shown as a refusal`() = runTest {
        // 401 is the one code this must not swallow: the app signs out on it, and reporting it as
        // "the server would not accept that book" would leave the session half dead.
        val repository = repository()
        server.enqueue(json("""{"detail": "Not authenticated"}""", code = 401))

        val thrown = runCatching { repository.uploadEpub(book()) }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is HttpException)
        assertEquals(401, (thrown as HttpException).code())
    }

    @Test
    fun `the capability is off by default so an older server hides the button`() {
        assertFalse(ServerCapabilities.Baseline.epubUpload)
        assertEquals(DefaultMaxEpubBytes, ServerCapabilities.Baseline.effectiveMaxEpubBytes)
    }
}
