package dk.perspektiva.ttsroad.data

import java.time.ZoneId
import java.util.Locale

/**
 * Turning `GET /api/mobile/logs` into the lines the Logs screen shows (#124).
 *
 * The surface is read-only and there is nothing to write back, so everything here is presentation:
 * a level, a message, when it happened in the phone's clock, and what it was about.
 *
 * The one judgement call in this file is what "no rows" means, and it is not a small one. On every
 * other list in the app an empty result is a mild disappointment; here it usually means *nothing has
 * gone wrong*, which is the answer someone opened the screen hoping for. So the emptiness is
 * described in terms of the filters that produced it rather than left as a blank pane —
 * see [serverLogsEmptyNote].
 */

/** One log line, ready to draw. */
data class ServerLogRow(
    val id: Int,
    /** `INFO`, `WARNING` or `ERROR`, upper-cased so the screen can switch on it. */
    val level: String,
    val message: String,
    /** When it happened, in the phone's zone, or null when the server sent nothing readable. */
    val time: String?,
    val fictionId: Int?,
    val chapterId: Int?,
    /**
     * What the line was about — a fiction, and a chapter within it — or null on a line about the
     * install itself. Most of a quiet log is the latter: the scheduler waking, voices refreshing.
     */
    val source: String?,
)

/**
 * The page's entries as rows, in the order the server sent them (newest first).
 *
 * [titles] names fictions the client already knows about, so an error reads "MOTHER OF LEARNING ·
 * CHAPTER 88" rather than "FICTION 7 · CHAPTER 88". Passed in rather than fetched: the log payload
 * carries ids only, the library the app has already loaded is the cheapest place to resolve them,
 * and a fiction that is not in it still gets its id shown rather than being dropped.
 */
fun serverLogRows(
    response: ServerLogsResponse?,
    titles: Map<Int, String> = emptyMap(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ServerLogRow> =
    response?.logs.orEmpty().map { entry ->
        ServerLogRow(
            id = entry.id,
            level = entry.level.trim().uppercase(),
            message = entry.message.trim(),
            time = formatServerTimestamp(entry.createdAt, zone, locale),
            fictionId = entry.fictionId,
            chapterId = entry.chapterId,
            source = logSource(entry, titles),
        )
    }

/**
 * Append a page to what is already on screen, dropping any id that is already there.
 *
 * The cursor makes a repeat impossible in theory — `before_id` is a strict `<` on a monotonic
 * primary key — and the de-duplication is here anyway because the cost of being wrong is a list
 * that shows the same failure twice and invites someone to go looking for the second one.
 */
fun mergeServerLogPages(existing: List<ServerLogRow>, next: List<ServerLogRow>): List<ServerLogRow> {
    if (existing.isEmpty()) return next
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + next.filterNot { it.id in seen }
}

/**
 * Whether this account, on this server, may look at the log at all.
 *
 * Two halves that mean different things and are both required: the capability says the server has
 * the route, `is_admin` says this account may reach it. Asking without the second is a 403 dressed
 * up as a fault, so the client does not ask — the server enforces it regardless, and hiding a
 * control the caller cannot use is presentation.
 */
fun canReadServerLogs(capabilities: ServerCapabilities, isAdmin: Boolean): Boolean =
    capabilities.logs && isAdmin

/**
 * Why the log cannot be shown, or null when it can.
 *
 * Two silences that are not interchangeable, which is the whole reason this is a function and not a
 * blank pane. "This server is too old to publish its log" is a permanent fact about the server and
 * nothing the user can act on from the phone; "you are not an admin" is a fact about this account on
 * a server that has the route. Collapsing them would leave someone updating a backend that is
 * already new enough.
 */
fun serverLogsUnavailableNote(capabilities: ServerCapabilities, isAdmin: Boolean): String? = when {
    canReadServerLogs(capabilities, isAdmin) -> null
    !capabilities.logs ->
        "This server does not publish its log to the app. It is on the web console, and a newer " +
            "backend would put it here too."

    else ->
        "The server's log is admin-only, exactly as it is on the web. This account is signed in " +
            "as a regular user."
}

/**
 * What an empty page means, said in terms of the filters that produced it.
 *
 * An empty log is not an empty list of results — it is a statement about the server, and which
 * statement depends entirely on what was asked. "No errors" is good news; "no lines at all" on a
 * running install is suspicious; "nothing for this book" is neither.
 */
fun serverLogsEmptyNote(level: String?, fictionId: Int?): String {
    val wanted = level?.trim()?.uppercase()?.takeIf { it in ServerLogLevels }
    return when {
        wanted != null && fictionId != null ->
            "Nothing at ${wanted.lowercase()} level for this fiction."

        wanted == "ERROR" -> "No errors. Nothing on this server has failed."
        wanted != null -> "Nothing at ${wanted.lowercase()} level."
        fictionId != null -> "Nothing has been logged about this fiction."
        else -> "This server's log is empty. Nothing has run yet, or it has been cleared."
    }
}

/** "MOTHER OF LEARNING · CHAPTER 88", or null when the line is about the install itself. */
private fun logSource(entry: ServerLogEntry, titles: Map<Int, String>): String? {
    val fiction = entry.fictionId?.let { id ->
        titles[id]?.takeIf { it.isNotBlank() } ?: "Fiction $id"
    }
    val chapter = entry.chapterId?.let { "Chapter $it" }
    return listOfNotNull(fiction, chapter).joinToString(" · ").takeIf { it.isNotEmpty() }
}
