package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

object TtsRoadMediaItems {
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

        ServerUrls.rewriteHostOrNull(fiction.coverImageUrl, serverUrl)
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

        ServerUrls.rewriteHostOrNull(cover, serverUrl)?.let { metadataBuilder.setArtworkUri(it.toUri()) }
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

