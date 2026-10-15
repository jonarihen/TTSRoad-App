package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimeMatchTest {
    private val minute = 60_000L
    private val midnight = 1_700_000_000_000L

    private fun snap(minutesFromMidnight: Long, chapterId: Int = 7) = HistorySnapshot(
        timestamp = midnight + minutesFromMidnight * minute,
        mediaId = "chapter:$chapterId",
        fictionId = 3,
        chapterId = chapterId,
        title = "Chapter $chapterId",
        fictionTitle = "Ashes of Aether",
        positionMs = 0L,
    )

    @Test
    fun `nothing recorded is reported as no history`() {
        assertEquals(SleepTimeMatch.NoHistory, matchSleepTime(emptyList(), midnight))
    }

    @Test
    fun `a typed time near a recorded moment jumps there`() {
        val trail = listOf(snap(-30), snap(-15), snap(-5))

        assertEquals(SleepTimeMatch.Near(trail[1]), matchSleepTime(trail, midnight - 11 * minute))
    }

    @Test
    fun `a typed time hours from anything recorded is not jumped to silently`() {
        // Listened 21:00-21:10, typed 23:49: the closest is 21:10, two hours and 39 minutes away.
        val trail = listOf(snap(-180), snap(-170))

        val match = matchSleepTime(trail, midnight - 11 * minute)

        assertEquals(SleepTimeMatch.Far(trail[1], 159 * minute), match)
    }

    @Test
    fun `server breadcrumbs alone are matched, as the list shows them`() {
        // New phone or after CLEAR: no local history, only the server's five-minute trail (#319).
        val remote = (0L..12L).map { snap(-60 + it * 5) }
        val trail = mergeBreadcrumbs(local = emptyList(), remote = remote)

        assertEquals(SleepTimeMatch.Near(snap(-10)), matchSleepTime(trail, midnight - 11 * minute))
    }

    @Test
    fun `the window edge still counts as near`() {
        val trail = listOf(snap(0))

        assertEquals(SleepTimeMatch.Near(trail[0]), matchSleepTime(trail, midnight - SleepTimeMatchWindowMs))
    }
}
