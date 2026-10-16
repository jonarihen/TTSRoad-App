package dk.perspektiva.ttsroad.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a fresh install checks automatically and announces anything new`() {
        val choices = UpdatePreferences.read(context)
        assertEquals(UpdatePromptChoices(), choices)
        assertTrue(choices.autoCheck)
        assertTrue(shouldAnnounceUpdate("1.0.0", choices, nowMs = 0L))
    }

    @Test
    fun `automatic checks can be turned off and back on`() {
        UpdatePreferences.setAutoCheck(context, false)
        assertFalse(UpdatePreferences.read(context).autoCheck)
        UpdatePreferences.setAutoCheck(context, true)
        assertTrue(UpdatePreferences.read(context).autoCheck)
    }

    @Test
    fun `a skipped version stays quiet, the next one does not`() {
        UpdatePreferences.skip(context, "1.2.0")
        val choices = UpdatePreferences.read(context)
        assertFalse(shouldAnnounceUpdate("1.2.0", choices, nowMs = 0L))
        assertTrue(shouldAnnounceUpdate("1.3.0", choices, nowMs = 0L))
    }

    @Test
    fun `later keeps the prompt away for a day`() {
        val now = 1_000_000L
        UpdatePreferences.snooze(context, now)
        val choices = UpdatePreferences.read(context)
        assertFalse(shouldAnnounceUpdate("1.2.0", choices, now + UpdateSnoozeMs - 1))
        assertTrue(shouldAnnounceUpdate("1.2.0", choices, now + UpdateSnoozeMs))
    }
}
