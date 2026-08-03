package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

/**
 * Raw `GET /api/mobile/chapters/{chapter_id}/readalong` body.
 *
 * `cues` are `[char_start, char_end, start_seconds]` and `paragraphs` are `[char_start, char_end]`,
 * both indexing into [text]. They arrive as bare arrays rather than objects because a chapter is
 * tens of thousands of cues long and object keys would roughly triple the payload.
 *
 * Both are typed `List<List<Double>>` even though the character offsets are integers: JSON has one
 * number type, and Moshi's Int adapter throws on a value written as `0.0`. One malformed row must
 * not cost the reader the whole chapter, so parsing stays permissive and
 * [ReadAlongDocument.from] does the validating.
 */
data class ReadAlongResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val chapter: ReadAlongChapter = ReadAlongChapter(),
    val text: String = "",
    val paragraphs: List<List<Double>> = emptyList(),
    val cues: List<List<Double>> = emptyList(),
)

data class ReadAlongChapter(
    val id: Int = 0,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "",
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "has_timings") val hasTimings: Boolean = false,
    @param:Json(name = "timing_version") val timingVersion: Int? = null,
)

/**
 * A read-along document plus its ETag, as persisted for offline reading.
 *
 * The raw response is stored rather than the derived [ReadAlongDocument]: sentences and spans are
 * recomputed on load, so a later build that improves the sentence splitter fixes cached chapters
 * too instead of reading back last month's segmentation.
 */
data class CachedReadAlong(
    val etag: String? = null,
    val response: ReadAlongResponse = ReadAlongResponse(),
)

/** What read-along affordance a chapter gets, given what the server and the chapter support. */
enum class ReadAlongAvailability(val offersReader: Boolean) {
    /** The server has no read-along at all — show nothing, not a button that 404s. */
    Unavailable(offersReader = false),

    /** The reader opens, but this chapter has no timings, so there is nothing to follow. */
    TextOnly(offersReader = true),

    /** The reader opens and follows playback. */
    FollowAlong(offersReader = true),
}

/**
 * Two independent gates: the server advertises `readalong`, and the chapter has timings.
 *
 * A chapter list that never mentions `has_timings` predates the field, and the loaded document is
 * the authority on whether follow-along actually works — so an unknown is treated as available
 * rather than hiding a feature that would have worked.
 */
fun readAlongAvailability(
    capabilities: ServerCapabilities,
    chapter: ChapterSummary,
): ReadAlongAvailability = when {
    !capabilities.readAlong -> ReadAlongAvailability.Unavailable
    chapter.hasTimings == false -> ReadAlongAvailability.TextOnly
    else -> ReadAlongAvailability.FollowAlong
}
