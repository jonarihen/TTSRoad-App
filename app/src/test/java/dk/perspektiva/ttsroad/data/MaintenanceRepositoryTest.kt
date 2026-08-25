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

private class FakeMaintenanceSessionStore(
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
 * The maintenance surface: repair a chapter (#107), act on a fiction (#112).
 *
 * Nine routes and one response model, so the things worth pinning are the ones a shared model can
 * quietly get wrong — that each action reaches the right URL and method, that the counts survive
 * parsing under the right names, and that the two capability flags gate the two families
 * independently rather than one standing in for both.
 */
class MaintenanceRepositoryTest {
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
        chapterMaintenance: Boolean = true,
        fictionMaintenance: Boolean = true,
    ): TtsRoadRepository {
        val store = FakeMaintenanceSessionStore()
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
                """
                {"api_version": 1, "server": {"name": "TTSRoad"},
                 "capabilities": {"chapter_maintenance": $chapterMaintenance,
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
    fun `retrying a chapter posts to its own route`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"status": "queued", "chapter_id": 42}"""))

        val result = repository.retryChapter(42)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/chapters/42/retry", request.path)
        assertEquals("POST", request.method)
        assertEquals("queued", result?.status)
    }

    @Test
    fun `excluding a chapter sends the flag both ways`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "chapter_id": 42, "excluded": true}"""))
        server.enqueue(json("""{"status": "ok", "chapter_id": 42, "excluded": false}"""))

        assertEquals(true, repository.setChapterExcluded(42, excluded = true)?.excluded)
        assertEquals(false, repository.setChapterExcluded(42, excluded = false)?.excluded)

        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains(""""excluded":true"""))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""excluded":false"""))
    }

    @Test
    fun `deleting a chapter is a DELETE with a body to confirm it`() = runTest {
        // A 204 over a flaky connection is indistinguishable from a request that never arrived,
        // which is why the mobile route answers with a body at all.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "chapter_id": 42, "deleted": true}"""))

        val result = repository.deleteChapter(42)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mobile/chapters/42", request.path)
        assertEquals("ok", result?.status)
    }

    @Test
    fun `poll defaults to the recent tail and asks for the lot only when told`() = runTest {
        // The difference between the two is a whole-serial re-ingest, so a default of `full` would
        // be an expensive thing to get by pressing the obvious button.
        val repository = repository()
        server.enqueue(json("""{"status": "started", "full_ingest": false, "partial_sync": 25}"""))
        server.enqueue(json("""{"status": "started", "full_ingest": true, "partial_sync": null}"""))

        val partial = repository.pollFiction(7)
        val full = repository.pollFiction(7, full = true)

        server.takeRequest()
        assertEquals("/api/mobile/fictions/7/poll?full=false", server.takeRequest().path)
        assertEquals("/api/mobile/fictions/7/poll?full=true", server.takeRequest().path)
        assertFalse(partial!!.fullIngest)
        assertEquals(25, partial.partialSync)
        assertTrue(full!!.fullIngest)
        assertNull(full.partialSync)
    }

    @Test
    fun `every fiction action reports how much it is about to do`() = runTest {
        // The counts are the only thing separating a no-op from four hundred conversions: all of
        // these answer `status: ok` and run in the background.
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "fiction_id": 7, "reset_count": 3}"""))
        server.enqueue(json("""{"status": "ok", "fiction_id": 7, "reset_count": 412}"""))
        server.enqueue(json("""{"status": "ok", "fiction_id": 7, "file_count": 412}"""))
        server.enqueue(json("""{"status": "ok", "fiction_id": 7, "excluded_count": 9}"""))

        assertEquals(3, repository.retryFailedChapters(7)?.resetCount)
        assertEquals(412, repository.reconvertAllChapters(7)?.resetCount)
        assertEquals(412, repository.retagFiction(7)?.fileCount)
        assertEquals(9, repository.applyChapterFilter(7)?.excludedCount)

        server.takeRequest()
        assertEquals("/api/mobile/fictions/7/retry-failed", server.takeRequest().path)
        assertEquals("/api/mobile/fictions/7/reconvert-all", server.takeRequest().path)
        assertEquals("/api/mobile/fictions/7/retag", server.takeRequest().path)
        assertEquals("/api/mobile/fictions/7/apply-chapter-filter", server.takeRequest().path)
    }

    /**
     * `detail` is only present when there was nothing to do because no filter is set. Reporting
     * "excluded 0 chapters" there would be true and useless; the server's own sentence is better.
     */
    @Test
    fun `apply-filter explains itself when there is no filter`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """{"status": "ok", "fiction_id": 7, "excluded_count": 0,
                    "detail": "No chapter filter is set for this fiction"}""",
            ),
        )

        val result = repository.applyChapterFilter(7)

        assertEquals("No chapter filter is set for this fiction", result?.detail)
    }

    @Test
    fun `retry-all-failed spans the library`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"status": "ok", "reset_count": 17, "fictions": 4}"""))

        val result = repository.retryAllFailed()

        server.takeRequest()
        assertEquals("/api/mobile/retry-all-failed", server.takeRequest().path)
        assertEquals(17, result?.resetCount)
        assertEquals(4, result?.fictions)
    }

    /**
     * The two flags gate two families, and neither may stand in for the other: a server can grow
     * one before the other, and a client that conflated them would offer a button that 404s.
     */
    @Test
    fun `the two capability flags gate independently`() = runTest {
        val chapterOnly = repository(chapterMaintenance = true, fictionMaintenance = false)
        server.enqueue(json("""{"status": "queued", "chapter_id": 42}"""))

        assertEquals("queued", chapterOnly.retryChapter(42)?.status)
        assertNull(chapterOnly.pollFiction(7))
        assertNull(chapterOnly.retryFailedChapters(7))
        assertNull(chapterOnly.retagFiction(7))
        assertNull(chapterOnly.applyChapterFilter(7))
        assertNull(chapterOnly.reconvertAllChapters(7))
        assertNull(chapterOnly.retryAllFailed())

        server.takeRequest()
        server.takeRequest()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an older server is asked nothing at all`() = runTest {
        val repository = repository(chapterMaintenance = false, fictionMaintenance = false)

        assertNull(repository.retryChapter(42))
        assertNull(repository.setChapterExcluded(42, excluded = true))
        assertNull(repository.deleteChapter(42))
        assertNull(repository.pollFiction(7))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}

/** The two maintenance flags, at the baseline and under a loose reading. */
class MaintenanceCapabilityTest {
    @Test
    fun `both are off at the baseline`() {
        assertFalse(ServerCapabilities.Baseline.chapterMaintenance)
        assertFalse(ServerCapabilities.Baseline.fictionMaintenance)
    }

    @Test
    fun `only a literal true enables them`() {
        // A loose reading would draw DELETE THIS CHAPTER against a server that has no such route.
        val loose = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("chapter_maintenance" to 1.0, "fiction_maintenance" to "yes"),
            ),
        )

        assertFalse(loose.chapterMaintenance)
        assertFalse(loose.fictionMaintenance)
        assertTrue(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("chapter_maintenance" to true)),
            ).chapterMaintenance,
        )
    }

    @Test
    fun `both are named in the capability panel`() {
        // #120: an unnamed flag lists by its raw key, which is worse than a sentence.
        assertEquals("Repair a chapter", CapabilityCatalog.label("chapter_maintenance"))
        assertEquals("Maintain a fiction", CapabilityCatalog.label("fiction_maintenance"))
    }
}
