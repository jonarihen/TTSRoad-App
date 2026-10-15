package dk.perspektiva.ttsroad

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.core.content.edit

/**
 * Whether to explain notifications before the system asks (#364).
 *
 * The system prompt used to appear straight after sign-in with nothing from the app to say why, and
 * "Don't allow" silently took the lockscreen and shade playback controls with it. The explanation is
 * offered once: after the person has answered it — either way — Settings > Notifications is the way
 * back, and a dialog on every cold start would be nagging.
 */
internal fun shouldOfferNotificationRationale(needsPermission: Boolean, answeredBefore: Boolean): Boolean =
    needsPermission && !answeredBefore

/** Remembers, per install, that the notification explanation has been answered. */
internal object NotificationRationalePrefs {
    private const val File = "ttsroad_onboarding"
    private const val Answered = "notification_rationale_answered"

    fun answered(context: Context): Boolean =
        context.getSharedPreferences(File, Context.MODE_PRIVATE).getBoolean(Answered, false)

    fun markAnswered(context: Context) {
        context.getSharedPreferences(File, Context.MODE_PRIVATE).edit { putBoolean(Answered, true) }
    }
}

internal const val NotificationRationaleText =
    "Allow notifications so you can pause and skip from the lock screen and the notification shade, " +
        "and hear when new chapters are ready. You can change this later in Settings > Notifications."

@Composable
internal fun NotificationRationaleDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        shape = RectangleShape,
        title = { Text("Playback controls and new chapters") },
        text = { Text(NotificationRationaleText) },
        confirmButton = {
            TextButton(onClick = onAllow, shape = RectangleShape) { Text("CONTINUE") }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, shape = RectangleShape) { Text("NOT NOW") }
        },
    )
}
