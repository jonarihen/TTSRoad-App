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
 * Returns [text] unchanged when nothing is signed in, which is also the case where there is no
 * address to leak.
 */
internal fun redactServerUrl(text: String?, serverUrl: String?): String? {
    if (text.isNullOrEmpty()) return text
    val origin = originOf(serverUrl) ?: return text
    return text.replace(origin, RedactedServer, ignoreCase = true)
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
