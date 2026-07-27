package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryCache] itself needs a repository and a coroutine scope, but the distinction the whole fix
 * rests on lives in [Cached]: telling "nothing to show, a spinner is the only option" apart from
 * "we have last time's data, so show it and refresh underneath". Collapsing those two is exactly
 * why every back-navigation used to blank the screen it returned to.
 */
class CachedTest {

    @Test
    fun `a fresh cache is an initial load`() {
        val state = Cached<String>()

        assertTrue(state.isInitialLoad)
        assertFalse(state.hasContent)
    }

    @Test
    fun `content means no spinner, even while refreshing`() {
        val state = Cached(value = "library", isRefreshing = true)

        assertTrue(state.hasContent)
        assertFalse(state.isInitialLoad)
    }

    /**
     * The case that decides whether a failed pull-to-refresh wipes the screen. It must not: the
     * user can still read and play from the library that is already there.
     */
    @Test
    fun `a failed refresh over existing content still counts as content`() {
        val state = Cached(value = "library", error = "HTTP 500", isRefreshing = false)

        assertTrue(state.hasContent)
        assertFalse(state.isInitialLoad)
    }

    @Test
    fun `a first load that failed is not an initial load any more`() {
        // Nothing to show and something to say, so the screen owes the user an error pane rather
        // than a spinner that never resolves.
        val state = Cached<String>(error = "No network")

        assertFalse(state.hasContent)
        assertFalse(state.isInitialLoad)
    }

    @Test
    fun `an empty list is content, not an absence of it`() {
        // A library with no fictions must render the empty state, not spin forever.
        val state = Cached(value = emptyList<String>())

        assertTrue(state.hasContent)
        assertFalse(state.isInitialLoad)
    }
}

class CachedChapterPatchTest {

    private fun chapter(id: Int, played: Boolean) = ChapterSummary(
        id = id,
        playback = PlaybackInfo(isPlayed = played),
    )

    /**
     * Mirrors what LibraryCache.applyPlayed does to the loaded list. Untouched rows must come back
     * by identity so Compose skips redrawing them - that is what keeps a 500-row list from
     * rebuilding, and the scroll position from jumping, over a single checkmark.
     */
    private fun List<ChapterSummary>.withPlayed(ids: Collection<Int>, played: Boolean) =
        map { chapter ->
            if (chapter.resolvedChapterId !in ids.toSet()) {
                chapter
            } else {
                chapter.copy(playback = (chapter.playback ?: PlaybackInfo()).copy(isPlayed = played))
            }
        }

    @Test
    fun `only the targeted rows change identity`() {
        val chapters = listOf(chapter(1, played = false), chapter(2, played = false))

        val updated = chapters.withPlayed(listOf(2), played = true)

        assertSame(chapters[0], updated[0])
        assertTrue(updated[1].playback?.isPlayed == true)
    }

    @Test
    fun `marking unplayed works the same way`() {
        val chapters = listOf(chapter(1, played = true))

        assertFalse(chapters.withPlayed(listOf(1), played = false).single().playback?.isPlayed == true)
    }

    @Test
    fun `a chapter with no playback info gains it`() {
        val chapters = listOf(ChapterSummary(id = 7))

        assertTrue(chapters.withPlayed(listOf(7), played = true).single().playback?.isPlayed == true)
    }

    @Test
    fun `an id that is not in the list changes nothing`() {
        val chapters = listOf(chapter(1, played = false))

        val updated = chapters.withPlayed(listOf(999), played = true)

        assertEquals(chapters, updated)
        assertSame(chapters[0], updated[0])
    }
}
