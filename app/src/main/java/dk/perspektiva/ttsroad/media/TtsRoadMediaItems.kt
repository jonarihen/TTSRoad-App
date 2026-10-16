package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dk.perspektiva.ttsroad.core.ServerUrls
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.QueueItem

object TtsRoadMediaIds {
    const val Root = "root"
    const val Continue = "continue"
    const val Fictions = "fictions"
    const val Recent = "recent"
    const val Queue = "queue"
    const val FictionPrefix = "fiction:"
    const val ChapterPrefix = "chapter:"

    /**
     * The "Resume: Chapter N" row at the top of a fiction's chapter list (#328). Its own prefix,
     * not the chapter's id, so the same chapter can also appear further down the list without two
     * rows sharing an id; it carries the fiction id, and the resume point is worked out on tap.
     */
    const val ResumePrefix = "resume:"

    fun fiction(fictionId: Int): String = "$FictionPrefix$fictionId"
    fun chapter(chapterId: Int): String = "$ChapterPrefix$chapterId"
    fun fictionId(mediaId: String): Int? = mediaId.removePrefix(FictionPrefix).toIntOrNull()
    fun chapterId(mediaId: String): Int? = mediaId.removePrefix(ChapterPrefix).toIntOrNull()
    fun resume(fictionId: Int): String = "$ResumePrefix$fictionId"

    /**
     * The fiction a media id asks to start as a whole: a fiction node played directly, or a resume
     * row. Null for anything else, including a chapter.
     */
    fun playableFictionId(mediaId: String): Int? = when {
        mediaId.startsWith(FictionPrefix) -> mediaId.removePrefix(FictionPrefix).toIntOrNull()
        mediaId.startsWith(ResumePrefix) -> mediaId.removePrefix(ResumePrefix).toIntOrNull()
        else -> null
    }
}

/**
 * Tell the car how far through a chapter the listener is, so browse rows draw a progress bar and a
 * started chapter stops looking identical to an untouched one.
 *
 * Media3 relays these through to the car's media browser as-is; there is no typed API for them.
 */
@OptIn(UnstableApi::class)
private fun Bundle.putCompletion(chapter: ChapterSummary) {
    val played = chapter.playback?.isPlayed == true
    val positionSeconds = chapter.resolvedPositionSeconds
    val durationSeconds = chapter.audioDuration?.takeIf { it > 0.0 }

    val status = when {
        played -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        positionSeconds > 0.0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
    }
    putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)

    // Only meaningful while partially played: a finished chapter is 100% by definition, and an
    // unstarted one has nothing to draw.
    if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED &&
        durationSeconds != null
    ) {
        putDouble(
            MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0),
        )
    }
}

internal const val AudioServerUrlExtra = "audio_server_url"

/** The account a queued item was queued for; stamped by the media service, see `withAudioAccount`. */
internal const val AudioAccountExtra = "audio_account"

object TtsRoadMediaItems {
    /**
     * Browse-node styling hints. Fictions are cover-led, so they read far better as a grid; chapters
     * are a long ordered list where the title is what matters, so they stay as rows.
     */
    @OptIn(UnstableApi::class)
    fun contentStyle(browsableGrid: Boolean, playableGrid: Boolean = false): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            if (browsableGrid) {
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            } else {
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            },
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            if (playableGrid) {
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            } else {
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            },
        )
    }

    fun root(): MediaItem = folder(
        mediaId = TtsRoadMediaIds.Root,
        title = "TTSRoad",
        subtitle = "Library",
    )

    fun folder(mediaId: String, title: String, subtitle: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    fun fictionFolder(fiction: FictionSummary, serverUrl: String? = null): MediaItem {
        val subtitle = listOfNotNull(
            fiction.author,
            "${fiction.doneChapters}/${fiction.totalChapters} ready",
        ).joinToString(" - ")

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(fiction.title)
            .setSubtitle(subtitle)
            .setArtist(fiction.author)
            .setIsBrowsable(true)
            // Playable as well (#328): starting a book you are halfway through should not mean
            // scrolling a 400-chapter list from a driver's seat. Played, it resumes.
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)

        ServerUrls.resolveCoverOrNull(fiction.coverImageUrl, serverUrl)
            ?.let { metadataBuilder.setArtworkUri(it.toUri()) }

        return MediaItem.Builder()
            .setMediaId(TtsRoadMediaIds.fiction(fiction.id))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * @param crossFiction whether this row sits in a list that spans books — Continue, Recent, a
     *   search. There the subtitle names the fiction, because "Chapter 212 · 41 min" with no book
     *   is unanswerable from a driver's seat, and many car UIs never show the album title (#331).
     *   Inside a fiction's own folder the book is already known, so the duration stays.
     */
    fun chapter(
        chapter: ChapterSummary,
        fiction: FictionSummary? = chapter.fiction,
        serverUrl: String? = null,
        crossFiction: Boolean = false,
    ): MediaItem? {
        val rawUrl = chapter.audio?.url ?: return null
        val audioUrl = ServerUrls.rewriteAudioUrlOrNull(rawUrl, serverUrl) ?: return null
        val audioUri = audioUrl.toUri()
        val extras = Bundle().apply {
            putString(AudioServerUrlExtra, serverUrl)
            putInt("fiction_id", chapter.resolvedFictionId)
            putInt("chapter_id", chapter.resolvedChapterId)
            chapter.displayNumber?.let { putDouble("display_number", it) }
            chapter.resolvedPositionSeconds.takeIf { it > 0.0 }?.let { putDouble("position_seconds", it) }
            putCompletion(chapter)
        }
        val fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle
        val author = fiction?.author ?: chapter.resolvedAuthor
        val cover = fiction?.coverImageUrl ?: chapter.resolvedCoverUrl
        val durationMs = chapter.audioDuration
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000).toLong() }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(chapter.resolvedTitle)
            .setAlbumTitle(fictionTitle)
            .setArtist(author)
            .setSubtitle(
                if (crossFiction) {
                    crossFictionSubtitle(fictionTitle, chapter) ?: chapter.audioDurationLabel
                } else {
                    chapter.audioDurationLabel
                },
            )
            .setExtras(extras)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        ServerUrls.resolveCoverOrNull(cover, serverUrl)?.let { metadataBuilder.setArtworkUri(it.toUri()) }
        durationMs?.let { metadataBuilder.setDurationMs(it) }

        return MediaItem.Builder()
            .setMediaId(TtsRoadMediaIds.chapter(chapter.resolvedChapterId))
            .setUri(audioUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(audioUri)
                    .build(),
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * A server-queue entry as a playable item.
     *
     * Separate from [chapter] because a queue entry is already flat — it carries its own fiction
     * title and cover rather than a [FictionSummary] to look them up in, which is exactly what lets
     * an Up Next list span several books.
     *
     * The media id is still `chapter:<id>`, so an item played from the queue is indistinguishable
     * downstream from one played from a fiction: progress sync and queue expansion read the same
     * extras either way.
     */
    fun queueItem(item: QueueItem, serverUrl: String? = null): MediaItem? {
        val rawUrl = item.audio?.url ?: return null
        val audioUrl = ServerUrls.rewriteAudioUrlOrNull(rawUrl, serverUrl) ?: return null
        val audioUri = audioUrl.toUri()
        val extras = Bundle().apply {
            putString(AudioServerUrlExtra, serverUrl)
            putInt("fiction_id", item.fictionId)
            putInt("chapter_id", item.chapterId)
            item.chapterNumber?.let { putDouble("display_number", it) }
            item.positionSeconds.takeIf { it > 0.0 }?.let { putDouble("position_seconds", it) }
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(item.resolvedTitle)
            .setAlbumTitle(item.fictionTitle)
            // The fiction is the useful subtitle here, not the duration: an Up Next list crosses
            // books, so "which book is this" is the question the row has to answer.
            .setSubtitle(item.fictionTitle)
            .setExtras(extras)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        ServerUrls.resolveCoverOrNull(item.coverImageUrl, serverUrl)
            ?.let { metadataBuilder.setArtworkUri(it.toUri()) }
        item.audioDuration
            ?.takeIf { it > 0.0 }
            ?.let { metadataBuilder.setDurationMs((it * 1000).toLong()) }

        return MediaItem.Builder()
            .setMediaId(TtsRoadMediaIds.chapter(item.chapterId))
            .setUri(audioUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(audioUri)
                    .build(),
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * "Ashes of Aether · 12 min left" for a started chapter, "Ashes of Aether · 41 min" for an
     * unstarted one, the fiction alone when there is no duration. Null without a fiction title.
     */
    internal fun crossFictionSubtitle(fictionTitle: String?, chapter: ChapterSummary): String? {
        val book = fictionTitle?.takeIf { it.isNotBlank() } ?: return null
        val started = chapter.resolvedPositionSeconds > 0.0 && !chapter.resolvedIsPlayed
        val remaining = chapter.resolvedRemainingSeconds.takeIf { started }
        val time = remaining?.let { seconds -> minutesLabel(seconds)?.let { "$it left" } }
            ?: chapter.audioDurationLabel?.takeIf { it.isNotBlank() }
        return listOfNotNull(book, time).joinToString(" \u00b7 ")
    }

    /** "12 min", "1 h 5 min", or null once nothing meaningful remains. */
    private fun minutesLabel(seconds: Double): String? {
        val minutes = (seconds / 60.0).toLong()
        return when {
            seconds <= 0.0 -> null
            minutes < 1L -> "<1 min"
            minutes < 60L -> "$minutes min"
            minutes % 60L == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /**
     * "Resume: Chapter 212", the first row of a fiction's chapter list (#328).
     *
     * Built from the chapter's own item so it carries the same artwork, URI and extras — a
     * controller that sends the item back whole gets the chapter's queue directly — under the
     * fiction's resume id, which is what a controller that sends only the id resolves.
     */
    fun resumeRow(fictionId: Int, chapterItem: MediaItem): MediaItem {
        val metadata = chapterItem.mediaMetadata.buildUpon()
            .setTitle("Resume: ${chapterItem.mediaMetadata.title ?: "chapter"}")
            .build()
        return chapterItem.buildUpon()
            .setMediaId(TtsRoadMediaIds.resume(fictionId))
            .setMediaMetadata(metadata)
            .build()
    }
}
