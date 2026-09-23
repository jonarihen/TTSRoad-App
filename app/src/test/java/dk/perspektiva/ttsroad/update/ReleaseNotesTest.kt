package dk.perspektiva.ttsroad.update

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the update dialog does with a GitHub release body.
 *
 * Tested as a pure string-to-AnnotatedString function rather than through the dialog, because the
 * failure this fixes is invisible in a screenshot until you read the words: markers rendered as
 * literal punctuation. The important assertion in most of these is that the *marker* is gone and
 * the *content* survived — dropping the text along with its asterisks would be a worse bug than
 * the one being fixed.
 */
class ReleaseNotesTest {

    @Test
    fun `bold markers are consumed and the word they wrapped is emphasised`() {
        val rendered = renderReleaseNotes("Playback is **fixed** now")

        assertEquals("Playback is fixed now", rendered.text)
        val bold = rendered.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("fixed", rendered.text.substring(bold.start, bold.end))
    }

    @Test
    fun `underscore bold is treated the same as asterisk bold`() {
        val rendered = renderReleaseNotes("This is __also__ bold")

        assertEquals("This is also bold", rendered.text)
        assertEquals(FontWeight.Bold, rendered.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `emphasis closing on the next line still resolves`() {
        // Hard-wrapped release prose routinely opens a span on one line and closes it on the next.
        // Parsing line by line left both halves of the markers on screen — the exact fault this
        // renderer exists to remove.
        val rendered = renderReleaseNotes("Playback is **fixed\nat last** now")

        assertEquals("Playback is fixed\nat last now", rendered.text)
        val bold = rendered.spanStyles.single()
        assertEquals("fixed\nat last", rendered.text.substring(bold.start, bold.end))
    }

    @Test
    fun `an unclosed marker cannot emphasise the rest of the notes`() {
        // A blank line is a paragraph break. Without that limit, one stray marker would reach
        // across everything after it and bold the remainder of the release body.
        val rendered = renderReleaseNotes("Opens **here\n\nA new paragraph** entirely")

        assertEquals("Opens **here\n\nA new paragraph** entirely", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    @Test
    fun `nested code inside bold loses both kinds of marker`() {
        val rendered = renderReleaseNotes("The **shared `OkHttpClient`** stays shared")

        assertEquals("The shared OkHttpClient stays shared", rendered.text)
        assertTrue(rendered.spanStyles.any { span ->
            rendered.text.substring(span.start, span.end).contains("OkHttpClient") &&
                span.item.fontWeight == FontWeight.Bold
        })
    }

    @Test
    fun `nested link inside bold keeps the label and the emphasis`() {
        val rendered = renderReleaseNotes("Read **[the notes](https://x.test)** first")

        assertEquals("Read the notes first", rendered.text)
        assertTrue(rendered.spanStyles.any { span ->
            rendered.text.substring(span.start, span.end) == "the notes" &&
                span.item.fontWeight == FontWeight.Bold
        })
    }

    @Test
    fun `double underscores inside an identifier are preserved literally`() {
        val rendered = renderReleaseNotes("Rename field__value__suffix carefully")

        assertEquals("Rename field__value__suffix carefully", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    @Test
    fun `a literal bracket does not hide valid markup after it`() {
        val rendered = renderReleaseNotes("[draft] **Fixed** the player")

        assertEquals("[draft] Fixed the player", rendered.text)
        assertEquals("Fixed", rendered.text.substring(
            rendered.spanStyles.single().start,
            rendered.spanStyles.single().end,
        ))
    }

    @Test
    fun `a complete backtick run requires an equal closing run`() {
        val rendered = renderReleaseNotes("Use ``a `literal` tick`` here")

        assertEquals("Use a `literal` tick here", rendered.text)
    }

    @Test
    fun `an unclosed backtick run is preserved whole`() {
        val rendered = renderReleaseNotes("Use ``unfinished")

        assertEquals("Use ``unfinished", rendered.text)
    }

    @Test
    fun `fenced code contents are preserved literally`() {
        val rendered = renderReleaseNotes("```yaml\n- name: **literal**\n## not a heading\n```")

        assertEquals("- name: **literal**\n## not a heading", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    @Test
    fun `balanced parentheses in a link destination leave no stray delimiter`() {
        val rendered = renderReleaseNotes("Read [docs](https://example.test/Foo_(bar))")

        assertEquals("Read docs", rendered.text)
    }

    @Test
    fun `literal brackets before a real link stay separate`() {
        val rendered = renderReleaseNotes("[draft] See [notes](https://x.test)")

        assertEquals("[draft] See notes", rendered.text)
    }

    @Test
    fun `asterisk operators with whitespace stay literal`() {
        val rendered = renderReleaseNotes("Compute 2 ** 3 ** 4")

        assertEquals("Compute 2 ** 3 ** 4", rendered.text)
    }

    @Test
    fun `a longer closing backtick run does not close a shorter opener`() {
        val rendered = renderReleaseNotes("Use ``value``` here")

        assertEquals("Use ``value``` here", rendered.text)
    }

    @Test(timeout = 1_000)
    fun `many unmatched link openers stay linear and readable`() {
        // The old regex retried its whole suffix at every `[`, taking seconds for a release body an
        // attacker with compromised GitHub credentials could make the app show on every launch.
        val body = "[".repeat(50_000)

        assertEquals(body, renderReleaseNotes(body).text)
    }

    @Test(timeout = 1_000)
    fun `malformed link candidates do not rescan or copy their suffixes`() {
        val body = ("[".repeat(2_045) + "](\n)").repeat(50)

        assertTrue(renderReleaseNotes(body).text.isNotEmpty())
    }

    @Test(timeout = 1_000)
    fun `thousands of headings stay linear`() {
        val body = (1..5_000).joinToString("\n") { "## Heading $it" }

        val rendered = renderReleaseNotes(body)

        assertTrue(rendered.text.startsWith("Heading 1"))
        assertTrue(rendered.text.endsWith("Heading 5000"))
    }

    @Test
    fun `a heading keeps its words, loses its hashes, and is emphasised`() {
        val rendered = renderReleaseNotes("## What's new")

        assertEquals("What's new", rendered.text)
        assertEquals(FontWeight.Bold, rendered.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `every bullet marker becomes one real bullet`() {
        // Three markers for one idea is an artefact of Markdown, not a choice the author made.
        val rendered = renderReleaseNotes("- one\n* two\n+ three")

        assertEquals("• one\n• two\n• three", rendered.text)
    }

    @Test
    fun `a nested bullet keeps its indent`() {
        val rendered = renderReleaseNotes("- outer\n  - inner")

        assertEquals("• outer\n  • inner", rendered.text)
    }

    @Test
    fun `code spans keep their content and drop their backticks`() {
        val rendered = renderReleaseNotes("Run `./gradlew test` first")

        assertEquals("Run ./gradlew test first", rendered.text)
    }

    @Test
    fun `a link keeps its label and drops the address`() {
        // Nothing in this dialog is clickable, so a bare URL is noise the reader cannot act on.
        val rendered = renderReleaseNotes("See [the changelog](https://example.com/notes)")

        assertEquals("See the changelog", rendered.text)
    }

    @Test
    fun `a lone asterisk in prose is left alone`() {
        // The one marker that turns up in ordinary writing. Eating it would silently rewrite a
        // sentence rather than merely fail to decorate one.
        val rendered = renderReleaseNotes("Rated 5 * by listeners")

        assertEquals("Rated 5 * by listeners", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    @Test
    fun `an unterminated marker is left visible rather than swallowing the rest`() {
        val rendered = renderReleaseNotes("This **never closes")

        assertEquals("This **never closes", rendered.text)
    }

    @Test
    fun `several markers on one line are all handled`() {
        val rendered = renderReleaseNotes("- **Fixed** the `player` in [0.17.0](https://x.test)")

        assertEquals("• Fixed the player in 0.17.0", rendered.text)
        assertEquals(FontWeight.Bold, rendered.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `a realistic release body carries no leftover markup`() {
        val rendered = renderReleaseNotes(
            """
            ## Added
            - **Recently listened** ordering
            - Source filter on browse

            ## Fixed
            - Zero-byte `EPUB` downloads
            """.trimIndent(),
        )

        assertFalse(rendered.text.contains("**"))
        assertFalse(rendered.text.contains("##"))
        assertFalse(rendered.text.contains('`'))
        assertTrue(rendered.text.contains("• Recently listened ordering"))
        assertTrue(rendered.text.contains("Zero-byte EPUB downloads"))
    }

    @Test
    fun `blank notes render as nothing rather than throwing`() {
        assertEquals("", renderReleaseNotes("").text)
        assertEquals("", renderReleaseNotes("   \n  ").text)
    }

    @Test
    fun `windows line endings do not leave stray carriage returns`() {
        val rendered = renderReleaseNotes("## Added\r\n- one\r\n")

        assertEquals("Added\n• one", rendered.text)
    }

    @Test
    fun `a long body is preserved whole for the dialog to scroll`() {
        // The old dialog cut notes at 400 characters; the fix is a scroll container, so the
        // renderer must not quietly reintroduce a limit of its own.
        val body = (1..80).joinToString("\n") { "- change number $it" }

        val rendered = renderReleaseNotes(body)

        assertEquals(80, rendered.text.lines().size)
        assertTrue(rendered.text.contains("• change number 80"))
    }
}
