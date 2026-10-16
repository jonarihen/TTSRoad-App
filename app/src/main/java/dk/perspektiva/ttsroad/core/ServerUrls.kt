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
     *
     * A server behind a reverse proxy on a subpath (`https://home/ttsroad/`) keeps that prefix
     * (#365): the backend builds media URLs from its own BASE_URL, which behind a proxy is usually an
     * internal address (`http://127.0.0.1:8000/audio/x`) or unset (`/audio/x`), and neither knows
     * about `/ttsroad`. A URL whose path already starts with the prefix — a BASE_URL configured with
     * the public subpath — is not prefixed twice.
     */
    fun rewriteHost(url: String, serverUrl: String?): String {
        if (url.isBlank()) return url
        return onServer(url, serverUrl) ?: url
    }

    /** [rewriteHost] for the optional cover/artwork URLs, which are null on most API models. */
    fun rewriteHostOrNull(url: String?, serverUrl: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { rewriteHost(it, serverUrl) }

    fun rewriteAudioUrlOrNull(url: String?, serverUrl: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        return onServer(value, serverUrl)
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
        val path = withoutPrefix(value.substring(absolute.value.length), pathPrefix(serverUrl))
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

    /** [url]'s path, query and fragment on [serverUrl]'s origin and under its path prefix. */
    private fun onServer(url: String, serverUrl: String?): String? {
        val origin = origin(serverUrl) ?: return null
        val prefix = pathPrefix(serverUrl)
        val absolute = Origin.find(url)
        val rest = if (absolute != null) url.substring(absolute.value.length) else "/" + url.trimStart('/')
        val path = if (rest.isEmpty() || rest[0] != '/') "/$rest" else rest
        return if (prefix.isEmpty() || hasPrefix(path, prefix)) origin + path else origin + prefix + path
    }

    /**
     * The path [serverUrl] was signed in under, without a trailing slash: `/ttsroad` for
     * `https://home/ttsroad/`, and empty for a server at the root.
     */
    private fun pathPrefix(serverUrl: String?): String {
        if (serverUrl.isNullOrBlank()) return ""
        val trimmed = serverUrl.trim()
        val origin = Origin.find(trimmed)?.value ?: return ""
        return trimmed.substring(origin.length)
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
    }

    private fun hasPrefix(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith("$prefix/") || path.startsWith("$prefix?") || path.startsWith("$prefix#")

    private fun withoutPrefix(path: String, prefix: String): String =
        if (prefix.isNotEmpty() && hasPrefix(path, prefix)) path.substring(prefix.length) else path

    /** `scheme://authority` of [serverUrl], or null if it is blank or has no authority. */
    private fun origin(serverUrl: String?): String? {
        if (serverUrl.isNullOrBlank()) return null
        val origin = Origin.find(serverUrl.trim())?.value ?: return null
        return origin.takeIf { it.substringAfter("://").isNotEmpty() }
    }
}
