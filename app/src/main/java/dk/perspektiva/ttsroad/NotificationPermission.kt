package dk.perspektiva.ttsroad

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The permission name is a plain string constant, so inlining it below API 33 is safe; every use is
 * gated on [shouldRequestNotificationPermission].
 */
@SuppressLint("InlinedApi")
internal val PostNotificationsPermission: String = Manifest.permission.POST_NOTIFICATIONS

/**
 * Android 13+ defaults `POST_NOTIFICATIONS` to denied. Without it the media notification posted by
 * the playback service is suppressed, taking the shade and lockscreen transport controls with it.
 * Below API 33 the permission is granted at install time, so there is nothing to ask for.
 */
internal fun shouldRequestNotificationPermission(sdkInt: Int, isGranted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted

internal fun needsNotificationPermission(context: Context): Boolean =
    shouldRequestNotificationPermission(
        sdkInt = Build.VERSION.SDK_INT,
        isGranted = ContextCompat.checkSelfPermission(context, PostNotificationsPermission) ==
            PackageManager.PERMISSION_GRANTED,
    )

/**
 * Reflects the permission *and* the per-app notification toggle, so Settings stays accurate when
 * notifications are switched off outside the app.
 */
internal fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Deep-links to this app's notification settings, so a denied prompt stays recoverable. */
internal fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
