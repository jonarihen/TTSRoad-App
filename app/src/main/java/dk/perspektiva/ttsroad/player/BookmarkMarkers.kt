package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.Bookmark

/**
 * A bookmark as a place on the scrub bar (#121).
 *
 * The BOOKMARK button has been on the player since 0.11.0 and in the car since 0.12.0, and the only
 * place a mark could be *seen* was a flat account-wide list in Settings. That closes the loop the
 * wrong way round: you press bookmark at the wheel precisely so you can come back to that spot, and
 * coming back meant Settings → Bookmarks → tap → the reader, rather than seeing it on the bar in
 * front of you.
 */
data class BookmarkMarker(
    val id: Int,
    val label: String,
    val positionMs: Long,
    /** Where along the bar this sits, 0..1. Precomputed so drawing does not re-divide per frame. */
    val fraction: Float,
)

/**
 * The marks that belong on this chapter's scrub bar.
 *
 * Filtered to [chapterId] rather than trusted: the account-wide list is what the endpoint returns
 * when nothing is passed, and drawing another chapter's marks on this bar would be worse than
 * drawing none. Anything past the end of the audio is dropped — a mark saved against a chapter that
 * was later re-converted shorter would otherwise pile up against the right edge, claiming a
 * position the audio does not have.
 */
fun bookmarkMarkers(
    bookmarks: List<Bookmark>,
    chapterId: Int?,
    durationMs: Long,
): List<BookmarkMarker> {
    if (chapterId == null || durationMs <= 0L) return emptyList()
    return bookmarks
        .asSequence()
        .filter { it.chapterId == chapterId }
        .mapNotNull { bookmark ->
            val positionMs = (bookmark.positionSeconds * 1000).toLong()
            if (positionMs < 0L || positionMs > durationMs) return@mapNotNull null
            BookmarkMarker(
                id = bookmark.id,
                label = bookmark.resolvedLabel,
                positionMs = positionMs,
                fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
            )
        }
        .sortedBy { it.positionMs }
        .toList()
}

/**
 * How close a tap has to land, as a fraction of the bar, before it counts as aiming at a mark.
 *
 * A marker is a two-dip line and a finger is not. Roughly a finger's width on a phone-width bar,
 * which is generous enough to hit and tight enough that a tap in open track is not silently
 * rewritten into a seek somewhere else.
 */
const val MarkerTapTolerance: Float = 0.04f

/**
 * The mark a tap at [fraction] was aiming at, or null if it was not aiming at one.
 *
 * Null matters as much as a hit: the marker lane is its own strip rather than an overlay on the
 * slider, so a tap that misses every mark must do *nothing* rather than seek to the nearest one —
 * scrubbing is the slider's job, and a lane that also scrubbed would make a mistimed tap jump the
 * playhead.
 */
fun markerAt(
    markers: List<BookmarkMarker>,
    fraction: Float,
    tolerance: Float = MarkerTapTolerance,
): BookmarkMarker? = markers
    .filter { kotlin.math.abs(it.fraction - fraction) <= tolerance }
    .minByOrNull { kotlin.math.abs(it.fraction - fraction) }
