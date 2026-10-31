package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.widget.NowPlayingSnapshot
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read where to resume only once the offline progress backlog has had its chance (#314).
 *
 * On a cold start from the car or a headset, the service's start-up flush and the resume's library
 * read used to run with no order between them. When the read won, playback resumed at the server's
 * older position, and the first 15s tick then recorded that older position for the same chapter —
 * and because the pending store keeps one entry per chapter, it replaced the newer queued one, which
 * was lost for good.
 *
 * [flush] is serialised behind any flush already running, so this simply waits its turn. A flush
 * that cannot send (offline, capabilities unknown) leaves the queue intact and the read goes ahead:
 * there is then nothing newer the server could have been told.
 */
internal suspend fun <T> afterPendingProgress(flush: suspend () -> Unit, read: suspend () -> T): T {
    flush()
    return read()
}

/** What to do about a resume the server could not be asked about (#327). */
internal sealed interface OfflineResume {
    /** The chapter's audio is on this phone: play it from [startPositionMs]. */
    data class Play(val item: MediaItem, val startPositionMs: Long) : OfflineResume

    /** Nothing on this phone to play. [message] says so in words a driver can act on. */
    data class Unavailable(val message: String) : OfflineResume
}

/**
 * Resume [chapter] — the newest continue-listening entry, from the library saved on disk — with no
 * server to build its fiction's queue from (#327).
 *
 * It can only play if its audio was downloaded; streamed audio needs the server that is not there.
 * The start position is the widget's note when that note is about the same chapter, because the
 * note is written on every tick and is newer than anything the server was told before the network
 * went; otherwise the position the saved library carries.
 */
internal fun offlineResume(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    serverUrl: String?,
    isDownloaded: (mediaId: String) -> Boolean,
    note: NowPlayingSnapshot?,
    /**
     * The newest unsent position queued for [chapter]. A resume cut off by the deadline (#483) may
     * not have flushed it, and the saved library is older: starting from the library's position
     * would let the next progress tick replace this newer entry (the pending store keeps one per
     * chapter, see #314). Ranks below the note, above the library.
     */
    pendingPositionSeconds: Double? = null,
): OfflineResume {
    val item = TtsRoadMediaItems.chapter(chapter, fiction, serverUrl)
    val title = chapter.resolvedTitle.takeIf { it.isNotBlank() } ?: "the last chapter"
    if (item == null || !isDownloaded(item.mediaId)) {
        return OfflineResume.Unavailable("Can't reach the server, and \"$title\" is not downloaded")
    }
    val fromNote = note
        ?.takeIf { it.chapterId > 0 && it.chapterId == chapter.resolvedChapterId }
        ?.positionMs
        ?.takeIf { it > 0L }
    val fromLibrary = chapter.startPositionSeconds.takeIf { it > 0.0 }?.let { (it * 1000).toLong() }
    val fromPending = pendingPositionSeconds?.takeIf { it > 0.0 }?.let { (it * 1000).toLong() }
    return OfflineResume.Play(item, fromNote ?: fromPending ?: fromLibrary ?: 0L)
}

/**
 * Run [online]; if it has not answered within [deadlineMs], or answers nothing, run [offline] (#483).
 *
 * A null from [online] means the network could not build a queue, which is what an offline resume
 * is for too. [offline] may throw to say why nothing can play.
 */
internal suspend fun <T : Any> withinResumeDeadline(
    deadlineMs: Long,
    online: suspend () -> T?,
    offline: suspend () -> T?,
): T? = withTimeoutOrNull(deadlineMs) { online() } ?: offline()

/**
 * The chapter list for a resume: the server's, or [saved] when that fails and [allowSaved] (#483).
 *
 * Resume passes false so that offline it falls to the downloaded-chapter path instead of building
 * a streaming queue from a saved list (#486) whose audio cannot be fetched.
 */
internal suspend fun <T : Any> chaptersOrSaved(
    allowSaved: Boolean,
    fetch: suspend () -> T?,
    saved: suspend () -> T?,
): T? = fetch() ?: if (allowSaved) saved() else null
