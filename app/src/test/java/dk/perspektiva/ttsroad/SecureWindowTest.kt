package dk.perspektiva.ttsroad

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.SecureFlagPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val isSecure: Boolean
        get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `the window is secure only while a secret screen is shown`() {
        var showing by mutableStateOf(false)
        compose.setContent { if (showing) SecureWindow() }
        compose.waitForIdle()
        assertFalse(isSecure)

        showing = true
        compose.waitForIdle()
        assertTrue(isSecure)

        showing = false
        compose.waitForIdle()
        assertFalse(isSecure)
    }

    @Test
    fun `overlapping secret screens keep the flag until the last one leaves`() {
        var setup by mutableStateOf(true)
        var codes by mutableStateOf(false)
        compose.setContent {
            if (setup) SecureWindow()
            if (codes) SecureWindow()
        }
        compose.waitForIdle()

        codes = true
        compose.waitForIdle()
        setup = false
        compose.waitForIdle()
        assertTrue(isSecure)

        codes = false
        compose.waitForIdle()
        assertFalse(isSecure)
    }

    @Test
    fun `secret dialogs secure their own window`() {
        assertEquals(SecureFlagPolicy.SecureOn, SecretDialogProperties.securePolicy)
    }
}
