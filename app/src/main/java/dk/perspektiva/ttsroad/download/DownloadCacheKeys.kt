package dk.perspektiva.ttsroad.download

/**
 * How a piece of chapter audio is identified inside the media cache.
 *
 * Media3's default cache key is the full request URL. That would be wrong here: the backend builds
 * absolute audio URLs from its own `BASE_URL`, and the app rewrites them onto whatever address the
 * phone actually signed in to (`ServerUrls.rewriteHost`). Keying on the URL means signing in again
 * over the LAN IP rather than the domain — or over a VPN, or on the emulator — silently orphans
 * every downloaded chapter and re-downloads the lot.
 *
 * Keying on the server-relative path instead makes a download survive the address changing, which
 * is the whole reason for downloading in the first place.
 *
 * Deliberately string-based for the same reason as [dk.perspektiva.ttsroad.core.ServerUrls]:
 * `android.net.Uri` is stubbed out in JVM unit tests, and `java.net.URI` rejects the unencoded
 * spaces the backend really does emit in EPUB-derived filenames.
 */
object DownloadCacheKeys {
    /** Leading `scheme://authority` of an absolute URL. */
    private val Origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*")

    /**
     * Cache key for [url]: its path and query with the origin stripped and the fragment dropped.
     *
     * The query is kept on purpose. If the backend ever versions a re-synthesised chapter through
     * the URL, reusing the old key would keep playing the stale audio forever. The fragment is
     * dropped because it never reaches the server.
     */
    fun forUrl(url: String): String {
        if (url.isBlank()) return url
        val withoutOrigin = Origin.find(url)?.let { url.substring(it.value.length) } ?: url
        val withoutFragment = withoutOrigin.substringBefore('#')
        return if (withoutFragment.startsWith("/")) withoutFragment else "/$withoutFragment"
    }
}
