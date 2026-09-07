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

/**
 * Skipping the adverts a server marks, on audio that was never re-narrated.
 *
 * Two halves, and the interesting failures are all in the second. Parsing is ordinary; the
 * arithmetic is not, because every way of getting it wrong takes something from a listener — a
 * target computed early clips the last line of a paragraph, a wait computed long plays the plug
 * anyway, and a segment left over from the previous chapter seeks into the middle of this one's
 * prose.
 */
class ChapterSkipsTest {

    /** 100–140s is a mid-chapter plug; 590–600s is a trailing note in a 600s chapter. */
    private val skips = ChapterSkips(
        chapterId = 7,
        segments = listOf(
            ChapterSkipSegment(startMs = 100_000, endMs = 140_000),
            ChapterSkipSegment(startMs = 590_000, endMs = 600_000),
        ),
        durationMs = 600_000,
    )

    @Test
    fun `outside an advert there is nowhere to jump to`() {
        assertNull(skips.targetFor(50_000))
        assertNull(skips.targetFor(160_000))
    }

    @Test
    fun `inside an advert the jump lands where it ends`() {
        assertEquals(140_000L, skips.targetFor(100_000))
        assertEquals(140_000L, skips.targetFor(120_000))
    }

    @Test
    fun `a quarter second short of an advert still counts as inside it`() {
        // The tolerance is on the near edge only: arriving just before an advert means hearing it.
        assertEquals(140_000L, skips.targetFor(99_900))
    }

    @Test
    fun `a quarter second short of the end of one does not`() {
        // A seek that saves a twentieth of a second costs a re-buffer to make.
        assertNull(skips.targetFor(139_900))
    }

    @Test
    fun `an advert that runs to the end is the end of the chapter`() {
        val target = skips.targetFor(592_000)

        assertEquals(600_000L, target)
        assertTrue(skips.endsChapter(target!!))
        // …and a mid-chapter one is not, however long it is.
        assertFalse(skips.endsChapter(140_000))
    }

    @Test
    fun `a chapter of unknown length still skips, it just cannot end itself`() {
        // The duration is unknown while a chapter is still buffering, and an advert reached in that
        // window should still be skipped rather than waited out.
        val unknown = skips.copy(durationMs = 0)

        assertEquals(140_000L, unknown.targetFor(120_000))
        assertFalse(unknown.endsChapter(600_000))
    }

    @Test
    fun `the wait is the distance to the next advert, scaled by speed`() {
        // 50s of prose before the plug at 100s, heard at 2x, is 25 seconds of wall clock — but the
        // ceiling is what actually applies, because a seek made from the car has to be noticed.
        assertEquals(ChapterSkips.MaxWaitMs, skips.waitMs(positionMs = 50_000, speed = 2f))

        // Close enough for the distance itself to be under the ceiling.
        assertEquals(9_750L, skips.waitMs(positionMs = 90_000, speed = 1f))
        assertEquals(4_875L, skips.waitMs(positionMs = 90_000, speed = 2f))
    }

    @Test
    fun `a position still inside an advert is looked at again immediately`() {
        // The ordinary path never sees this: a seek moves the clock past the segment before the
        // next wait is computed. This is the under-shoot case — and waiting out the ceiling there
        // would mean twenty seconds of the advert this exists to skip.
        assertEquals(ChapterSkips.MinWaitMs, skips.waitMs(positionMs = 99_800, speed = 1f))
        assertEquals(ChapterSkips.MinWaitMs, skips.waitMs(positionMs = 120_000, speed = 1f))
        // …and it is a floor, not a spin: the value is a quarter second, not zero.
        assertTrue(ChapterSkips.MinWaitMs > 0)
    }

    @Test
    fun `past the last advert there is nothing left to wait for`() {
        // The ceiling, not "never": the clock is re-read because a listener can seek backwards
        // into an advert from the notification without this loop being told.
        val onlyMidChapter = skips.copy(segments = skips.segments.take(1))

        assertEquals(ChapterSkips.MaxWaitMs, onlyMidChapter.waitMs(positionMs = 200_000, speed = 1f))
    }

    @Test
    fun `a malformed segment is dropped rather than guessed at`() {
        // These are seconds of somebody's book. A row whose numbers do not make sense is not a row
        // whose numbers can be repaired, and acting on one seeks into prose.
        val parsed = ChapterSkips.from(
            ChapterSkipsResponse(
                chapterId = 7,
                audioDuration = 600.0,
                segments = listOf(
                    ChapterSkipSegmentDto(startSeconds = 140.0, endSeconds = 100.0),
                    ChapterSkipSegmentDto(startSeconds = -5.0, endSeconds = 10.0),
                    ChapterSkipSegmentDto(startSeconds = 20.0, endSeconds = 20.0),
                    ChapterSkipSegmentDto(startSeconds = 100.0, endSeconds = 140.0),
                ),
            ),
            chapterId = 7,
        )

        assertEquals(listOf(ChapterSkipSegment(100_000, 140_000)), parsed.segments)
        assertEquals(600_000L, parsed.durationMs)
    }

    @Test
    fun `segments arrive in order whatever order they were sent in`() {
        val parsed = ChapterSkips.from(
            ChapterSkipsResponse(
                segments = listOf(
                    ChapterSkipSegmentDto(startSeconds = 590.0, endSeconds = 600.0),
                    ChapterSkipSegmentDto(startSeconds = 100.0, endSeconds = 140.0),
                ),
            ),
            chapterId = 7,
        )

        assertEquals(listOf(100_000L, 590_000L), parsed.segments.map { it.startMs })
    }
}

/**
 * The wire half: what the server sends, and what this client refuses to ask for.
 */
class ChapterSkipsRepositoryTest {
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

    private suspend fun repository(playbackSkips: Boolean = true): TtsRoadRepository {
        val store = FakeSessionStore(SessionState())
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(
                token = "t0ken",
                user = MobileUser(id = 1, username = "listener", isAdmin = false),
            ),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """
                {"api_version": 1, "server": {"name": "TTSRoad"},
                 "capabilities": {"playback_skips": $playbackSkips}, "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    @Test
    fun `the payload parses under the names the backend sends`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"api_version": 1, "chapter_id": 10, "has_timings": true, "rule_count": 1,
                 "audio_duration": 1420.5,
                 "segments": [
                   {"start_seconds": 1389.2, "end_seconds": 1420.5, "duration_seconds": 31.3,
                    "label": "global:skip_between:4",
                    "preview": "Important Announcement! Twenty chapters ahead are on Patreon."}
                 ],
                 "total_skipped_seconds": 31.3}
                """,
            ),
        )

        val skips = repository.chapterSkips(10)

        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/mobile/chapters/10/skips", request.path)
        assertEquals("Bearer t0ken", request.getHeader("Authorization"))
        assertEquals(1, skips.segments.size)
        assertEquals(1_389_200L, skips.segments.single().startMs)
        assertEquals(1_420_500L, skips.segments.single().endMs)
        assertEquals(1_420_500L, skips.durationMs)
    }

    @Test
    fun `a server without the capability is never asked`() = runTest {
        val repository = repository(playbackSkips = false)

        val skips = repository.chapterSkips(10)

        assertTrue(skips.isEmpty)
        // Discovery only. Asking would 404 on every chapter of every older server.
        server.takeRequest()
        assertEquals(0, server.requestCount - 1)
    }

    @Test
    fun `a chapter with nothing to skip is an ordinary answer`() = runTest {
        val repository = repository()
        server.enqueue(
            json(
                """
                {"api_version": 1, "chapter_id": 10, "has_timings": false, "rule_count": 0,
                 "audio_duration": 1420.5, "segments": [], "total_skipped_seconds": 0.0}
                """,
            ),
        )

        assertTrue(repository.chapterSkips(10).isEmpty)
    }

    @Test
    fun `a failure costs the skipping, never the chapter`() = runTest {
        // The worst case of getting this wrong is hearing an advert. Taking playback down to avoid
        // that would be a far worse trade, so a 500 answers "nothing to skip".
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(repository.chapterSkips(10).isEmpty)
    }
}
