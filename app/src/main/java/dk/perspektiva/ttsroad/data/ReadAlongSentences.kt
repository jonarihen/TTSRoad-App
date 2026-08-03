package dk.perspektiva.ttsroad.data

/**
 * Splits a chapter into sentence spans, one paragraph at a time.
 *
 * The sentence band — not the word — is the reader's primary follow-along cue. At the 1.5x-2x this
 * app is actually listened at, a lone highlighted word moves faster than the eye tracks and the
 * reader loses the line entirely; a band that holds still for a few seconds keeps the place while
 * the word accent moves inside it. That makes a band ending in the wrong spot a visible defect, so
 * the splitter earns its handful of special cases.
 *
 * When in doubt it under-splits. A band that runs one sentence long still reads correctly; one that
 * stops mid-sentence looks like the reader lost sync.
 */
fun segmentSentences(text: String, paragraphs: List<TextSpan>): List<TextSpan> {
    if (text.isEmpty()) return emptyList()
    val sentences = ArrayList<TextSpan>()
    for (paragraph in paragraphs) {
        // The server's offsets and the text it sent can disagree; clamping is cheaper than trusting.
        val from = paragraph.start.coerceIn(0, text.length)
        val to = paragraph.end.coerceIn(0, text.length)
        if (to > from) segmentParagraph(text, from, to, sentences)
    }
    return sentences
}

/** A sentence never spans a paragraph break, so each paragraph is segmented on its own. */
private fun segmentParagraph(text: String, from: Int, to: Int, out: MutableList<TextSpan>) {
    var sentenceStart = from
    var index = from
    while (index < to) {
        if (text[index] !in Terminators) {
            index++
            continue
        }
        val breakEnd = sentenceBreakEnd(text, index, to)
        if (breakEnd < 0) {
            index++
            continue
        }
        out.addTrimmed(text, sentenceStart, breakEnd)
        var next = breakEnd
        while (next < to && text[next].isWhitespace()) next++
        sentenceStart = next
        index = next
    }
    out.addTrimmed(text, sentenceStart, to)
}

/**
 * Where the sentence starting before [at] ends, or -1 if the punctuation at [at] does not actually
 * end one. The end is exclusive and includes the terminator run and any quote it closes, so
 * `"Go north."` bands as one unit rather than leaving the quote mark stranded.
 */
private fun sentenceBreakEnd(text: String, at: Int, limit: Int): Int {
    var end = at + 1
    while (end < limit && text[end] in Terminators) end++
    val terminatorRun = end - at
    while (end < limit && text[end] in Closers) end++

    // The end of a paragraph always ends a sentence, punctuation or not.
    if (end >= limit) return end

    // "3.5", "U.S.A", the inner stop of "e.g." — a terminator glued to the next character is
    // punctuation inside a word, not the end of anything.
    if (!text[end].isWhitespace()) return -1

    // A lone full stop is the ambiguous one; "?" and "!" are not abbreviated with.
    if (terminatorRun == 1 && text[at] == '.' && endsWithAbbreviation(text, at)) return -1

    // Fiction convention: `"Stop." he snarled` is one sentence with a speech tag, so a lowercase
    // word after the closing quote is a continuation rather than a new sentence.
    val resume = firstNonWhitespace(text, end, limit)
    if (resume < limit && !startsSentence(text[resume])) return -1

    return end
}

/** True when the word before the full stop at [dotIndex] is an abbreviation or an initial. */
private fun endsWithAbbreviation(text: String, dotIndex: Int): Boolean {
    var start = dotIndex
    // Dots are part of the token so "e.g" is recognised whole rather than as a bare "g".
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '.')) start--
    if (start == dotIndex) return false
    val token = text.substring(start, dotIndex)
    // "J. R. Vale" — a single letter is an initial, never a sentence on its own.
    if (token.length == 1 && token[0].isLetter()) return true
    return token.lowercase() in Abbreviations
}

private fun firstNonWhitespace(text: String, from: Int, limit: Int): Int {
    var index = from
    while (index < limit && text[index].isWhitespace()) index++
    return index
}

private fun startsSentence(char: Char): Boolean =
    char.isUpperCase() || char.isDigit() || char in Openers

/** Sentences are stored without their surrounding whitespace, so the band never has a ragged edge. */
private fun MutableList<TextSpan>.addTrimmed(text: String, from: Int, to: Int) {
    var start = from
    var end = to
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (end > start) add(TextSpan(start, end))
}

private val Terminators = charArrayOf('.', '!', '?', '…')
private val Closers = charArrayOf('"', '\'', '”', '’', ')', ']', '»')
private val Openers = charArrayOf(
    '"', '\'', '“', '‘', '(', '[', '«', '—', '–', '-', '*',
)

/**
 * Kept short and fiction-flavoured on purpose. Every entry buys a correct band after "Mr." at the
 * cost of a missed break when the word genuinely ends a sentence, so words that are commonly
 * sentence-final ("etc.", "No.") are deliberately absent.
 */
private val Abbreviations = setOf(
    "mr", "mrs", "ms", "mx", "dr", "prof", "st", "sr", "jr", "vs",
    "capt", "lt", "sgt", "col", "gen", "rev", "hon", "e.g", "i.e",
)
