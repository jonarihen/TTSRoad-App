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
 * A path is only unique *per server*, though — for the same reason chapter ids are. Two TTSRoad
 * instances holding a fiction with the same slug produce the same `/audio/<slug>/<file>`, and the
 * cache would happily serve one server's audio to the other. So the key is scoped by the server's
 * own identity when one is known: not the address the phone reached it on, which is exactly the
 * thing that changes, but the `base_url` the server reports about itself through
 * `/api/mobile/capabilities`.
 *
 * With no identity — an older server that does not advertise one, or capabilities not fetched yet —
 * the key stays the bare path, which is what shipped in 0.8.0.
 *
 * Deliberately string-based for the same reason as [dk.perspektiva.ttsroad.core.ServerUrls]:
 * `android.net.Uri` is stubbed out in JVM unit tests, and `java.net.URI` rejects the unencoded
 * spaces the backend really does emit in EPUB-derived filenames.
 */
object DownloadCacheKeys {
    /** Leading `scheme://authority` of an absolute URL. */
    private val Origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*")

    /**
     * Separates the server identity from the path. A space cannot appear in an identity — it is an
     * authority plus a path prefix — so it cannot be confused with one that is part of a filename.
     */
    private const val Separator = " "

    /**
     * Cache key for [url] on the server identified by [serverIdentity]: its path and query with the
     * origin stripped and the fragment dropped, prefixed by the identity when there is one.
     *
     * The query is kept on purpose. If the backend ever versions a re-synthesised chapter through
     * the URL, reusing the old key would keep playing the stale audio forever. The fragment is
     * dropped because it never reaches the server.
     */
    fun forUrl(url: String, serverIdentity: String? = null): String {
        if (url.isBlank()) return url
        val withoutOrigin = Origin.find(url)?.let { url.substring(it.value.length) } ?: url
        val withoutFragment = withoutOrigin.substringBefore('#')
        val path = if (withoutFragment.startsWith("/")) withoutFragment else "/$withoutFragment"
        return if (serverIdentity.isNullOrBlank()) path else serverIdentity + Separator + path
    }

    /**
     * Whether [key] already names a server, as opposed to being a bare 0.8.0 path key.
     *
     * A scoped key starts with an authority; an unscoped one is a path, and [forUrl] guarantees the
     * leading slash. That is enough to tell one server's entries from the unattributed ones without
     * having to parse either.
     */
    fun isScoped(key: String): Boolean = key.isNotBlank() && !key.startsWith("/")

    /**
     * The identity to scope keys by for a server reporting [baseUrl], or null when it reports
     * nothing usable.
     *
     * The scheme is dropped on purpose: an admin putting the instance behind TLS changes
     * `http://…` to `https://…` without becoming a different server, and orphaning every download
     * over that would be the same failure keying on the URL causes. What is left — authority plus
     * any path prefix, lowercased — distinguishes two instances without being disturbed by how
     * either one is reached.
     */
    fun serverIdentity(baseUrl: String?): String? {
        val value = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val origin = Origin.find(value) ?: return null
        val authority = origin.value.substringAfter("://").takeIf { it.isNotEmpty() } ?: return null
        val prefix = value.substring(origin.value.length).substringBefore('#').substringBefore('?')
        return (authority + prefix).trimEnd('/').lowercase()
    }
}
