package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

/**
 * The seconds of a chapter's MP3 that are advert, disclaimer or Patreon plug rather than story.
 *
 * The server finds them by matching an admin's skip rules against the text that was *already*
 * narrated, and converts the character range into a time range through the read-along cues. The
 * audio is untouched — nothing is re-synthesised and nothing is re-downloaded — so the whole feature
 * lives in the player: it seeks past what the server describes.
 *
 * Two consequences the client has to respect, both from `app/services/playback_skips.py`:
 *
 * - **A chapter with no read-along document cannot be skipped**, because the cues are the only thing
 *   that knows where in the file a word is. That is the [hasTimings] `false` with a non-zero
 *   [ruleCount] case, and it is the one worth explaining to a user rather than silently doing
 *   nothing.
 * - **The boundaries err towards skipping less.** A syllable of "join here" may survive; a clipped
 *   last line of a chapter would not be recoverable, so the server never risks it. Do not "tidy" a
 *   boundary outwards here to compensate.
 */
data class ChapterSkipsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "rule_count") val ruleCount: Int = 0,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    val segments: List<SkipSegmentWire> = emptyList(),
    @param:Json(name = "total_skipped_seconds") val totalSkippedSeconds: Double = 0.0,
)

/**
 * One region, as sent.
 *
 * `label` and `preview` are diagnostics — which rule claimed the stretch, and the start of the text
 * it claimed. They are deliberately not UI strings: `label` is of the form `global:skip_between:4`.
 * Kept because they are what makes a wrong skip reportable at all.
 */
data class SkipSegmentWire(
    @param:Json(name = "start_seconds") val startSeconds: Double = 0.0,
    @param:Json(name = "end_seconds") val endSeconds: Double = 0.0,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    val label: String = "",
    val preview: String = "",
)

/** One region of a chapter to jump over, in milliseconds of media time. */
data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val label: String,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * A chapter's skip regions, validated into something the per-tick lookup can trust.
 *
 * Built by [from], which sorts and drops unsalvageable rows in the same spirit as
 * [ReadAlongDocument.from]: the contract says segments arrive sorted, non-overlapping and merged,
 * but the lookup is only correct if that actually holds, and one bad row would make the player seek
 * at random for the rest of a chapter. Validating once on load is cheap insurance.
 */
data class ChapterSkips(
    val chapterId: Int,
    val segments: List<SkipSegment> = emptyList(),
    val hasTimings: Boolean = false,
    val ruleCount: Int = 0,
    val audioDurationMs: Long? = null,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /**
     * True when this chapter's text matches a rule but was narrated before read-along existed.
     *
     * The one state worth a word to the user: there is something to skip and no way to time it.
     * `POST /api/chapters/{id}/timings` on any signed-in account rebuilds the document from the
     * stored text and the existing MP3, which is the supported repair.
     */
    val needsTimings: Boolean get() = !hasTimings && ruleCount > 0

    companion object {
        /** Nothing to skip. Used for a chapter with no answer yet, and for a server without the route. */
        fun empty(chapterId: Int): ChapterSkips = ChapterSkips(chapterId = chapterId)

        fun from(response: ChapterSkipsResponse, chapterId: Int): ChapterSkips {
            val durationMs = response.audioDuration
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { (it * 1000).toLong() }
            val segments = response.segments
                .mapNotNull { wire ->
                    val start = wire.startSeconds
                    val end = wire.endSeconds
                    if (!start.isFinite() || !end.isFinite()) return@mapNotNull null
                    if (end <= start || end <= 0) return@mapNotNull null
                    SkipSegment(
                        startMs = (start.coerceAtLeast(0.0) * 1000).toLong(),
                        endMs = (end * 1000).toLong(),
                        label = wire.label,
                    )
                }
                .sortedBy { it.startMs }
            return ChapterSkips(
                chapterId = chapterId,
                segments = segments,
                hasTimings = response.hasTimings,
                ruleCount = response.ruleCount,
                audioDurationMs = durationMs,
            )
        }
    }
}
