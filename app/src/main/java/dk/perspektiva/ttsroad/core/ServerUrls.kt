package dk.perspektiva.ttsroad.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val ServerCoverPath = Regex("^/cover(?:/|[?#]|$)")

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

    fun rewriteAudioUrlOrNull(url: String?, serverUrl: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val origin = origin(serverUrl) ?: return null
        val absolute = Origin.find(value)
        return if (absolute != null) {
            origin + value.substring(absolute.value.length)
        } else {
            origin + "/" + value.trimStart('/')
        }
    }

    /**
     * Resolve a cover URL without rewriting third-party artwork onto the TTSRoad server.
     *
     * Royal Road covers are absolute CDN URLs and must remain on that host. TTSRoad-owned EPUB
     * covers use `/cover/...`; those still need the connected-host rewrite when the backend's
     * configured BASE_URL differs from the address the phone used.
     */
    fun resolveCoverOrNull(url: String?, serverUrl: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val absolute = Origin.find(value) ?: return rewriteHost(value, serverUrl)
        val path = value.substring(absolute.value.length)
        return if (ServerCoverPath.containsMatchIn(path)) rewriteHost(value, serverUrl) else value
    }

    /** True only when both URLs have the same scheme, host and effective port. */
    fun isSameOrigin(requestUrl: String?, serverUrl: String?): Boolean {
        if (requestUrl.isNullOrBlank() || serverUrl.isNullOrBlank()) return false
        val request = requestUrl.toHttpUrlOrNull() ?: return false
        val server = serverUrl.toHttpUrlOrNull() ?: return false
        return request.scheme.equals(server.scheme, ignoreCase = true) &&
            request.host.equals(server.host, ignoreCase = true) &&
            request.port == server.port
    }

    /** `scheme://authority` of [serverUrl], or null if it is blank or has no authority. */
    private fun origin(serverUrl: String?): String? {
        if (serverUrl.isNullOrBlank()) return null
        val origin = Origin.find(serverUrl.trim())?.value ?: return null
        return origin.takeIf { it.substringAfter("://").isNotEmpty() }
    }
}
