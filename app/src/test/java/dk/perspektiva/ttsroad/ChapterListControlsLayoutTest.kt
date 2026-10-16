package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ChapterFilter
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ChapterListControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp", fontScale = 1.5f)
    fun `filter and sort controls wrap without overlap at narrow large text`() {
        compose.setContent {
            TtsRoadTheme {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ChapterListControls(
                        filter = ChapterFilter.All,
                        ascending = false,
                        showJumpToCurrent = true,
                        query = "",
                        onQuery = {},
                        onFilter = {},
                        onToggleSort = {},
                        onJumpToCurrent = {},
                    )
                }
            }
        }

        val labels = listOf("All", "Unplayed", "Ready", "NEWEST", "JUMP TO CURRENT")
        for (label in labels) {
            val rect = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            if (rect.right > 320.dp) {
                throw RuntimeException("LABEL_OVERFLOW: $label right is ${rect.right}")
            }
        }
    }

    @Test
    @Config(sdk = [34])
    fun `unplayed and ready chips combine and toggle independently`() {
        var filter by mutableStateOf(ChapterFilter.Unplayed)
        compose.setContent {
            TtsRoadTheme {
                ChapterListControls(
                    filter = filter,
                    ascending = true,
                    showJumpToCurrent = false,
                    query = "",
                    onQuery = {},
                    onFilter = { filter = it },
                    onToggleSort = {},
                    onJumpToCurrent = {},
                )
            }
        }

        compose.onNodeWithText("Ready").performClick()
        compose.waitForIdle()
        assertEquals(ChapterFilter.UnplayedReady, filter)
        compose.onNodeWithText("Unplayed").assertIsSelected()
        compose.onNodeWithText("Ready").assertIsSelected()
        compose.onNodeWithText("All").assertIsNotSelected()

        compose.onNodeWithText("Unplayed").performClick()
        compose.waitForIdle()
        assertEquals(ChapterFilter.Ready, filter)

        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        assertEquals(ChapterFilter.All, filter)
    }
}
