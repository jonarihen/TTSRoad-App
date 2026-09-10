package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ChapterFilter
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
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

        val labels = ChapterFilter.entries.map { it.label } + listOf("NEWEST", "JUMP TO CURRENT")
        for (label in labels) {
            val rect = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            if (rect.right > 320.dp) {
                throw RuntimeException("LABEL_OVERFLOW: $label right is ${rect.right}")
            }
        }
    }
}
