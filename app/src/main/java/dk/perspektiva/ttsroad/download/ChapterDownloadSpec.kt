package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.core.ServerUrls
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds

/**
 * Everything needed to ask Media3 to download one chapter, with no Android types in it.
 *
 * `DownloadRequest` needs an `android.net.Uri`, which is stubbed out in JVM unit tests, so the
 * decisions worth testing — which id, which host, which cache key — are made here and the request
 * is assembled from this in [OfflineDownloads].
 */
data class ChapterDownloadSpec(
    /** Same string as the chapter's media id, so a row can look its own download up. */
    val id: String,
    /** Absolute URL on the host the user actually signed in to. */
    val url: String,
    /** Host-independent cache key — see [DownloadCacheKeys]. */
    val cacheKey: String,
    val fictionId: Int,
    val chapterId: Int,
) {
    /** The ids as Media3 stores them alongside the download. See [decodeDownloadIds]. */
    fun encodedIds(): ByteArray = "$fictionId:$chapterId".toByteArray(Charsets.UTF_8)
}

/** The identity carried on a download record, recovered after a restart. */
data class DownloadIds(val fictionId: Int, val chapterId: Int)

/**
 * Build the download for [chapter], or null when there is nothing to download yet — a chapter still
 * being synthesised has no `audio` object at all.
 *
 * The URL is rewritten onto [serverUrl] for the same reason playback does it: the backend builds
 * absolute URLs from its own configured `BASE_URL`, which is routinely not an address the phone can
 * reach. Downloading the raw URL was a real shipped bug for artwork in 0.7.0.
 */
fun chapterDownloadSpec(chapter: ChapterSummary, serverUrl: String?): ChapterDownloadSpec? {
    val rawUrl = chapter.audio?.url?.takeIf { it.isNotBlank() } ?: return null
    val url = ServerUrls.rewriteHost(rawUrl.trim(), serverUrl)
    return ChapterDownloadSpec(
        id = TtsRoadMediaIds.chapter(chapter.resolvedChapterId),
        url = url,
        cacheKey = DownloadCacheKeys.forUrl(url),
        fictionId = chapter.resolvedFictionId,
        chapterId = chapter.resolvedChapterId,
    )
}

/**
 * Read the ids back off a download record. Returns null for anything unparseable, so a record
 * written by an older build degrades to "unknown" instead of to fiction 0, chapter 0 — which would
 * look like a real chapter to every caller downstream.
 */
fun decodeDownloadIds(data: ByteArray?): DownloadIds? {
    val parts = data?.takeIf { it.isNotEmpty() }
        ?.toString(Charsets.UTF_8)
        ?.split(':')
        ?.takeIf { it.size == 2 }
        ?: return null
    val fictionId = parts[0].toIntOrNull() ?: return null
    val chapterId = parts[1].toIntOrNull() ?: return null
    return DownloadIds(fictionId, chapterId)
}
