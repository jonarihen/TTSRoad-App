package dk.perspektiva.ttsroad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.WeakHashMap

/**
 * Keeps whatever is on screen out of screenshots, screen recordings and the Recents thumbnail for
 * as long as this is in the composition, by setting `FLAG_SECURE` on the activity's window.
 *
 * For screens that show a secret the user is meant to copy somewhere else exactly once — the TOTP
 * setup key and the recovery codes. Cleared again when the last such screen leaves, so the rest of
 * the app stays screenshot-able.
 *
 * A dialog has its own window, which only inherits the flag if it was set when the dialog opened;
 * pair this with [SecretDialogProperties] on the dialog itself rather than relying on that order.
 */
@Composable
internal fun SecureWindow() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SecureWindows.acquire(window)
        onDispose { SecureWindows.release(window) }
    }
}

/** Dialog properties for a dialog that shows a secret: its own window is always `FLAG_SECURE`. */
internal val SecretDialogProperties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

/**
 * Counts the secret screens open on each window, so that two of them overlapping — the setup dialog
 * handing over to the recovery codes — does not clear the flag while the second is still showing.
 */
internal object SecureWindows {
    private val holders = WeakHashMap<Window, Int>()

    fun acquire(window: Window) = synchronized(holders) {
        val count = holders[window] ?: 0
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        holders[window] = count + 1
    }

    fun release(window: Window) = synchronized(holders) {
        val count = (holders[window] ?: return@synchronized) - 1
        if (count <= 0) {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[window] = count
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
