package dk.perspektiva.ttsroad

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry

/**
 * Posts "a chapter you were waiting for can be played now" into the shade (#175).
 *
 * **Its own channel**, deliberately. The media notification is the app's other one, and someone who
 * wants to silence "a chapter is ready" almost never wants to silence the transport controls too —
 * one channel for both would make that choice impossible.
 *
 * Posted **only** for the pulled → ready transition; the state before that lives in the in-app list
 * and the badge, which are surfaces somebody chooses to look at. Being told twice about one chapter
 * is how a channel gets turned off, and on a phone the second notification also buries whatever
 * else was in the shade.
 *
 * Nothing here talks to FCM. Everything it posts is a rendering of state the app already polled, so
 * it works on a deployment with no push credential at all — which is every deployment today. When
 * push arrives it should hand its payload to [notifyReady] rather than build a second path.
 */
class NewChapterNotifier(private val context: Context) {

    /**
     * Creates the channel if it does not exist.
     *
     * Idempotent, and safe to call before anything is ever posted: a channel that exists shows up
     * in the system settings, so somebody can turn this off *before* the first one arrives rather
     * than only in response to being interrupted.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ChannelId,
            context.getString(R.string.new_chapter_channel_name),
            // Default, not high: a chapter finishing converting is worth the shade and a sound, and
            // is never worth a heads-up card over whatever someone is doing.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.new_chapter_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts one notification for [fresh], or nothing when it is empty.
     *
     * Several chapters collapse into one line rather than one notification each — see
     * [dk.perspektiva.ttsroad.data.readyNotificationText]. A single chapter's notification opens
     * straight into it; a batch opens the list.
     */
    fun notifyReady(title: String, body: String, single: ChapterNotificationEntry?) {
        // Checked rather than assumed: POST_NOTIFICATIONS is denied by default on Android 13+, and
        // the per-app toggle can be turned off at any time afterwards.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_new_chapter)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openIntent(single))
            .build()

        runCatching {
            // The tag keeps this to one live notification: a serial converting a backlog posts an
            // updated line rather than a stack of them.
            NotificationManagerCompat.from(context).notify(Tag, NotificationId, notification)
        }
    }

    /** Clears the notice, for when the list it announced has been dealt with in the app. */
    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(Tag, NotificationId) }
    }

    private fun openIntent(single: ChapterNotificationEntry?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ExtraOpenNotifications, true)
            single?.let {
                putExtra(ExtraFictionId, it.fiction.id)
                putExtra(ExtraChapterId, it.chapter.id)
            }
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode= */ NotificationId,
            intent,
            // Mutable would let another app rewrite the extras; UPDATE_CURRENT so a second batch
            // replaces the first one's destination rather than reusing a stale chapter id.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ChannelId: String = "ttsroad_new_chapters"
        const val Tag: String = "ttsroad-new-chapter"
        const val NotificationId: Int = 4175

        /** Set on the launch intent so the activity knows to open the notices list. */
        const val ExtraOpenNotifications: String = "dk.perspektiva.ttsroad.OPEN_NOTIFICATIONS"
        const val ExtraFictionId: String = "dk.perspektiva.ttsroad.NOTIFICATION_FICTION_ID"
        const val ExtraChapterId: String = "dk.perspektiva.ttsroad.NOTIFICATION_CHAPTER_ID"
    }
}
