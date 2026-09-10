package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.ChapterSkips
import dk.perspektiva.ttsroad.data.SkipSegment

/**
 * Where playback should jump to, given where it is.
 *
 * A pure function of a position and a segment list, deliberately separate from the seeking, so the
 * boundary arithmetic can be tested without a player, a service or a device — the same split the web
 * player makes with `ttsroadAdSkipTarget` in `app/static/app.js`, and for the same reason: whether a
 * quarter-second tolerance is applied to the near edge or both edges is the entire question, and it
 * is invisible in a screenshot.
 */

/**
 * How far before a segment counts as being inside it.
 *
 * The tolerance is on the **near edge only**. A quarter second short of an advert still means
 * hearing the advert; a quarter second short of the *end* of one means the seek saves nothing and
 * costs a re-buffer, which is worse than the sliver it removes.
 */
const val AdSkipEdgeMs: Long = 250L

/**
 * A skip landing within this of the end of a chapter goes to the end instead.
 *
 * What follows a trailing advert is silence and an ID3 tag. Letting the chapter end hands the
 * decision to whatever owns the end of a chapter — auto-advance, the server queue, an armed sleep
 * timer — rather than making it here, which is the only place that decision is correctly made.
 */
const val AdSkipTailMs: Long = 1_500L

/** A seek to perform, and the segment that asked for it. */
data class AdSkipTarget(
    val segment: SkipSegment,
    val targetMs: Long,
)

/**
 * The seek to perform at [positionMs], or null when playback is not inside an advert.
 *
 * [durationMs] may be zero or negative when the player has not read the stream yet; the segment's
 * own end is then used unclamped, which is safe because the server never emits an end beyond the
 * audio it measured.
 */
fun adSkipTarget(
    skips: ChapterSkips,
    positionMs: Long,
    durationMs: Long,
): AdSkipTarget? {
    val total = durationMs.takeIf { it > 0L } ?: 0L
    for (segment in skips.segments) {
        if (positionMs < segment.startMs - AdSkipEdgeMs) {
            // Segments are sorted, so the first one still ahead of us ends the search: nothing
            // after it can contain this position either.
            return null
        }
        if (positionMs >= segment.endMs - AdSkipEdgeMs) continue
        var target = if (total > 0) minOf(segment.endMs, total) else segment.endMs
        if (total > 0 && target >= total - AdSkipTailMs) target = total
        // A target at or behind where we already are would be a seek that achieves nothing, and
        // repeating it every tick would be an audible stutter rather than a skip.
        if (target <= positionMs + AdSkipEdgeMs) continue
        return AdSkipTarget(segment, target)
    }
    return null
}

/** "31s", or "1m 05s" — for the one-line notice shown when a skip happens. */
fun formatAdSkipLength(millis: Long): String {
    val total = (millis / 1000.0).let { if (it < 1) 1L else Math.round(it) }
    if (total < 60) return "${total}s"
    return "${total / 60}m ${(total % 60).toString().padStart(2, '0')}s"
}
