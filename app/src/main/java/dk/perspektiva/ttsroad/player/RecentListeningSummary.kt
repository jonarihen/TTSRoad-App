package dk.perspektiva.ttsroad.player

import java.time.Instant
import java.time.ZoneId

/**
 * How long this phone has been listening, worked out from the playback log it already keeps.
 *
 * The web has a Stats page — hours listened, streaks, an activity grid — and the app has none of it
 * (#117), which is backwards, because the phone and the car are where the listening happens. The
 * server half of that needs a payload this client cannot invent. This is the half that needs
 * nobody: [PlaybackHistoryStore] already writes a wall-clock → position snapshot every fifteen
 * seconds of playback, so "what did I listen to today, and for how long" is a sum over data sitting
 * in `filesDir`.
 *
 * **What this cannot be, and must not pretend to be.** The store keeps 2000 snapshots — about eight
 * and a half hours of listening — and drops the oldest as new ones arrive. There is no all-time
 * total here, no "hours this year", and no streak: the days that would prove a streak were
 * overwritten days ago. Everything below is scoped to what is still on disk, and the surface that
 * renders it says so.
 *
 * **How time is counted.** A snapshot is a point, not a span, so listening time is the gap between
 * consecutive snapshots — but only where that gap was really spent listening. Three rules do the
 * work, and each exists because the naive sum gets a real case wrong:
 *
 * - A gap longer than [ContinuousGapMs] is a break, not listening. Playback logs on a fifteen-second
 *   tick, on pause and at the end of a chapter, so nothing is recorded while paused: the gap from a
 *   pause at midnight to a resume at eight is nine hours of sleep, and summing it would report a
 *   night in bed as a night of listening.
 * - Within a chapter, the counted time is capped by how far the audio actually moved. Pausing
 *   twenty seconds past the tick and resuming a minute later leaves one gap under the break
 *   threshold that holds twenty seconds of listening and forty of silence; the position only
 *   advanced by the twenty. This also makes the count right at speed — someone listening at 1.75x
 *   covers twenty-six seconds of audio in a fifteen-second tick, and the answer to "how long did
 *   you listen" is fifteen.
 * - A gap where the position did not move at all counts as nothing. A stalled stream and a chapter
 *   that ran out on the nightstand both keep logging the same position, and neither is listening.
 *
 * Every rule errs downwards. A summary that quietly rounds a night's sleep up into listening time
 * is worse than one that loses the fifteen seconds between a resume and the next tick.
 */
data class RecentListeningSummary(
    /** Listening attributed to the local day containing `now`. */
    val todayMs: Long = 0L,
    /** Listening across every snapshot still on disk, whatever day it fell on. */
    val retainedMs: Long = 0L,
    /** Distinct chapters with listening time today. */
    val todayChapters: Int = 0,
    /** Separate sittings today — runs of listening with a break between them. */
    val todaySittings: Int = 0,
    /** Today's time split by book, longest first. */
    val todayFictions: List<FictionListeningTime> = emptyList(),
    /** Timestamp of the oldest snapshot still kept, or null when nothing has been recorded. */
    val oldestAt: Long? = null,
    /** Timestamp of the newest snapshot still kept. */
    val newestAt: Long? = null,
    /** Snapshots the store is holding. */
    val snapshots: Int = 0,
    /**
     * Whether the store is full, i.e. listening older than [oldestAt] has already been dropped.
     *
     * The difference matters to the copy: a half-full log genuinely covers everything since
     * [oldestAt], while a full one is a window that has been sliding for a while.
     */
    val atCapacity: Boolean = false,
) {
    /** Whether anything has been recorded at all — the empty state is a different sentence. */
    val hasHistory: Boolean get() = snapshots > 0
}

/** One book's share of a window's listening time. */
data class FictionListeningTime(
    /** Zero when the played item carried no `fiction_id` extra, which is why it is not a title. */
    val fictionId: Int,
    /** As the player knew it. Null when the media item had no album title. */
    val title: String?,
    val listenedMs: Long,
    val chapters: Int,
)

/**
 * The longest gap between two snapshots that still counts as one unbroken stretch of listening.
 *
 * Six ticks. The tick itself is fifteen seconds, so this tolerates a chapter change, a buffering
 * stall and a scheduler that ran late without treating any of them as a break — and stops well
 * short of the shortest pause anyone takes deliberately.
 */
const val ContinuousGapMs: Long = 90_000L

/**
 * What [PlaybackHistoryStore] keeps. Duplicated as a parameter default rather than read from the
 * store because this file is arithmetic over a list and has no business holding a `Context`.
 */
const val HistorySnapshotCapacity: Int = 2000

/**
 * Total up [history] into what can honestly be said about it.
 *
 * [history] is expected in recording order, which is how the store writes it. It is deliberately
 * not sorted: if the device clock moved backwards mid-session, the recorded order is the truth
 * about what happened and a sort would invent a gap or reorder chapters. A non-positive gap is
 * treated as a break, so a clock change costs one interval rather than corrupting the total.
 */
fun recentListeningSummary(
    history: List<HistorySnapshot>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    maxGapMs: Long = ContinuousGapMs,
    capacity: Int = HistorySnapshotCapacity,
): RecentListeningSummary {
    if (history.isEmpty()) return RecentListeningSummary()

    val dayStart = startOfDayMs(now, zone)
    val dayEnd = startOfNextDayMs(now, zone)

    var retainedMs = 0L
    var todayMs = 0L
    var sittings = 0
    // Whether the previous interval put time into today. A run that began yesterday and crossed
    // midnight is one sitting today, not two, and this is what makes it so.
    var previousCountedToday = false

    val chaptersToday = mutableSetOf<String>()
    val fictions = LinkedHashMap<Int, FictionAccumulator>()

    for (index in 1 until history.size) {
        val from = history[index - 1]
        val to = history[index]
        val gap = to.timestamp - from.timestamp
        if (gap <= 0L || gap > maxGapMs) {
            previousCountedToday = false
            continue
        }
        val listened = listenedMs(from, to, gap)
        if (listened <= 0L) {
            previousCountedToday = false
            continue
        }
        retainedMs += listened

        // Split at midnight rather than filing the whole interval under one day. The share is
        // proportional, which is exact whenever the interval counted in full — the ordinary case.
        val overlap = overlapMs(from.timestamp, to.timestamp, dayStart, dayEnd)
        val today = if (overlap <= 0L) 0L else overlap * listened / gap
        if (today <= 0L) {
            previousCountedToday = false
            continue
        }

        todayMs += today
        if (!previousCountedToday) sittings++
        previousCountedToday = true

        // Attributed to the chapter that was playing when the interval *began*. Auto-advance puts
        // the boundary somewhere inside the interval and nothing records where; the opening
        // chapter is the one that held most of it.
        chaptersToday += from.mediaId
        val accumulator = fictions.getOrPut(from.fictionId) { FictionAccumulator() }
        accumulator.listenedMs += today
        accumulator.chapters += from.mediaId
        if (accumulator.title == null) {
            accumulator.title = from.fictionTitle?.takeIf { it.isNotBlank() }
        }
    }

    return RecentListeningSummary(
        todayMs = todayMs,
        retainedMs = retainedMs,
        todayChapters = chaptersToday.size,
        todaySittings = sittings,
        todayFictions = fictions.entries
            .map { (fictionId, accumulator) ->
                FictionListeningTime(
                    fictionId = fictionId,
                    title = accumulator.title,
                    listenedMs = accumulator.listenedMs,
                    chapters = accumulator.chapters.size,
                )
            }
            // Longest first, ties broken by id so rows do not reshuffle between recompositions.
            .sortedWith(
                compareByDescending<FictionListeningTime> { it.listenedMs }.thenBy { it.fictionId },
            ),
        oldestAt = history.first().timestamp,
        newestAt = history.last().timestamp,
        snapshots = history.size,
        atCapacity = history.size >= capacity,
    )
}

/**
 * How much of [gap] between two snapshots was spent listening.
 *
 * Across a chapter boundary the positions are not comparable — one is near the end of a chapter and
 * the next near the start of another — so the gap stands on its own, bounded as it already is by
 * the break threshold.
 */
private fun listenedMs(from: HistorySnapshot, to: HistorySnapshot, gap: Long): Long = when {
    from.mediaId != to.mediaId -> gap
    // Capped by the audio actually covered: a pause inside the interval, or playback above 1x.
    to.positionMs > from.positionMs -> minOf(gap, to.positionMs - from.positionMs)
    // A seek backwards. The wall-clock time was still spent listening, before the rewind.
    to.positionMs < from.positionMs -> gap
    // The audio did not move: a stall, or a finished chapter sitting on the nightstand.
    else -> 0L
}

/** Milliseconds of `[from, to)` falling inside `[windowStart, windowEnd)`. */
private fun overlapMs(from: Long, to: Long, windowStart: Long, windowEnd: Long): Long =
    (minOf(to, windowEnd) - maxOf(from, windowStart)).coerceAtLeast(0L)

private fun startOfDayMs(epochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

private fun startOfNextDayMs(epochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()

private class FictionAccumulator {
    var title: String? = null
    var listenedMs: Long = 0L
    val chapters: MutableSet<String> = mutableSetOf()
}
