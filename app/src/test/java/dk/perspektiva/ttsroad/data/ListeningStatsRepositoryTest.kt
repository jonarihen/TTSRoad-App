package dk.perspektiva.ttsroad.data

import java.time.ZoneId
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

private class FakeStatsSessionStore(
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
 * `GET /api/mobile/stats` — the listening figures the web has always had (#117).
 *
 * Two things here are load-bearing beyond "the JSON parses". The first is that a server without the
 * endpoint answers **null** rather than throwing, because the screen says something different for
 * each and collapsing them would tell someone on an old server they had never listened to anything.
 * The second is the conditional request: this is per-user aggregation over every playback row on the
 * account, the server offers an `ETag` precisely because a stats screen gets opened and closed a
 * lot, and a `304` carries no body — so a client that fed the response straight to Moshi would turn
 * the cheap path into a parse failure.
 */
class ListeningStatsRepositoryTest {
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

    private suspend fun repository(listeningStats: Boolean = true): TtsRoadRepository {
        val store = FakeStatsSessionStore()
        store.saveLogin(
            server.url("/").toString(),
            LoginResponse(token = "t0ken", user = MobileUser(id = 1, username = "reader")),
        )
        val repository = TtsRoadRepository(store)
        server.enqueue(
            json(
                """
                {"api_version": 1, "server": {"name": "TTSRoad"},
                 "capabilities": {"listening_stats": $listeningStats},
                 "limits": {}}
                """,
            ),
        )
        repository.refreshCurrentCapabilities()
        return repository
    }

    /** Any opaque validator token; the client only ever echoes it back. */
    private val etag = "W/\"stats-9\""

    /** A payload shaped exactly like `app/services/stats.py` builds it. */
    private val fullPayload = """
        {"api_version": 1,
         "generated_at": "2026-03-12T09:00:00Z",
         "weeks": 12,
         "stats": {
           "has_data": true,
           "seconds": 351000.0,
           "time_label": "97h 30m",
           "hours": 97.5,
           "chapters_finished": 412,
           "chapters_finished_label": "412",
           "chapters_in_progress": 3,
           "books_started": 9,
           "books_finished": 2,
           "words": 1204331,
           "words_label": "1.20M",
           "words_exact_label": "1,204,331",
           "pages": 4817,
           "pages_label": "4,817",
           "words_per_page": 250,
           "uncounted_chapters": 6,
           "current_streak": 4,
           "longest_streak": 31,
           "first_listened_at": "2025-04-02T18:11:00Z",
           "last_listened_at": "2026-03-12T07:40:00Z",
           "daily_average_label": "17m",
           "busiest_day": {"date": "2025-12-27", "time_label": "6h 12m", "chapters": 11},
           "activity_weeks": [
             [{"date": "2026-03-09", "future": false, "chapters": 2, "level": 3,
               "label": "2026-03-09 — 2 chapters finished, 1h 4m"},
              {"date": "2026-03-10", "future": false, "chapters": 0, "level": 0,
               "label": "2026-03-10 — nothing recorded"},
              {"date": "2026-03-15", "future": true, "chapters": 0, "level": 0,
               "label": "2026-03-15 — nothing recorded"}]
           ],
           "activity_days": 84,
           "top_fictions": [
             {"id": 7, "title": "Mother of Learning", "author": "nobody103",
              "seconds": 180000.0, "time_label": "50h 0m", "chapters_finished": 108,
              "total_chapters": 108, "percent": 100, "complete": true}
           ],
           "comparisons": [
             {"value": "1.6×", "label": "the Lord of the Rings, unabridged",
              "detail": "≈ 60 hours"}
           ],
           "milestones": [
             {"icon": "clock", "group": "Hours", "title": "100 hours listened",
              "earned": false, "progress": 97, "detail": "3 to go"}
           ]
         }}
    """

    @Test
    fun `a server without the endpoint answers null and is never asked`() = runTest {
        val repository = repository(listeningStats = false)

        assertNull(repository.listeningStats())

        // Only the capability probe. Asking anyway would 404, and the screen would report a
        // failure where the honest answer is "this server does not do that".
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `every group of figures survives the wire`() = runTest {
        val repository = repository()
        server.enqueue(json(fullPayload))

        val response = repository.listeningStats()

        server.takeRequest()
        assertEquals("/api/mobile/stats?weeks=12", server.takeRequest().path)
        assertNotNull(response)
        assertEquals(12, response?.weeks)

        val stats = response!!.stats
        assertTrue(stats.hasData)
        assertEquals("97h 30m", stats.timeLabel)
        assertEquals(97.5, stats.hours, 0.001)
        assertEquals(412, stats.chaptersFinished)
        assertEquals(3, stats.chaptersInProgress)
        assertEquals(9, stats.booksStarted)
        assertEquals(2, stats.booksFinished)
        assertEquals(1_204_331L, stats.words)
        assertEquals("1.20M", stats.wordsLabel)
        assertEquals(4_817L, stats.pages)
        assertEquals(6, stats.uncountedChapters)
        assertEquals(4, stats.currentStreak)
        assertEquals(31, stats.longestStreak)
        assertEquals("17m", stats.dailyAverageLabel)
        assertEquals(84, stats.activityDays)

        assertEquals("2025-12-27", stats.busiestDay?.date)
        assertEquals("6h 12m", stats.busiestDay?.timeLabel)
        assertEquals(11, stats.busiestDay?.chapters)

        val week = stats.activityWeeks.single()
        assertEquals(3, week.size)
        assertEquals(3, week[0].level)
        assertFalse(week[0].future)
        assertEquals("2026-03-09 — 2 chapters finished, 1h 4m", week[0].label)
        // A day that has not happened is not a quiet day, and the payload distinguishes them.
        assertTrue(week[2].future)

        val fiction = stats.topFictions.single()
        assertEquals(7, fiction.id)
        assertEquals("Mother of Learning", fiction.title)
        assertEquals("50h 0m", fiction.timeLabel)
        assertEquals(108, fiction.totalChapters)
        assertEquals(100, fiction.percent)
        assertTrue(fiction.complete)

        // Pre-formatted server-side on purpose: re-deriving the multiplier here is how two clients
        // start describing one account two different ways.
        assertEquals("1.6×", stats.comparisons.single().value)
        assertEquals("the Lord of the Rings, unabridged", stats.comparisons.single().label)
        assertEquals("100 hours listened", stats.milestones.single().title)
        assertEquals(97, stats.milestones.single().progress)
        assertFalse(stats.milestones.single().earned)
    }

    @Test
    fun `an empty account is data, not a failure`() = runTest {
        val repository = repository()
        server.enqueue(
            json("""{"api_version": 1, "weeks": 12, "stats": {"has_data": false, "seconds": 0.0}}"""),
        )

        val stats = repository.listeningStats()?.stats

        assertNotNull(stats)
        assertFalse(stats!!.hasData)
        assertTrue(stats.activityWeeks.isEmpty())
    }

    @Test
    fun `the second look sends the etag and a 304 answers from the cache`() = runTest {
        val repository = repository()
        server.enqueue(json(fullPayload).setHeader("ETag", etag))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", etag))

        val first = repository.listeningStats()
        val second = repository.listeningStats()

        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertEquals(etag, server.takeRequest().getHeader("If-None-Match"))
        // The 304 carried no body at all. Anything but the cached payload here means the cheap
        // path is answering with an empty stats object.
        assertEquals("97h 30m", first?.stats?.timeLabel)
        assertEquals("97h 30m", second?.stats?.timeLabel)
    }

    /**
     * A different grid size will not answer the previous `ETag` — the server folds `weeks` into the
     * revision — so the two must not share a cache entry.
     */
    @Test
    fun `a different weeks value asks unconditionally`() = runTest {
        val repository = repository()
        server.enqueue(json(fullPayload).setHeader("ETag", etag))
        server.enqueue(json(fullPayload))

        repository.listeningStats(weeks = 12)
        repository.listeningStats(weeks = 4)

        server.takeRequest()
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/api/mobile/stats?weeks=4", second.path)
        assertNull(second.getHeader("If-None-Match"))
    }

    /** The server answers 422 outside 1..53 rather than clamping, so the client clamps first. */
    @Test
    fun `weeks is clamped to the range the server accepts`() = runTest {
        val repository = repository()
        server.enqueue(json(fullPayload))
        server.enqueue(json(fullPayload))

        repository.listeningStats(weeks = 0)
        repository.listeningStats(weeks = 400)

        server.takeRequest()
        assertEquals("/api/mobile/stats?weeks=1", server.takeRequest().path)
        assertEquals("/api/mobile/stats?weeks=53", server.takeRequest().path)
    }

    @Test
    fun `a server failure throws rather than looking like an empty account`() = runTest {
        val repository = repository()
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = runCatching { repository.listeningStats() }.exceptionOrNull()

        assertNotNull(thrown)
    }

    @Test
    fun `the busiest day resolves to a local calendar day, not a UTC instant`() {
        val zone = ZoneId.of("Europe/Copenhagen")
        val day = BusiestListeningDay(date = "2025-12-27", timeLabel = "6h 12m", chapters = 11)

        val millis = day.startOfDayMillis(zone)

        // 2025-12-27 00:00 in Copenhagen is 23:00 UTC on the 26th. Reading the bare date as a UTC
        // instant would show the wrong day to anyone east of Greenwich.
        assertEquals(1766790000000L, millis)
    }

    @Test
    fun `a date the client cannot read is null rather than a crash`() {
        assertNull(BusiestListeningDay(date = "").startOfDayMillis())
        assertNull(BusiestListeningDay(date = "not-a-date").startOfDayMillis())
    }
}
