package dk.perspektiva.ttsroad.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The re-target decision behind #89, tested directly rather than through the composable.
 *
 * The composable's contribution is only bookkeeping — it seeds "previous" from what was playing
 * when the entry was composed and re-seeds it after every change — so the sequences below drive
 * the function the way that bookkeeping does, one playing-chapter change at a time.
 */
class ReaderFollowTest {

    /**
     * Replays a run of playing-chapter changes against a reader that starts on [readerChapterId],
     * exactly as the composable does: seed from what is playing at composition, re-seed after each
     * change, and stop at the first re-target — because that re-target replaces the entry, and the
     * next composition starts over on the new chapter.
     *
     * @return the chapter the reader was moved to, or null if it stayed put for the whole run.
     */
    private fun follow(readerChapterId: Int, playing: List<Int?>): Int? {
        var previous = playing.firstOrNull()
        for (current in playing.drop(1)) {
            val target = readerFollowTarget(readerChapterId, previous, current)
            previous = current
            if (target != null) return target
        }
        return null
    }

    @Test
    fun `reading along and the chapter ends moves the reader on with the audio`() {
        assertEquals(11, readerFollowTarget(10, previousPlayingChapterId = 10, playingChapterId = 11))
    }

    @Test
    fun `a reader opened on another chapter is never dragged off it`() {
        // Opened on 20 while 3 plays, then left alone through four auto-advances.
        assertNull(follow(readerChapterId = 20, playing = listOf(3, 4, 5, 6, 7)))
    }

    @Test
    fun `not following stays not following even when the player passes the open chapter`() {
        // 5 is open while 3 plays. The audio reaches 5 — which is where following legitimately
        // resumes — and only the chapter after that moves the reader.
        assertNull(readerFollowTarget(5, previousPlayingChapterId = 3, playingChapterId = 4))
        assertNull(readerFollowTarget(5, previousPlayingChapterId = 4, playingChapterId = 5))
        assertEquals(6, readerFollowTarget(5, previousPlayingChapterId = 5, playingChapterId = 6))
        assertEquals(6, follow(readerChapterId = 5, playing = listOf(3, 4, 5, 6)))
    }

    @Test
    fun `nothing playing leaves the reader where it is`() {
        // Playback stopped or the queue emptied: there is nothing to follow, and the chapter
        // already open is the useful thing to keep showing.
        assertNull(readerFollowTarget(10, previousPlayingChapterId = 10, playingChapterId = null))
    }

    @Test
    fun `starting playback elsewhere does not yank an open reader across to it`() {
        // Nothing was playing, so the reader was not following anything.
        assertNull(readerFollowTarget(10, previousPlayingChapterId = null, playingChapterId = 42))
    }

    @Test
    fun `resuming the chapter already open is not a re-target`() {
        // The steady state on every tick, and the first composition of a reader opened from the
        // player. Re-targeting onto the chapter already shown would reload the page for nothing.
        assertNull(readerFollowTarget(10, previousPlayingChapterId = 10, playingChapterId = 10))
        assertNull(readerFollowTarget(10, previousPlayingChapterId = null, playingChapterId = 10))
    }

    @Test
    fun `next and previous move the reader too, not only the end of a chapter`() {
        // Nothing here distinguishes an auto-advance from a tap on the transport controls, and it
        // should not: both are the player moving while the reader is showing what it was playing.
        assertEquals(9, readerFollowTarget(10, previousPlayingChapterId = 10, playingChapterId = 9))
    }

    @Test
    fun `following survives chapter after chapter`() {
        // The overnight case: each re-target re-seeds on the new chapter, so the next one follows
        // as well rather than the reader falling behind after one hop.
        assertEquals(11, follow(readerChapterId = 10, playing = listOf(10, 11)))
        assertEquals(12, follow(readerChapterId = 11, playing = listOf(11, 12)))
        assertEquals(13, follow(readerChapterId = 12, playing = listOf(12, 13)))
    }

    @Test
    fun `following into a chapter is decided before its text is known`() {
        // The target is whatever is playing, with no reference to whether that chapter has a
        // read-along document. One that 404s must show its own "no text" state rather than
        // leaving the finished chapter's text on screen, which was the bug wearing a hat.
        assertEquals(11, readerFollowTarget(10, previousPlayingChapterId = 10, playingChapterId = 11))
    }
}
