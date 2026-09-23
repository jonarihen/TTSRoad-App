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
 * `**bold**`, `__bold__`, `` `code` `` and `[label](url)`, matched left to right.
 *
 * The two emphasis forms may contain a single newline, because hard-wrapped prose routinely opens
 * on one line and closes on the next. They may not contain a *blank* line: that is a paragraph
 * break, and an unclosed marker would otherwise reach across the rest of the notes and emphasise
 * everything after it. Code spans and links stay single-line, which is what Markdown itself says.
 */
private val Inline = Regex(
    """\*\*((?:(?!\*\*)(?!\n\n)[\s\S])+?)\*\*""" +
        """|__((?:(?!__)(?!\n\n)[\s\S])+?)__""" +
        """|`([^`\n]+)`""" +
        """|\[([^\]\n]+)]\(([^)\n]*)\)""",
)

/**
 * Append one line, converting the inline markers it carries.
 *
 * Single `*` is deliberately not treated as emphasis. It is the one marker that appears in ordinary
 * prose — a footnote, a wildcard, a literal asterisk — and eating it would silently rewrite a
 * sentence rather than fail to decorate one.
 */
private fun AnnotatedString.Builder.appendInline(body: String, boldRanges: List<IntRange>) {
    // Emphasis from a line prefix is carried as source offsets rather than as text, because the
    // inline pass below rewrites the string as it goes and the two would otherwise disagree about
    // where a heading ends.
    fun emphasisedAt(index: Int) = boldRanges.any { index in it }

    fun appendPlain(text: String, from: Int) {
        // Emitted as contiguous runs rather than per character: one span per letter would be the
        // same picture built from hundreds of styles, and a heading is a single emphasised range.
        var runStart = 0
        while (runStart < text.length) {
            val emphasised = emphasisedAt(from + runStart)
            var runEnd = runStart
            while (runEnd < text.length && emphasisedAt(from + runEnd) == emphasised) runEnd++
            val run = text.substring(runStart, runEnd)
            if (emphasised) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run) }
            } else {
                append(run)
            }
            runStart = runEnd
        }
    }

    var cursor = 0
    Inline.findAll(body).forEach { match ->
        appendPlain(body.substring(cursor, match.range.first), cursor)
        val bold = match.groupValues[1].ifEmpty { match.groupValues[2] }
        val code = match.groupValues[3]
        val link = match.groupValues[4]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> append(code)
            else -> append(link)
        }
        cursor = match.range.last + 1
    }
    appendPlain(body.substring(cursor), cursor)
}
