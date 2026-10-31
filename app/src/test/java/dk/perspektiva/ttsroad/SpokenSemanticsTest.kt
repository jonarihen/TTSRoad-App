package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.SectionHeader
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Heading semantics (#492) and spoken forms of times and speeds (#493). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SpokenSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `screen titles, section headers and kickers are headings`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    AppTopBar(title = "Ashes", canGoBack = false, onBack = {})
                    SectionHeader(kicker = "01", title = "Session")
                    MetaText(text = "// Sign in")
                }
            }
        }

        compose.onAllNodes(isHeading).assertCountEquals(3)
        compose.onNodeWithText("ASHES").assert(isHeading)
        compose.onNodeWithText("SESSION").assert(isHeading)
    }

    @Test
    fun `speeds and time left are spoken in words`() {
        assertEquals("1.5 times", spokenSpeed(1.5f))
        assertEquals("28 minutes left", spokenTimeLeft(28 * 60_000L, 1f, 28 * 60_000L))
        assertEquals(
            "28 minutes left, 18 minutes 40 seconds at 1.5 times speed",
            spokenTimeLeft(28 * 60_000L, 1.5f, 18 * 60_000L + 40_000L),
        )
    }
}
