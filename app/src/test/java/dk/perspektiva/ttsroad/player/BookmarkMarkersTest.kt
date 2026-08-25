package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bookmarks as places on the scrub bar (#121).
 *
 * Two rules here can go wrong silently rather than loudly, which is why they are pinned: a mark
 * belonging to another chapter drawn on this bar, and a mark past the end of audio that was
 * re-converted shorter. Both draw a line the user can tap, and both seek somewhere meaningless.
 */
class BookmarkMarkersTest {
    private fun mark(id: Int, chapterId: Int, seconds: Double, label: String? = null) = Bookmark(
        id = id,
        chapterId = chapterId,
        positionSeconds = seconds,
        label = label,
    )

    @Test
    fun `a mark lands at its fraction of the chapter`() {
        val markers = bookmarkMarkers(
            bookmarks = listOf(mark(1, chapterId = 7, seconds = 300.0)),
            chapterId = 7,
            durationMs = 1_200_000,
        )

        assertEquals(1, markers.size)
        assertEquals(300_000L, markers.first().positionMs)
        assertEquals(0.25f, markers.first().fraction, 0.0001f)
    }

    /**
     * The account-wide list is what the endpoint returns when nothing is passed, so a caller that
     * forgets to scope it would otherwise decorate this chapter's bar with another book's marks.
     */
    @Test
    fun `another chapter's marks never reach this bar`() {
        val markers = bookmarkMarkers(
            bookmarks = listOf(mark(1, chapterId = 7, seconds = 10.0), mark(2, chapterId = 8, seconds = 10.0)),
            chapterId = 7,
            durationMs = 60_000,
        )

        assertEquals(listOf(1), markers.map { it.id })
    }

    /**
     * A chapter re-converted shorter leaves marks past its own end. Clamping them to 1.0 would pile
     * them against the right edge claiming a position the audio does not have, so they are dropped.
     */
    @Test
    fun `a mark past the end of the audio is dropped, not clamped`() {
        val markers = bookmarkMarkers(
            bookmarks = listOf(mark(1, chapterId = 7, seconds = 90.0)),
            chapterId = 7,
            durationMs = 60_000,
        )

        assertTrue(markers.isEmpty())
    }

    @Test
    fun `nothing is drawn before a duration is known`() {
        // The player reports 0 until the media is prepared; dividing by it would be every mark at
        // the same place, or worse.
        assertTrue(
            bookmarkMarkers(listOf(mark(1, 7, 10.0)), chapterId = 7, durationMs = 0).isEmpty(),
        )
        assertTrue(
            bookmarkMarkers(listOf(mark(1, 7, 10.0)), chapterId = null, durationMs = 60_000)
                .isEmpty(),
        )
    }

    @Test
    fun `marks come back in playing order`() {
        val markers = bookmarkMarkers(
            bookmarks = listOf(mark(1, 7, 40.0), mark(2, 7, 10.0), mark(3, 7, 25.0)),
            chapterId = 7,
            durationMs = 60_000,
        )

        assertEquals(listOf(2, 3, 1), markers.map { it.id })
    }

    @Test
    fun `an unlabelled mark still has something to announce`() {
        val markers = bookmarkMarkers(listOf(mark(1, 7, 10.0)), chapterId = 7, durationMs = 60_000)

        assertEquals("Bookmark", markers.first().label)
    }
}

/** Which mark a tap on the lane was aiming at — and when it was aiming at none. */
class MarkerTapTest {
    private fun marker(id: Int, fraction: Float) =
        BookmarkMarker(id = id, label = "m$id", positionMs = 0L, fraction = fraction)

    @Test
    fun `a tap on a mark finds it`() {
        val markers = listOf(marker(1, 0.25f), marker(2, 0.75f))

        assertEquals(2, markerAt(markers, fraction = 0.75f)?.id)
    }

    @Test
    fun `a near miss still counts, because a finger is not two dips wide`() {
        val markers = listOf(marker(1, 0.25f))

        assertEquals(1, markerAt(markers, fraction = 0.27f)?.id)
    }

    /**
     * The lane is not a second scrubber. A tap in open track must do nothing at all — falling back
     * to "nearest mark" would turn a mistimed tap into a jump the user did not ask for, which is
     * the worst thing a player control can do while someone is listening.
     */
    @Test
    fun `a tap in open track finds nothing`() {
        val markers = listOf(marker(1, 0.1f), marker(2, 0.9f))

        assertNull(markerAt(markers, fraction = 0.5f))
    }

    @Test
    fun `between two close marks the nearer one wins`() {
        val markers = listOf(marker(1, 0.50f), marker(2, 0.53f))

        assertEquals(2, markerAt(markers, fraction = 0.525f)?.id)
    }

    @Test
    fun `an empty lane cannot be tapped into anything`() {
        assertNull(markerAt(emptyList(), fraction = 0.5f))
    }
}
