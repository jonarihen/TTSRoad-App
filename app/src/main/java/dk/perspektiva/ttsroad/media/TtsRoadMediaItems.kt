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

object TtsRoadMediaIds {
    const val Root = "root"
    const val Continue = "continue"
    const val Fictions = "fictions"
    const val Recent = "recent"
    const val FictionPrefix = "fiction:"
    const val ChapterPrefix = "chapter:"

    fun fiction(fictionId: Int): String = "$FictionPrefix$fictionId"
    fun chapter(chapterId: Int): String = "$ChapterPrefix$chapterId"
    fun fictionId(mediaId: String): Int? = mediaId.removePrefix(FictionPrefix).toIntOrNull()
    fun chapterId(mediaId: String): Int? = mediaId.removePrefix(ChapterPrefix).toIntOrNull()
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
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)

        ServerUrls.resolveCoverOrNull(fiction.coverImageUrl, serverUrl)
            ?.let { metadataBuilder.setArtworkUri(it.toUri()) }

        return MediaItem.Builder()
            .setMediaId(TtsRoadMediaIds.fiction(fiction.id))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun chapter(
        chapter: ChapterSummary,
        fiction: FictionSummary? = chapter.fiction,
        serverUrl: String? = null,
    ): MediaItem? {
        val rawUrl = chapter.audio?.url ?: return null
        val audioUri = ServerUrls.rewriteHost(rawUrl, serverUrl).toUri()
        val extras = Bundle().apply {
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
            .setSubtitle(chapter.audioDurationLabel)
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
}
