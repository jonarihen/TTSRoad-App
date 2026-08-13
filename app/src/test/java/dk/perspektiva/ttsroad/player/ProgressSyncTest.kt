package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.normalizeBaseUrl
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class FakeSyncSessionStore(private var state: SessionState = SessionState()) : SessionStore {
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
 * Draining the queue to the server.
 *
 * The behaviour that matters is what happens to a *loser*: an item the server rejects as `stale`
 * has to leave the queue and hand back the server's own state, or the phone retries a write that
 * can never win and never drains.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var file: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        file = File(context.filesDir, "pending_progress.json")
        if (file.exists()) file.delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun capabilities(batchProgress: Boolean, maxItems: Int? = null) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.5.0"},
      "capabilities": {"batch_progress": $batchProgress},
      "limits": ${if (maxItems == null) "{}" else """{"max_playback_sync_items": $maxItems}"""}
    }
    """

    private suspend fun repository(
        batchProgress: Boolean,
        maxItems: Int? = null,
    ): TtsRoadRepository {
        val store = FakeSyncSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilities(batchProgress, maxItems))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun pendingStore(clock: () -> Long = System::currentTimeMillis) =
        PendingProgressStore(RuntimeEnvironment.getApplication(), clock)

    @Test
    fun `queued positions go to the batch endpoint with their stamps`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore { 1_700_000_000_000L }
        store.record(1, 7, 30.0, false)
        server.enqueue(
            MockResponse()
                .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}""")
                .setHeader("Content-Type", "application/json"),
        )

        val result = ProgressSync(repository, store).flush()

        server.takeRequest() // capabilities
        val request = server.takeRequest()
        assertEquals("/api/mobile/playback/sync", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, """"chapter_id":7""" in body)
        assertTrue(body, """"client_updated_at":"2023-11-14T22:13:20Z"""" in body)
        assertEquals(1, result.accepted)
        assertTrue(result.drained)
        assertTrue(store.isEmpty())
    }

    @Test
    fun `a stale rejection drops the entry and reports what the server holds`() = runTest {
        // The bug this whole change exists for, seen from the winning side: the browser reached a
        // newer position, the phone's older one loses, and the phone must not keep retrying it.
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false)
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "accepted": [],
                      "rejected": [{"chapter_id": 7, "reason": "stale",
                                    "server_updated_at": "2026-08-11T10:00:00Z"}],
                      "server_state": [{"chapter_id": 7, "position_seconds": 4200.0,
                                        "is_played": false}]
                    }
                    """,
                )
                .setHeader("Content-Type", "application/json"),
        )

        val result = ProgressSync(repository, store).flush()

        assertTrue(store.isEmpty())
        assertEquals(1, result.overriddenByServer.size)
        assertEquals(4200.0, result.overriddenByServer[0].positionSeconds, 0.001)
    }

    @Test
    fun `rejections that cannot succeed on a retry are not retried forever`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false)
        store.record(1, 8, 0.0, false)
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "accepted": [],
                      "rejected": [{"chapter_id": 7, "reason": "not_found"},
                                   {"chapter_id": 8, "reason": "empty"}],
                      "server_state": []
                    }
                    """,
                )
                .setHeader("Content-Type", "application/json"),
        )

        ProgressSync(repository, store).flush()

        // A deleted chapter and an empty position would otherwise block the queue permanently.
        assertTrue(store.isEmpty())
    }

    @Test
    fun `nothing is lost when the flush fails`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false)
        server.enqueue(MockResponse().setResponseCode(500))

        val result = ProgressSync(repository, store).flush()

        assertFalse(result.drained)
        assertEquals(1, store.pending().size)
        assertEquals(30.0, store.pending()[0].positionSeconds, 0.001)
    }

    @Test
    fun `the backlog is split into batches the server will accept`() = runTest {
        val repository = repository(batchProgress = true, maxItems = 2)
        val store = pendingStore()
        for (chapter in 1..5) store.record(1, chapter, 10.0, false)
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setBody("""{"accepted": [], "rejected": [], "server_state": []}""")
                    .setHeader("Content-Type", "application/json"),
            )
        }

        ProgressSync(repository, store).flush()

        server.takeRequest() // capabilities
        // 5 entries at 2 per batch is three calls, not one oversized one that would 400.
        assertEquals(2, server.takeRequest().body.readUtf8().split(""""chapter_id"""").size - 1)
        assertEquals(2, server.takeRequest().body.readUtf8().split(""""chapter_id"""").size - 1)
        assertEquals(1, server.takeRequest().body.readUtf8().split(""""chapter_id"""").size - 1)
    }

    @Test
    fun `an older server falls back to the single-item endpoint`() = runTest {
        // The backend deliberately keeps /playback/progress working for clients that cannot stamp
        // their writes. It is unordered, but it is what an older server has.
        val repository = repository(batchProgress = false)
        val store = pendingStore()
        store.record(1, 7, 30.0, false)
        server.enqueue(
            MockResponse()
                .setBody("""{"status": "ok", "chapter_id": 7}""")
                .setHeader("Content-Type", "application/json"),
        )

        val result = ProgressSync(repository, store).flush()

        server.takeRequest() // capabilities
        assertEquals("/api/mobile/playback/progress", server.takeRequest().path)
        assertTrue(result.drained)
        assertTrue(store.isEmpty())
    }

    @Test
    fun `the fallback path also keeps what it could not send`() = runTest {
        val repository = repository(batchProgress = false)
        val store = pendingStore()
        store.record(1, 7, 30.0, false)
        server.enqueue(MockResponse().setResponseCode(500))

        ProgressSync(repository, store).flush()

        assertEquals(1, store.pending().size)
    }

    @Test
    fun `flushing an empty queue makes no request`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()

        val result = ProgressSync(repository, store).flush()

        assertTrue(result.drained)
        assertEquals(0, result.sent)
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}
