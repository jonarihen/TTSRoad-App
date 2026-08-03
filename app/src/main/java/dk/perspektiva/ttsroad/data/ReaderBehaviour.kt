package dk.perspektiva.ttsroad.data

/**
 * Reader behaviour that is pure enough to test, kept out of the composable so it can be.
 */

/**
 * Whether the reader should hold the screen awake.
 *
 * The sleep timer's fade wins over the preference. Reading is why the screen is held bright in the
 * first place, but the fade means the listener is on their way out, and a reader left open would
 * otherwise keep the screen lit long after the audio has stopped.
 */
fun shouldKeepReaderScreenOn(preferenceEnabled: Boolean, sleepTimerFading: Boolean): Boolean =
    preferenceEnabled && !sleepTimerFading

/**
 * Where auto-scroll puts the active paragraph, as a lazy-list scroll offset in pixels.
 *
 * Negative, because the offset leaves space above the item. The line being spoken sits a third of
 * the way down: reading runs ahead of the audio, so the rest of the sentence has to be visible
 * below it, and pinning the active line to the very top leaves nothing to read into.
 */
fun readerAutoScrollOffsetPx(viewportHeightPx: Int): Int =
    if (viewportHeightPx <= 0) 0 else -(viewportHeightPx / 3)
