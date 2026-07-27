package dk.perspektiva.ttsroad.core

/**
 * The server builds absolute media URLs from its configured BASE_URL, which may not be the host
 * the device actually used to log in (and may even be relative if BASE_URL is unset). Rewriting
 * the scheme/host/port to the server the user is connected to keeps audio *and* artwork loading
 * regardless of how BASE_URL is configured.
 *
 * Deliberately string-based: `android.net.Uri` is stubbed out in JVM unit tests, and `java.net.URI`
 * is stricter than the URLs a media server actually emits (unencoded spaces in cover filenames).
 */
object ServerUrls {
    /** Leading `scheme://authority` of an absolute URL. */
    private val Origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*")

    /**
     * Return [url] pointed at [serverUrl]'s host, preserving path, query and fragment. Relative
     * URLs are resolved against [serverUrl]. Returns [url] unchanged when [serverUrl] is blank or
     * has no usable origin.
     */
    fun rewriteHost(url: String, serverUrl: String?): String {
        if (url.isBlank()) return url
        val origin = origin(serverUrl) ?: return url
        val absolute = Origin.find(url)
        return if (absolute != null) {
            origin + url.substring(absolute.value.length)
        } else {
            origin + "/" + url.trimStart('/')
        }
    }

    /** [rewriteHost] for the optional cover/artwork URLs, which are null on most API models. */
    fun rewriteHostOrNull(url: String?, serverUrl: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { rewriteHost(it, serverUrl) }

    /** `scheme://authority` of [serverUrl], or null if it is blank or has no authority. */
    private fun origin(serverUrl: String?): String? {
        if (serverUrl.isNullOrBlank()) return null
        val origin = Origin.find(serverUrl.trim())?.value ?: return null
        return origin.takeIf { it.substringAfter("://").isNotEmpty() }
    }
}
