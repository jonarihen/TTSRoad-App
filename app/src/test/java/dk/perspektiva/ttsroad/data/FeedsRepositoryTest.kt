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

private class FakeFeedsSessionStore(
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
 * Podcast feed URLs (#115) and the listening-state backup (#116).
 *
 * The distinction worth pinning hardest is between the two kinds of token: the account's combined
 * feed and OPML are the caller's own credential and rotating them is self-service, while a
 * fiction's feed token is shared by everyone subscribed to that fiction. They live behind different
 * routes and different gates, and swapping them would either refuse a rotation the user is entitled
 * to or hand out one that re-subscribes strangers.
 */
class FeedsRepositoryTest {
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

    private suspend fun repository(
        feedUrls: Boolean = true,
        listeningStateBackup: Boolean = true,
        fictionMaintenance: Boolean = true,
    ): TtsRoadRepository {
        val store = FakeFeedsSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """
                {"api_version": 1, "server": {"name": "TTSRoad"},
                 "capabilities": {"feed_urls": $feedUrls,
                                  "listening_state_backup": $listeningStateBackup,
                                  "fiction_maintenance": $fictionMaintenance},
                 "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `the feed list carries the account pair and a URL per fiction`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"scope": "followed",
                 "library": {"feed_token_version": 3,
                             "feed_url": "https://s/feed/all/tok3.xml",
                             "opml_url": "https://s/feed/opml/tok3.opml"},
                 "fictions": [{"fiction_id": 7, "title": "Ashes", "slug": "ashes",
                               "feed_token_version": 1,
                               "feed_url": "https://s/feed/ftok/ashes.xml"}]}
                """,
            ),
        )

        val feeds = repository.feeds()

        server.takeRequest()
        assertEquals("/api/mobile/feeds?scope=followed", server.takeRequest().path)
        assertEquals("https://s/feed/all/tok3.xml", feeds?.library?.feedUrl)
        assertEquals("https://s/feed/opml/tok3.opml", feeds?.library?.opmlUrl)
        assertEquals(7, feeds?.fictions?.single()?.fictionId)
        assertEquals("https://s/feed/ftok/ashes.xml", feeds?.fictions?.single()?.feedUrl)
    }

    /**
     * The fiction screen asks for the whole server, not the shelf. A fiction screen can be opened
     * from browse-all for a book this account does not follow, and the followed-only default would
     * answer nothing for exactly that case.
     */
    @Test
    fun `the scope is the caller's to choose`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"scope": "all", "library": {}, "fictions": []}"""))

        repository.feeds(scope = LibraryScopeAll)

        server.takeRequest()
        assertEquals("/api/mobile/feeds?scope=all", server.takeRequest().path)
    }

    @Test
    fun `rotating the account links answers with the new pair`() = runTest {
        // Adopted from the answer rather than re-fetching the whole feed list to read back two
        // strings that were just handed over.
        val repository = repository()
        server.enqueue(
            json(
                """{"status": "ok", "feed_token_version": 4,
                    "feed_url": "https://s/feed/all/tok4.xml",
                    "opml_url": "https://s/feed/opml/tok4.opml"}""",
            ),
        )

        val rotated = repository.rotateLibraryFeed()

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/feeds/rotate", request.path)
        assertEquals("POST", request.method)
        assertEquals(4, rotated?.feedTokenVersion)
        assertEquals("https://s/feed/all/tok4.xml", rotated?.feedUrl)
    }

    /**
     * A fiction's token is a different route with a different gate. It is shared by every account
     * subscribed to that fiction, which is why the server admin-gates it and leaves the account
     * pair above self-service.
     */
    @Test
    fun `rotating a fiction's token is a different route with a different gate`() = runTest {
        val repository = repository(feedUrls = true, fictionMaintenance = true)
        server.enqueue(
            json(
                """{"status": "ok", "fiction_id": 7, "feed_token_version": 2,
                    "feed_url": "https://s/feed/newtok/ashes.xml"}""",
            ),
        )

        val rotated = repository.rotateFictionFeedToken(7)

        server.takeRequest()
        assertEquals(
            "/api/mobile/fictions/7/feed-token/rotate",
            server.takeRequest().path,
        )
        assertEquals("https://s/feed/newtok/ashes.xml", rotated?.feedUrl)

        // Gated on fiction_maintenance, not feed_urls: a server can list feeds read-only.
        val listOnly = repository(feedUrls = true, fictionMaintenance = false)
        assertNull(listOnly.rotateFictionFeedToken(7))
    }

    /**
     * The document is never parsed into a typed model, in either direction. A backup from a server
     * newer than this build has to round-trip whole; projecting it onto known fields would trim it
     * silently, and the trimming would only be discovered when the restore was needed.
     */
    @Test
    fun `a listening-state document survives a round trip untouched`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"document": {"version": 2, "exported_at": "2026-08-25T00:00:00Z",
                              "a_key_this_build_has_never_heard_of": {"nested": [1, 2, 3]},
                              "positions": [{"chapter_id": 42, "position_seconds": 12.5}]}}
                """,
            ),
        )

        val document = repository.exportListeningState()

        server.takeRequest()
        assertEquals("/api/mobile/listening-state", server.takeRequest().path)
        assertTrue(document!!.containsKey("a_key_this_build_has_never_heard_of"))

        server.enqueue(json("""{"status": "ok", "report": {"positions_restored": 1}}"""))
        repository.importListeningState(document)

        val posted = server.takeRequest()
        assertEquals("POST", posted.method)
        val body = posted.body.readUtf8()
        // Wrapped, which is the shape the export handed over — the server takes it either way.
        assertTrue(body, body.startsWith("""{"document":"""))
        assertTrue(body, "a_key_this_build_has_never_heard_of" in body)
        assertTrue(body, "nested" in body)
    }

    @Test
    fun `the restore report comes back as the server's own numbers`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"status": "ok", "report": {"positions": 401, "bookmarks": 12}}"""),
        )

        val report = repository.importListeningState(mapOf("version" to 2))

        assertEquals(401.0, (report?.get("positions") as Number).toDouble(), 0.001)
    }

    @Test
    fun `an older server is asked none of it`() = runTest {
        val repository = repository(feedUrls = false, listeningStateBackup = false)

        assertNull(repository.feeds())
        assertNull(repository.rotateLibraryFeed())
        assertNull(repository.exportListeningState())
        assertNull(repository.importListeningState(mapOf("version" to 2)))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}

/** The two new flags. */
class FeedCapabilityTest {
    @Test
    fun `both are off at the baseline`() {
        assertFalse(ServerCapabilities.Baseline.feedUrls)
        assertFalse(ServerCapabilities.Baseline.listeningStateBackup)
    }

    @Test
    fun `only a literal true enables them`() {
        val loose = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("feed_urls" to "yes", "listening_state_backup" to 1.0),
            ),
        )
        assertFalse(loose.feedUrls)
        assertFalse(loose.listeningStateBackup)

        val strict = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("feed_urls" to true, "listening_state_backup" to true),
            ),
        )
        assertTrue(strict.feedUrls)
        assertTrue(strict.listeningStateBackup)
    }

    @Test
    fun `both are named in the capability panel`() {
        assertEquals("Podcast feed links", CapabilityCatalog.label("feed_urls"))
        assertEquals("Back up your progress", CapabilityCatalog.label("listening_state_backup"))
    }
}
