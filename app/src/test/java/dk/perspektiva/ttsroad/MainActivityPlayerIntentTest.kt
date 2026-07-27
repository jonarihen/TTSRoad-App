package dk.perspektiva.ttsroad

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The media notification's content intent is built by the service and read back by the activity.
 * These tests pin both halves together so the notification cannot silently stop opening the player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityPlayerIntentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `player intent targets MainActivity`() {
        val intent = MainActivity.playerIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `player intent reuses an existing activity instead of stacking a new one`() {
        val intent = MainActivity.playerIntent(context)

        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun `player intent is recognised as a request to open the player`() {
        assertTrue(MainActivity.consumeOpenPlayer(MainActivity.playerIntent(context)))
    }

    @Test
    fun `the request is consumed once so a recreation does not bounce back to the player`() {
        val intent = MainActivity.playerIntent(context)

        assertTrue(MainActivity.consumeOpenPlayer(intent))
        assertFalse(MainActivity.consumeOpenPlayer(intent))
    }

    @Test
    fun `an ordinary launch does not open the player`() {
        assertFalse(MainActivity.consumeOpenPlayer(Intent(context, MainActivity::class.java)))
        assertFalse(MainActivity.consumeOpenPlayer(null))
    }
}
