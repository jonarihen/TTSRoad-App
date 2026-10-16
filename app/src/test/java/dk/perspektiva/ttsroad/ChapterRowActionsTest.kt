package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #356: "Play next", "Add to queue", "Mark all previous" and "Convert again" were behind an
 * unlabelled long press on the chapter row. The press is now named, and a ⋮ button opens the same
 * sheet without the gesture wherever the row has room for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class ChapterRowActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the long press says what it opens`() {
        renderRow(onLongPress = {})

        val longClick = compose.onNodeWithText(chapter.resolvedTitle, substring = true)
            .fetchSemanticsNode().config[SemanticsActions.OnLongClick]
        assertEquals("Chapter actions", longClick.label)
    }

    @Test
    fun `the overflow button opens the same actions as the long press`() {
        var opened = 0
        renderRow(onLongPress = { opened++ })

        compose.onNodeWithContentDescription("Chapter actions")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a row with no actions offers no overflow`() {
        renderRow(onLongPress = null)

        compose.onAllNodes(hasContentDescription("Chapter actions")).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `a 320 dp phone keeps the title and leaves the actions to the named long press`() {
        renderRow(onLongPress = {})

        compose.onAllNodes(hasContentDescription("Chapter actions")).assertCountEquals(0)
        val longClick = compose.onNodeWithText(chapter.resolvedTitle, substring = true)
            .fetchSemanticsNode().config[SemanticsActions.OnLongClick]
        assertEquals("Chapter actions", longClick.label)
    }

    @Test
    fun `the overflow overlaps no other action and leaves the title room`() {
        renderRow(onLongPress = {})

        val names = listOf("Download for offline", "Mark played", "Play", "Chapter actions")
        val bounds = names.map { name ->
            name to compose.onNodeWithContentDescription(name).getUnclippedBoundsInRoot()
                .let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
        }
        for ((firstName, first) in bounds) {
            for ((secondName, second) in bounds) {
                if (firstName == secondName) continue
                assertFalse("$firstName overlaps $secondName", first.overlaps(second))
            }
        }
        // The title's column runs from the end of the number column to the 8 dp before the first
        // action. The text node itself is no help: an ellipsized line reports only what it drew.
        val number = compose.onNodeWithText("12", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val titleRoom = bounds.first().second.left - number.right.value - 8f
        assertTrue("title has only $titleRoom dp", titleRoom >= 64f)
    }

    private fun renderRow(onLongPress: (() -> Unit)?) {
        compose.setContent {
            TtsRoadTheme {
                // The fiction screen's own horizontal padding, so the width is the real one.
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ChapterRow(
                        chapter = chapter,
                        fiction = null,
                        onPlay = {},
                        onMarkPlayed = {},
                        onLongPress = onLongPress,
                        onToggleDownload = {},
                    )
                }
            }
        }
    }

    private companion object {
        val chapter = ChapterSummary(
            id = 1,
            fictionId = 7,
            title = "Chapter 12: A Long Enough Title To Take The Width",
            displayNumber = 12.0,
            status = "ready",
            audio = AudioInfo(url = "https://example.invalid/audio/12.mp3"),
            playback = PlaybackInfo(positionSeconds = 30.0),
        )
    }
}
