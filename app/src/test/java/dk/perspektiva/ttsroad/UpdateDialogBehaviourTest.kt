package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the update dialog does with each answer (#363, #369). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class UpdateDialogBehaviourTest {
    @get:Rule val compose = createComposeRule()

    private val release = ReleaseInfo(
        versionName = "0.17.0",
        notes = "Playback is steadier.\n\n### Fixed\n- Shared OkHttpClient (#286)",
        apkUrl = "https://x.test/a.apk",
    )
    private val events = mutableListOf<String>()

    private fun show(state: UpdateState, canInstall: Boolean = true) {
        compose.setContent {
            TtsRoadTheme {
                UpdateOverlay(
                    state = state,
                    onDownload = { events += "download ${it.versionName}" },
                    onDismiss = { events += "dismiss" },
                    onLater = { events += "later" },
                    onSkip = { events += "skip ${it.versionName}" },
                    canInstallPackages = { canInstall },
                )
            }
        }
    }

    @Test
    fun `later and skip are separate answers`() {
        show(UpdateState.Available(release))
        compose.onNodeWithText("SKIP THIS VERSION").assertIsDisplayed().performClick()
        compose.onNodeWithText("LATER").assertIsDisplayed().performClick()
        assertEquals(listOf("skip 0.17.0", "later"), events)
    }

    @Test
    fun `the plain summary leads and the changelog waits behind a toggle`() {
        show(UpdateState.Available(release))
        compose.onNodeWithText("Playback is steadier.").assertIsDisplayed()
        compose.onNodeWithText("Shared OkHttpClient", substring = true).assertDoesNotExist()
        compose.onNodeWithText("WHAT CHANGED IN DETAIL").performClick()
        compose.onNodeWithText("Shared OkHttpClient", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a changelog with no plain opening still gets a plain line and stays collapsed`() {
        show(UpdateState.Available(release.copy(notes = "### Fixed\n- Keystore rebind (#12)")))
        compose.onNodeWithText(PlainUpdateSummary).assertIsDisplayed()
        compose.onNodeWithText("Keystore rebind", substring = true).assertDoesNotExist()
        compose.onNodeWithText("WHAT CHANGED IN DETAIL").performClick()
        compose.onNodeWithText("Keystore rebind", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unannounced release opens nothing`() {
        show(UpdateState.Available(release, announce = false))
        compose.onNodeWithText("UPDATE AVAILABLE").assertDoesNotExist()
    }

    @Test
    fun `with install permission granted the download starts straight away`() {
        show(UpdateState.Available(release), canInstall = true)
        compose.onNodeWithText("DOWNLOAD & INSTALL").performClick()
        assertEquals(listOf("download 0.17.0"), events)
    }

    @Test
    fun `without install permission the app explains before Android asks`() {
        show(UpdateState.Available(release), canInstall = false)
        compose.onNodeWithText("DOWNLOAD & INSTALL").performClick()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithText(InstallPermissionExplanation).assertIsDisplayed()

        compose.onNodeWithText("BACK").performClick()
        compose.onNodeWithText("DOWNLOAD & INSTALL").performClick()
        compose.onNodeWithText("CONTINUE").performClick()
        assertEquals(listOf("download 0.17.0"), events)
    }

    @Test
    fun `a rejected update is explained in a dialog`() {
        show(UpdateState.Rejected("signed with a different key"))
        compose.onNodeWithText("UPDATE NOT INSTALLED").assertIsDisplayed()
        compose.onNodeWithText("signed with a different key").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun `the launch check switch flips and reports`() {
        var saved: Boolean? = null
        compose.setContent {
            TtsRoadTheme { AutoUpdateCheckSettingContent(initiallyEnabled = true, onEnabledChange = { saved = it }) }
        }
        compose.onNodeWithTag(AutoUpdateCheckToggleTag).assertIsOn().performClick()
        compose.onNodeWithTag(AutoUpdateCheckToggleTag).assertIsOff()
        assertEquals(false, saved)
    }
}
