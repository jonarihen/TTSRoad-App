package dk.perspektiva.ttsroad.download

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dk.perspektiva.ttsroad.R
import dk.perspektiva.ttsroad.core.ServiceLocator

/**
 * Foreground service that runs the download queue.
 *
 * Separate from [dk.perspektiva.ttsroad.media.TtsRoadMediaService] on purpose: a download is a data
 * sync, not media playback, and Android 14+ requires the foreground service type to match what the
 * service actually does. Both share the one [OfflineDownloads] instance, so there is still only one
 * cache and one download index in the process.
 *
 * Opted in because the offline package is still marked unstable in media3 1.10.0.
 */
@OptIn(UnstableApi::class)
class TtsRoadDownloadService : DownloadService(
    ForegroundNotificationId,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    ChannelId,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {
    private val notifications: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, ChannelId)
    }

    override fun getDownloadManager(): DownloadManager =
        ServiceLocator.offlineDownloads(this).downloadManager

    /**
     * No scheduler, so nothing restarts downloads from the background.
     *
     * A [androidx.media3.exoplayer.scheduler.PlatformScheduler] would need `RECEIVE_BOOT_COMPLETED`
     * and would wake the phone to finish an audiobook chapter. The app resumes unfinished downloads
     * the next time it is opened instead — see [OfflineDownloads.resumeUnfinished].
     */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification = notifications.buildProgressNotification(
        /* context= */ this,
        /* smallIcon= */ R.drawable.ic_stat_download,
        /* contentIntent= */ null,
        /* message= */ null,
        downloads,
        notMetRequirements,
    )

    private companion object {
        /** Distinct from the media notification's id, or one would replace the other. */
        const val ForegroundNotificationId = 2
        const val ChannelId = "ttsroad_downloads"
    }
}
