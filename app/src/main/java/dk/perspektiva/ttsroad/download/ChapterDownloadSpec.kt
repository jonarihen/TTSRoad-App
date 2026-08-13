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
/**
 * Who asked for a download — the one thing that decides whether it may be deleted automatically.
 *
 * Keep-ahead maintains a sliding window and has to clean up behind itself, but the class comment on
 * [OfflineDownloads] promises that nothing the user downloaded disappears behind their back. Both
 * hold only if the two can be told apart on disk, across restarts.
 */
enum class DownloadOrigin {
    /** The user asked for this chapter by name. Never removed except by hand. */
    Manual,

    /** Queued by keep-ahead, and removable by it once the window has moved on. */
    Auto,
}

data class ChapterDownloadSpec(
    /** Same string as the chapter's media id, so a row can look its own download up. */
    val id: String,
    /** Absolute URL on the host the user actually signed in to. */
    val url: String,
    /** Host-independent cache key — see [DownloadCacheKeys]. */
    val cacheKey: String,
    val fictionId: Int,
    val chapterId: Int,
    val origin: DownloadOrigin = DownloadOrigin.Manual,
) {
    /**
     * The ids as Media3 stores them alongside the download. See [decodeDownloadIds].
     *
     * A manual download encodes to exactly the bytes 0.9.0 wrote, so upgrading re-reads every
     * existing record as manual rather than orphaning it — and "manual" is the reading that cannot
     * cause a deletion.
     */
    fun encodedIds(): ByteArray = when (origin) {
        DownloadOrigin.Manual -> "$fictionId:$chapterId"
        DownloadOrigin.Auto -> "$fictionId:$chapterId:$AutoMarker"
    }.toByteArray(Charsets.UTF_8)
}

/** The identity carried on a download record, recovered after a restart. */
data class DownloadIds(
    val fictionId: Int,
    val chapterId: Int,
    val origin: DownloadOrigin = DownloadOrigin.Manual,
)

private const val AutoMarker = "auto"

/**
 * Build the download for [chapter], or null when there is nothing to download yet — a chapter still
 * being synthesised has no `audio` object at all.
 *
 * The URL is rewritten onto [serverUrl] for the same reason playback does it: the backend builds
 * absolute URLs from its own configured `BASE_URL`, which is routinely not an address the phone can
 * reach. Downloading the raw URL was a real shipped bug for artwork in 0.7.0.
 *
 * [serverIdentity] scopes the cache key to the server the chapter came from; null keeps the 0.8.0
 * key. See [DownloadCacheKeys].
 */
fun chapterDownloadSpec(
    chapter: ChapterSummary,
    serverUrl: String?,
    serverIdentity: String? = null,
    origin: DownloadOrigin = DownloadOrigin.Manual,
): ChapterDownloadSpec? {
    val rawUrl = chapter.audio?.url?.takeIf { it.isNotBlank() } ?: return null
    val url = ServerUrls.rewriteHost(rawUrl.trim(), serverUrl)
    return ChapterDownloadSpec(
        id = TtsRoadMediaIds.chapter(chapter.resolvedChapterId),
        url = url,
        cacheKey = DownloadCacheKeys.forUrl(url, serverIdentity),
        fictionId = chapter.resolvedFictionId,
        chapterId = chapter.resolvedChapterId,
        origin = origin,
    )
}

/**
 * Read the ids back off a download record. Returns null for anything unparseable, so a record
 * written by an older build degrades to "unknown" instead of to fiction 0, chapter 0 — which would
 * look like a real chapter to every caller downstream.
 *
 * Two fields is the 0.9.0 record and reads as [DownloadOrigin.Manual]. A third field is the origin
 * marker; anything in it other than `auto` also reads as manual, because the question this answers
 * is "may keep-ahead delete this", and an unrecognised record has not earned a yes.
 */
fun decodeDownloadIds(data: ByteArray?): DownloadIds? {
    val parts = data?.takeIf { it.isNotEmpty() }
        ?.toString(Charsets.UTF_8)
        ?.split(':')
        ?.takeIf { it.size == 2 || it.size == 3 }
        ?: return null
    val fictionId = parts[0].toIntOrNull() ?: return null
    val chapterId = parts[1].toIntOrNull() ?: return null
    val origin = if (parts.getOrNull(2) == AutoMarker) DownloadOrigin.Auto else DownloadOrigin.Manual
    return DownloadIds(fictionId, chapterId, origin)
}
