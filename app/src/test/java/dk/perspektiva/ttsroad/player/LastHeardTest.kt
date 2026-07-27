package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastHeardTest {
    private val now = 8 * 60 * 60_000L // 08:00, as epoch-ish millis; only deltas matter here.

    private fun snapshot(
        minutesBeforeNow: Long,
        positionMs: Long,
        mediaId: String = "chapter:7",
    ) = HistorySnapshot(
        timestamp = now - minutesBeforeNow * 60_000L,
        mediaId = mediaId,
        fictionId = 3,
        chapterId = 7,
        title = "Chapter 7",
        fictionTitle = "Ashes of Aether",
        positionMs = positionMs,
    )

    @Test
    fun `nothing to offer without any history`() {
        assertNull(lastHeardSnapshot(emptyList(), now))
    }

    @Test
    fun `offers the newest snapshot after an overnight gap`() {
        val history = listOf(
            snapshot(minutesBeforeNow = 600, positionMs = 60_000L),
            snapshot(minutesBeforeNow = 540, positionMs = 4_320_000L),
        )

        assertEquals(history.last(), lastHeardSnapshot(history, now))
    }

    @Test
    fun `stays quiet while the listening is still recent`() {
        val history = listOf(snapshot(minutesBeforeNow = 5, positionMs = 60_000L))

        assertNull(lastHeardSnapshot(history, now))
    }

    @Test
    fun `the threshold is the boundary, not an approximation`() {
        val justInside = listOf(snapshot(minutesBeforeNow = 29, positionMs = 1_000L))
        val justOutside = listOf(snapshot(minutesBeforeNow = 31, positionMs = 1_000L))

        assertNull(lastHeardSnapshot(justInside, now))
        assertEquals(justOutside.last(), lastHeardSnapshot(justOutside, now))
    }

    /**
     * The nightstand case: the stream stalled and kept logging the same position for an hour. The
     * useful answer is when the audio stopped moving, not the last identical repeat.
     */
    @Test
    fun `prefers the first of a run of snapshots at the same position`() {
        val stalledAt = 4_320_000L
        val history = listOf(
            snapshot(minutesBeforeNow = 130, positionMs = 3_000_000L),
            snapshot(minutesBeforeNow = 120, positionMs = stalledAt),
            snapshot(minutesBeforeNow = 110, positionMs = stalledAt),
            snapshot(minutesBeforeNow = 100, positionMs = stalledAt),
        )

        val chosen = lastHeardSnapshot(history, now)

        assertEquals(now - 120 * 60_000L, chosen?.timestamp)
        assertEquals(stalledAt, chosen?.positionMs)
    }

    @Test
    fun `an identical position in a different chapter does not extend the run`() {
        val history = listOf(
            snapshot(minutesBeforeNow = 120, positionMs = 90_000L, mediaId = "chapter:6"),
            snapshot(minutesBeforeNow = 110, positionMs = 90_000L, mediaId = "chapter:7"),
        )

        assertEquals("chapter:7", lastHeardSnapshot(history, now)?.mediaId)
        assertEquals(now - 110 * 60_000L, lastHeardSnapshot(history, now)?.timestamp)
    }

    @Test
    fun `a run covering the whole history still resolves to its first entry`() {
        val history = listOf(
            snapshot(minutesBeforeNow = 200, positionMs = 500L),
            snapshot(minutesBeforeNow = 190, positionMs = 500L),
        )

        assertEquals(now - 200 * 60_000L, lastHeardSnapshot(history, now)?.timestamp)
    }

    @Test
    fun `age is measured from the newest snapshot, not the run it resolves to`() {
        // The run starts 40 minutes ago but the last repeat is 2 minutes old: playback is still
        // going, so there is nothing to catch up on.
        val history = listOf(
            snapshot(minutesBeforeNow = 40, positionMs = 700L),
            snapshot(minutesBeforeNow = 2, positionMs = 700L),
        )

        assertNull(lastHeardSnapshot(history, now))
    }
}
