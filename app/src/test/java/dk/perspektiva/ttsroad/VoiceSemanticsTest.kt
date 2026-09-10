package dk.perspektiva.ttsroad

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dk.perspektiva.ttsroad.data.MobileVoice
import dk.perspektiva.ttsroad.data.VoiceChoice
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class VoiceSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `voice group header has button role and expanded state description`() {
        val voices = listOf(
            MobileVoice(name = "en-US-BrianNeural", locale = "en-US", gender = "Male"),
        )

        compose.setContent {
            TtsRoadTheme {
                VoicePickerContent(
                    voices = voices,
                    current = "en-US-BrianNeural",
                    onSelect = {},
                )
            }
        }

        val headerNode = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        headerNode.assertIsDisplayed()
    }

    @Test
    fun `voice choice row has radio button role and selected state`() {
        val choice = VoiceChoice(
            name = "en-US-BrianNeural",
            shortName = "Brian",
            locale = "en-US",
            gender = "Male",
        )

        compose.setContent {
            TtsRoadTheme {
                VoiceChoiceRow(
                    choice = choice,
                    selected = true,
                    onSelect = {},
                )
            }
        }

        val radioNode = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        radioNode.assertIsDisplayed()
        radioNode.assertIsSelected()
    }
}
