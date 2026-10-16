package dk.perspektiva.ttsroad.core

/**
 * The decisions behind crash reporting, kept away from the SDK so they can be tested.
 *
 * This app talks to a server the user runs themselves. Two things follow, and both are the reason
 * this file exists rather than a one-line `SentryAndroid.init`:
 *
 * - **Reporting is off unless a DSN was deliberately configured.** No DSN is the default and a
 *   perfectly good configuration — the SDK is never initialised and nothing leaves the phone.
 * - **A private server's address is not diagnostic data.** The host someone self-hosts on is
 *   routinely their home, and it turns up in stack traces, breadcrumbs and HTTP spans. It is
 *   redacted before anything is sent, even to their own instance.
 */

/** Placeholder substituted for the signed-in server's address. */
internal const val RedactedServer = "<server>"

/**
 * The DSN to report to, or null for "report nothing".
 *
 * Blank is treated as absent rather than as a malformed DSN: an unset `BuildConfig` field, an empty
 * `local.properties` entry and a deliberately cleared value all mean the same thing, and none of
 * them should reach the SDK.
 */
internal fun crashReportingDsn(configured: String?): String? =
    configured?.trim()?.takeIf { it.isNotEmpty() }

/** Whether crash reporting should be started at all. */
internal fun crashReportingEnabled(configured: String?): Boolean =
    crashReportingDsn(configured) != null

/**
 * Remove the signed-in server's address from [text].
 *
 * Matches on scheme-and-authority rather than the whole configured URL, because the same server
 * shows up as `https://ttsroad.example.com`, `http://192.168.1.20:8000` and with assorted paths
 * appended — redacting only the exact configured string would miss most occurrences.
 *
 * The bare host is redacted too, with or without its port. OkHttp's most common network errors
 * name the host with no scheme at all — `Unable to resolve host "home.example.com"`, `Hostname
 * home.example.com not verified`, `Failed to connect to home.example.com/203.0.113.4:8000` — and the
 * address it resolved to, printed straight after the host, is redacted along with it.
 *
 * Returns [text] unchanged when nothing is signed in, which is also the case where there is no
 * address to leak.
 */
internal fun redactServerUrl(text: String?, serverUrl: String?): String? {
    if (text.isNullOrEmpty()) return text
    val origin = originOf(serverUrl) ?: return text
    var redacted = text.replace(origin, RedactedServer, ignoreCase = true)
    val host = hostOf(origin) ?: return redacted
    for (form in setOf(host, host.removeSurrounding("[", "]"))) {
        redacted = bareHostPattern(form).replace(redacted, RedactedServer)
    }
    return ResolvedAddressAfterRedaction.replace(redacted, RedactedServer)
}

/**
 * [host] standing on its own, optionally with a `:port`.
 *
 * Bounded so a longer name that merely contains it — `tts.example.com` inside
 * `mytts.example.com.cdn.net` — is left alone, while a host ending a sentence (`...example.com.`)
 * or quoted (`"example.com"`) is still caught.
 */
private fun bareHostPattern(host: String): Regex = Regex(
    "(?<![A-Za-z0-9.-])" + Regex.escape(host) + "(?::\\d+)?(?![A-Za-z0-9-]|\\.[A-Za-z0-9])",
    RegexOption.IGNORE_CASE,
)

/**
 * The resolved address OkHttp prints after the host — `<server>/203.0.113.4:8000`, or an IPv6
 * literal — once the host itself has been replaced. A path such as `<server>/api/...` never
 * matches: it neither starts with a dotted quad nor carries a colon.
 */
private val ResolvedAddressAfterRedaction = Regex(
    Regex.escape(RedactedServer) +
        "/(?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[?[0-9A-Fa-f]*:[0-9A-Fa-f:.%]*]?)(?::\\d+)?",
)

/** Host of an origin from [originOf], without user info or port; IPv6 literals keep brackets. */
private fun hostOf(origin: String): String? {
    val authority = origin.substringAfter("://").substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return host.takeIf { it.isNotEmpty() && it != "[]" }
}

/**
 * Scheme and authority of [url] — `https://host:port` — or null if there is not one.
 *
 * Deliberately string-based, matching [ServerUrls]: this runs inside crash handling, where a throw
 * from a URL parser would lose the very report being assembled.
 */
private fun originOf(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfFirst(authorityStart) { it == '/' || it == '?' || it == '#' }
    val authority = value.substring(authorityStart, authorityEnd)
    if (authority.isEmpty()) return null
    return value.substring(0, authorityStart) + authority
}

/** Index of the first character from [start] matching [predicate], or the end of the string. */
private inline fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
    for (i in start until length) if (predicate(this[i])) return i
    return length
}

/** How many signed-in servers redaction keeps remembering after the user has moved on from them. */
internal const val RememberedServerLimit = 8

/**
 * [text] with every server in [serverUrls] redacted — the current one and those signed in to
 * before it, whose addresses still turn up in stale breadcrumbs, cached URLs and old queue items.
 */
internal fun redactServerUrls(text: String?, serverUrls: Collection<String>): String? =
    serverUrls.fold(text) { redacted, server -> redactServerUrl(redacted, server) }

/**
 * [known] with [serverUrl] moved to the front, capped at [RememberedServerLimit]. Origins only, so
 * one server reached with assorted paths is remembered once; blank or scheme-less input is ignored.
 */
internal fun rememberServer(known: List<String>, serverUrl: String?): List<String> {
    val origin = originOf(serverUrl) ?: return known
    return (listOf(origin) + known.filterNot { it.equals(origin, ignoreCase = true) })
        .take(RememberedServerLimit)
}

/**
 * The host a configured DSN reports to — `sentry.example.com` from
 * `https://<public-key>@sentry.example.com/<project>`, with its port if it has one — or null when there is no DSN. The public key
 * is left out: it is what lets anyone submit events, and the point is only to say *where* reports go.
 */
internal fun crashReportingHost(configured: String?): String? {
    val origin = originOf(crashReportingDsn(configured)) ?: return null
    return origin.substringAfter("://").substringAfterLast('@').takeIf { it.isNotEmpty() }
}

/**
 * The line Settings shows, so it is visible whether *this* APK — perhaps one the updater
 * installed — reports crashes anywhere, and where.
 */
internal fun crashReportingStatus(configured: String?, enabledOnDevice: Boolean): String {
    val host = crashReportingHost(configured) ?: return "Off (not configured in this build)"
    return if (enabledOnDevice) "On → $host" else "Off on this phone (build reports to $host)"
}

/**
 * [data] with every string value passed through [redact]. Breadcrumb data carries the HTTP
 * integration's `url`, OkHttp's `host`, navigation routes and the like — the same address the
 * message redaction removes, in a field it never looked at.
 */
internal fun redactStringValues(data: Map<String, Any?>, redact: (String) -> String?): Map<String, Any?> =
    data.mapValues { (_, value) -> if (value is String) redact(value) ?: value else value }
