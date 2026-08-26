package dk.perspektiva.ttsroad.widget

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dk.perspektiva.ttsroad.media.TtsRoadMediaService
import dk.perspektiva.ttsroad.media.TtsRoadSessionCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * The widget's buttons, issued against the one real playback session (#150).
 *
 * Every action here connects a short-lived [MediaController], sends one command and releases it.
 * That is not a second playback path: it is the same session the notification, the lockscreen and
 * Android Auto drive, so a widget tap and a notification tap are indistinguishable to the player.
 *
 * Connecting also *starts* the service when the process is dead, which is what makes play work from
 * a cold home screen — the service's `onPlaybackResumption` then restores the newest
 * continue-listening item, exactly as it does for a media-button press.
 *
 * All of it runs on [Dispatchers.Main]. Media3 verifies the application thread on every
 * [MediaController] call — `isPlaying`, `play`, `sendCustomCommand` and `release` alike — and Glance
 * dispatches [ActionCallback.onAction] on a background worker, so doing this anywhere else throws.
 * `player/PlaybackController` connects the same way for the same reason.
 *
 * The controller is released in a `finally`. A leaked one keeps a bound service alive for a widget
 * that has already finished drawing.
 */
private suspend fun <T> withController(context: Context, block: suspend (MediaController) -> T): T? =
    withContext(Dispatchers.Main) {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, TtsRoadMediaService::class.java),
        )
        val controller = runCatching {
            MediaController.Builder(context.applicationContext, token).buildAsync().await()
        }.getOrNull() ?: return@withContext null
        try {
            block(controller)
        } catch (_: Exception) {
            null
        } finally {
            controller.release()
        }
    }

/**
 * Redraw every placed widget after an action, so the button reflects what it just did.
 *
 * The service publishes its own refresh once the player has actually changed state; this is the
 * immediate one, so the button does not sit on its old label waiting for a listener to fire.
 */
private suspend fun refresh(context: Context) {
    runCatching { NowPlayingWidget().updateAll(context) }
}

class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            // Asked of the live player rather than of the stored note: by the time a tap arrives the
            // snapshot may be minutes old, and toggling from a stale reading would pause a player
            // the user just started somewhere else.
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        refresh(context)
    }
}

/**
 * The same 30-second skips the notification offers, sent as the session's own custom commands.
 *
 * Media3 has no built-in transport command for "seek by N inside the current item", which is why
 * [TtsRoadSessionCommands] exists; reusing it here means the widget and the notification cannot
 * drift into meaning different amounts.
 */
private suspend fun sendSkip(context: Context, command: SessionCommand) {
    withController(context) { controller ->
        controller.sendCustomCommand(command, Bundle.EMPTY).await()
    }
    refresh(context)
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        sendSkip(context, TtsRoadSessionCommands.skipBackCommand)
    }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        sendSkip(context, TtsRoadSessionCommands.skipForwardCommand)
    }
}
