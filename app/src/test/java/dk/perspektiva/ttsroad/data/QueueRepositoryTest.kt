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

private class FakeQueueSessionStore(
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
 * The server-side cross-library queue.
 *
 * The distinction most likely to be got wrong is `id` versus `chapter_id`: `remove` and `reorder`
 * take the *queue row* id, and sending a chapter id would silently remove the wrong thing.
 */
class QueueRepositoryTest {
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

    private fun capabilities(queue: Boolean) = """
    {
      "api_version": 1,
      "server": {"name": "TTSRoad", "version": "1.5.0"},
      "capabilities": {"queue": $queue},
      "limits": {}
    }
    """

    private suspend fun repository(queue: Boolean): TtsRoadRepository {
        val store = FakeQueueSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            MockResponse()
                .setBody(capabilities(queue))
                .setHeader("Content-Type", "application/json"),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `the queue reads as a flat cross-fiction list`() = runTest {
        val repository = repository(queue = true)
        server.enqueue(
            json(
                """
                {"items": [{"id": 11, "position": 0, "chapter_id": 42, "chapter_title": "The Gate",
                            "fiction_id": 1, "fiction_title": "Ashes",
                            "audio": {"url": "https://s/audio/ashes/42.mp3"}},
                           {"id": 12, "position": 1, "chapter_id": 7, "chapter_title": "Embers",
                            "fiction_id": 2, "fiction_title": "Cinders",
                            "audio": {"url": "https://s/audio/cinders/7.mp3"}}],
                 "total": 2, "when_empty": "continue", "max_items": 200}
                """,
            ),
        )

        val queue = repository.queue()

        server.takeRequest()
        assertEquals("/api/mobile/queue", server.takeRequest().path)
        assertEquals(2, queue?.total)
        // Two different fictions in one list is the whole point of a cross-library queue.
        assertEquals(listOf(1, 2), queue?.items?.map { it.fictionId })
        assertEquals("continue", queue?.whenEmpty)
    }

    @Test
    fun `a queue row id is not the chapter id`() = runTest {
        // Removing by chapter id would take out the wrong row, or none.
        val repository = repository(queue = true)
        server.enqueue(
            json("""{"items": [{"id": 11, "chapter_id": 42}], "total": 1}"""),
        )

        val item = repository.queue()?.items?.first()

        assertEquals(11, item?.id)
        assertEquals(42, item?.chapterId)
    }

    @Test
    fun `play next and add to queue differ only by mode`() = runTest {
        val repository = repository(queue = true)
        server.enqueue(json("""{"status": "ok", "items": [], "total": 0}"""))
        server.enqueue(json("""{"status": "ok", "items": [], "total": 0}"""))

        repository.addToQueue(listOf(42), playNext = true)
        repository.addToQueue(listOf(42), playNext = false)

        server.takeRequest()
        val next = server.takeRequest().body.readUtf8()
        val end = server.takeRequest().body.readUtf8()
        assertTrue(next, """"action":"add"""" in next)
        assertTrue(next, """"mode":"next"""" in next)
        assertTrue(end, """"mode":"end"""" in end)
    }

    @Test
    fun `remove sends item ids`() = runTest {
        val repository = repository(queue = true)
        server.enqueue(json("""{"status": "ok", "items": [], "total": 0}"""))

        repository.removeFromQueue(listOf(11))

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"action":"remove"""" in body)
        assertTrue(body, """"item_ids":[11]""" in body)
    }

    @Test
    fun `advance reports what should play next and where it came from`() = runTest {
        val repository = repository(queue = true)
        server.enqueue(
            json(
                """
                {"status": "playing", "source": "queue",
                 "item": {"id": 11, "chapter_id": 42, "chapter_title": "The Gate",
                          "fiction_id": 1, "fiction_title": "Ashes", "position_seconds": 12.0,
                          "audio": {"url": "https://s/audio/ashes/42.mp3"}},
                 "items": [], "total": 0}
                """,
            ),
        )

        val advanced = repository.advanceQueue()

        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains(""""action":"advance""""))
        assertEquals(QueueStatusPlaying, advanced?.status)
        assertEquals(42, advanced?.item?.chapterId)
        assertEquals("queue", advanced?.source)
        // A part-played chapter resumes where it was left, not at zero.
        assertEquals(12.0, advanced?.item?.positionSeconds ?: 0.0, 0.001)
    }

    @Test
    fun `an empty advance says so rather than inventing something to play`() = runTest {
        // This is what `queue_when_empty = stop` looks like from here: the book ends and nothing
        // follows, which is the behaviour the app had before the server queue existed.
        val repository = repository(queue = true)
        server.enqueue(json("""{"status": "empty", "item": null, "source": null, "items": []}"""))

        val advanced = repository.advanceQueue()

        assertNull(advanced?.item)
        assertFalse(advanced?.status == QueueStatusPlaying)
    }

    @Test
    fun `advance can continue into another book entirely`() = runTest {
        // `queue_when_empty = continue`: the server picks the oldest unplayed chapter in the
        // library. The decision is the server's, which is why the app calls advance at all.
        val repository = repository(queue = true)
        server.enqueue(
            json(
                """
                {"status": "playing", "source": "continue",
                 "item": {"chapter_id": 99, "fiction_id": 7, "fiction_title": "Something Else",
                          "audio": {"url": "https://s/audio/else/99.mp3"}},
                 "items": []}
                """,
            ),
        )

        val advanced = repository.advanceQueue()

        assertEquals("continue", advanced?.source)
        assertEquals(7, advanced?.item?.fictionId)
    }

    @Test
    fun `an older server is never asked about a queue`() = runTest {
        val repository = repository(queue = false)

        assertNull(repository.queue())
        assertNull(repository.addToQueue(listOf(42)))
        assertNull(repository.removeFromQueue(listOf(11)))
        assertNull(repository.clearQueue())
        assertNull(repository.advanceQueue())

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `queueing nothing makes no request`() = runTest {
        val repository = repository(queue = true)

        assertNull(repository.addToQueue(emptyList()))
        assertNull(repository.removeFromQueue(emptyList()))

        server.takeRequest()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an item with no audio still parses`() = runTest {
        // The MediaItem builder returns null for these rather than the parse failing.
        val repository = repository(queue = true)
        server.enqueue(json("""{"items": [{"id": 11, "chapter_id": 42, "audio": null}], "total": 1}"""))

        val item = repository.queue()?.items?.first()

        assertNull(item?.audio)
        assertEquals("Chapter", item?.resolvedTitle)
    }
}

/** The `queue` capability flag. */
class QueueCapabilityTest {

    @Test
    fun `queue is off at the baseline`() {
        assertFalse(ServerCapabilities.Baseline.queue)
    }

    @Test
    fun `only a literal true enables it`() {
        // The Up Next browse node is only offered when the server can back it; a loose reading
        // would put an empty dead end in the car.
        assertTrue(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("queue" to true)),
            ).queue,
        )
        assertFalse(
            ServerCapabilities.from(
                CapabilitiesResponse(capabilities = mapOf("queue" to 1.0)),
            ).queue,
        )
        assertFalse(ServerCapabilities.from(CapabilitiesResponse()).queue)
    }
}
