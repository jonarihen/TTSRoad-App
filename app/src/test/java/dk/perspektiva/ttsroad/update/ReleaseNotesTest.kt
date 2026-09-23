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
