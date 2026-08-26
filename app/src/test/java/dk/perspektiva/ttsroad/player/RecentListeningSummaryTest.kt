package dk.perspektiva.ttsroad.player

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind Settings → Recent listening (#117).
 *
 * A fixed zone and fixed dates, because half of what this file is about is where the local day
 * boundary falls. `Europe/Copenhagen` is the phone's zone and, more usefully, is not UTC — a bug
 * that filed everything under the UTC day would pass in UTC and be invisible.
 */
class RecentListeningSummaryTest {
    private val zone = ZoneId.of("Europe/Copenhagen")
    private val today = LocalDate.of(2026, 3, 12)
    private val yesterday = today.minusDays(1)

    private fun at(date: LocalDate, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute, second))
            .atZone(zone).toInstant().toEpochMilli()

    private fun snap(
        timestamp: Long,
        positionMs: Long,
        chapterId: Int = 1,
        fictionId: Int = 7,
        fictionTitle: String? = "Mother of Learning",
    ) = HistorySnapshot(
        timestamp = timestamp,
        mediaId = "chapter:$chapterId",
        fictionId = fictionId,
        chapterId = chapterId,
        title = "Chapter $chapterId",
        fictionTitle = fictionTitle,
        positionMs = positionMs,
    )

    /**
     * A stretch of ordinary playback: the service's 15s tick, position advancing in real time.
     *
     * [ticks] snapshots means [ticks] - 1 intervals, which is the whole reason a single snapshot
     * is worth nothing.
     */
    private fun run(
        start: Long,
        ticks: Int,
        chapterId: Int = 1,
        fictionId: Int = 7,
        fictionTitle: String? = "Mother of Learning",
        startPositionMs: Long = 0L,
    ): List<HistorySnapshot> = (0 until ticks).map { index ->
        snap(
            timestamp = start + index * 15_000L,
            positionMs = startPositionMs + index * 15_000L,
            chapterId = chapterId,
            fictionId = fictionId,
            fictionTitle = fictionTitle,
        )
    }

    private fun summarise(
        history: List<HistorySnapshot>,
        now: Long = at(today, 20, 0),
    ) = recentListeningSummary(history, now = now, zone = zone)

    @Test
    fun `an empty history says nothing rather than zero hours`() {
        val summary = summarise(emptyList())

        assertFalse(summary.hasHistory)
        assertEquals(0L, summary.todayMs)
        assertEquals(0L, summary.retainedMs)
        assertEquals(0, summary.snapshots)
        assertNull(summary.oldestAt)
        assertNull(summary.newestAt)
        assertFalse(summary.atCapacity)
    }

    @Test
    fun `a single snapshot is a point, not a span`() {
        // Something was recorded, so the store is not empty — but one point measures no duration,
        // and inventing a tick's worth of listening for it would be a guess.
        val summary = summarise(listOf(snap(at(today, 9, 0), positionMs = 0L)))

        assertTrue(summary.hasHistory)
        assertEquals(1, summary.snapshots)
        assertEquals(0L, summary.todayMs)
        assertEquals(0L, summary.retainedMs)
        assertEquals(at(today, 9, 0), summary.oldestAt)
        assertEquals(at(today, 9, 0), summary.newestAt)
    }

    @Test
    fun `an unbroken run counts every gap between its ticks`() {
        // 41 ticks = 40 intervals of 15s = ten minutes.
        val summary = summarise(run(start = at(today, 9, 0), ticks = 41))

        assertEquals(600_000L, summary.todayMs)
        assertEquals(600_000L, summary.retainedMs)
        assertEquals(1, summary.todayChapters)
        assertEquals(1, summary.todaySittings)
    }

    @Test
    fun `a night with playback paused is not a night of listening`() {
        // The case the break threshold exists for: pause before bed, resume after breakfast. The
        // store records nothing in between, so the naive sum would report eight hours.
        val evening = run(start = at(yesterday, 22, 0), ticks = 5)   // one minute
        val morning = run(start = at(today, 7, 0), ticks = 9)        // two minutes
        val summary = summarise(evening + morning)

        assertEquals(120_000L, summary.todayMs)
        assertEquals(180_000L, summary.retainedMs)
        assertEquals(1, summary.todaySittings)
    }

    @Test
    fun `two sittings today are two sittings`() {
        val commute = run(start = at(today, 8, 0), ticks = 5)
        val walk = run(start = at(today, 17, 0), ticks = 5, startPositionMs = 900_000L)
        val summary = summarise(commute + walk)

        assertEquals(120_000L, summary.todayMs)
        assertEquals(2, summary.todaySittings)
    }

    @Test
    fun `a gap just inside the threshold is still one sitting`() {
        val first = run(start = at(today, 8, 0), ticks = 3)
        // 90s after the last tick, position advanced by the same 90s: still listening as far as
        // anything recorded can tell.
        val resumed = listOf(snap(at(today, 8, 2, 0), positionMs = 120_000L))
        val summary = summarise(first + resumed)

        assertEquals(120_000L, summary.todayMs)
        assertEquals(1, summary.todaySittings)
    }

    @Test
    fun `a gap just over the threshold is a break`() {
        val first = run(start = at(today, 8, 0), ticks = 3)
        val resumed = listOf(snap(at(today, 8, 2, 1), positionMs = 121_000L))
        val summary = summarise(first + resumed)

        assertEquals(30_000L, summary.todayMs)
        assertEquals(1, summary.todaySittings)
    }

    @Test
    fun `a pause inside one interval only counts the audio that played`() {
        // Paused twenty seconds after a tick and resumed a minute later, so the whole thing lands
        // in a single 80s gap that is under the break threshold. Only 20s of it was listening, and
        // the position says so.
        val history = listOf(
            snap(at(today, 8, 0, 0), positionMs = 0L),
            snap(at(today, 8, 1, 20), positionMs = 20_000L),
        )
        val summary = summarise(history)

        assertEquals(20_000L, summary.todayMs)
        assertEquals(20_000L, summary.retainedMs)
    }

    @Test
    fun `listening at 1_75x reports the time spent, not the audio covered`() {
        // 15s of wall clock covers 26.25s of audio. The question is how long you listened.
        val history = (0 until 5).map { index ->
            snap(at(today, 8, 0) + index * 15_000L, positionMs = index * 26_250L)
        }
        val summary = summarise(history)

        assertEquals(60_000L, summary.todayMs)
    }

    @Test
    fun `a chapter that ran out on the nightstand counts as nothing`() {
        // A stalled stream and a finished chapter both keep logging the same position.
        val history = (0 until 40).map { index ->
            snap(at(today, 2, 0) + index * 15_000L, positionMs = 1_800_000L)
        }
        val summary = summarise(history)

        assertEquals(0L, summary.todayMs)
        assertEquals(0L, summary.retainedMs)
        assertEquals(0, summary.todaySittings)
        assertEquals(40, summary.snapshots)
    }

    @Test
    fun `a seek backwards still spent the time it took to get there`() {
        val history = listOf(
            snap(at(today, 8, 0, 0), positionMs = 300_000L),
            // Fifteen seconds later, rewound past where it started.
            snap(at(today, 8, 0, 15), positionMs = 240_000L),
            snap(at(today, 8, 0, 30), positionMs = 255_000L),
        )
        val summary = summarise(history)

        assertEquals(30_000L, summary.todayMs)
    }

    @Test
    fun `auto-advance across a chapter boundary is not a break`() {
        val first = run(start = at(today, 8, 0), ticks = 3, chapterId = 1)
        // The next chapter starts near zero, so the positions are not comparable; the gap stands.
        val second = run(start = at(today, 8, 0, 45), ticks = 3, chapterId = 2)
        val summary = summarise(first + second)

        assertEquals(75_000L, summary.todayMs)
        assertEquals(2, summary.todayChapters)
        assertEquals(1, summary.todaySittings)
    }

    @Test
    fun `an interval straddling midnight is split between the two days`() {
        val history = listOf(
            snap(at(yesterday, 23, 59, 55), positionMs = 0L),
            snap(at(today, 0, 0, 5), positionMs = 10_000L),
            snap(at(today, 0, 0, 20), positionMs = 25_000L),
        )
        val summary = summarise(history)

        // Five of the straddling ten seconds, plus the fifteen wholly inside today.
        assertEquals(20_000L, summary.todayMs)
        assertEquals(25_000L, summary.retainedMs)
    }

    @Test
    fun `a run that began yesterday and crossed midnight is one sitting today`() {
        val history = run(start = at(yesterday, 23, 58), ticks = 17) // through 00:02

        val summary = summarise(history)

        assertEquals(1, summary.todaySittings)
        assertEquals(120_000L, summary.todayMs)
        assertEquals(240_000L, summary.retainedMs)
    }

    @Test
    fun `yesterday's listening stays out of today's total`() {
        val summary = summarise(run(start = at(yesterday, 20, 0), ticks = 41))

        assertEquals(0L, summary.todayMs)
        assertEquals(600_000L, summary.retainedMs)
        assertEquals(0, summary.todayChapters)
        assertEquals(0, summary.todaySittings)
        assertTrue(summary.todayFictions.isEmpty())
    }

    @Test
    fun `the day boundary follows the phone's zone, not UTC`() {
        // 00:30 local on the 12th is 23:30 UTC on the 11th. Under a UTC day this listening would
        // be filed against yesterday and today would read zero.
        val summary = summarise(run(start = at(today, 0, 30), ticks = 5))

        assertEquals(60_000L, summary.todayMs)
    }

    @Test
    fun `a clock moved backwards costs one interval, not the total`() {
        val history = listOf(
            snap(at(today, 8, 0, 0), positionMs = 0L),
            snap(at(today, 8, 0, 15), positionMs = 15_000L),
            // The device clock jumped back an hour mid-session; the recorded order is unchanged.
            snap(at(today, 7, 0, 30), positionMs = 30_000L),
            snap(at(today, 7, 0, 45), positionMs = 45_000L),
        )
        val summary = summarise(history)

        assertEquals(30_000L, summary.todayMs)
        assertTrue(summary.todayMs >= 0L)
    }

    @Test
    fun `time is split by book, longest first`() {
        val long = run(start = at(today, 8, 0), ticks = 9, fictionId = 7, fictionTitle = "Worm")
        val short = run(
            start = at(today, 10, 0),
            ticks = 3,
            chapterId = 40,
            fictionId = 9,
            fictionTitle = "Pale",
        )
        val more = run(
            start = at(today, 11, 0),
            ticks = 3,
            chapterId = 41,
            fictionId = 9,
            fictionTitle = "Pale",
        )
        val summary = summarise(long + short + more)

        assertEquals(2, summary.todayFictions.size)
        val first = summary.todayFictions[0]
        assertEquals(7, first.fictionId)
        assertEquals("Worm", first.title)
        assertEquals(120_000L, first.listenedMs)
        assertEquals(1, first.chapters)

        val second = summary.todayFictions[1]
        assertEquals(9, second.fictionId)
        assertEquals("Pale", second.title)
        assertEquals(60_000L, second.listenedMs)
        assertEquals(2, second.chapters)

        assertEquals(180_000L, summary.todayMs)
        assertEquals(3, summary.todayChapters)
    }

    @Test
    fun `a book the player had no title for is still counted`() {
        val summary = summarise(run(start = at(today, 8, 0), ticks = 5, fictionTitle = null))

        assertEquals(1, summary.todayFictions.size)
        assertNull(summary.todayFictions[0].title)
        assertEquals(60_000L, summary.todayFictions[0].listenedMs)
    }

    @Test
    fun `a blank album title is treated as no title`() {
        val summary = summarise(run(start = at(today, 8, 0), ticks = 5, fictionTitle = "  "))

        assertNull(summary.todayFictions[0].title)
    }

    @Test
    fun `a full store is flagged, because it has already dropped older listening`() {
        val full = run(start = at(today, 8, 0), ticks = HistorySnapshotCapacity)

        val summary = summarise(full, now = at(today, 20, 0))

        assertEquals(HistorySnapshotCapacity, summary.snapshots)
        assertTrue(summary.atCapacity)
        // 1999 intervals of 15s — the eight and a half hours the cap is worth, and the reason the
        // surface cannot claim an all-time total.
        assertEquals(29_985_000L, summary.retainedMs)
        assertEquals(at(today, 8, 0), summary.oldestAt)
    }

    @Test
    fun `a store one snapshot short of full is not flagged`() {
        val nearlyFull = run(start = at(today, 8, 0), ticks = HistorySnapshotCapacity - 1)

        assertFalse(summarise(nearlyFull).atCapacity)
    }

    @Test
    fun `the cap is a sliding window, so the total only covers what is left`() {
        // Six hours of listening, but only the newest 2000 snapshots survived — exactly what the
        // store does. The summary must report the window it has, not the six hours.
        val everything = run(start = at(today, 6, 0), ticks = 2_400)
        val kept = everything.takeLast(HistorySnapshotCapacity)

        val summary = summarise(kept)

        assertTrue(summary.atCapacity)
        assertEquals(29_985_000L, summary.retainedMs)
        assertEquals(everything[400].timestamp, summary.oldestAt)
        assertEquals(everything.last().timestamp, summary.newestAt)
    }
}
