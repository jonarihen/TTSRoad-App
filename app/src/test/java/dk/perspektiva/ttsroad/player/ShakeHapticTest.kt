package dk.perspektiva.ttsroad.player

import android.os.Vibrator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShakeHapticTest {
    @Test
    fun `extending the timer by shake vibrates`() {
        val context = RuntimeEnvironment.getApplication()
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrateExtended(context)
        assertTrue(shadowOf(vibrator).isVibrating)
    }
}
