package dk.perspektiva.ttsroad.data

/** A half-open `[start, end)` character range into a chapter's text. */
data class TextSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start

    fun contains(offset: Int): Boolean = offset in start until end
}

/** One timed unit — usually a word — with the media time at which it starts being spoken. */
data class ReadAlongCue(val span: TextSpan, val startSeconds: Double)

/**
 * What to draw at a given moment: a sentence band, with a stronger accent on the word inside it.
 *
 * [None] is the honest answer for a position before the first cue or a chapter with no timings at
 * all — the reader still shows the text, just without following.
 */
data class ReadAlongHighlight(
    val cueIndex: Int = -1,
    val word: TextSpan? = null,
    val sentenceIndex: Int = -1,
    val sentence: TextSpan? = null,
) {
    val isActive: Boolean get() = cueIndex >= 0

    companion object {
        val None = ReadAlongHighlight()
    }
}

/**
 * A chapter's text with everything the reader needs to follow it: paragraph spans to lay it out,
 * cue spans to highlight, and sentences derived from both.
 *
 * Every lookup here is a binary search. A chapter runs to tens of thousands of cues and the reader
 * resolves the active one on every frame, so a scan would burn the frame budget on nothing.
 *
 * Positions are always **media time**, which is what the player reports, so a listener at 2x needs
 * no adjustment and nothing here has to know about playback speed.
 */
data class ReadAlongDocument(
    val chapterId: Int = 0,
    val fictionId: Int = 0,
    val title: String = "",
    val chapterNumber: Double? = null,
    val audioDurationSeconds: Double = 0.0,
    val text: String = "",
    val paragraphs: List<TextSpan> = emptyList(),
    /** Sorted by [ReadAlongCue.startSeconds] and non-overlapping — [from] guarantees it. */
    val cues: List<ReadAlongCue> = emptyList(),
) {
    /** Derived once, since the reader asks for the enclosing sentence on every frame. */
    val sentences: List<TextSpan> = segmentSentences(text, paragraphs)

    /** False for a chapter converted before timing existed — it still reads, it just cannot follow. */
    val hasTimings: Boolean get() = cues.isNotEmpty()

    fun textIn(span: TextSpan): String =
        text.substring(span.start.coerceIn(0, text.length), span.end.coerceIn(0, text.length))

    /**
     * Index of the cue being spoken at [positionSeconds], or -1 when nothing is.
     *
     * Past the last cue the answer stays the last cue rather than becoming -1. Skip-silence shortens
     * the media timeline relative to the file the server derived timings from, and a reported
     * duration can disagree with the server's by seconds — losing the highlight at the end of every
     * chapter would read as a bug, and there is nothing else it could sensibly be.
     */
    fun cueIndexAt(positionSeconds: Double): Int {
        if (cues.isEmpty() || positionSeconds.isNaN()) return -1
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startSeconds <= positionSeconds) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** A cue ends where the next one starts; the last runs to the end of the audio. */
    fun cueEndSeconds(index: Int): Double {
        cues.getOrNull(index + 1)?.let { return it.startSeconds }
        val start = cues.getOrNull(index)?.startSeconds ?: 0.0
        return maxOf(audioDurationSeconds, start)
    }

    fun highlightAt(positionSeconds: Double): ReadAlongHighlight {
        val cueIndex = cueIndexAt(positionSeconds)
        if (cueIndex < 0) return ReadAlongHighlight.None
        val word = cues[cueIndex].span
        val sentenceIndex = sentenceIndexAt(word.start)
        return ReadAlongHighlight(
            cueIndex = cueIndex,
            word = word,
            sentenceIndex = sentenceIndex,
            sentence = sentences.getOrNull(sentenceIndex),
        )
    }

    /**
     * The reader's actual entry point: the position the player *reports*, in milliseconds.
     *
     * Deliberately a pure function of that position and nothing else. Anything that extrapolated
     * from a clock ("16ms of wall time passed, advance 16ms") would drift against skip-silence for
     * the length of a chapter; re-reading the reported position every frame means the error can
     * never accumulate.
     */
    fun highlightAtMillis(positionMs: Long): ReadAlongHighlight = highlightAt(positionMs / 1000.0)

    /** Index of the sentence covering [offset], or the last one starting before it. */
    fun sentenceIndexAt(offset: Int): Int = spanIndexAt(sentences, offset)

    /** Index of the paragraph covering [offset] — what auto-scroll scrolls to. */
    fun paragraphIndexAt(offset: Int): Int = spanIndexAt(paragraphs, offset)

    /**
     * Media time to seek to for a tap at character [offset], or null when nothing is timed.
     *
     * Prefers the cue covering the offset, then the nearer neighbour: a finger lands on the space
     * between two words, or on punctuation no cue covers, about as often as it lands on a word.
     */
    fun seekSecondsForOffset(offset: Int): Double? {
        if (cues.isEmpty()) return null
        return cues[cueIndexForOffset(offset)].startSeconds
    }

    private fun cueIndexForOffset(offset: Int): Int {
        var low = 0
        var high = cues.size - 1
        var at = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].span.start <= offset) {
                at = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (at < 0) return 0
        if (cues[at].span.contains(offset)) return at
        val next = at + 1
        if (next >= cues.size) return at
        val gapBefore = offset - cues[at].span.end
        val gapAfter = cues[next].span.start - offset
        // Ties go forwards: at the midpoint of a gap, the word about to be read is the better guess.
        return if (gapAfter <= gapBefore) next else at
    }

    /** Last span starting at or before [offset]; spans are sorted and non-overlapping. */
    private fun spanIndexAt(spans: List<TextSpan>, offset: Int): Int {
        if (spans.isEmpty()) return -1
        var low = 0
        var high = spans.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (spans[mid].start <= offset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        /**
         * Validate the wire payload into something the binary searches can trust.
         *
         * The contract says cues arrive sorted and inside the text, but the searches are only
         * correct if that actually holds — one bad row would make the highlight jump at random for
         * the rest of the chapter. Sorting a chapter's cues once on load is cheap insurance, and
         * rows that cannot be salvaged are dropped rather than failing the whole chapter.
         */
        fun from(response: ReadAlongResponse): ReadAlongDocument {
            val text = response.text
            val paragraphs = response.paragraphs
                .mapNotNull { it.toSpan(text.length) }
                .ifEmpty { paragraphsFromLineBreaks(text) }
            val cues = response.cues
                .mapNotNull { row ->
                    if (row.size < 3) return@mapNotNull null
                    row.toSpan(text.length)?.let { ReadAlongCue(it, row[2]) }
                }
                .sortedBy { it.startSeconds }
            return ReadAlongDocument(
                chapterId = response.chapter.id,
                fictionId = response.chapter.fictionId,
                title = response.chapter.title,
                chapterNumber = response.chapter.chapterNumber,
                audioDurationSeconds = response.chapter.audioDuration ?: 0.0,
                text = text,
                paragraphs = paragraphs,
                cues = cues,
            )
        }

        private fun List<Double>.toSpan(textLength: Int): TextSpan? {
            if (size < 2) return null
            val start = this[0].toInt().coerceIn(0, textLength)
            val end = this[1].toInt().coerceIn(0, textLength)
            return if (end > start) TextSpan(start, end) else null
        }

        /**
         * Stand-in paragraphs for a payload that sent none. Rendering a whole chapter as one
         * unbroken block would be unreadable, and line breaks are what the text already has.
         */
        private fun paragraphsFromLineBreaks(text: String): List<TextSpan> {
            val spans = ArrayList<TextSpan>()
            var index = 0
            while (index < text.length) {
                while (index < text.length && text[index] == '\n') index++
                if (index >= text.length) break
                val start = index
                while (index < text.length && text[index] != '\n') index++
                var end = index
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end > start) spans.add(TextSpan(start, end))
            }
            return spans
        }
    }
}
