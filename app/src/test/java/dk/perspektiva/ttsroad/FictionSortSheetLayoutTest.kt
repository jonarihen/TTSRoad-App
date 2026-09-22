package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import dk.perspektiva.ttsroad.data.FictionSort
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FictionSortSheetLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the last sort stays reachable on a narrow phone`() {
        var selected: FictionSort? = null
        compose.setContent {
            TtsRoadTheme {
                FictionSortSheet(
                    selected = FictionSort.Title,
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("fiction-sort-options")
            .performScrollToIndex(FictionSort.entries.lastIndex)
        compose.onNodeWithText(FictionSort.PercentConverted.label)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(FictionSort.PercentConverted, selected)
    }
}
