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

private class FakeFictionSessionStore(
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

/**
 * Adding, editing and deleting fictions over the mobile mirror of `/api/fictions`.
 *
 * Two things carry most of the weight here. First, that a refusal keeps the server's own words: it
 * is the half that knows which sites have adapters, and "already tracked" is a different instruction
 * to the user than "that is not a URL I can read". Second, that a server without the capability is
 * never called at all — these routes destroy shared data, and probing for them is not free.
 *
 * The editing half adds a third: what goes on the wire is exactly what the caller asked to change.
 * A PATCH marks every field it sets as hand-edited and the server stops refreshing it from the
 * source afterwards, so an extra field in the body is not waste — it is a fiction quietly frozen
 * against its own updates.
 */
class FictionManagementRepositoryTest {
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

    private fun capabilities(fictionManagement: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.6.0"},
      "capabilities": {"fiction_management": $fictionManagement},
      "limits": {}
    }
    """

    private suspend fun repository(fictionManagement: Boolean = true): TtsRoadRepository {
        val store = FakeFictionSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "admin", isAdmin = true),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(json(capabilities(fictionManagement)))
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `adding posts the url to the mobile route and reports the tracked fiction`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "status": "ok",
                    "fiction": {"id": 7, "title": "Ashfall", "total_chapters": 0}}""",
                code = 201,
            ),
        )

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"fiction_url":"https://www.royalroad.com/fiction/12345"}""",
            request.body.readUtf8(),
        )
        assertTrue(result is FictionAddResult.Added)
        assertEquals("Ashfall", (result as FictionAddResult.Added).fiction?.title)
    }

    @Test
    fun `a surrounding space in a pasted url is trimmed rather than rejected`() = runTest {
        // Pasting from a browser share sheet routinely brings whitespace with it, and the server's
        // URL validator would refuse the untrimmed string.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}""", 201))

        repository.addFiction("  https://www.royalroad.com/fiction/12345\n")

        server.takeRequest()
        assertEquals(
            """{"fiction_url":"https://www.royalroad.com/fiction/12345"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `the server's reason for refusing a url is what the caller gets`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Not a supported fiction site"}""", code = 400))

        val result = repository.addFiction("https://example.com/not-a-fiction")

        assertTrue(result is FictionAddResult.Refused)
        assertEquals("Not a supported fiction site", (result as FictionAddResult.Refused).message)
    }

    @Test
    fun `a fiction already tracked is a refusal with the server's words, not a crash`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Fiction already tracked"}""", code = 409))

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertEquals("Fiction already tracked", (result as FictionAddResult.Refused).message)
    }

    @Test
    fun `a refusal with an unreadable body still says something useful`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>nope</html>"))

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertTrue(result is FictionAddResult.Refused)
        assertTrue((result as FictionAddResult.Refused).message.isNotBlank())
    }

    @Test
    fun `a blank url never reaches the server`() = runTest {
        val repository = repository()

        val result = repository.addFiction("   ")

        assertTrue(result is FictionAddResult.Refused)
        // Only the capabilities call was made.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server without fiction management is not asked to add`() = runTest {
        val repository = repository(fictionManagement = false)

        val result = repository.addFiction("https://www.royalroad.com/fiction/12345")

        assertEquals(FictionAddResult.Unsupported, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `deleting sends DELETE to the mobile route and believes the body`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"api_version": 1, "status": "ok", "fiction_id": 7, "deleted": true}"""),
        )

        val deleted = repository.deleteFiction(7)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/7", request.path)
        assertEquals("DELETE", request.method)
        assertEquals(true, deleted)
    }

    @Test
    fun `deleting something already gone counts as deleted`() = runTest {
        // From the browser, or a second tap on a list that has not refreshed. The caller wanted the
        // fiction gone and it is gone; reporting an error would be reporting the wrong thing.
        val repository = repository()
        server.enqueue(json("""{"detail": "Fiction not found"}""", code = 404))

        assertEquals(true, repository.deleteFiction(7))
    }

    @Test
    fun `a server without fiction management is not asked to delete`() = runTest {
        val repository = repository(fictionManagement = false)

        assertNull(repository.deleteFiction(7))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `editing patches the mobile route with only the fields it was handed`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"api_version": 1, "status": "ok",
                    "fiction": {"id": 7, "title": "Ashfall", "metadata_overrides": ["title"]}}""",
            ),
        )

        val result = repository.updateFiction(7, FictionUpdateRequest(title = "Ashfall"))

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/7", request.path)
        assertEquals("PATCH", request.method)
        // Null fields are omitted, which is what tells the server to leave them alone.
        assertEquals("""{"title":"Ashfall"}""", request.body.readUtf8())
        assertTrue(result is FictionEditResult.Saved)
        assertEquals(
            listOf("title"),
            (result as FictionEditResult.Saved).fiction?.metadataOverrides,
        )
    }

    @Test
    fun `editing a narrator sends the voice and rate the conversion pipeline expects`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"status": "ok", "fiction": {"id": 7, "title": "Ashfall",
                     "voice": "en-US-BrianNeural", "rate": "+15%"}}""",
            ),
        )

        val result = repository.updateFiction(
            7,
            FictionUpdateRequest(voice = "en-US-BrianNeural", rate = "+15%"),
        )

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals(
            """{"voice":"en-US-BrianNeural","rate":"+15%"}""",
            request.body.readUtf8(),
        )
        assertEquals("en-US-BrianNeural", (result as FictionEditResult.Saved).fiction?.voice)
        assertEquals("+15%", result.fiction?.rate)
    }

    @Test
    fun `an emptied field is sent as an empty string rather than dropped`() = runTest {
        // "" clears the value server-side; omitting the key leaves it alone. The difference is the
        // difference between clearing an author and failing to.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}"""))

        repository.updateFiction(7, FictionUpdateRequest(author = "", description = ""))

        server.takeRequest()
        assertEquals("""{"author":"","description":""}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `tags go up as a list, and an empty list is how they are cleared`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}"""))

        repository.updateFiction(7, FictionUpdateRequest(tags = emptyList()))

        server.takeRequest()
        assertEquals("""{"tags":[]}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `handing fields back to the source sends clear_overrides and nothing else`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall", "metadata_overrides": []}}"""),
        )

        val result = repository.updateFiction(
            7,
            FictionUpdateRequest(clearOverrides = listOf("description", "tags")),
        )

        server.takeRequest()
        assertEquals(
            """{"clear_overrides":["description","tags"]}""",
            server.takeRequest().body.readUtf8(),
        )
        assertEquals(
            emptyList<String>(),
            (result as FictionEditResult.Saved).fiction?.metadataOverrides,
        )
    }

    @Test
    fun `the server's reason for refusing an edit is what the caller gets`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Title must not be empty"}""", code = 400))

        val result = repository.updateFiction(7, FictionUpdateRequest(title = "   "))

        assertEquals("Title must not be empty", (result as FictionEditResult.Refused).message)
    }

    @Test
    fun `a server without fiction management is not asked to edit`() = runTest {
        val repository = repository(fictionManagement = false)

        assertEquals(
            FictionEditResult.Unsupported,
            repository.updateFiction(7, FictionUpdateRequest(title = "Ashfall")),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an older server echoes a fiction with no overrides, which is how it is recognised`() = runTest {
        // It accepts the description, drops it, and answers exactly as a newer server would. The
        // absent `metadata_overrides` key is the only thing that separates the two.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}"""))

        val result = repository.updateFiction(7, FictionUpdateRequest(description = "A city under ash."))

        val fiction = (result as FictionEditResult.Saved).fiction
        assertNull(fiction?.metadataOverrides)
        assertEquals(false, fiction?.supportsMetadataEditing)
    }

    @Test
    fun `a cover is posted as multipart to the cover route, in a part named file`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall",
                     "cover_image_url": "/cover/abc123.jpg"}}"""),
        )

        val result = repository.uploadFictionCover(7, byteArrayOf(1, 2, 3), "image/jpeg")

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/fictions/7/cover", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        // The server looks for this exact part name; anything else is a 422 it cannot explain.
        assertTrue(body.contains("""name="file""""))
        assertTrue(body.contains("""filename="cover.jpg""""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertEquals(
            "/cover/abc123.jpg",
            (result as FictionEditResult.Saved).fiction?.coverImageUrl,
        )
    }

    @Test
    fun `the part filename follows the image type`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction": {"id": 7, "title": "Ashfall"}}"""))

        repository.uploadFictionCover(7, byteArrayOf(9), "image/png")

        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("""filename="cover.png""""))
    }

    @Test
    fun `a server that predates cover uploads is reported as unable, not as a failure`() = runTest {
        // 404 on this route means the backend has no cover endpoint: the fiction was loaded a
        // moment ago and the PATCH route shares its id space.
        val repository = repository()
        server.enqueue(json("""{"detail": "Not Found"}""", code = 404))

        assertEquals(
            FictionEditResult.Unsupported,
            repository.uploadFictionCover(7, byteArrayOf(1), "image/jpeg"),
        )
    }

    @Test
    fun `an image the server refuses keeps the server's explanation`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"detail": "Cover must be an image"}""", code = 400))

        val result = repository.uploadFictionCover(7, byteArrayOf(1), "image/jpeg")

        assertEquals("Cover must be an image", (result as FictionEditResult.Refused).message)
    }

    @Test
    fun `an oversized image never leaves the phone`() = runTest {
        // The server answers 413, but only after the bytes have been pushed up a mobile connection.
        val repository = repository()

        val result = repository.uploadFictionCover(
            7,
            ByteArray((MaxCoverUploadBytes + 1).toInt()),
            "image/jpeg",
        )

        assertTrue(result is FictionEditResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a file that is not an image is refused before the request`() = runTest {
        val repository = repository()

        val result = repository.uploadFictionCover(7, byteArrayOf(1), "application/pdf")

        assertTrue(result is FictionEditResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a server without fiction management is not asked for a cover either`() = runTest {
        val repository = repository(fictionManagement = false)

        assertEquals(
            FictionEditResult.Unsupported,
            repository.uploadFictionCover(7, byteArrayOf(1), "image/jpeg"),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the capability is off by default so an older server hides the controls`() {
        assertEquals(false, ServerCapabilities.Baseline.fictionManagement)
    }

    @Test
    fun `only a literal true turns fiction management on`() {
        val response = CapabilitiesResponse(capabilities = mapOf("fiction_management" to "yes"))
        assertEquals(false, ServerCapabilities.from(response).fictionManagement)
    }
}
