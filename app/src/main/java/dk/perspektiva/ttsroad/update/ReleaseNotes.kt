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
    lines.forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')
        val line = rawLine.trimEnd()
        val heading = HeadingPrefix.find(line)
        val bullet = BulletPrefix.find(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(line.removeRange(heading.range))
            }

            bullet != null -> {
                // The indent is preserved so nested lists keep their shape; only the marker moves.
                append(bullet.groupValues[1])
                append("• ")
                appendInline(line.removeRange(bullet.range))
            }

            else -> appendInline(line)
        }
    }
}

/** `##` and friends, with the space after them, so the text starts where the author meant. */
private val HeadingPrefix = Regex("""^\s{0,3}#{1,6}\s+""")

/** A list marker, capturing the indent before it so nesting survives. */
private val BulletPrefix = Regex("""^(\s*)[-*+]\s+""")

/** `**bold**`, `__bold__`, `` `code` `` and `[label](url)`, matched left to right. */
private val Inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)]*)\)""")

/**
 * Append one line, converting the inline markers it carries.
 *
 * Single `*` is deliberately not treated as emphasis. It is the one marker that appears in ordinary
 * prose — a footnote, a wildcard, a literal asterisk — and eating it would silently rewrite a
 * sentence rather than fail to decorate one.
 */
private fun AnnotatedString.Builder.appendInline(line: String) {
    var cursor = 0
    Inline.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
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
    append(line.substring(cursor))
}
