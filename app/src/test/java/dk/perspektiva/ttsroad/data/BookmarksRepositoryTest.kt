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

private class FakeBookmarkSessionStore(
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
 * The bookmark calls over the wire.
 *
 * The interesting parts are the ones a reader cannot verify: that only `manual` marks are asked
 * for, that a partial update does not send fields it was not given, and that the capability gate
 * stops the call being made at all.
 */
class BookmarksRepositoryTest {
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

    private fun capabilities(bookmarks: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.5.0"},
      "capabilities": {"bookmarks": $bookmarks},
      "limits": {}
    }
    """

    private suspend fun repository(bookmarks: Boolean): TtsRoadRepository {
        val store = FakeBookmarkSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilities(bookmarks))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `the list asks for manual marks only`() = runTest {
        val repository = repository(bookmarks = true)
        server.enqueue(
            json(
                """
                {"bookmarks": [{"id": 3, "chapter_id": 7, "position_seconds": 61.5,
                                "position_label": "1:01", "label": "the lighthouse",
                                "chapter_title": "Ch 7", "fiction_title": "Ashes"}],
                 "deleted": []}
                """,
            ),
        )

        val bookmarks = repository.bookmarks()

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        // The `auto` rows in the same table are the web player's jump-back breadcrumbs; a list of
        // chosen marks drowned in them would be useless.
        assertTrue(request.path!!, "kind=manual" in request.path!!)
        assertEquals(1, bookmarks?.size)
        assertEquals("the lighthouse", bookmarks?.first()?.label)
        assertEquals("Ashes", bookmarks?.first()?.fictionTitle)
    }

    @Test
    fun `a created bookmark is sent as a manual one`() = runTest {
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"bookmark": {"id": 9, "chapter_id": 7, "position_seconds": 30.0}}"""))

        val created = repository.createBookmark(chapterId = 7, positionSeconds = 30.0)

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"kind":"manual"""" in body)
        assertTrue(body, """"chapter_id":7""" in body)
        assertEquals(9, created?.id)
    }

    @Test
    fun `a blank label is not sent as an empty string`() = runTest {
        // The server trims and stores null for an empty string, so sending one would be a write
        // that says nothing. Omitting it lets the server keep its own default.
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"bookmark": {"id": 9, "chapter_id": 7}}"""))

        repository.createBookmark(chapterId = 7, positionSeconds = 1.0, label = "   ")

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"label"""" !in body)
    }

    @Test
    fun `a negative position cannot be sent`() = runTest {
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"bookmark": {"id": 9, "chapter_id": 7}}"""))

        repository.createBookmark(chapterId = 7, positionSeconds = -12.0)

        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains(""""position_seconds":0"""))
    }

    @Test
    fun `a partial update leaves untouched fields out of the body`() = runTest {
        // The server checks key presence, so a key that is absent leaves the stored value alone.
        // Sending nulls for everything would wipe the note whenever the label was renamed.
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"bookmark": {"id": 9, "chapter_id": 7, "label": "new"}}"""))

        repository.updateBookmark(bookmarkId = 9, label = "new", note = null)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/mobile/bookmarks/9", request.path)
        val body = request.body.readUtf8()
        assertEquals("""{"label":"new"}""", body)
    }

    @Test
    fun `deleting reports success`() = runTest {
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"status": "deleted", "id": 9}"""))

        assertTrue(repository.deleteBookmark(9))

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/bookmarks/9", request.path)
    }

    @Test
    fun `deleting something already gone is not an error`() = runTest {
        // Deleted from the browser, or a double tap. The caller got the outcome it asked for.
        val repository = repository(bookmarks = true)
        server.enqueue(MockResponse().setResponseCode(404))

        assertTrue(repository.deleteBookmark(9))
    }

    @Test
    fun `an older server is never asked`() = runTest {
        val repository = repository(bookmarks = false)

        assertNull(repository.bookmarks())
        assertNull(repository.createBookmark(chapterId = 7, positionSeconds = 1.0))
        assertNull(repository.updateBookmark(9, "x", null))
        assertTrue(!repository.deleteBookmark(9))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `null and empty mean different things`() = runTest {
        // Null is "this server cannot do bookmarks", which hides the UI. Empty is "none yet".
        val repository = repository(bookmarks = true)
        server.enqueue(json("""{"bookmarks": [], "deleted": []}"""))

        assertEquals(emptyList<Bookmark>(), repository.bookmarks())
    }

    @Test
    fun `a bookmark whose chapter is gone still parses`() = runTest {
        // The server returns nulls for the joined titles when the chapter has been removed. One
        // such row must not take the whole list down with it.
        val repository = repository(bookmarks = true)
        server.enqueue(
            json(
                """
                {"bookmarks": [{"id": 3, "chapter_id": 7, "position_seconds": 0,
                                "label": null, "chapter_title": null, "fiction_title": null,
                                "chapter_number": null}],
                 "deleted": []}
                """,
            ),
        )

        val bookmarks = repository.bookmarks()

        assertEquals(1, bookmarks?.size)
        assertEquals("Bookmark", bookmarks?.first()?.resolvedLabel)
    }
}

/** The label fallback chain, which is what stops a row rendering as blank. */
class BookmarkLabelTest {

    @Test
    fun `an explicit label wins`() {
        val bookmark = Bookmark(id = 1, label = "the lighthouse", chapterTitle = "Ch 7")
        assertEquals("the lighthouse", bookmark.resolvedLabel)
    }

    @Test
    fun `the chapter title stands in for a missing label`() {
        assertEquals("Ch 7", Bookmark(id = 1, label = null, chapterTitle = "Ch 7").resolvedLabel)
    }

    @Test
    fun `a blank label is treated as absent`() {
        assertEquals("Ch 7", Bookmark(id = 1, label = "   ", chapterTitle = "Ch 7").resolvedLabel)
    }

    @Test
    fun `there is always something to show`() {
        assertEquals("Bookmark", Bookmark(id = 1).resolvedLabel)
        assertEquals("Bookmark", Bookmark(id = 1, label = "", chapterTitle = "").resolvedLabel)
    }

    @Test
    fun `a bookmark defaults to a manual one`() {
        assertEquals(BookmarkKindManual, Bookmark(id = 1).kind)
    }
}
