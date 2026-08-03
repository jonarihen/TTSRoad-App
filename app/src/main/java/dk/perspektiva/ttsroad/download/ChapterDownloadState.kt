package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import kotlin.math.floor

/**
 * What a chapter row draws for its download affordance.
 *
 * Media3's download states are a superset of what the UI has to distinguish — the difference
 * between "waiting for the queue" and "waiting for a network" is not something a chapter row can
 * usefully act on — so they are collapsed here rather than leaking int constants into Compose.
 */
enum class ChapterDownloadState {
    /** Nothing on disk and nothing queued: the row offers a download. */
    None,

    /** Accepted but not transferring yet, including waiting on the network requirement. */
    Queued,

    /** Bytes are moving; the row shows progress. */
    Downloading,

    /** Complete on disk: the row offers a delete and the chapter plays with the server unreachable. */
    Downloaded,

    /** The transfer gave up. Tapping retries. */
    Failed,

    /** A delete is in flight. */
    Removing,
    ;

    /** True only when the audio is fully on disk — a part-downloaded chapter still needs the server. */
    val isAvailableOffline: Boolean
        get() = this == Downloaded

    /** True while the download manager still has work to do, so the row's action stays disabled. */
    val isBusy: Boolean
        get() = this == Queued || this == Downloading || this == Removing
}

/** One chapter's download, as the UI needs it. */
data class ChapterDownload(
    val state: ChapterDownloadState,
    val percent: Int = 0,
    val bytesDownloaded: Long = 0L,
)

/**
 * Collapse a Media3 [Download] state into what a row renders.
 *
 * Opted in because the offline package is still marked unstable in media3 1.10.0.
 */
@OptIn(UnstableApi::class)
fun chapterDownloadState(media3State: Int): ChapterDownloadState = when (media3State) {
    Download.STATE_COMPLETED -> ChapterDownloadState.Downloaded
    Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> ChapterDownloadState.Downloading
    // STOPPED is what Media3 reports while the network requirement is unmet. That is a wait, not a
    // failure, and drawing it as one would have the user retrying a download that is already fine.
    Download.STATE_QUEUED, Download.STATE_STOPPED -> ChapterDownloadState.Queued
    Download.STATE_FAILED -> ChapterDownloadState.Failed
    Download.STATE_REMOVING -> ChapterDownloadState.Removing
    else -> ChapterDownloadState.None
}

/**
 * Media3's percentage as a whole number the UI can draw.
 *
 * It is `C.PERCENTAGE_UNSET` (-1) until the content length is known, and NaN if a zero-length
 * response ever divides through — neither should reach a progress bar.
 */
fun downloadPercent(percentDownloaded: Float): Int {
    if (percentDownloaded.isNaN()) return 0
    return floor(percentDownloaded.toDouble()).toInt().coerceIn(0, 100)
}
