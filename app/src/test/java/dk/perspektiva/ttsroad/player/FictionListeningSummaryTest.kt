package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.LibraryProgress
import dk.perspektiva.ttsroad.data.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FictionListeningSummaryTest {
    private fun chapter(
        id: Int,
        durationSeconds: Double? = 600.0,
        positionSeconds: Double = 0.0,
        remainingSeconds: Double? = null,
        isPlayed: Boolean = false,
        hasAudio: Boolean = true,
    ) = ChapterSummary(
        id = id,
        fictionId = 1,
        title = "Chapter $id",
        playable = hasAudio,
        audioDuration = durationSeconds,
        audio = if (hasAudio) AudioInfo(url = "https://server/audio/$id.mp3") else null,
        playback = PlaybackInfo(
            positionSeconds = positionSeconds,
            isPlayed = isPlayed,
            remainingSeconds = remainingSeconds,
        ),
    )

    @Test
    fun `an untouched fiction has everything remaining`() {
        val summary = fictionListeningSummary(listOf(chapter(1), chapter(2), chapter(3)))

        assertEquals(3, summary.playable)
        assertEquals(0, summary.played)
        assertEquals(3, summary.unplayed)
        assertEquals(1800.0, summary.remainingSeconds, 0.001)
        assertTrue(summary.hasRemaining)
    }

    @Test
    fun `the library aggregate becomes the detail summary without new arithmetic`() {
        val summary = LibraryProgress(
            chaptersReady = 73,
            chaptersPlayed = 12,
            chaptersUnplayed = 61,
            durationSeconds = 196_692.0,
            remainingSeconds = 150_031.0,
            remainingLabel = "41h 41m",
        ).toFictionListeningSummary()

        assertEquals(73, summary.playable)
        assertEquals(12, summary.played)
        assertEquals(61, summary.unplayed)
        assertEquals(150_031.0, summary.remainingSeconds, 0.001)
        assertEquals("41h 41m", summary.remainingLabel)
        assertTrue(summary.hasRemaining)
    }

    @Test
    fun `a zero remainder with known duration is a finished answer`() {
        val summary = LibraryProgress(
            chaptersReady = 2,
            chaptersPlayed = 2,
            durationSeconds = 1200.0,
            remainingSeconds = 0.0,
            remainingLabel = "0m",
        ).toFictionListeningSummary()

        assertTrue(summary.hasRemaining)
        assertEquals("0m", summary.remainingLabel)
    }

    @Test
    fun `the server's remaining_seconds wins over local arithmetic`() {
        // Deliberately inconsistent with duration minus position: the server is the authority, and
        // this is what proves the local fallback is a fallback rather than the primary path.
        val summary = fictionListeningSummary(
            listOf(chapter(1, positionSeconds = 100.0, remainingSeconds = 42.0)),
        )

        assertEquals(42.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `remaining falls back to duration minus position when the server omits it`() {
        val summary = fictionListeningSummary(listOf(chapter(1, positionSeconds = 150.0)))

        assertEquals(450.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `a chapter marked played counts as finished even at position zero`() {
        // Mark-played is a real action, and trusting the position here would report a fiction the
        // user has explicitly finished as entirely unheard.
        val summary = fictionListeningSummary(
            listOf(chapter(1, isPlayed = true), chapter(2)),
        )

        assertEquals(1, summary.played)
        assertEquals(1, summary.unplayed)
        assertEquals(600.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `chapters without audio are not counted`() {
        val summary = fictionListeningSummary(
            listOf(chapter(1), chapter(2, hasAudio = false)),
        )

        assertEquals(1, summary.playable)
        assertEquals(600.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `a duplicated chapter is counted once`() {
        val summary = fictionListeningSummary(listOf(chapter(1), chapter(1)))

        assertEquals(1, summary.playable)
        assertEquals(600.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `no durations anywhere reports unknown rather than zero remaining`() {
        val summary = fictionListeningSummary(
            listOf(chapter(1, durationSeconds = null), chapter(2, durationSeconds = null)),
        )

        assertEquals(2, summary.playable)
        assertEquals(2, summary.unplayed)
        assertFalse(summary.hasRemaining)
    }

    @Test
    fun `an all-played fiction still reports its chapter counts`() {
        val summary = fictionListeningSummary(
            listOf(chapter(1, isPlayed = true), chapter(2, isPlayed = true)),
        )

        assertEquals(2, summary.played)
        assertEquals(0, summary.unplayed)
        assertEquals(0.0, summary.remainingSeconds, 0.001)
        assertFalse(summary.hasRemaining)
    }

    @Test
    fun `an empty chapter list is empty rather than a crash`() {
        val summary = fictionListeningSummary(emptyList())

        assertEquals(0, summary.playable)
        assertEquals(0, summary.unplayed)
        assertFalse(summary.hasRemaining)
    }

    @Test
    fun `a position past the duration never yields negative remaining`() {
        val summary = fictionListeningSummary(
            listOf(chapter(1, durationSeconds = 600.0, positionSeconds = 900.0)),
        )

        assertEquals(0.0, summary.remainingSeconds, 0.001)
    }

    @Test
    fun `spans read in hours and minutes`() {
        assertEquals("54h 38m", formatListeningSpan(54 * 3600.0 + 38 * 60.0))
        assertEquals("38m", formatListeningSpan(38 * 60.0))
        assertEquals("1h 0m", formatListeningSpan(3600.0))
        assertEquals("0m", formatListeningSpan(0.0))
        assertEquals("0m", formatListeningSpan(-5.0))
    }

    @Test
    fun `a span that rounds away still reads as some audio`() {
        assertEquals("<1m", formatListeningSpan(20.0))
    }

    @Test
    fun `speed scales the finish estimate`() {
        assertEquals(3600.0, listeningSpanAtSpeed(7200.0, 2f), 0.001)
        assertEquals(7200.0, listeningSpanAtSpeed(7200.0, 1f), 0.001)
    }

    @Test
    fun `an impossible speed leaves the span alone rather than producing infinity`() {
        assertEquals(7200.0, listeningSpanAtSpeed(7200.0, 0f), 0.001)
        assertEquals(7200.0, listeningSpanAtSpeed(7200.0, -1f), 0.001)
        assertEquals(7200.0, listeningSpanAtSpeed(7200.0, Float.NaN), 0.001)
    }

    @Test
    fun `chapter remaining clamps to the chapter`() {
        assertEquals(4_000L, remainingMs(positionMs = 6_000L, durationMs = 10_000L))
        assertEquals(0L, remainingMs(positionMs = 12_000L, durationMs = 10_000L))
        assertEquals(0L, remainingMs(positionMs = 0L, durationMs = 0L))
    }

    @Test
    fun `chapter remaining at speed is wall-clock time`() {
        assertEquals(2_000L, remainingMsAtSpeed(positionMs = 6_000L, durationMs = 10_000L, speed = 2f))
        assertEquals(4_000L, remainingMsAtSpeed(positionMs = 6_000L, durationMs = 10_000L, speed = 1f))
        assertEquals(4_000L, remainingMsAtSpeed(positionMs = 6_000L, durationMs = 10_000L, speed = 0f))
    }
}
