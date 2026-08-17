package dk.perspektiva.ttsroad.media

import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The buttons the notification, the lockscreen and the Android Auto transport row are offered.
 *
 * Worth pinning down: a button in the wrong slot displaces one of the 30-second skips, which are
 * what a driver actually reaches for, and the failure is invisible from anywhere but a car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsRoadSessionCommandsTest {

    private fun actions(buttons: List<CommandButton>) =
        buttons.mapNotNull { it.sessionCommand?.customAction }

    @Test
    fun `a server without bookmarks is offered no bookmark button`() {
        val buttons = TtsRoadSessionCommands.mediaButtonPreferences(bookmarks = false)

        assertEquals(
            listOf(TtsRoadSessionCommands.SkipBack, TtsRoadSessionCommands.SkipForward),
            actions(buttons),
        )
    }

    @Test
    fun `the default is no bookmark button`() {
        // What a session built before capability discovery finishes gets — every cold start, and
        // every start from the car with no UI running. It must not offer what it cannot honour.
        assertFalse(
            actions(TtsRoadSessionCommands.mediaButtonPreferences())
                .contains(TtsRoadSessionCommands.Bookmark),
        )
    }

    @Test
    fun `a server with bookmarks gains the button without losing the skips`() {
        val buttons = TtsRoadSessionCommands.mediaButtonPreferences(bookmarks = true)

        assertEquals(
            listOf(
                TtsRoadSessionCommands.SkipBack,
                TtsRoadSessionCommands.SkipForward,
                TtsRoadSessionCommands.Bookmark,
            ),
            actions(buttons),
        )
    }

    @Test
    fun `the skips keep the slots either side of play-pause`() {
        val buttons = TtsRoadSessionCommands.mediaButtonPreferences(bookmarks = true)
        val back = buttons.first { it.sessionCommand?.customAction == TtsRoadSessionCommands.SkipBack }
        val forward =
            buttons.first { it.sessionCommand?.customAction == TtsRoadSessionCommands.SkipForward }

        assertTrue(back.slots.contains(CommandButton.SLOT_BACK))
        assertTrue(forward.slots.contains(CommandButton.SLOT_FORWARD))
    }

    @Test
    fun `the bookmark button stays in the overflow`() {
        // The point of the gating: adding bookmarks must not cost a skip its slot.
        val bookmark = TtsRoadSessionCommands.mediaButtonPreferences(bookmarks = true)
            .first { it.sessionCommand?.customAction == TtsRoadSessionCommands.Bookmark }

        assertEquals(listOf(CommandButton.SLOT_OVERFLOW), bookmark.slots.asList())
    }
}
