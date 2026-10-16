package dk.perspektiva.ttsroad.media

import android.view.KeyEvent

/**
 * What a hardware media key should do, decided before Media3's defaults get it (#336).
 *
 * Earbuds turn a double tap into "next" and a triple tap into "previous" before Android sees them,
 * so Media3's defaults change *chapter* — a whole chapter lost to a half-asleep tap. For spoken
 * audio the meaning that fits is "skip a bit", by the interval the user configured. Fast-forward
 * and rewind keys get the same interval rather than ExoPlayer's fixed default increments.
 */
internal sealed interface HeadsetButtonAction {
    /** Not ours to decide: let Media3 handle it as it always has. */
    data object PassThrough : HeadsetButtonAction

    /** A key we handle, but not this half of it — the release, or an auto-repeat. */
    data object Consume : HeadsetButtonAction

    /** Seek by the skip interval: +1 forward, −1 back. */
    data class Seek(val direction: Int) : HeadsetButtonAction
}

/**
 * @param fromOwnPendingIntent whether the intent is one of Media3's own notification buttons.
 *   Those carry the session URI as their data; a key from a headset or the car does not. The
 *   notification's previous/next buttons are labelled as chapter buttons and must stay that.
 * @param nextPreviousSeek the user's setting: whether next/previous keys seek (the default) or
 *   change chapter.
 */
internal fun headsetButtonAction(
    keyEvent: KeyEvent?,
    fromOwnPendingIntent: Boolean,
    nextPreviousSeek: Boolean,
): HeadsetButtonAction {
    if (keyEvent == null || fromOwnPendingIntent) return HeadsetButtonAction.PassThrough
    val direction = when (keyEvent.keyCode) {
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
        KeyEvent.KEYCODE_MEDIA_REWIND -> -1
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        -> if (nextPreviousSeek) 1 else return HeadsetButtonAction.PassThrough
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        -> if (nextPreviousSeek) -1 else return HeadsetButtonAction.PassThrough
        else -> return HeadsetButtonAction.PassThrough
    }
    return if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
        HeadsetButtonAction.Seek(direction)
    } else {
        HeadsetButtonAction.Consume
    }
}
