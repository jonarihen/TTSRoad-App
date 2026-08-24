package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far back the jump-back sheet can actually reach.
 *
 * The history holds about eight and a half hours; the sheet holds two dozen rows. At a flat
 * five-minute spacing those two dozen rows covered the most recent two hours and nothing older,
 * which is the wrong two hours for the case the feature exists for — falling asleep and waking up
 * to a book that played all night.
 */
class JumpBackOptionsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    /** A snapshot every 15 seconds, the way the service records them, going back [hours]. */
    private fun history(hours: Int): List<HistorySnapshot> {
        val ticks = hours * 60 * 4
        return (ticks downTo 0).map { tick ->
            val ago = tick * 15_000L
            HistorySnapshot(
                timestamp = now - ago,
                mediaId = "chapter:${1 + tick / 240}",
                fictionId = 1,
                chapterId = 1 + tick / 240,
                title = "Chapter ${1 + tick / 240}",
                fictionTitle = "Ashes of Aether",
                positionMs = ago,
            )
        }
    }

    private fun agesMinutes(options: List<HistorySnapshot>): List<Long> =
        options.map { (now - it.timestamp) / minute }

    @Test
    fun `an overnight history reaches back most of the night, not two hours of it`() {
        val options = jumpBackOptions(history(hours = 8), now)

        val oldest = agesMinutes(options).max()
        // The old flat spacing capped out at 24 x 5 = 120 minutes. Anything at all past that is the
        // point of the change; this asserts it reaches most of what the store actually holds.
        assertTrue("reached only ${oldest}m back", oldest >= 6 * 60)
    }

    @Test
    fun `the sheet still fits in two dozen rows`() {
        assertTrue(jumpBackOptions(history(hours = 8), now).size <= MaxJumpBackOptions)
    }

    @Test
    fun `the recent end keeps five-minute steps, where a rewind is a correction`() {
        val recent = agesMinutes(jumpBackOptions(history(hours = 8), now)).filter { it < 30 }

        assertTrue("expected several fine-grained offers, got $recent", recent.size >= 4)
        recent.zipWithNext { newer, older ->
            assertTrue("steps of ${older - newer}m near the present", older - newer <= 6)
        }
    }

    @Test
    fun `the far end coarsens rather than filling the sheet with the same half hour`() {
        val distant = agesMinutes(jumpBackOptions(history(hours = 8), now)).filter { it > 150 }

        assertTrue("expected offers past two and a half hours, got $distant", distant.isNotEmpty())
        distant.zipWithNext { newer, older ->
            assertTrue("steps of ${older - newer}m at the far end", older - newer >= 25)
        }
    }

    @Test
    fun `offers are newest first`() {
        val ages = agesMinutes(jumpBackOptions(history(hours = 8), now))

        assertEquals(ages.sorted(), ages)
    }

    @Test
    fun `the last minute is never offered, since it is where playback already is`() {
        val options = jumpBackOptions(history(hours = 1), now)

        assertTrue(options.all { now - it.timestamp >= minute })
    }

    @Test
    fun `a short history offers what little it has`() {
        val options = jumpBackOptions(history(hours = 1), now)

        assertTrue(options.isNotEmpty())
        assertTrue(agesMinutes(options).max() >= 50)
    }

    @Test
    fun `an empty history offers nothing rather than failing`() {
        assertTrue(jumpBackOptions(emptyList(), now).isEmpty())
    }

    @Test
    fun `a history of nothing but the last minute offers nothing`() {
        val recent = listOf(
            HistorySnapshot(now - 10_000L, "chapter:1", 1, 1, "Chapter 1", "Ashes", 0L),
        )

        assertTrue(jumpBackOptions(recent, now).isEmpty())
    }

    @Test
    fun `a gap in the history does not shift everything after it out of step`() {
        // Spacing is measured against the last row kept, not the previous snapshot, so a night with
        // playback paused in the middle does not desynchronise the older half.
        val withGap = history(hours = 1) + history(hours = 8).filter {
            now - it.timestamp in (4 * 60 * minute)..(8 * 60 * minute)
        }
        val options = jumpBackOptions(withGap.sortedBy { it.timestamp }, now)

        assertTrue(options.size <= MaxJumpBackOptions)
        agesMinutes(options).zipWithNext { newer, older ->
            assertTrue("two offers ${older - newer}m apart", older > newer)
        }
    }

    @Test
    fun `the spacing rule widens with age and never narrows`() {
        val ages = listOf(0L, 29 * minute, 30 * minute, 119 * minute, 120 * minute, 8 * 60 * minute)

        val spacings = ages.map(::jumpBackSpacingMs)

        assertEquals(spacings.sorted(), spacings)
        assertEquals(5 * minute, jumpBackSpacingMs(10 * minute))
        assertEquals(15 * minute, jumpBackSpacingMs(60 * minute))
        assertEquals(30 * minute, jumpBackSpacingMs(5 * 60 * minute))
    }
}
