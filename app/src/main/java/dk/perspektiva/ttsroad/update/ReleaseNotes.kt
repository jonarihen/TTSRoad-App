package dk.perspektiva.ttsroad.update

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * GitHub release bodies are Markdown, and the update dialog was drawing them raw — so a heading
 * arrived on screen as `## What's new` and an emphasised word as `**fixed**`. Asterisks in a
 * dialog asking for permission to install something read as corruption, which is the last
 * impression an updater should give.
 *
 * This is deliberately a *small* subset renderer, not a Markdown library:
 *
 * - `#` headings become bold lines, keeping the author's structure without inventing type scale.
 * - `**bold**` and `__bold__` become bold spans.
 * - `-`, `*` and `+` bullets are normalised to a real `•`, because three different markers for one
 *   idea is an artefact of the source format rather than anything the reader chose.
 * - `` `code` `` keeps its content and loses its backticks.
 * - `[label](url)` keeps the label. The URL is dropped rather than shown: nothing in this dialog
 *   is clickable, and a bare URL in prose is noise the reader cannot act on.
 *
 * Anything it does not recognise is passed through untouched. That is the important half — an
 * unparsed release note must still be *readable*, so the failure mode is plain text rather than a
 * blank dialog or an exception in front of an install prompt.
 */
fun renderReleaseNotes(markdown: String): AnnotatedString = buildAnnotatedString {
    val lines = markdown.replace("\r\n", "\n").trim().lines()
    // Line prefixes are per line, but inline spans are not: Markdown lets `**bold**` open on one
    // line and close on the next, and release prose is routinely hard-wrapped that way. Stripping
    // the prefixes first and then parsing the joined result is what lets a wrapped emphasis close
    // — parsing line by line leaves both halves of its markers on screen, which is the exact fault
    // this renderer exists to remove.
    val body = StringBuilder()
    val boldRanges = mutableListOf<IntRange>()
    lines.forEachIndexed { index, rawLine ->
        if (index > 0) body.append('\n')
        val line = rawLine.trimEnd()
        val heading = HeadingPrefix.find(line)
        val bullet = BulletPrefix.find(line)
        when {
            heading != null -> {
                val start = body.length
                body.append(line.removeRange(heading.range))
                // A heading is emphasised as a whole line, so it is recorded rather than matched:
                // its text carries no markers of its own to find.
                boldRanges += start until body.length
            }

            bullet != null -> {
                // The indent is preserved so nested lists keep their shape; only the marker moves.
                body.append(bullet.groupValues[1]).append("• ").append(line.removeRange(bullet.range))
            }

            else -> body.append(line)
        }
    }
    appendInline(body.toString(), boldRanges)
}

/** `##` and friends, with the space after them, so the text starts where the author meant. */
private val HeadingPrefix = Regex("""^\s{0,3}#{1,6}\s+""")

/** A list marker, capturing the indent before it so nesting survives. */
private val BulletPrefix = Regex("""^(\s*)[-*+]\s+""")

/**
 * Append inline Markdown with a bounded, left-to-right scanner.
 *
 * Regex was tempting here and wrong in three ways: nested code inside bold stayed raw, intraword
 * `__` silently rewrote identifiers, and many unmatched `[` characters made the link alternative
 * rescan the remaining suffix over and over. This scanner advances at least one character on every
 * pass and looks no more than [MaxLinkSpan] characters ahead for a link, so malformed release text
 * cannot turn composition into quadratic work.
 */
private fun AnnotatedString.Builder.appendInline(body: String, boldRanges: List<IntRange>) {
    fun headingAt(index: Int) = boldRanges.any { index in it }

    fun appendStyled(text: String, bold: Boolean) {
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } else append(text)
    }

    fun scan(from: Int, until: Int, forceBold: Boolean) {
        var index = from
        while (index < until) {
            val headingBold = headingAt(index)
            val bold = forceBold || headingBold

            val marker = when {
                body.startsWith("**", index) -> "**"
                body.startsWith("__", index) && underscoreDelimiter(body, index, until) -> "__"
                else -> null
            }
            if (marker != null) {
                val close = body.boundedIndexOf(marker, index + 2, minOf(until, index + MaxInlineSpan))
                    ?.takeIf { "\n\n" !in body.substring(index + 2, it) }
                if (close != null) {
                    // Recurse so **the `client`** loses its backticks as well as its outer markers.
                    scan(index + 2, close, forceBold = true)
                    index = close + 2
                    continue
                }
            }

            if (body[index] == '`') {
                val close = body.boundedIndexOf("`", index + 1, minOf(until, index + MaxInlineSpan))
                if (close != null && '\n' !in body.substring(index + 1, close)) {
                    appendStyled(body.substring(index + 1, close), bold)
                    index = close + 1
                    continue
                }
            }

            if (body[index] == '[') {
                val limit = minOf(until, index + MaxLinkSpan)
                val labelEnd = body.boundedIndexOf("](", index + 1, limit)
                val urlEnd = labelEnd?.let { body.boundedIndexOf(")", it + 2, limit) }
                if (labelEnd != null && urlEnd != null && '\n' !in body.substring(index, urlEnd)) {
                    scan(index + 1, labelEnd, forceBold = bold)
                    index = urlEnd + 1
                    continue
                }
                // This window was already proved not to contain a complete link. Emit it whole and
                // skip it; retrying from every `[` inside would turn a bounded 2K probe into 2K×N
                // overlapping work on a body made entirely of open brackets.
                appendStyled(body.substring(index, limit), bold)
                index = limit
                continue
            }

            // Grow a plain run until the next character that *might* begin markup or the heading
            // style changes. Grouping avoids one AnnotatedString span per character.
            var end = index + 1
            while (
                end < until &&
                body[end] !in "*_`[" &&
                headingAt(end) == headingBold
            ) end++
            appendStyled(body.substring(index, end), bold)
            index = end
        }
    }

    scan(0, body.length, forceBold = false)
}

/** `__` inside an identifier is punctuation, not emphasis, in GitHub Markdown. */
private fun underscoreDelimiter(body: String, start: Int, until: Int): Boolean {
    val close = body.boundedIndexOf("__", start + 2, minOf(until, start + MaxInlineSpan)) ?: return false
    val beforeOpen = body.getOrNull(start - 1)
    val afterOpen = body.getOrNull(start + 2)
    val beforeClose = body.getOrNull(close - 1)
    val afterClose = body.getOrNull(close + 2)
    return beforeOpen?.isLetterOrDigit() != true &&
        afterOpen?.isWhitespace() != true &&
        beforeClose?.isWhitespace() != true &&
        afterClose?.isLetterOrDigit() != true
}

/** Search only [from, until), returning null without scanning the rest of an attacker-controlled body. */
private fun String.boundedIndexOf(needle: String, from: Int, until: Int): Int? {
    if (needle.isEmpty() || from >= until) return null
    val lastStart = until - needle.length
    var index = from
    while (index <= lastStart) {
        if (regionMatches(index, needle, 0, needle.length)) return index
        index++
    }
    return null
}

/** Enough for human emphasis and code, while bounding an unclosed marker's UI-thread work. */
private const val MaxInlineSpan = 8_192

/** Enough for any human link label and URL, while bounding malformed-input work on the UI thread. */
private const val MaxLinkSpan = 2_048
