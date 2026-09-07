package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

/**
 * Stretches of a chapter that are an advert or a disclaimer rather than the book.
 *
 * Serials carry things nobody subscribed for: a Patreon plug welded onto the end of every chapter,
 * an "I don't own Marvel, this is a fan work" paragraph welded onto the front of one. The server
 * matches a rule against the text a chapter was **already narrated from** and converts what it
 * finds into seconds of the existing MP3, using the read-along cues. So the file on this phone is
 * still the right file — nothing is re-narrated and nothing is re-downloaded. The player just
 * seeks past it.
 *
 * Server contract: TTSRoad `playback_skips`, `GET /api/mobile/chapters/{id}/skips`.
 *
 * Times arrive in seconds and are kept in milliseconds here, because every player-side comparison
 * is against `currentPosition`. They are *media* time, so a listener at 2x needs no adjustment to
 * the values — only to how long the wait until the next one is.
 */
data class ChapterSkipsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "rule_count") val ruleCount: Int = 0,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    val segments: List<ChapterSkipSegmentDto> = emptyList(),
    @param:Json(name = "total_skipped_seconds") val totalSkippedSeconds: Double = 0.0,
)

data class ChapterSkipSegmentDto(
    @param:Json(name = "start_seconds") val startSeconds: Double = 0.0,
    @param:Json(name = "end_seconds") val endSeconds: Double = 0.0,
    @param:Json(name = "duration_seconds") val durationSeconds: Double = 0.0,
    val label: String? = null,
    val preview: String? = null,
)

/** One stretch to jump over, in milliseconds of media time. */
data class ChapterSkipSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * A chapter's skip list, and every decision made from it.
 *
 * Pure, and deliberately the whole of the thinking: the service around it holds a player and a
 * coroutine, neither of which a unit test wants, while *where to seek and when to look again* is
 * arithmetic that can go wrong in ways a listener notices — a target computed early clips the last
 * line of a paragraph, and a wait computed long plays the advert anyway.
 */
data class ChapterSkips(
    val chapterId: Int,
    val segments: List<ChapterSkipSegment> = emptyList(),
    val durationMs: Long = 0,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /**
     * Where playback should jump to from [positionMs], or null when it is not inside an advert.
     *
     * The tolerance is on the near edge only. A quarter second short of an advert still means
     * hearing it, so that counts as inside; a quarter second short of the *end* of one means the
     * seek saves nothing and costs a re-buffer, so that does not.
     */
    fun targetFor(positionMs: Long): Long? {
        val segment = segments.firstOrNull {
            positionMs >= it.startMs - EdgeToleranceMs && positionMs < it.endMs - EdgeToleranceMs
        } ?: return null
        val target = if (durationMs > 0) minOf(segment.endMs, durationMs) else segment.endMs
        if (target <= positionMs + EdgeToleranceMs) return null
        return target
    }

    /**
     * Whether jumping to [targetMs] is really the end of the chapter.
     *
     * What follows a trailing advert is silence and an ID3 tag. Ending the chapter hands the
     * decision to whatever owns the end of one — auto-advance, the sleep timer, the server queue —
     * rather than leaving a sliver of plug playing to nobody.
     */
    fun endsChapter(targetMs: Long): Boolean =
        durationMs > 0 && targetMs >= durationMs - TailToleranceMs

    /**
     * How long to wait before looking at the clock again, from [positionMs] at [speed].
     *
     * The alternative is a fixed half-second poll for the length of every chapter, which is a wake
     * twice a second all night for a feature that fires twice a chapter. This sleeps until just
     * before the next advert instead and re-checks then — and the clamp at both ends means a
     * mis-estimate costs a second of advert, never a missed one.
     */
    fun waitMs(positionMs: Long, speed: Float): Long {
        // Still inside one. The ordinary path never sees this — a seek moves the clock past the
        // segment before the wait is computed — so this is the under-shoot case: a player that
        // snapped to a frame boundary, or a seek that did not take. Looking again immediately is
        // what stops that from becoming forty seconds of advert.
        if (targetFor(positionMs) != null) return MinWaitMs
        val next = segments.firstOrNull { positionMs < it.startMs - EdgeToleranceMs }
            ?: return MaxWaitMs
        val rate = if (speed > 0.1f) speed else 1f
        val untilBoundary = ((next.startMs - EdgeToleranceMs - positionMs) / rate).toLong()
        return untilBoundary.coerceIn(MinWaitMs, MaxWaitMs)
    }

    companion object {
        const val EdgeToleranceMs = 250L
        const val TailToleranceMs = 1_500L
        const val MinWaitMs = 250L

        /**
         * A ceiling rather than "sleep until the advert". Position is read from a player that can
         * be seeked, paused and re-rated from the car or the notification without this loop being
         * told, so it re-reads the clock at least this often and recomputes from what it finds.
         */
        const val MaxWaitMs = 20_000L

        /** Nothing to skip. The state for a chapter with no rules, no timings, or no answer. */
        fun none(chapterId: Int): ChapterSkips = ChapterSkips(chapterId)

        /**
         * Read a server payload, dropping anything that cannot be acted on.
         *
         * A malformed row is dropped rather than clamped: these are seconds of somebody's book, and
         * a segment whose numbers do not make sense is not a segment whose numbers can be guessed.
         */
        fun from(response: ChapterSkipsResponse, chapterId: Int): ChapterSkips {
            val segments = response.segments
                .mapNotNull { segment ->
                    val start = segment.startSeconds
                    val end = segment.endSeconds
                    if (!start.isFinite() || !end.isFinite() || end <= start || start < 0) {
                        null
                    } else {
                        ChapterSkipSegment(
                            startMs = (start * 1000).toLong(),
                            endMs = (end * 1000).toLong(),
                        )
                    }
                }
                .sortedBy { it.startMs }
            return ChapterSkips(
                chapterId = chapterId,
                segments = segments,
                durationMs = ((response.audioDuration ?: 0.0) * 1000).toLong().coerceAtLeast(0),
            )
        }
    }
}
