package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a refresh actually spends once the server advertises `delta_sync` (#110).
 *
 * The merge rules are unit-tested next door; this is the orchestration around them — which cursor
 * is sent where, and what is skipped entirely when the index says nothing moved.
 *
 * [LibraryCache] launches onto `Dispatchers.Main.immediate` but the repository hops to
 * `Dispatchers.IO` for the call itself, so a refresh is genuinely asynchronous and asserting
 * straight after [LibraryCache.refreshLibrary] would race it. Every test therefore waits for the
 * refresh flag to fall before reading anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCacheDeltaTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        // Unconfined rather than a test dispatcher: the continuation comes back from a real IO
        // thread, and a scheduler this test does not drive would leave it queued forever.
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun enqueueCapabilities(deltaSync: Boolean) = server.enqueue(
        json("""{"api_version":1,"capabilities":{"delta_sync":$deltaSync},"limits":{}}"""),
    )

    private fun enqueueLibrary(serverTime: String, delta: Boolean = false, fictions: String = "") =
        server.enqueue(
            json(
                """
                {"scope":"followed","following_ids":[7],"fictions":[$fictions],
                 "continue_listening":[],"recent_chapters":[],
                 "server_time":"$serverTime","delta":$delta,"deleted":[]}
                """.trimIndent(),
            ),
        )

    private fun enqueueChapters(serverTime: String, delta: Boolean = false, chapters: String = "") =
        server.enqueue(
            json(
                """
                {"fiction":{"id":7,"title":"Book"},"chapters":[$chapters],
                 "server_time":"$serverTime","delta":$delta,"deleted":[]}
                """.trimIndent(),
            ),
        )

    private fun enqueueSyncIndex(serverTime: String, libraryChanged: Boolean, fictions: String) =
        server.enqueue(
            json(
                """
                {"server_time":"$serverTime","delta":true,
                 "changed":{"library":$libraryChanged,"fictions":[$fictions],
                            "playback":0,"bookmarks":0},
                 "deleted":{"fictions":[],"chapters":[],"bookmarks":[]}}
                """.trimIndent(),
            ),
        )

    private fun cache(deltaSync: Boolean = true): LibraryCache = runBlocking {
        val repository = TtsRoadRepository(
            FakeSessionStore(
                SessionState(serverUrl = server.url("/").toString(), token = "token"),
            ),
        )
        enqueueCapabilities(deltaSync)
        repository.refreshCurrentCapabilities()
        server.takeRequest()
        LibraryCache(repository)
    }

    /** Block until the launched refresh has written its result back. */
    private fun <T> StateFlow<Cached<T>>.settled(): Cached<T> = runBlocking {
        withTimeout(10_000) { first { !it.isRefreshing } }
    }

    private fun LibraryCache.refreshLibraryAndSettle(): Cached<LibraryResponse> {
        refreshLibrary()
        return library.settled()
    }

    private fun RecordedRequest.query(name: String): String? =
        requestUrl?.queryParameter(name)

    private fun nextRequest(): RecordedRequest = server.takeRequest()

    @Test
    fun `the first refresh has no cursor to spend and asks for everything`() {
        val cache = cache()
        enqueueLibrary(serverTime = "t1")

        val settled = cache.refreshLibraryAndSettle()

        val request = nextRequest()
        assertTrue(request.path.orEmpty().startsWith("/api/mobile/library"))
        assertNull(request.query("updated_since"))
        assertNull(settled.error)
        assertEquals(listOf(7), settled.value?.followingIds)
    }

    @Test
    fun `a chapter delta is taken against that fiction's own cursor, not the library's`() {
        val cache = cache()

        // Full library at t1, then a full chapter list at t2 — the chapter watermark is now
        // *ahead* of the library's, which is the ordinary result of opening a book after a refresh.
        enqueueLibrary(serverTime = "t1")
        cache.refreshLibraryAndSettle()
        enqueueChapters(serverTime = "t2", chapters = """{"id":1,"chapter_number":1.0}""")
        cache.refreshChapters(7)
        cache.chapters(7).settled()
        nextRequest()
        nextRequest()

        enqueueSyncIndex(
            serverTime = "t3",
            libraryChanged = false,
            fictions = """{"fiction_id":7,"changed_chapters":1}""",
        )
        enqueueChapters(
            serverTime = "t3",
            delta = true,
            chapters = """{"id":2,"chapter_number":2.0}""",
        )
        // Naming a fiction moves the shelf too: its row carries the progress aggregate and the
        // done-chapter count, so the index reporting changed chapters implies a sparse library pull.
        enqueueLibrary(serverTime = "t3", delta = true)

        cache.refreshLibraryAndSettle()

        assertEquals("t1", nextRequest().query("updated_since"))
        // The bug this guards: sending the library's t1 here would ask for a window the chapter
        // list already has — and with the two cursors the other way round, skip one it does not,
        // then advance past it for good.
        val chapterPull = nextRequest()
        assertTrue(chapterPull.path.orEmpty().startsWith("/api/mobile/fictions/7/chapters"))
        assertEquals("t2", chapterPull.query("updated_since"))
        assertEquals("t1", nextRequest().query("updated_since"))
        assertEquals(
            listOf(1, 2),
            cache.chapters(7).value.value?.map(ChapterSummary::resolvedChapterId),
        )
    }

    @Test
    fun `an index reporting nothing moved costs exactly one request`() {
        val cache = cache()
        enqueueLibrary(serverTime = "t1")
        cache.refreshLibraryAndSettle()
        nextRequest()
        val before = server.requestCount

        enqueueSyncIndex(serverTime = "t2", libraryChanged = false, fictions = "")

        val settled = cache.refreshLibraryAndSettle()

        assertEquals("/api/mobile/sync?updated_since=t1", nextRequest().path)
        assertEquals(1, server.requestCount - before)
        assertNull(settled.error)
    }

    @Test
    fun `a library that moved is pulled sparsely and merged onto what is on screen`() {
        val cache = cache()
        enqueueLibrary(serverTime = "t1", fictions = """{"id":7,"title":"Before"}""")
        cache.refreshLibraryAndSettle()
        nextRequest()

        enqueueSyncIndex(serverTime = "t2", libraryChanged = true, fictions = "")
        enqueueLibrary(serverTime = "t2", delta = true, fictions = """{"id":7,"title":"After"}""")

        val settled = cache.refreshLibraryAndSettle()

        nextRequest()
        assertEquals("t1", nextRequest().query("updated_since"))
        assertEquals(listOf("After"), settled.value?.fictions?.map { it.title })
        assertEquals("t2", settled.value?.serverTime)
    }

    @Test
    fun `a server without the flag is never probed and keeps refetching in full`() {
        val cache = cache(deltaSync = false)
        enqueueLibrary(serverTime = "t1")
        cache.refreshLibraryAndSettle()
        nextRequest()

        enqueueLibrary(serverTime = "t2")
        cache.refreshLibraryAndSettle()

        val second = nextRequest()
        assertTrue(second.path.orEmpty().startsWith("/api/mobile/library"))
        assertNull(second.query("updated_since"))
    }

    @Test
    fun `signing out drops every cursor so the next session starts from a full pull`() {
        val cache = cache()
        enqueueLibrary(serverTime = "t1")
        cache.refreshLibraryAndSettle()
        nextRequest()

        cache.clear()
        enqueueLibrary(serverTime = "t2")
        cache.refreshLibraryAndSettle()

        assertNull(nextRequest().query("updated_since"))
    }
}
