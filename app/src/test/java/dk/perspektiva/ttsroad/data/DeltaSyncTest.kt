package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sparse merge rules for the server-issued delta cursor (#110). */
class DeltaSyncTest {
    @Test
    fun `the index names only fictions whose chapter representation moved`() {
        val index = DeltaSyncResponse(
            serverTime = "t2",
            changed = DeltaChanged(
                fictions = listOf(
                    DeltaFictionChange(fictionId = 1, changedChapters = 2),
                    DeltaFictionChange(fictionId = 2, changedPlayback = 1),
                    DeltaFictionChange(fictionId = 3, deletedChapters = 1),
                    DeltaFictionChange(fictionId = 4),
                ),
            ),
        )

        assertEquals(listOf(1, 2, 3), index.fictionsWithChapterChanges())
        assertTrue(index.libraryMoved())
        assertFalse(DeltaSyncResponse(serverTime = "t2").libraryMoved())
    }

    @Test
    fun `library delta replaces changed rows removes unfollows and keeps the rails whole`() {
        val untouched = FictionSummary(id = 1, title = "Ash")
        val renamed = FictionSummary(id = 2, title = "Before")
        val unfollowed = FictionSummary(id = 3, title = "Gone from shelf")
        val current = LibraryResponse(
            followingIds = listOf(1, 2, 3),
            fictions = listOf(untouched, renamed, unfollowed),
            continueListening = listOf(ChapterSummary(id = 10)),
            recentChapters = listOf(ChapterSummary(id = 11)),
            serverTime = "t1",
        )
        val newContinue = ChapterSummary(id = 12)
        val update = LibraryResponse(
            followingIds = listOf(1, 2, 4),
            fictions = listOf(
                FictionSummary(id = 2, title = "After"),
                FictionSummary(id = 4, title = "Added"),
            ),
            continueListening = listOf(newContinue),
            recentChapters = emptyList(),
            serverTime = "t2",
            updatedSince = "t1",
            delta = true,
        )

        val merged = mergeLibraryDelta(current, update)

        assertEquals(listOf(4, 2, 1), merged.fictions.map(FictionSummary::id))
        assertEquals("After", merged.fictions.first { it.id == 2 }.title)
        assertSame(untouched, merged.fictions.first { it.id == 1 })
        assertEquals(listOf(newContinue), merged.continueListening)
        assertTrue(merged.recentChapters.isEmpty())
        assertFalse(merged.delta)
    }

    @Test
    fun `browse-all delta does not mistake membership for visibility`() {
        val current = LibraryResponse(
            scope = LibraryScopeAll,
            followingIds = listOf(1),
            fictions = listOf(
                FictionSummary(id = 1, title = "Followed"),
                FictionSummary(id = 2, title = "Not followed"),
            ),
        )
        val update = LibraryResponse(
            scope = LibraryScopeAll,
            followingIds = emptyList(),
            delta = true,
        )

        val merged = mergeLibraryDelta(current, update)

        assertEquals(listOf(1, 2), merged.fictions.map(FictionSummary::id))
        assertTrue(merged.followingIds.isEmpty())
    }

    @Test
    fun `chapter delta replaces adds and deletes without rebuilding untouched rows`() {
        val first = ChapterSummary(id = 1, title = "One", chapterNumber = 1.0)
        val oldSecond = ChapterSummary(id = 2, title = "Old two", chapterNumber = 2.0)
        val removed = ChapterSummary(id = 3, title = "Three", chapterNumber = 3.0)
        val update = ChaptersResponse(
            fiction = FictionSummary(id = 7),
            chapters = listOf(
                ChapterSummary(id = 2, title = "New two", chapterNumber = 2.0),
                ChapterSummary(id = 4, title = "Four", chapterNumber = 4.0),
            ),
            delta = true,
            deleted = listOf(3),
        )

        val merged = mergeChapterDelta(listOf(first, oldSecond, removed), update)

        assertEquals(listOf(1, 2, 4), merged.map(ChapterSummary::resolvedChapterId))
        assertSame(first, merged.first())
        assertEquals("New two", merged[1].title)
    }

    @Test
    fun `a full chapter response is taken as sent rather than re-sorted`() {
        val update = ChaptersResponse(
            fiction = FictionSummary(id = 7),
            chapters = listOf(ChapterSummary(id = 9), ChapterSummary(id = 2)),
        )

        assertEquals(
            listOf(9, 2),
            mergeChapterDelta(listOf(ChapterSummary(id = 1)), update)
                .map(ChapterSummary::resolvedChapterId),
        )
    }

    @Test
    fun `a merged chapter lands where the server would have put it, not after the playable ones`() {
        // player_index is assigned to playable chapters only, so ordering a merge by it would
        // sink every pending chapter to the bottom of a list it was interleaved through.
        val one = ChapterSummary(id = 1, chapterNumber = 1.0, playerIndex = 0, playable = true)
        val three = ChapterSummary(id = 3, chapterNumber = 3.0, playerIndex = 1, playable = true)
        val update = ChaptersResponse(
            fiction = FictionSummary(id = 7),
            chapters = listOf(
                ChapterSummary(id = 2, chapterNumber = 2.0, playerIndex = null, playable = false),
            ),
            delta = true,
        )

        val merged = mergeChapterDelta(listOf(one, three), update)

        assertEquals(listOf(1, 2, 3), merged.map(ChapterSummary::resolvedChapterId))
    }

    @Test
    fun `an unnumbered chapter sorts last and ties break on id, exactly as the server does`() {
        val unnumbered = ChapterSummary(id = 4, chapterNumber = null)
        val update = ChaptersResponse(
            fiction = FictionSummary(id = 7),
            chapters = listOf(
                ChapterSummary(id = 8, chapterNumber = 2.0),
                ChapterSummary(id = 5, chapterNumber = 2.0),
            ),
            delta = true,
        )

        val merged = mergeChapterDelta(listOf(unnumbered), update)

        assertEquals(listOf(5, 8, 4), merged.map(ChapterSummary::resolvedChapterId))
    }
}
