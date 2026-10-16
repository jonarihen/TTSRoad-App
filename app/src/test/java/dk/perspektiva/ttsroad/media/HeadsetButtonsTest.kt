package dk.perspektiva.ttsroad.media

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #336: earbud double/triple taps arrive as next/previous and used to change chapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadsetButtonsTest {

    private fun down(keyCode: Int) = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
    private fun up(keyCode: Int) = KeyEvent(KeyEvent.ACTION_UP, keyCode)

    private fun action(
        event: KeyEvent?,
        fromOwnPendingIntent: Boolean = false,
        nextPreviousSeek: Boolean = true,
    ) = headsetButtonAction(event, fromOwnPendingIntent, nextPreviousSeek)

    @Test
    fun `next seeks forward and previous seeks back`() {
        assertEquals(HeadsetButtonAction.Seek(1), action(down(KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertEquals(HeadsetButtonAction.Seek(-1), action(down(KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
        assertEquals(HeadsetButtonAction.Seek(1), action(down(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD)))
        assertEquals(HeadsetButtonAction.Seek(-1), action(down(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD)))
    }

    @Test
    fun `the release and auto-repeats are swallowed so a press seeks once`() {
        assertEquals(HeadsetButtonAction.Consume, action(up(KeyEvent.KEYCODE_MEDIA_NEXT)))
        val repeat = KeyEvent.changeTimeRepeat(down(KeyEvent.KEYCODE_MEDIA_NEXT), 0L, 1)
        assertEquals(HeadsetButtonAction.Consume, action(repeat))
    }

    @Test
    fun `with the setting off next and previous change chapter as before`() {
        assertEquals(
            HeadsetButtonAction.PassThrough,
            action(down(KeyEvent.KEYCODE_MEDIA_NEXT), nextPreviousSeek = false),
        )
        assertEquals(
            HeadsetButtonAction.PassThrough,
            action(up(KeyEvent.KEYCODE_MEDIA_PREVIOUS), nextPreviousSeek = false),
        )
    }

    @Test
    fun `fast forward and rewind use the skip interval either way`() {
        assertEquals(
            HeadsetButtonAction.Seek(1),
            action(down(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD), nextPreviousSeek = false),
        )
        assertEquals(HeadsetButtonAction.Seek(-1), action(down(KeyEvent.KEYCODE_MEDIA_REWIND)))
    }

    @Test
    fun `the notification's own chapter buttons keep their meaning`() {
        assertEquals(
            HeadsetButtonAction.PassThrough,
            action(down(KeyEvent.KEYCODE_MEDIA_NEXT), fromOwnPendingIntent = true),
        )
    }

    @Test
    fun `play-pause and missing keys are left to Media3`() {
        assertEquals(HeadsetButtonAction.PassThrough, action(down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(HeadsetButtonAction.PassThrough, action(down(KeyEvent.KEYCODE_HEADSETHOOK)))
        assertEquals(HeadsetButtonAction.PassThrough, action(null))
    }
}
