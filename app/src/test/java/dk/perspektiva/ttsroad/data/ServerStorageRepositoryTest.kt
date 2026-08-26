package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `GET /api/mobile/storage` — how much disk the server is using, per fiction (#124).
 *
 * The contract point worth pinning in a test rather than in a comment is the pairing: every
 * `…_bytes` arrives with a `…_label` the server already rendered, and the label is what gets shown.
 * The counts exist for arithmetic a string cannot do — the share of the volume in use — and the
 * moment a client starts formatting its own the phone and the browser begin describing the same
 * file two different ways.
 *
 * The other is what is *not* here. There is no mobile route for the orphan scan, the orphan delete,
 * the voice-sample delete, the excluded-audio delete or the per-fiction audio delete, and no client
 * method that would call one.
 */
class ServerStorageRepositoryTest {
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

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private suspend fun repository(storage: Boolean = true): TtsRoadRepository {
        val store = FakeSessionStore(SessionState())
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
                 "capabilities": {"storage": $storage}, "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    /** Shaped exactly as `app/routers/storage.py` builds it. */
    private val payload = """
        {"api_version": 1,
         "total_audio_bytes": 512000000000, "total_audio_label": "477 GB",
         "excluded_audio_bytes": 2147483648, "excluded_audio_label": "2.0 GB",
         "epub_bytes": 104857600, "epub_label": "100 MB",
         "cover_bytes": 20971520, "cover_label": "20 MB",
         "voice_sample_bytes": 1048576, "voice_sample_label": "1.0 MB", "voice_sample_count": 14,
         "export_bytes": 32212254720, "export_label": "30 GB",
         "exports": [
           {"id": 3, "fiction_id": 7, "fiction_title": "Mother of Learning",
            "part_index": 1, "part_count": 1, "chapter_count": 108,
            "size_bytes": 32212254720, "size_label": "30 GB",
            "duration_seconds": 180000.0, "duration_label": "50h 0m",
            "download_url": "https://ttsroad.example/api/exports/3/download"}
         ],
         "ffmpeg_available": false,
         "volume_total_bytes": 1000000000000, "volume_total_label": "931 GB",
         "volume_free_bytes": 250000000000, "volume_free_label": "233 GB",
         "per_fiction": [
           {"id": 7, "title": "Mother of Learning", "slug": "mother-of-learning",
            "audio_bytes": 400000000000, "audio_label": "373 GB",
            "excluded_bytes": 2147483648, "excluded_label": "2.0 GB"},
           {"id": 9, "title": "Just Added", "slug": "just-added",
            "audio_bytes": 0, "audio_label": "0 B",
            "excluded_bytes": 0, "excluded_label": "0 B"}
         ]}
    """

    @Test
    fun `a server without the endpoint answers null and is never asked`() = runTest {
        val repository = repository(storage = false)

        assertNull(repository.serverStorage())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `every total arrives with the label the server rendered for it`() = runTest {
        val repository = repository()
        server.enqueue(json(payload))

        val response = repository.serverStorage()

        server.takeRequest()
        assertEquals("/api/mobile/storage", server.takeRequest().path)
        assertNotNull(response)
        assertEquals(512_000_000_000L, response!!.totalAudioBytes)
        assertEquals("477 GB", response.totalAudioLabel)
        assertEquals("2.0 GB", response.excludedAudioLabel)
        assertEquals("100 MB", response.epubLabel)
        assertEquals("20 MB", response.coverLabel)
        assertEquals("1.0 MB", response.voiceSampleLabel)
        assertEquals(14, response.voiceSampleCount)
        assertEquals("30 GB", response.exportLabel)
        assertEquals("931 GB", response.volumeTotalLabel)
        assertEquals("233 GB", response.volumeFreeLabel)

        // The runtime fact behind the capability: the route exists, the machine has no encoder.
        assertFalse(response.ffmpegAvailable)

        // Same rows as /api/mobile/exports, parsed by the same model.
        assertEquals(1, response.exports.size)
        assertEquals("Mother of Learning", response.exports.single().fictionTitle)
        assertEquals("30 GB", response.exports.single().sizeLabel)
    }

    @Test
    fun `per-fiction rows carry both the size and the reclaimable part of it`() = runTest {
        val repository = repository()
        server.enqueue(json(payload))

        val rows = repository.serverStorage()!!.perFiction

        assertEquals(2, rows.size)
        val biggest = rows.first()
        assertEquals(7, biggest.id)
        assertEquals("Mother of Learning", biggest.title)
        assertEquals("mother-of-learning", biggest.slug)
        assertEquals(400_000_000_000L, biggest.audioBytes)
        assertEquals("373 GB", biggest.audioLabel)
        // The figure this whole card is worth having for: audio of excluded chapters, still on
        // disk. Shown here, reclaimed on the web.
        assertEquals("2.0 GB", biggest.excludedLabel)
    }

    /**
     * The overview drops fictions with no audio from the table and counts them instead. They are
     * real books — freshly added, still converting — but a disk-usage table is not where they are
     * worth a row of "0 B" each, and dropping them silently would leave someone scrolling for a
     * book that is on the server.
     */
    @Test
    fun `a fiction with no audio is counted rather than listed`() = runTest {
        val repository = repository()
        server.enqueue(json(payload))

        val overview = serverStorageOverview(repository.serverStorage())

        assertNotNull(overview)
        assertEquals(listOf(7), overview!!.rows.map { it.id })
        assertEquals(1, overview.emptyFictions)
        // 750 GB of a 931 GB volume, from the byte counts — the one figure the labels cannot give.
        assertEquals(0.75f, overview.usedFraction, 0.001f)
        assertNotNull(overview.encoderNote)
    }

    @Test
    fun `a 403 throws rather than reading as an empty disk`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(403))

        val thrown = runCatching { repository.serverStorage() }.exceptionOrNull()

        assertNotNull(thrown)
    }

    /**
     * The read-only line, asserted rather than trusted to memory. `tests/test_mobile_logs_and_storage.py`
     * makes the same assertion on the server; this is the client half.
     */
    @Test
    fun `the client exposes no storage route that changes anything`() {
        val mutating = TtsRoadApi::class.java.methods
            .map { it.name }
            .filter { it.contains("storage", ignoreCase = true) }

        assertEquals(listOf("serverStorage"), mutating)
        assertTrue(
            TtsRoadRepository::class.java.methods
                .map { it.name }
                .filter { it.contains("storage", ignoreCase = true) }
                .none { it != "serverStorage" },
        )
    }
}
