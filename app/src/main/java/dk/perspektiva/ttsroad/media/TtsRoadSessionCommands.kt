package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList

/**
 * The transport controls offered to everything outside the app UI: the notification, the lockscreen,
 * and the Android Auto transport row.
 *
 * Media3 has no built-in player command for "seek by N inside the current item" that surfaces as
 * a transport button, and none at all for "bookmark this", so these are custom session commands
 * handled in [TtsRoadMediaService]'s session callback.
 */
object TtsRoadSessionCommands {
    const val SkipBack = "dk.perspektiva.ttsroad.SKIP_BACK"
    const val SkipForward = "dk.perspektiva.ttsroad.SKIP_FORWARD"
    const val Bookmark = "dk.perspektiva.ttsroad.BOOKMARK"

    val skipBackCommand = SessionCommand(SkipBack, Bundle.EMPTY)
    val skipForwardCommand = SessionCommand(SkipForward, Bundle.EMPTY)
    val bookmarkCommand = SessionCommand(Bookmark, Bundle.EMPTY)

    /**
     * Buttons for the session's media button preferences. [CommandButton.SLOT_BACK] and
     * [CommandButton.SLOT_FORWARD] are the slots either side of play/pause, which is where this
     * app wants the 30-second skips — previous/next chapter fall back to the secondary slots.
     * Each also declares [CommandButton.SLOT_OVERFLOW] so a surface with room for fewer buttons
     * can still reach them.
     *
     * @param bookmarks whether the signed-in server can hold a bookmark. Off by default so a
     *   session built before capability discovery has finished — which is every cold start, and
     *   every start from the car with no UI running — offers nothing it might not be able to honour.
     */
    @OptIn(UnstableApi::class)
    fun mediaButtonPreferences(bookmarks: Boolean = false): ImmutableList<CommandButton> {
        val buttons = ImmutableList.builder<CommandButton>()
            .add(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
                    .setSessionCommand(skipBackCommand)
                    .setDisplayName("Back 30 seconds")
                    .setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            .add(
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                    .setSessionCommand(skipForwardCommand)
                    .setDisplayName("Forward 30 seconds")
                    .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        if (bookmarks) {
            // Overflow only, and last. The 30-second skips are what a driver reaches for constantly
            // and they own the slots either side of play/pause; a bookmark button that displaced one
            // of them would cost more than it adds. Overflow is where the car puts extra actions,
            // which is exactly what this is.
            buttons.add(
                CommandButton.Builder(CommandButton.ICON_BOOKMARK_FILLED)
                    .setSessionCommand(bookmarkCommand)
                    .setDisplayName("Bookmark this moment")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }
        return buttons.build()
    }
}
