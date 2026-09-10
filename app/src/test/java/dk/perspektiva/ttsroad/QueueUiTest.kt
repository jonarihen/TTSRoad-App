package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.QueueItem
import dk.perspektiva.ttsroad.data.QueueWhenEmptyContinue
import dk.perspektiva.ttsroad.data.QueueWhenEmptyStop
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class QueueUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `queue row action buttons have 48dp touch targets without overlapping`() {
        val item = QueueItem(
            id = 1,
            chapterId = 10,
            fictionId = 2,
            chapterTitle = "A Very Long Chapter Title That Fits Over Two Lines On Narrow Devices Easily",
            fictionTitle = "A Super Long Fiction Title",
        )
        compose.setContent {
            TtsRoadTheme {
                QueueRow(
                    item = item,
                    position = 1,
                    onMoveUp = {},
                    onMoveDown = {},
                    onRemove = {},
                    onPlay = {},
                )
            }
        }

        val actions = listOf("Move up", "Move down", "Remove from queue")
        val bounds = actions.map { desc ->
            val node = compose.onNodeWithContentDescription(desc)
            node.assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            desc to node.getUnclippedBoundsInRoot()
        }

        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                val b1 = bounds[i].second
                val b2 = bounds[j].second
                val overlaps = b1.left < b2.right && b1.right > b2.left && b1.top < b2.bottom && b1.bottom > b2.top
                assertFalse("action buttons overlap: ${bounds[i].first} and ${bounds[j].first}", overlaps)
            }
        }
    }

    @Test
    fun `QueueWhenEmptyCard disabled state disables choice options`() {
        var selected: String? = null
        compose.setContent {
            TtsRoadTheme {
                QueueWhenEmptyCard(
                    value = QueueWhenEmptyStop,
                    enabled = false,
                    onSelect = { selected = it },
                )
            }
        }

        compose.onNodeWithText("STOP").assertIsNotEnabled()
        compose.onNodeWithText("KEEP GOING").assertIsNotEnabled()

        compose.onNodeWithText("KEEP GOING").performClick()
        assertEquals(null, selected)
    }

    @Test
    fun `QueueWhenEmptyCard enabled state allows selection`() {
        var selected: String? = null
        compose.setContent {
            TtsRoadTheme {
                QueueWhenEmptyCard(
                    value = QueueWhenEmptyStop,
                    enabled = true,
                    onSelect = { selected = it },
                )
            }
        }

        compose.onNodeWithText("KEEP GOING").assertIsEnabled().performClick()
        assertEquals(QueueWhenEmptyContinue, selected)
    }
}
