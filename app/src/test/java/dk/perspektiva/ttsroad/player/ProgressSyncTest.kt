package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.LoginResponse
import dk.perspektiva.ttsroad.data.MobileUser
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SessionStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.normalizeBaseUrl
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** A session store whose answer the test controls, and which counts how often it is asked. */
private class ScriptedSessionStore(var answer: (call: Int) -> SessionState) : SessionStore {
    var calls = 0
    override suspend fun current(): SessionState = answer(++calls)
    override suspend fun saveLogin(baseUrl: String, response: LoginResponse) = Unit
    override suspend fun clearToken() = Unit
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

    private val owner get() = SessionState(serverUrl = server.url("/").toString(), token = "t0ken", username = "reader")

    @Test
    fun `queued positions go to the batch endpoint with their stamps`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore { 1_700_000_000_000L }
        store.record(1, 7, 30.0, false, owner = owner)
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
    fun `accepted played chapters are announced, stale and unplayed ones are not`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, true, owner = owner)
        store.record(1, 8, 30.0, false, owner = owner)
        store.record(1, 9, 30.0, true, owner = owner)
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"accepted": [{"chapter_id": 7}, {"chapter_id": 8}],
                       "rejected": [{"chapter_id": 9, "reason": "stale"}], "server_state": []}""",
                )
                .setHeader("Content-Type", "application/json"),
        )
        val told = mutableListOf<Pair<Int, List<Int>>>()

        ProgressSync(repository, store) { fiction, ids -> told += fiction to ids.toList() }.flush()

        assertEquals(listOf(1 to listOf(7)), told)
    }

    @Test
    fun `a stale rejection drops the entry and reports what the server holds`() = runTest {
        // The bug this whole change exists for, seen from the winning side: the browser reached a
        // newer position, the phone's older one loses, and the phone must not keep retrying it.
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
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
        store.record(1, 7, 30.0, false, owner = owner)
        store.record(1, 8, 0.0, false, owner = owner)
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
        store.record(1, 7, 30.0, false, owner = owner)
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
        for (chapter in 1..5) store.record(1, chapter, 10.0, false, owner = owner)
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
        store.record(1, 7, 30.0, false, owner = owner)
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
        store.record(1, 7, 30.0, false, owner = owner)
        server.enqueue(MockResponse().setResponseCode(500))

        ProgressSync(repository, store).flush()

        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a refused entry is dropped and the ones behind it are still sent`() = runTest {
        // A chapter deleted on the server while its position was queued: a 404 at the head of the
        // queue used to block every later save, on every tick, forever.
        val repository = repository(batchProgress = false)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
        store.record(1, 8, 40.0, false, owner = owner)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setBody("""{"status": "ok", "chapter_id": 8}""")
                .setHeader("Content-Type", "application/json"),
        )

        val result = ProgressSync(repository, store).flush()

        server.takeRequest() // capabilities
        assertTrue(server.takeRequest().body.readUtf8().contains(""""chapter_id":7"""))
        assertTrue(server.takeRequest().body.readUtf8().contains(""""chapter_id":8"""))
        assertEquals(1, result.accepted)
        assertTrue(result.drained)
        assertTrue(store.isEmpty())
    }

    @Test
    fun `a 401 keeps the whole queue for the account to come back`() = runTest {
        val repository = repository(batchProgress = false)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
        store.record(1, 8, 40.0, false, owner = owner)
        server.enqueue(MockResponse().setResponseCode(401))

        ProgressSync(repository, store).flush()

        assertEquals(2, store.pending().size)
    }

    @Test
    fun `a batch the server refuses outright is not retried forever`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
        server.enqueue(MockResponse().setResponseCode(422))

        val result = ProgressSync(repository, store).flush()

        assertTrue(result.drained)
        assertTrue(store.isEmpty())
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

    @Test
    fun `an account switch without sign-out never posts the previous account's backlog`() = runTest {
        for (batch in listOf(true, false)) {
            server.shutdown()
            server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
            val repository = repository(batchProgress = batch)
            server.takeRequest() // capabilities
            val store = pendingStore()
            store.clear()
            // Account "previous" listened offline on the same backend, then "reader" signed in over it.
            store.record(1, 7, 30.0, false, owner = owner.copy(token = "previous-token", username = "previous"))
            store.record(1, 8, 40.0, false, owner = owner)
            server.enqueue(
                MockResponse()
                    .setBody(if (batch) """{"accepted": [{"chapter_id": 8}], "rejected": [], "server_state": []}"""
                        else """{"status":"saved","chapter_id":8}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val result = ProgressSync(repository, store).flush()

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertTrue(body, """"chapter_id":8""" in body)
            assertFalse(body, """"chapter_id":7""" in body)
            assertEquals(2, server.requestCount)
            assertEquals(1, result.sent)
            assertTrue(result.drained)
            // Kept for its own account rather than dropped: it is still that listener's position.
            assertEquals(listOf(7), store.pending().map { it.chapterId })
            assertEquals("previous", store.pending().single().ownerAccount)
        }
    }

    @Test
    fun `an entry written before owners existed parses but is never sent`() = runTest {
        val repository = repository(batchProgress = true)
        server.takeRequest() // capabilities
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "pending_progress.json").writeText(
            """[{"fictionId":1,"chapterId":7,"positionSeconds":30.0,"isPlayed":false,""" +
                """"clientUpdatedAt":"2023-11-14T22:13:20Z","recordedAtMillis":1700000000000,"recordId":"legacy"}]""",
        )
        val store = pendingStore()
        assertEquals(listOf("legacy"), store.pending().map { it.recordId })
        assertNull(store.pending().single().ownerServerUrl)

        val result = ProgressSync(repository, store).flush()

        assertEquals(0, result.sent)
        assertEquals(1, server.requestCount)
        assertEquals(1, store.pending().size)
    }

    private suspend fun scriptedRepository(batchProgress: Boolean, sessions: ScriptedSessionStore): TtsRoadRepository {
        val repository = TtsRoadRepository(sessions)
        server.enqueue(MockResponse().setBody(capabilities(batchProgress)).setHeader("Content-Type", "application/json"))
        repository.refreshCurrentCapabilities()
        server.takeRequest() // capabilities
        sessions.calls = 0
        return repository
    }

    @Test
    fun `a progress request carries the selected account's credentials even if another signs in first`() = runTest {
        for (batch in listOf(true, false)) {
            server.shutdown()
            server = MockWebServer().also { it.start() }
            val alice = SessionState(serverUrl = server.url("/").toString(), token = "alice-token", username = "alice")
            val bob = alice.copy(token = "bob-token", username = "bob")
            // Alice for the ownership check and the per-batch re-check; Bob from then on, i.e. Bob
            // signs in after the entries were selected but before the request is built.
            val sessions = ScriptedSessionStore { call -> if (call <= 2) alice else bob }
            val repository = scriptedRepository(batch, sessions)
            val store = pendingStore()
            store.clear()
            store.record(1, 7, 30.0, false, owner = alice)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                if (batch) """{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""
                else """{"status":"saved","chapter_id":7}""",
            ))

            ProgressSync(repository, store).flush()

            val request = server.takeRequest()
            assertEquals("Bearer alice-token", request.getHeader("Authorization"))
            assertTrue(store.pending().isEmpty())
        }
    }

    @Test
    fun `a repository progress call bound to a session never uses the current one's token`() = runTest {
        val alice = SessionState(serverUrl = server.url("/").toString(), token = "alice-token", username = "alice")
        val bob = alice.copy(token = "bob-token", username = "bob")
        val repository = scriptedRepository(batchProgress = true, ScriptedSessionStore { bob })
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"accepted": [], "rejected": [], "server_state": []}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"status":"saved","chapter_id":7}"""))

        repository.syncProgress(listOf(PlaybackSyncItemFor(7)), asSession = alice)
        repository.saveProgress(1, 7, 30.0, false, asSession = alice)

        assertEquals("Bearer alice-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer alice-token", server.takeRequest().getHeader("Authorization"))
    }

    private fun PlaybackSyncItemFor(chapterId: Int) = dk.perspektiva.ttsroad.data.PlaybackSyncItem(
        chapterId = chapterId,
        positionSeconds = 1.0,
        isPlayed = false,
        clientUpdatedAt = "2023-11-14T22:13:20Z",
    )

    @Test
    fun `owners match however the server address was spelled, including entries already on disk`() = runTest {
        val port = server.port
        val session = SessionState(serverUrl = "http://localhost:$port/", token = "t", username = "reader")
        for (spelling in listOf("http://LOCALHOST:$port", "http://localhost:$port/", "HTTP://Localhost:$port//")) {
            val entry = PendingProgress(1, 7, 1.0, false, "2023-11-14T22:13:20Z", 1L,
                ownerServerUrl = spelling, ownerAccount = "reader")
            assertTrue(spelling, entry.ownedBy(session))
            assertFalse(spelling, entry.ownedBy(session.copy(username = "other")))
        }
        val https = SessionState(serverUrl = "https://home.example/ttsroad/", token = "t", username = "reader")
        for ((spelling, owned) in listOf(
            "https://HOME.example:443/ttsroad" to true,
            "https://home.example/other/" to false,
            "http://home.example/ttsroad/" to false,
        )) {
            val entry = PendingProgress(1, 7, 1.0, false, "2023-11-14T22:13:20Z", 1L,
                ownerServerUrl = spelling, ownerAccount = "reader")
            assertEquals(spelling, owned, entry.ownedBy(https))
        }

        // A file written by the previous build with another spelling of this very server. Spelled
        // from the server's own host: MockWebServer binds to whatever the machine resolves
        // "localhost" to, which on CI is not the literal name, so a hard-coded "LOCALHOST" there
        // was a different server and the entry (rightly) did not coalesce.
        val spelled = server.url("/").toString().uppercase().trimEnd('/')
        val repository = repository(batchProgress = true)
        server.takeRequest() // capabilities
        File(RuntimeEnvironment.getApplication().filesDir, "pending_progress.json").writeText(
            """[{"fictionId":1,"chapterId":7,"positionSeconds":30.0,"isPlayed":false,""" +
                """"clientUpdatedAt":"2023-11-14T22:13:20Z","recordedAtMillis":1700000000000,"recordId":"spelled",""" +
                """"ownerServerUrl":"$spelled","ownerAccount":"reader"}]""",
        )
        val store = pendingStore()
        // Recorded again under the canonical spelling: coalesced with the stored entry, not added.
        store.record(1, 7, 31.0, false, owner = owner)
        assertEquals(1, store.pending().size)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))

        val result = ProgressSync(repository, store).flush()

        assertEquals(1, result.sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a returning account's retained progress is flushed when it becomes current again`() = runTest {
        val alice = SessionState(serverUrl = server.url("/").toString(), token = "alice-token", username = "alice")
        val bob = alice.copy(token = "bob-token", username = "bob")
        var current = alice
        val sessions = ScriptedSessionStore { current }
        val repository = scriptedRepository(batchProgress = true, sessions)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = alice)
        val sync = ProgressSync(repository, store)
        var requested = 0
        val trigger = OwnerProgressFlush { requested++ }

        current = bob
        trigger.onSession(bob)
        assertEquals(1, requested)
        sync.flush()
        assertEquals(0, server.requestCount - 1) // only the capabilities call so far
        assertEquals(1, store.pending().size)

        trigger.onSession(SessionState(serverUrl = alice.serverUrl))
        assertEquals(1, requested)

        current = alice
        trigger.onSession(alice)
        assertEquals(2, requested)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))
        sync.flush()

        val request = server.takeRequest()
        assertEquals("/api/mobile/playback/sync", request.path)
        assertEquals("Bearer alice-token", request.getHeader("Authorization"))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a same-account token rotation asks for another flush`() {
        // A password change rotates the token mid-flush: the old-token request fails or the next
        // batch stops at the session check, and nothing else would retry with nothing playing.
        val alice = SessionState(serverUrl = "https://home.example/", token = "alice-token", username = "alice")
        var requested = 0
        val trigger = OwnerProgressFlush { requested++ }

        trigger.onSession(alice)
        trigger.onSession(alice)
        assertEquals(1, requested)

        trigger.onSession(alice.copy(token = "alice-rotated"))
        assertEquals(2, requested)
        trigger.onSession(alice.copy(token = "alice-rotated"))
        assertEquals(2, requested)

        trigger.onSession(SessionState(serverUrl = alice.serverUrl))
        assertEquals(2, requested)
    }

    @Test
    fun `a rotated token flushes the backlog the old token left behind`() = runTest {
        val alice = SessionState(serverUrl = server.url("/").toString(), token = "alice-token", username = "alice")
        var current = alice.copy(token = "alice-rotated")
        val sessions = ScriptedSessionStore { current }
        val repository = scriptedRepository(batchProgress = true, sessions)
        val store = pendingStore()
        // Recorded under the old token; ownership is by server and account, not by credential.
        store.record(1, 7, 30.0, false, owner = alice)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))

        val result = ProgressSync(repository, store).flush()

        val request = server.takeRequest()
        assertEquals("/api/mobile/playback/sync", request.path)
        assertEquals("Bearer alice-rotated", request.getHeader("Authorization"))
        assertEquals(1, result.accepted)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a discovery that fails while a concurrent one succeeds still sends stamped progress`() = runTest {
        val owner = SessionState(serverUrl = server.url("/").toString(), token = "alice-token", username = "alice")
        val repository = TtsRoadRepository(ScriptedSessionStore { owner })
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
        val capabilityCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/capabilities" -> if (capabilityCalls.incrementAndGet() == 1) {
                    // While the flush's own discovery is in flight, another caller (the UI's
                    // refresh) discovers the same server successfully; then this one fails.
                    runBlocking { repository.capabilities(owner.serverUrl, forceRefresh = true) }
                    MockResponse().setResponseCode(503)
                } else {
                    MockResponse().setBody(capabilities(batchProgress = true)).setHeader("Content-Type", "application/json")
                }
                "/api/mobile/playback/sync" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}""")
                else -> MockResponse().setResponseCode(500)
            }
        }

        val result = ProgressSync(repository, store).flush()

        assertEquals(2, capabilityCalls.get())
        val paths = List(server.requestCount) { server.takeRequest().path }
        assertTrue(paths.toString(), "/api/mobile/playback/sync" in paths)
        assertFalse(paths.toString(), "/api/mobile/playback/progress" in paths)
        assertEquals(1, result.accepted)
        assertTrue(store.pending().isEmpty())
    }

    /**
     * Published capabilities from [previous] (as if TtsRoadApp had discovered them for the account
     * signed in before), with [owner] now current and its own capabilities not yet discovered.
     */
    private suspend fun switchedRepository(previous: MockWebServer, previousBatch: Boolean, owner: SessionState): TtsRoadRepository {
        val before = SessionState(serverUrl = previous.url("/").toString(), token = "before", username = "before")
        var current = before
        val repository = TtsRoadRepository(ScriptedSessionStore { current })
        previous.enqueue(MockResponse().setBody(capabilities(previousBatch)).setHeader("Content-Type", "application/json"))
        repository.refreshCurrentCapabilities()
        assertEquals(previousBatch, repository.currentCapabilities.value.batchProgress)
        current = owner
        return repository
    }

    @Test
    fun `a flush right after a switch uses the owner's server capabilities, not the published ones`() = runTest {
        for (ownerBatch in listOf(true, false)) {
            server.shutdown()
            server = MockWebServer().also { it.start() }
            val previous = MockWebServer().also { it.start() }
            try {
                val owner = SessionState(serverUrl = server.url("/").toString(), token = "owner-token", username = "reader")
                val repository = switchedRepository(previous, previousBatch = !ownerBatch, owner = owner)
                val store = pendingStore { 1_700_000_000_000L }
                store.clear()
                store.record(1, 7, 30.0, false, owner = owner)
                server.enqueue(MockResponse().setBody(capabilities(ownerBatch)).setHeader("Content-Type", "application/json"))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    if (ownerBatch) """{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""
                    else """{"status":"saved","chapter_id":7}""",
                ))

                ProgressSync(repository, store).flush()

                assertEquals("/api/mobile/capabilities", server.takeRequest().path)
                val request = server.takeRequest()
                assertEquals(
                    if (ownerBatch) "/api/mobile/playback/sync" else "/api/mobile/playback/progress",
                    request.path,
                )
                if (ownerBatch) assertTrue(""""client_updated_at":"2023-11-14T22:13:20Z"""" in request.body.readUtf8())
                assertEquals("Bearer owner-token", request.getHeader("Authorization"))
                assertEquals(1, previous.requestCount) // only its own discovery, nothing posted there
                assertTrue(store.pending().isEmpty())
            } finally {
                previous.shutdown()
            }
        }
    }

    @Test
    fun `unresolved capabilities leave the backlog untouched instead of using the legacy endpoint`() = runTest {
        val previous = MockWebServer().also { it.start() }
        try {
            val owner = SessionState(serverUrl = server.url("/").toString(), token = "owner-token", username = "reader")
            // The previous server had batch support; the owner's discovery fails transiently.
            val repository = switchedRepository(previous, previousBatch = true, owner = owner)
            val store = pendingStore()
            store.record(1, 7, 30.0, false, owner = owner)
            server.enqueue(MockResponse().setResponseCode(503))

            val result = ProgressSync(repository, store).flush()

            assertFalse(result.drained)
            assertEquals(0, result.sent)
            assertEquals("/api/mobile/capabilities", server.takeRequest().path)
            assertEquals(1, server.requestCount)
            assertEquals(1, previous.requestCount)
            assertEquals(1, store.pending().size)

            // Once the owner's server answers, the same entry goes out stamped.
            server.enqueue(MockResponse().setBody(capabilities(true)).setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))
            ProgressSync(repository, store).flush()
            assertEquals("/api/mobile/capabilities", server.takeRequest().path)
            assertEquals("/api/mobile/playback/sync", server.takeRequest().path)
            assertTrue(store.pending().isEmpty())
        } finally {
            previous.shutdown()
        }
    }

    @Test
    fun `a flush that met unresolved capabilities retries with backoff until discovery recovers`() = runTest {
        val previous = MockWebServer().also { it.start() }
        try {
            val owner = SessionState(serverUrl = server.url("/").toString(), token = "owner-token", username = "reader")
            val repository = switchedRepository(previous, previousBatch = false, owner = owner)
            val store = pendingStore()
            store.record(1, 7, 30.0, false, owner = owner)
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(capabilities(true)).setHeader("Content-Type", "application/json"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))
            val slept = mutableListOf<Long>()

            val result = ProgressSync(repository, store).flushUntilCapabilitiesResolve(listOf(1L, 2L, 3L)) { slept += it }

            assertEquals(listOf(1L, 2L), slept)
            assertFalse(result.capabilitiesUnresolved)
            assertTrue(result.drained)
            assertEquals(List(3) { "/api/mobile/capabilities" } + "/api/mobile/playback/sync",
                List(4) { server.takeRequest().path })
            assertTrue(store.pending().isEmpty())
        } finally {
            previous.shutdown()
        }
    }

    @Test
    fun `the capability retry is bounded and keeps the backlog when discovery never recovers`() = runTest {
        val previous = MockWebServer().also { it.start() }
        try {
            val owner = SessionState(serverUrl = server.url("/").toString(), token = "owner-token", username = "reader")
            val repository = switchedRepository(previous, previousBatch = true, owner = owner)
            val store = pendingStore()
            store.record(1, 7, 30.0, false, owner = owner)
            repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
            val slept = mutableListOf<Long>()

            val result = ProgressSync(repository, store).flushUntilCapabilitiesResolve(listOf(1L, 2L, 3L)) { slept += it }

            assertEquals(listOf(1L, 2L, 3L), slept)
            assertTrue(result.capabilitiesUnresolved)
            assertEquals(4, server.requestCount) // four discovery attempts, no progress request
            assertEquals(1, store.pending().size)
        } finally {
            previous.shutdown()
        }
    }

    @Test
    fun `a flush whose capabilities are known never waits`() = runTest {
        val repository = repository(batchProgress = true)
        val store = pendingStore()
        store.record(1, 7, 30.0, false, owner = owner)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"accepted": [{"chapter_id": 7}], "rejected": [], "server_state": []}"""))
        val slept = mutableListOf<Long>()

        ProgressSync(repository, store).flushUntilCapabilitiesResolve { slept += it }

        assertTrue(slept.isEmpty())
        assertTrue(store.pending().isEmpty())
    }
}
