package dk.perspektiva.ttsroad.update

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Turn the GitHub release body's CommonMark into readable Compose text.
 *
 * The first implementation grew a hand-written Markdown scanner. Review kept finding syntax it
 * would have to reimplement — nested markup, fenced blocks, delimiter boundaries, backtick runs,
 * balanced link destinations — plus malformed-input paths that ran synchronously while the update
 * dialog composed. That is exactly what a standards parser is for. CommonMark owns tokenisation and
 * malformed-input behaviour; this file only decides how its AST maps to the deliberately small
 * visual vocabulary of the dialog.
 *
 * Headings and strong emphasis are bold. Lists use a real bullet. Code keeps its literal content.
 * Links keep their readable label and drop the destination because the dialog does not make them
 * clickable. Unsupported nodes render their children, so the failure mode remains readable text.
 */
fun renderReleaseNotes(markdown: String): AnnotatedString {
    if (markdown.isBlank()) return AnnotatedString("")
    val document = ReleaseNotesParser.parse(markdown.replace("\r\n", "\n").trim())
    return buildAnnotatedString { appendNode(document, listDepth = 0) }.trimTrailingLines()
}

private val ReleaseNotesParser: Parser = Parser.builder().build()

private fun AnnotatedString.Builder.appendNode(node: Node, listDepth: Int) {
    when (node) {
        is Text -> append(node.literal)
        is Code -> append(node.literal)
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is StrongEmphasis, is Heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendChildren(node, listDepth)
        }

        is Link -> appendChildren(node, listDepth)
        is FencedCodeBlock -> {
            ensureLineStart()
            append(node.literal.trimEnd())
            append('\n')
        }

        is IndentedCodeBlock -> {
            ensureLineStart()
            append(node.literal.trimEnd())
            append('\n')
        }

        is BulletList -> appendChildren(node, listDepth + 1)
        is ListItem -> {
            ensureLineStart()
            append("  ".repeat((listDepth - 1).coerceAtLeast(0)))
            append("• ")
            appendChildren(node, listDepth)
            ensureLineEnd()
        }

        is Paragraph -> {
            // Blank lines between top-level paragraphs are semantic. Inside a list item the list
            // itself owns line breaks, so adding one there would turn every bullet into a paragraph.
            if (listDepth == 0 && length > 0) ensureBlankLine()
            appendChildren(node, listDepth)
            ensureLineEnd()
        }

        else -> appendChildren(node, listDepth)
    }
}

private fun AnnotatedString.Builder.appendChildren(parent: Node, listDepth: Int) {
    var child = parent.firstChild
    while (child != null) {
        appendNode(child, listDepth)
        child = child.next
    }
}

private fun AnnotatedString.Builder.ensureLineStart() {
    if (length > 0 && toAnnotatedString().text.lastOrNull() != '\n') append('\n')
}

private fun AnnotatedString.Builder.ensureLineEnd() {
    if (length == 0 || toAnnotatedString().text.lastOrNull() != '\n') append('\n')
}

private fun AnnotatedString.Builder.ensureBlankLine() {
    val text = toAnnotatedString().text
    when {
        text.endsWith("\n\n") -> Unit
        text.endsWith('\n') -> append('\n')
        else -> append("\n\n")
    }
}

private fun AnnotatedString.trimTrailingLines(): AnnotatedString {
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return if (end <= 0) AnnotatedString("") else subSequence(0, end)
}
