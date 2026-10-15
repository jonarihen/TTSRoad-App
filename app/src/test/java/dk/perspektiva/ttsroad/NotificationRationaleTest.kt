package dk.perspektiva.ttsroad

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class NotificationRationaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the explanation is offered only when the permission is missing and unanswered`() {
        assertTrue(shouldOfferNotificationRationale(needsPermission = true, answeredBefore = false))
        assertFalse(shouldOfferNotificationRationale(needsPermission = true, answeredBefore = true))
        assertFalse(shouldOfferNotificationRationale(needsPermission = false, answeredBefore = false))
    }

    @Test
    fun `an answer is remembered`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(NotificationRationalePrefs.answered(context))

        NotificationRationalePrefs.markAnswered(context)

        assertTrue(NotificationRationalePrefs.answered(context))
    }

    @Test
    fun `the dialog explains why and leads to the system prompt only on continue`() {
        var allowed = 0
        var declined = 0
        compose.setContent {
            TtsRoadTheme {
                NotificationRationaleDialog(onAllow = { allowed++ }, onNotNow = { declined++ })
            }
        }

        compose.onNodeWithText(NotificationRationaleText).assertExists()
        compose.onNodeWithText("NOT NOW").performClick()
        assertEquals(0 to 1, allowed to declined)
        compose.onNodeWithText("CONTINUE").performClick()
        assertEquals(1 to 1, allowed to declined)
    }
}
