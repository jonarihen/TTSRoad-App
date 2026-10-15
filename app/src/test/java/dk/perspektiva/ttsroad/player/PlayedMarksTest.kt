package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `is_played` is written by the server exactly as sent, so whatever this answers is what the chapter
 * becomes (#325). The web player keeps the flag sticky while a played chapter is re-listened to.
 */
class PlayedMarksTest {
    @Test
    fun `replaying a chapter loaded as played keeps it played below the threshold`() {
        val marks = PlayedMarks()
        assertTrue(marks.resolve(chapterId = 1, loadedAsPlayed = true, thresholdReached = false))
    }

    @Test
    fun `an unplayed chapter below the threshold stays unplayed`() {
        assertFalse(PlayedMarks().resolve(chapterId = 1, loadedAsPlayed = false, thresholdReached = false))
    }

    @Test
    fun `an automatic mark survives seeking back in the same item`() {
        val marks = PlayedMarks()
        assertTrue(marks.resolve(chapterId = 1, loadedAsPlayed = false, thresholdReached = true))
        assertTrue(marks.resolve(chapterId = 1, loadedAsPlayed = false, thresholdReached = false))
    }

    @Test
    fun `unticking in the app outranks the stale played flag on the loaded item`() {
        val marks = PlayedMarks()
        marks.remember(listOf(1), played = false)
        assertFalse(marks.resolve(chapterId = 1, loadedAsPlayed = true, thresholdReached = false))
    }

    @Test
    fun `an explicit mark outranks an item loaded as unplayed`() {
        val marks = PlayedMarks()
        marks.remember(listOf(1, 2), played = true)
        assertTrue(marks.resolve(chapterId = 2, loadedAsPlayed = false, thresholdReached = false))
    }

    @Test
    fun `sign-out forgets what was known`() {
        val marks = PlayedMarks()
        marks.remember(listOf(1), played = true)
        marks.clear()
        assertFalse(marks.resolve(chapterId = 1, loadedAsPlayed = false, thresholdReached = false))
    }
}
