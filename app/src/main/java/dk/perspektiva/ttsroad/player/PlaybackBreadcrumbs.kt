package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.Bookmark
import dk.perspektiva.ttsroad.data.BookmarkKindAuto
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Jump-back breadcrumbs kept on the server, so "where was I at 23:49" can be answered on a device
 * that was not the one playing.
 *
 * A breadcrumb is a `kind: "auto"` bookmark. The rows are shared with the web client, which is the
 * entire point: the same table, so a moment recorded on the laptop is findable from the phone.
 *
 * This is an *overlay* on [PlaybackHistoryStore], not a replacement for it. The local store records
 * every ~15s and holds 2000 entries — roughly eight hours at a resolution the server store cannot
 * match, and the deepest jump-back reach of the three clients. That stays exactly as it is; what
 * goes to the server is a much coarser trail, for the cross-device case only.
 */

/**
 * How often a breadcrumb is written to the server while playing.
 *
 * Deliberately far coarser than the local store's ~15s tick, and the number is forced by the
 * server's window rather than chosen for comfort: the server keeps
 * `MAX_AUTO_BREADCRUMBS_PER_USER = 240` breadcrumbs. Writing every local snapshot through would buy
 * about **one hour** of cross-device reach while spending 240 writes an hour, for ever. At five
 * minutes the same 240 rows span roughly twenty hours, which is what the overnight case actually
 * needs, at twelve writes an hour.
 */
const val ServerBreadcrumbIntervalMs: Long = 5 * 60_000L

/**
 * How many of our own breadcrumbs to leave on the server.
 *
 * Matches the server's `MAX_AUTO_BREADCRUMBS_PER_USER`, and the client prunes to it **itself**
 * rather than trusting the server to. That is not belt-and-braces, it is the difference between
 * working and doing harm: the rolling window ships in `jonarihen/TTSRoad#90`, which is still an open
 * PR, and against a server without it there is only `MAX_BOOKMARKS_PER_USER = 5000` counting *both*
 * kinds and raising `409` at the cap. An unbounded breadcrumb writer would walk that shared budget
 * up and then lock the account out of making **manual** bookmarks — the automatic feature quietly
 * breaking the deliberate one.
 *
 * Pruning is self-balancing across both server versions. Where the window exists the server has
 * already tombstoned everything past 240, so a listing never returns more than that and this prunes
 * nothing. Where it does not, this keeps the trail bounded on its own.
 */
const val MaxServerBreadcrumbs: Int = 240

/**
 * How often the trail is pruned back to [MaxServerBreadcrumbs].
 *
 * Pruning costs a listing, so it is not worth doing on every write. At twelve writes an hour it
 * takes twenty hours of playback to reach the cap from empty, so an hourly check cannot be
 * outrun — and against a server that already windows, the listing comes back at or under the cap
 * and nothing is deleted.
 */
const val BreadcrumbPruneIntervalMs: Long = 60 * 60_000L

/**
 * Whether enough time has passed to write another breadcrumb.
 *
 * [lastWrittenAt] is null before the first write of a session, which always qualifies — the moment
 * playback starts is worth a mark, and waiting five minutes for the first one would lose the case
 * where someone listens briefly and stops.
 */
fun shouldWriteBreadcrumb(
    lastWrittenAt: Long?,
    now: Long,
    intervalMs: Long = ServerBreadcrumbIntervalMs,
): Boolean {
    if (lastWrittenAt == null) return true
    // A clock that jumped backwards (a timezone fix, an NTP correction) would otherwise wedge this
    // until real time caught up, which for a manual clock change could be days.
    if (now < lastWrittenAt) return true
    return now - lastWrittenAt >= intervalMs
}

/**
 * Ids of the breadcrumbs to delete so that at most [keep] remain, oldest first out.
 *
 * Rows without a parseable timestamp sort as oldest. They cannot be placed on the trail, so they are
 * the least useful thing to keep and the first thing worth spending the budget on.
 */
fun breadcrumbsToPrune(existing: List<Bookmark>, keep: Int = MaxServerBreadcrumbs): List<Int> {
    val breadcrumbs = existing.filter { it.kind == BookmarkKindAuto }
    if (breadcrumbs.size <= keep) return emptyList()
    return breadcrumbs
        .sortedBy { parseServerInstant(it.createdAt) ?: Long.MIN_VALUE }
        .dropLast(keep)
        .map { it.id }
        .filter { it > 0 }
}

/**
 * A server breadcrumb as a point on the local timeline, or null when it cannot be placed on one.
 *
 * A row with no parseable `created_at`, or no chapter, is dropped rather than shown at the epoch:
 * the sheet is a list of clock times, and an entry claiming 1970 is worse than a missing entry.
 */
fun breadcrumbSnapshot(bookmark: Bookmark): HistorySnapshot? {
    val timestamp = parseServerInstant(bookmark.createdAt) ?: return null
    val chapterId = bookmark.chapterId.takeIf { it > 0 } ?: return null
    return HistorySnapshot(
        timestamp = timestamp,
        mediaId = "chapter:$chapterId",
        fictionId = bookmark.fictionId ?: 0,
        chapterId = chapterId,
        title = bookmark.chapterTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter $chapterId",
        fictionTitle = bookmark.fictionTitle,
        positionMs = (bookmark.positionSeconds * 1000.0).toLong().coerceAtLeast(0L),
    )
}

/**
 * Local and server breadcrumbs as one timeline, oldest first — the order [PlaybackHistoryStore]
 * publishes and the jump-back sheet expects.
 *
 * The local copy of a moment wins over the server's. Both clients record the same playback, so most
 * server rows written by *this* phone are duplicates of something already on disk, and the local one
 * is the better record: it is finer-grained and its position was not rounded through
 * `position_seconds`. [nearMs] is the window in which two entries on the same chapter are treated as
 * the same moment rather than two.
 */
fun mergeBreadcrumbs(
    local: List<HistorySnapshot>,
    remote: List<HistorySnapshot>,
    nearMs: Long = ServerBreadcrumbIntervalMs / 2,
): List<HistorySnapshot> {
    if (remote.isEmpty()) return local
    val localByChapter = local.groupBy { it.mediaId }
    val extra = remote.filter { candidate ->
        localByChapter[candidate.mediaId].orEmpty().none { existing ->
            kotlin.math.abs(existing.timestamp - candidate.timestamp) <= nearMs
        }
    }
    if (extra.isEmpty()) return local
    return (local + extra).sortedBy { it.timestamp }
}

/**
 * Epoch millis from the server's ISO-8601 UTC stamp, or null if it cannot be read.
 *
 * The backend renders these with `datetime.isoformat()` and swaps `+00:00` for `Z`, giving either no
 * fractional digits or six; [Instant.parse] takes both. Null rather than a throw because this runs
 * over a whole list, and one unreadable row should cost that row, not the trail.
 */
internal fun parseServerInstant(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // A server that sent a naive stamp with no zone at all. Every column in that database is
        // naive UTC, so reading it as UTC is right rather than a guess.
        try {
            Instant.parse("${text}Z").toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
