package dk.perspektiva.ttsroad.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a "bookmark this" press from the car or the notification resolves to.
 *
 * Pure, so the interesting part — which presses mark something and where the mark lands — is tested
 * without a session, a controller or a player.
 */
class SessionBookmarksTest {

    @Test
    fun `a press marks the chapter at the position it landed on`() {
        val target = bookmarkTargetFor(chapterId = 42, positionMs = 93_500L)

        assertEquals(42, target?.chapterId)
        assertEquals(93.5, target?.positionSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `nothing playing is nothing to mark`() {
        assertNull(bookmarkTargetFor(chapterId = null, positionMs = 0L))
    }

    @Test
    fun `an item that is not a chapter is nothing to mark`() {
        // `chapter_id` is what the whole progress path keys on, and Bundle.getInt answers 0 for a
        // missing key — so a fiction-level entry arrives here as 0 rather than null.
        assertNull(bookmarkTargetFor(chapterId = 0, positionMs = 12_000L))
        assertNull(bookmarkTargetFor(chapterId = -1, positionMs = 12_000L))
    }

    @Test
    fun `a negative position marks the start of the chapter, not before it`() {
        // Media3 can report a negative position around a seek or a discontinuity. A press in that
        // window belongs at the top of the chapter; a negative offset is not something to send.
        assertEquals(0.0, bookmarkTargetFor(chapterId = 7, positionMs = -250L)?.positionSeconds ?: -1.0, 0.0001)
    }

    @Test
    fun `the position keeps sub-second precision`() {
        // Seconds are what the server takes, but integer seconds would land the mark up to a second
        // out — which at a normal reading pace is most of a sentence, and this feature exists to
        // catch a particular line.
        assertEquals(1.234, bookmarkTargetFor(chapterId = 1, positionMs = 1_234L)?.positionSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `only a failure carries a message worth showing`() {
        // Media3 surfaces a SessionError to the controller and has nothing for success, so the
        // outcomes that need to say something are exactly the ones that did not save.
        assertEquals("Bookmark saved", BookmarkOutcome.Written.message)
        for (outcome in BookmarkOutcome.entries - BookmarkOutcome.Written) {
            assertEquals("$outcome should explain itself", true, outcome.message.isNotBlank())
        }
    }
}
