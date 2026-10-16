package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.core.crashReportingStatus
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportingSettingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a build without a dsn says so and offers no switch`() {
        compose.setContent {
            TtsRoadTheme {
                CrashReportingSettingContent(
                    configured = false,
                    initiallyEnabled = true,
                    status = { crashReportingStatus(null, true) },
                    onEnabledChange = {},
                )
            }
        }

        compose.onNodeWithText("Off (not configured in this build)").assertExists()
        compose.onNodeWithTag(CrashReportingToggleTag).assertDoesNotExist()
    }

    @Test
    fun `a configured build can be switched off at runtime`() {
        val dsn = "https://key@sentry.example.com/1"
        var stored = true
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            TtsRoadTheme {
                CrashReportingSettingContent(
                    configured = true,
                    initiallyEnabled = stored,
                    status = { crashReportingStatus(dsn, stored) },
                    onEnabledChange = {
                        stored = it
                        changes += it
                    },
                )
            }
        }

        compose.onNodeWithText("On → sentry.example.com").assertExists()
        compose.onNodeWithTag(CrashReportingToggleTag).assertIsOn().performClick()

        compose.onNodeWithTag(CrashReportingToggleTag).assertIsOff()
        compose.onNodeWithText("Off on this phone (build reports to sentry.example.com)").assertExists()
        assertEquals(listOf(false), changes)
    }
}
