package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.Bookmark
import dk.perspektiva.ttsroad.data.BookmarkKindAuto
import dk.perspektiva.ttsroad.data.BookmarkKindManual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBreadcrumbsTest {
    private val base = 1_700_000_000_000L

    private fun crumb(
        id: Int,
        createdAt: String? = "2023-11-14T22:13:20Z",
        chapterId: Int = 7,
        kind: String = BookmarkKindAuto,
        positionSeconds: Double = 90.0,
    ) = Bookmark(
        id = id,
        chapterId = chapterId,
        fictionId = 3,
        positionSeconds = positionSeconds,
        kind = kind,
        createdAt = createdAt,
        chapterTitle = "Chapter $chapterId",
        fictionTitle = "Ashes of Aether",
    )

    private fun snapshot(timestamp: Long, chapterId: Int = 7, positionMs: Long = 1_000L) =
        HistorySnapshot(
            timestamp = timestamp,
            mediaId = "chapter:$chapterId",
            fictionId = 3,
            chapterId = chapterId,
            title = "Chapter $chapterId",
            fictionTitle = "Ashes of Aether",
            positionMs = positionMs,
        )

    // --- cadence ---

    @Test
    fun `the first breadcrumb of a session is always written`() {
        // Waiting five minutes for the first one would lose someone who listens briefly and stops.
        assertTrue(shouldWriteBreadcrumb(lastWrittenAt = null, now = base))
    }

    @Test
    fun `breadcrumbs are not written more often than the interval`() {
        assertFalse(shouldWriteBreadcrumb(lastWrittenAt = base, now = base + 60_000L))
        assertFalse(shouldWriteBreadcrumb(lastWrittenAt = base, now = base + ServerBreadcrumbIntervalMs - 1))
        assertTrue(shouldWriteBreadcrumb(lastWrittenAt = base, now = base + ServerBreadcrumbIntervalMs))
    }

    @Test
    fun `a clock that jumped backwards does not wedge the trail`() {
        // A timezone fix or an NTP correction would otherwise stall writes until real time caught
        // up, which for a manual clock change could be days.
        assertTrue(shouldWriteBreadcrumb(lastWrittenAt = base, now = base - 86_400_000L))
    }

    @Test
    fun `the cadence keeps the server window covering a night`() {
        // The whole reason the interval is what it is: 240 rows at this spacing must span more than
        // an overnight, or the cross-device case this exists for cannot be answered.
        val spanHours = MaxServerBreadcrumbs * ServerBreadcrumbIntervalMs / 3_600_000.0
        assertTrue("window spans only ${spanHours}h", spanHours >= 12.0)
    }

    // --- pruning ---

    @Test
    fun `nothing is pruned below the cap`() {
        val existing = (1..MaxServerBreadcrumbs).map { crumb(it) }

        assertEquals(emptyList<Int>(), breadcrumbsToPrune(existing))
    }

    @Test
    fun `the oldest are pruned first`() {
        val existing = listOf(
            crumb(1, createdAt = "2023-11-14T20:00:00Z"),
            crumb(2, createdAt = "2023-11-14T22:00:00Z"),
            crumb(3, createdAt = "2023-11-14T21:00:00Z"),
        )

        assertEquals(listOf(1, 3), breadcrumbsToPrune(existing, keep = 1))
    }

    @Test
    fun `manual bookmarks are never pruned`() {
        // The account's own marks share this table. Deleting one to make room for a machine-written
        // breadcrumb would be the worst possible version of this feature.
        val existing = listOf(
            crumb(1, kind = BookmarkKindManual, createdAt = "2023-11-14T19:00:00Z"),
            crumb(2, createdAt = "2023-11-14T20:00:00Z"),
            crumb(3, createdAt = "2023-11-14T21:00:00Z"),
        )

        assertEquals(listOf(2), breadcrumbsToPrune(existing, keep = 1))
    }

    @Test
    fun `unreadable rows are spent first`() {
        // They cannot be placed on the trail, so they are the least useful thing to keep.
        val existing = listOf(
            crumb(1, createdAt = "2023-11-14T20:00:00Z"),
            crumb(2, createdAt = null),
            crumb(3, createdAt = "not a timestamp"),
        )

        assertEquals(listOf(2, 3), breadcrumbsToPrune(existing, keep = 1))
    }

    @Test
    fun `a server that already windows leaves nothing to prune`() {
        // Self-balancing across both server versions: where the rolling window exists the listing
        // never comes back above the cap, so the client's own pruning is a no-op.
        val existing = (1..MaxServerBreadcrumbs).map { crumb(it) }

        assertTrue(breadcrumbsToPrune(existing).isEmpty())
    }

    // --- mapping ---

    @Test
    fun `a breadcrumb becomes a point on the timeline`() {
        val snap = breadcrumbSnapshot(crumb(1, positionSeconds = 90.5))

        assertEquals(base, snap?.timestamp)
        assertEquals("chapter:7", snap?.mediaId)
        assertEquals(7, snap?.chapterId)
        assertEquals(3, snap?.fictionId)
        assertEquals(90_500L, snap?.positionMs)
        assertEquals("Chapter 7", snap?.title)
    }

    @Test
    fun `a breadcrumb with no usable timestamp is dropped rather than dated to the epoch`() {
        // The sheet is a list of clock times; an entry claiming 1970 is worse than a missing one.
        assertNull(breadcrumbSnapshot(crumb(1, createdAt = null)))
        assertNull(breadcrumbSnapshot(crumb(1, createdAt = "yesterday")))
    }

    @Test
    fun `a breadcrumb with no chapter is dropped`() {
        assertNull(breadcrumbSnapshot(crumb(1, chapterId = 0)))
    }

    @Test
    fun `both of the server's fractional-second shapes parse`() {
        // The backend renders these with datetime.isoformat(), which emits either no fractional
        // digits or six.
        assertEquals(base, parseServerInstant("2023-11-14T22:13:20Z"))
        assertEquals(base + 412L, parseServerInstant("2023-11-14T22:13:20.412000Z"))
    }

    @Test
    fun `a stamp with no zone is read as UTC`() {
        // Every timestamp column in that database is naive UTC, so this is right rather than a guess.
        assertEquals(base, parseServerInstant("2023-11-14T22:13:20"))
    }

    @Test
    fun `an unreadable stamp is null rather than a throw`() {
        // This runs over a whole list; one bad row should cost that row, not the trail.
        assertNull(parseServerInstant(null))
        assertNull(parseServerInstant("   "))
        assertNull(parseServerInstant("14/11/2023"))
    }

    // --- merging ---

    @Test
    fun `remote moments the phone never saw are added`() {
        val local = listOf(snapshot(base))
        val remote = listOf(snapshot(base + 3 * 60 * 60_000L, chapterId = 9))

        val merged = mergeBreadcrumbs(local, remote)

        assertEquals(2, merged.size)
        assertEquals(listOf(base, base + 3 * 60 * 60_000L), merged.map { it.timestamp })
    }

    @Test
    fun `the local copy of the same moment wins`() {
        // Most server rows were written by this phone, and the local one is the better record: it
        // is finer-grained and its position did not round through position_seconds.
        val local = listOf(snapshot(base, positionMs = 12_345L))
        val remote = listOf(snapshot(base + 1_000L, positionMs = 12_000L))

        val merged = mergeBreadcrumbs(local, remote)

        assertEquals(1, merged.size)
        assertEquals(12_345L, merged.single().positionMs)
    }

    @Test
    fun `the same clock time on a different chapter is a different moment`() {
        val local = listOf(snapshot(base, chapterId = 7))
        val remote = listOf(snapshot(base, chapterId = 8))

        assertEquals(2, mergeBreadcrumbs(local, remote).size)
    }

    @Test
    fun `merging is ordered oldest first, as the sheet expects`() {
        val local = listOf(snapshot(base), snapshot(base + 60 * 60_000L))
        val remote = listOf(snapshot(base + 30 * 60_000L, chapterId = 9))

        val merged = mergeBreadcrumbs(local, remote)

        assertEquals(merged.map { it.timestamp }.sorted(), merged.map { it.timestamp })
    }

    @Test
    fun `nothing from the server leaves the local trail untouched`() {
        val local = listOf(snapshot(base), snapshot(base + 60_000L))

        assertEquals(local, mergeBreadcrumbs(local, emptyList()))
    }

    @Test
    fun `a phone with no local history still gets the account's trail`() {
        // The case the whole feature exists for: this device was not the one playing.
        val remote = listOf(snapshot(base), snapshot(base + 60 * 60_000L, chapterId = 9))

        assertEquals(2, mergeBreadcrumbs(emptyList(), remote).size)
    }
}
