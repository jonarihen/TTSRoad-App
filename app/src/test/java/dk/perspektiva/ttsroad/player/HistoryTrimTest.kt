package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTrimTest {
    private val now = 1_000_000_000_000L

    private fun snap(ageMs: Long) = HistorySnapshot(
        timestamp = now - ageMs, mediaId = "chapter:1", title = "t", fictionTitle = null, positionMs = 0,
    )

    @Test
    fun `a heavy listener's day before yesterday is still there, a fourth day is not`() {
        val hour = 3_600_000L
        val history = listOf(snap(80 * hour), snap(71 * hour), snap(30 * hour), snap(0))

        val kept = trimHistory(history, now)

        assertEquals(listOf(71 * hour, 30 * hour, 0L), kept.map { now - it.timestamp })
    }

    @Test
    fun `the count is only a ceiling and keeps the newest`() {
        val history = (0..HistorySnapshotCapacity).map { snap((HistorySnapshotCapacity - it) * 1_000L) }

        val kept = trimHistory(history, now)

        assertEquals(HistorySnapshotCapacity, kept.size)
        assertEquals(history.last(), kept.last())
        assertEquals(history[1], kept.first())
    }
}
