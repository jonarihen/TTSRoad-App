package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList

/**
 * The -30s / +30s transport controls offered to everything outside the app UI: the notification,
 * the lockscreen, and the Android Auto transport row.
 *
 * Media3 has no built-in player command for "seek by N inside the current item" that surfaces as
 * a transport button, so these are custom session commands handled in
 * [TtsRoadMediaService]'s session callback.
 */
object TtsRoadSessionCommands {
    const val SkipBack = "dk.perspektiva.ttsroad.SKIP_BACK"
    const val SkipForward = "dk.perspektiva.ttsroad.SKIP_FORWARD"

    val skipBackCommand = SessionCommand(SkipBack, Bundle.EMPTY)
    val skipForwardCommand = SessionCommand(SkipForward, Bundle.EMPTY)

    /**
     * Buttons for the session's media button preferences. [CommandButton.SLOT_BACK] and
     * [CommandButton.SLOT_FORWARD] are the slots either side of play/pause, which is where this
     * app wants the 30-second skips — previous/next chapter fall back to the secondary slots.
     * Each also declares [CommandButton.SLOT_OVERFLOW] so a surface with room for fewer buttons
     * can still reach them.
     */
    @OptIn(UnstableApi::class)
    fun mediaButtonPreferences(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
            .setSessionCommand(skipBackCommand)
            .setDisplayName("Back 30 seconds")
            .setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setSessionCommand(skipForwardCommand)
            .setDisplayName("Forward 30 seconds")
            .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
            .build(),
    )
}
