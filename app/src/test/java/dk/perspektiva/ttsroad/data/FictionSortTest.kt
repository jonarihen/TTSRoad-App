package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FictionSortTest {

    private fun fiction(
        id: Int,
        title: String = "Untitled",
        author: String? = null,
        rating: Double? = null,
        createdAt: String? = null,
        updatedAt: String? = null,
        progress: LibraryProgress? = null,
    ) = FictionSummary(
        id = id,
        title = title,
        author = author,
        rating = rating,
        createdAt = createdAt,
        updatedAt = updatedAt,
        progress = progress,
    )

    @Test
    fun `recently updated puts the newest first`() {
        val rows = listOf(
            fiction(id = 1, updatedAt = "2026-07-01T09:15:00Z"),
            fiction(id = 2, updatedAt = "2026-08-26T23:00:00Z"),
            fiction(id = 3, updatedAt = "2026-08-01T00:00:00Z"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.RecentlyUpdated)

        assertEquals(listOf(2, 3, 1), sorted.map { it.id })
    }

    @Test
    fun `the fixed-width ISO format orders correctly across a year and a month boundary`() {
        // The whole reason these compare as strings. A format that padded differently — or dropped
        // the leading zero on a month — would sort "2026-9-01" above "2026-10-01" and this test is
        // what would notice.
        val rows = listOf(
            fiction(id = 1, createdAt = "2025-12-31T23:59:59Z"),
            fiction(id = 2, createdAt = "2026-01-01T00:00:00Z"),
            fiction(id = 3, createdAt = "2026-09-30T12:00:00Z"),
            fiction(id = 4, createdAt = "2026-10-01T12:00:00Z"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.RecentlyAdded)

        assertEquals(listOf(4, 3, 2, 1), sorted.map { it.id })
    }

    @Test
    fun `a server that never sent the dates sorts last rather than first`() {
        // "We were not told" is not "a long time ago". An older server sends no created_at at all,
        // and letting that read as the epoch would bury the book that actually just arrived.
        val rows = listOf(
            fiction(id = 1, createdAt = null),
            fiction(id = 2, createdAt = "2026-08-01T00:00:00Z"),
            fiction(id = 3, createdAt = null),
            fiction(id = 4, createdAt = "2026-08-26T00:00:00Z"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.RecentlyAdded)

        assertEquals(listOf(4, 2, 1, 3), sorted.map { it.id })
    }

    @Test
    fun `an entirely undated shelf keeps the order the server sent`() {
        val rows = listOf(fiction(id = 7), fiction(id = 3), fiction(id = 9))

        val sorted = rows.sortedForBrowsing(FictionSort.RecentlyUpdated)

        assertEquals(listOf(7, 3, 9), sorted.map { it.id })
    }

    @Test
    fun `title order ignores case`() {
        val rows = listOf(
            fiction(id = 1, title = "the last horizon"),
            fiction(id = 2, title = "Ashes of the Sun"),
            fiction(id = 3, title = "zenith"),
            fiction(id = 4, title = "Beneath"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.Title)

        assertEquals(listOf(2, 4, 1, 3), sorted.map { it.id })
    }

    @Test
    fun `author order puts the unattributed and the blank at the end`() {
        // Blank is its own case: the server sends "" for a book whose author it could not read, and
        // an empty string sorts above every real name unless it is treated as absent.
        val rows = listOf(
            fiction(id = 1, author = null),
            fiction(id = 2, author = "Wong"),
            fiction(id = 3, author = "   "),
            fiction(id = 4, author = "abernathy"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.Author)

        assertEquals(listOf(4, 2, 1, 3), sorted.map { it.id })
    }

    @Test
    fun `rating order is highest first with the unrated last`() {
        val rows = listOf(
            fiction(id = 1, rating = 4.1),
            fiction(id = 2, rating = null),
            fiction(id = 3, rating = 4.85),
            fiction(id = 4, rating = 2.0),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.Rating)

        assertEquals(listOf(3, 1, 4, 2), sorted.map { it.id })
    }

    @Test
    fun `most left is absolute remaining time with an older server last`() {
        val rows = listOf(
            fiction(id = 1, progress = LibraryProgress(durationSeconds = 7200.0, remainingSeconds = 900.0)),
            fiction(id = 2, progress = null),
            fiction(id = 3, progress = LibraryProgress(durationSeconds = 3600.0, remainingSeconds = 3600.0)),
            fiction(id = 4, progress = LibraryProgress(durationSeconds = 14_400.0, remainingSeconds = 1800.0)),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.MostLeft)

        assertEquals(listOf(3, 4, 1, 2), sorted.map { it.id })
    }

    @Test
    fun `least finished is relative to the fiction length`() {
        val rows = listOf(
            // Two hours left, but eight of ten hours already heard.
            fiction(id = 1, progress = LibraryProgress(durationSeconds = 36_000.0, remainingSeconds = 7200.0)),
            // Only one hour left, but this two-hour book is less finished.
            fiction(id = 2, progress = LibraryProgress(durationSeconds = 7200.0, remainingSeconds = 3600.0)),
            fiction(id = 3, progress = LibraryProgress(durationSeconds = 1800.0, remainingSeconds = 1800.0)),
            fiction(id = 4, progress = LibraryProgress()),
            fiction(id = 5, progress = null),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.LeastFinished)

        assertEquals(listOf(3, 2, 1, 4, 5), sorted.map { it.id })
    }

    @Test
    fun `ties keep the order they arrived in`() {
        // Stability is not a nicety here: an unstable comparator would let the grid reorder itself
        // between recompositions while the user was looking at it.
        val rows = listOf(
            fiction(id = 1, updatedAt = "2026-08-01T00:00:00Z"),
            fiction(id = 2, updatedAt = "2026-08-01T00:00:00Z"),
            fiction(id = 3, updatedAt = "2026-08-01T00:00:00Z"),
        )

        val sorted = rows.sortedForBrowsing(FictionSort.RecentlyUpdated)

        assertEquals(listOf(1, 2, 3), sorted.map { it.id })
    }

    @Test
    fun `sorting does not disturb the caller's list`() {
        val rows = listOf(fiction(id = 1, title = "Zed"), fiction(id = 2, title = "Alpha"))

        rows.sortedForBrowsing(FictionSort.Title)

        assertEquals(listOf(1, 2), rows.map { it.id })
    }

    @Test
    fun `every order is total, so no option can drop or duplicate a row`() {
        val rows = listOf(
            fiction(id = 1, title = "B", author = "x", rating = 1.0, createdAt = "2026-01-01T00:00:00Z"),
            fiction(id = 2, title = "A"),
            fiction(id = 3, title = "C", rating = 5.0, updatedAt = "2026-02-01T00:00:00Z"),
        )

        FictionSort.entries.forEach { option ->
            val sorted = rows.sortedForBrowsing(option)
            assertEquals(option.name, setOf(1, 2, 3), sorted.map { it.id }.toSet())
            assertEquals(option.name, 3, sorted.size)
        }
    }
}
