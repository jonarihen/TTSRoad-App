package dk.perspektiva.ttsroad.data

import java.time.ZoneOffset
import java.util.Locale
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
 * `GET /api/mobile/exports` — the finished M4B audiobooks (#113).
 *
 * The endpoint was written for this app and had no client for four releases, so the first thing
 * worth pinning is simply that the payload parses under the names the backend actually sends. The
 * second is the pair of gates: the capability flag here, `is_admin` at the call site, because a
 * non-admin asking gets a 403 rather than an empty list.
 */
class AudiobookExportsRepositoryTest {
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

    private suspend fun repository(audiobookExport: Boolean = true): TtsRoadRepository {
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
                 "capabilities": {"audiobook_export": $audiobookExport}, "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    private fun json(body: String) =
        MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    /** The full payload as `export_payload` plus the mobile route's own additions send it. */
    private val twoPartBatch = """
        {"api_version": 1,
         "ffmpeg_available": true,
         "exports": [
           {"id": 12, "fiction_id": 7, "fiction_title": "Ashes of the Sun",
            "fiction_slug": "ashes-of-the-sun", "batch_id": "b-42",
            "part_index": 1, "part_count": 2, "title": "Ashes of the Sun, Part 1",
            "filename": "ashes-of-the-sun-part-1.m4b", "status": "done", "progress": 100,
            "error_message": null, "encode_mode": "aac", "chapter_count": 120,
            "first_chapter_number": 1, "last_chapter_number": 120,
            "duration_seconds": 43380.0, "duration_label": "12h 03m",
            "size_bytes": 1503238553, "size_label": "1.4 GB", "estimated_bytes": 1500000000,
            "created_at": "2026-08-20T09:00:00Z", "completed_at": "2026-08-20T10:41:00Z",
            "download_url": "https://configured.example/api/exports/12/download",
            "downloadable": true, "deletable": true,
            "requires_bearer_auth": true, "playable_in_app": false},
           {"id": 13, "fiction_id": 7, "fiction_title": "Ashes of the Sun",
            "batch_id": "b-42", "part_index": 2, "part_count": 2,
            "title": "Ashes of the Sun, Part 2", "filename": "ashes-of-the-sun-part-2.m4b",
            "status": "done", "chapter_count": 118,
            "first_chapter_number": 121, "last_chapter_number": 238,
            "duration_seconds": 42000.0, "duration_label": "11h 40m",
            "size_bytes": 1400000000, "size_label": "1.3 GB",
            "created_at": "2026-08-20T09:00:00Z", "completed_at": "2026-08-20T12:02:00Z",
            "download_url": "https://configured.example/api/exports/13/download",
            "requires_bearer_auth": true, "playable_in_app": false}
         ]}
    """

    @Test
    fun `the list parses under the names the backend sends`() = runTest {
        val repository = repository()
        server.enqueue(json(twoPartBatch))

        val response = repository.audiobookExports()

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/exports", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))

        assertTrue(response!!.ffmpegAvailable)
        val first = response.exports.first()
        assertEquals(12, first.id)
        assertEquals(7, first.fictionId)
        assertEquals("Ashes of the Sun", first.fictionTitle)
        assertEquals(1, first.partIndex)
        assertEquals(2, first.partCount)
        assertEquals(120, first.chapterCount)
        assertEquals(1, first.firstChapterNumber)
        assertEquals(120, first.lastChapterNumber)
        assertEquals("12h 03m", first.durationLabel)
        assertEquals(1_503_238_553L, first.sizeBytes)
        assertEquals("1.4 GB", first.sizeLabel)
        assertEquals("2026-08-20T10:41:00Z", first.completedAt)
        assertEquals("https://configured.example/api/exports/12/download", first.downloadUrl)
    }

    /**
     * The two flags the endpoint exists to state. Both are what stop a client offering the wrong
     * thing: a play button for a file the app should not play, or a plain link to a browser that
     * would be handed a 401.
     */
    @Test
    fun `every entry says it is not playable here and needs a bearer token`() = runTest {
        val repository = repository()
        server.enqueue(json(twoPartBatch))

        val exports = repository.audiobookExports()!!.exports

        assertTrue(exports.all { it.requiresBearerAuth })
        assertFalse(exports.any { it.playableInApp })
    }

    /**
     * The server is free to add fields — that is the contract — and a strict model would fail the
     * whole list over one of them. This is the same guarantee the capability payload relies on.
     */
    @Test
    fun `a field this build has never heard of does not break the parse`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"ffmpeg_available": true,
                 "exports": [{"id": 1, "fiction_title": "Ashes", "size_label": "800 MB",
                              "a_field_added_later": {"nested": true}}]}
                """,
            ),
        )

        val exports = repository.audiobookExports()!!.exports

        assertEquals("Ashes", exports.single().fictionTitle)
    }

    /**
     * `ffmpeg_available` is not the capability. A server with the route and without the binary is a
     * real state, and one an empty list would describe as "nothing exported yet".
     */
    @Test
    fun `a server without ffmpeg still answers, and says so`() = runTest {
        val repository = repository()
        server.enqueue(json("""{"ffmpeg_available": false, "exports": []}"""))

        val response = repository.audiobookExports()

        assertFalse(response!!.ffmpegAvailable)
        assertTrue(response.exports.isEmpty())
        assertNotNull(audiobookExportEncoderNote(response))
    }

    @Test
    fun `a server without the route is never asked`() = runTest {
        val repository = repository(audiobookExport = false)

        assertNull(repository.audiobookExports())

        // Only the capability probe from setup; nothing was sent to a route that is not there.
        server.takeRequest()
        assertEquals(1, server.requestCount)
    }
}

/** Turning the payload into the lines Settings draws. */
class AudiobookExportRowsTest {

    private fun export(
        id: Int = 12,
        fictionTitle: String? = "Ashes of the Sun",
        title: String? = "Ashes of the Sun, Part 1",
        filename: String? = "ashes-part-1.m4b",
        partIndex: Int = 1,
        partCount: Int = 1,
        chapterCount: Int = 120,
        firstChapterNumber: Int? = 1,
        lastChapterNumber: Int? = 120,
        durationLabel: String? = "12h 03m",
        sizeLabel: String? = "1.4 GB",
        createdAt: String? = "2026-08-20T09:00:00Z",
        completedAt: String? = "2026-08-20T10:41:00Z",
        downloadUrl: String? = "https://configured.example/api/exports/12/download",
    ) = AudiobookExport(
        id = id,
        fictionTitle = fictionTitle,
        title = title,
        filename = filename,
        partIndex = partIndex,
        partCount = partCount,
        chapterCount = chapterCount,
        firstChapterNumber = firstChapterNumber,
        lastChapterNumber = lastChapterNumber,
        durationLabel = durationLabel,
        sizeLabel = sizeLabel,
        createdAt = createdAt,
        completedAt = completedAt,
        downloadUrl = downloadUrl,
    )

    /** UTC and a fixed locale, so the assertions do not depend on where the build runs. */
    private fun rows(
        vararg exports: AudiobookExport,
        ffmpegAvailable: Boolean = true,
        resolveUrl: (String) -> String = { it },
    ) = audiobookExportRows(
        AudiobookExportsResponse(
            ffmpegAvailable = ffmpegAvailable,
            exports = exports.toList(),
        ),
        zone = ZoneOffset.UTC,
        locale = Locale.UK,
        resolveUrl = resolveUrl,
    )

    @Test
    fun `a single-part export is named after the fiction, with size, length and range`() {
        val row = rows(export()).single()

        assertEquals("Ashes of the Sun", row.title)
        assertEquals("1.4 GB · 12h 03m · chapters 1–120", row.detail)
        assertEquals("20 Aug 2026, 10:41", row.finished)
    }

    /**
     * A row is a *file*, not a request: a long serial exports as several volumes sharing a batch.
     * Without the part label every one of them is the same line repeated, and the whole point of
     * the list — telling which files exist — is lost.
     */
    @Test
    fun `a split batch says which volume each row is`() {
        val listed = rows(
            export(id = 12, partIndex = 1, partCount = 2),
            export(id = 13, partIndex = 2, partCount = 2),
        )

        assertEquals("Ashes of the Sun — part 1 of 2", listed.first().title)
        assertEquals("Ashes of the Sun — part 2 of 2", listed.last().title)
    }

    /**
     * `fiction_title` comes from a join and is nullable. The export's own title already carries a
     * part suffix, so it is the fallback rather than something the part label is appended to.
     */
    @Test
    fun `a row without a fiction title falls back rather than going blank`() {
        assertEquals(
            "Ashes of the Sun, Part 1",
            rows(export(fictionTitle = null, partCount = 2)).single().title,
        )
        assertEquals(
            "ashes-part-1.m4b",
            rows(export(fictionTitle = "  ", title = null)).single().title,
        )
        assertEquals(
            "Export 12",
            rows(export(fictionTitle = null, title = null, filename = null)).single().title,
        )
    }

    /**
     * `chapter_number` is nullable server-side — an unnumbered interlude is a real thing — so a
     * range is only claimed when both ends of it exist.
     */
    @Test
    fun `unnumbered chapters are counted instead of ranged`() {
        val row = rows(
            export(firstChapterNumber = null, lastChapterNumber = null, chapterCount = 118),
        ).single()

        assertEquals("1.4 GB · 12h 03m · 118 chapters", row.detail)
    }

    @Test
    fun `a one-chapter volume does not read as a range from itself to itself`() {
        val row = rows(export(firstChapterNumber = 7, lastChapterNumber = 7)).single()

        assertTrue(row.detail, row.detail.endsWith("chapter 7"))
    }

    /** A server that labels nothing leaves an empty detail line, not the word "null". */
    @Test
    fun `missing labels drop out of the detail line`() {
        val row = rows(
            export(
                sizeLabel = null,
                durationLabel = "",
                firstChapterNumber = null,
                lastChapterNumber = null,
                chapterCount = 0,
            ),
        ).single()

        assertEquals("", row.detail)
    }

    /**
     * The backend builds the URL from its own `BASE_URL`, which may be an address this phone has
     * never resolved — and is a relative path when `BASE_URL` is unset, which is worse than useless
     * in a share sheet. The host the user actually signed in to is the one that works.
     */
    @Test
    fun `the download URL is pointed at the host this phone reached`() {
        val row = rows(export()) { url -> url.replace("https://configured.example", "http://10.0.0.4:8000") }
            .single()

        assertEquals("http://10.0.0.4:8000/api/exports/12/download", row.downloadUrl)
    }

    @Test
    fun `an export with no link is null rather than an empty share`() {
        assertNull(rows(export(downloadUrl = null)).single().downloadUrl)
        assertNull(rows(export(downloadUrl = "   ")).single().downloadUrl)
    }

    /** A row that never finished encoding is not listed at all, but a missing date still shows. */
    @Test
    fun `the queued time stands in when the server recorded no completion`() {
        assertEquals("20 Aug 2026, 09:00", rows(export(completedAt = null)).single().finished)
        assertNull(rows(export(completedAt = null, createdAt = null)).single().finished)
    }

    @Test
    fun `nothing to list produces no rows and no complaint`() {
        assertTrue(audiobookExportRows(null).isEmpty())
        assertTrue(audiobookExportRows(AudiobookExportsResponse()).isEmpty())
    }

    /**
     * Nothing has been fetched yet is not the same as a server that cannot encode. Saying the
     * second before asking would be a guess presented as fact.
     */
    @Test
    fun `the encoder note is only made once the server has answered`() {
        assertNull(audiobookExportEncoderNote(null))
        assertNull(audiobookExportEncoderNote(AudiobookExportsResponse(ffmpegAvailable = true)))
        assertTrue(
            audiobookExportEncoderNote(AudiobookExportsResponse(ffmpegAvailable = false))
                .orEmpty()
                .contains("ffmpeg"),
        )
    }
}

/** The capability that gates the whole section. */
class AudiobookExportCapabilityTest {
    @Test
    fun `it is off at the baseline`() {
        assertFalse(ServerCapabilities.Baseline.audiobookExport)
    }

    @Test
    fun `only a literal true enables it`() {
        val loose = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("audiobook_export" to "yes")),
        )
        assertFalse(loose.audiobookExport)

        val strict = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("audiobook_export" to true)),
        )
        assertTrue(strict.audiobookExport)
    }

    @Test
    fun `it is named in the capability panel`() {
        assertEquals("Audiobook exports", CapabilityCatalog.label("audiobook_export"))
    }
}
