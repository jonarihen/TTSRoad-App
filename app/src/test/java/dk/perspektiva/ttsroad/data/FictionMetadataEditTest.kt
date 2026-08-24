package dk.perspektiva.ttsroad.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the metadata editor.
 *
 * Most of the weight is on one thing: a `PATCH` marks every field it sets as hand-edited and the
 * server then stops refreshing that field from the source. So "which fields go on the wire" is not
 * a matter of efficiency — sending a field nobody touched freezes it against its own updates, for
 * everyone, until someone finds the undo. The diff below is what keeps a form that was opened,
 * read and saved from doing that.
 */
class FictionMetadataEditTest {

    private fun fiction(
        title: String = "Ashfall",
        author: String? = "R. Vane",
        description: String? = "A city under ash.",
        tags: List<String> = listOf("Fantasy", "Slow burn"),
        overrides: List<String>? = emptyList(),
    ) = FictionSummary(
        id = 7,
        title = title,
        author = author,
        description = description,
        tags = tags,
        metadataOverrides = overrides,
    )

    @Test
    fun `a form nobody touched sends nothing at all`() {
        val fiction = fiction()

        assertNull(fictionMetadataPatch(fiction, FictionMetadataDraft.of(fiction)))
    }

    @Test
    fun `only the field that changed is sent`() {
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(title = "Ashfall: Book One")

        val patch = fictionMetadataPatch(fiction, draft)

        assertEquals("Ashfall: Book One", patch?.title)
        assertNull(patch?.author)
        assertNull(patch?.description)
        assertNull(patch?.tags)
    }

    @Test
    fun `surrounding whitespace is not a change`() {
        // Editing a field and putting it back, or a keyboard that adds a trailing space, must not
        // quietly protect a field against the source.
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(title = "  Ashfall  ", author = "R. Vane ")

        assertNull(fictionMetadataPatch(fiction, draft))
    }

    @Test
    fun `emptying the author sends an empty string, which is how it is cleared`() {
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(author = "")

        val patch = fictionMetadataPatch(fiction, draft)

        assertEquals("", patch?.author)
        assertNull(patch?.title)
    }

    @Test
    fun `emptying the synopsis sends an empty string too`() {
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(description = "   ")

        assertEquals("", fictionMetadataPatch(fiction, draft)?.description)
    }

    @Test
    fun `an emptied title is never sent`() {
        // The server answers 400 for it, and the screen disables saving — but the patch builder is
        // the last place that could send one, so it declines here as well.
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(title = "  ")

        assertNull(fictionMetadataPatch(fiction, draft))
        assertFalse(draft.hasUsableTitle)
    }

    @Test
    fun `a fiction with no author yet is filled in rather than left alone`() {
        val fiction = fiction(author = null)
        val draft = FictionMetadataDraft.of(fiction).copy(author = "R. Vane")

        assertEquals("R. Vane", fictionMetadataPatch(fiction, draft)?.author)
    }

    @Test
    fun `retyping the tags exactly as they are is not a change`() {
        val fiction = fiction()

        val draft = FictionMetadataDraft.of(fiction).copy(tags = "Fantasy, Slow burn")

        assertNull(fictionMetadataPatch(fiction, draft))
    }

    @Test
    fun `editing the tags sends the parsed list`() {
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(tags = "Fantasy, Progression, ")

        assertEquals(listOf("Fantasy", "Progression"), fictionMetadataPatch(fiction, draft)?.tags)
    }

    @Test
    fun `clearing the tags sends an empty list, which is how they are cleared`() {
        val fiction = fiction()
        val draft = FictionMetadataDraft.of(fiction).copy(tags = "")

        val patch = fictionMetadataPatch(fiction, draft)

        assertNotNull(patch)
        assertEquals(emptyList<String>(), patch?.tags)
    }

    @Test
    fun `tags are trimmed, de-duplicated case-insensitively and capped`() {
        // The same normalisation the server applies, so the chips under the field are a preview of
        // what will be stored rather than a guess at it.
        // The first spelling wins, so the trailing FANTASY is dropped rather than recasing the tag
        // already in the list — retyping a tag should not silently rewrite it.
        assertEquals(
            listOf("Fantasy", "slow burn"),
            parseFictionTags(" Fantasy , slow burn,FANTASY ,, "),
        )
        assertEquals(MaxFictionTags, parseFictionTags((1..80).joinToString(",") { "tag$it" }).size)
        assertEquals(
            MaxFictionTagLength,
            parseFictionTags("x".repeat(400)).single().length,
        )
    }

    @Test
    fun `a pasted tag list split over lines still parses`() {
        assertEquals(listOf("Fantasy", "LitRPG"), parseFictionTags("Fantasy\nLitRPG"))
    }

    @Test
    fun `formatting round-trips a stored tag list back into the field`() {
        val tags = listOf("Fantasy", "Slow burn")

        assertEquals(tags, parseFictionTags(formatFictionTags(tags)))
    }

    @Test
    fun `an unedited fiction reports no overridden fields`() {
        val fiction = fiction(overrides = emptyList())

        assertTrue(fiction.supportsMetadataEditing)
        assertEquals(emptyList<String>(), fiction.overriddenFields)
        assertFalse(fiction.isMetadataOverridden(MetadataFieldTitle))
    }

    @Test
    fun `overridden fields come back in presentation order`() {
        val fiction = fiction(overrides = listOf("tags", "title", "cover_image_url"))

        assertEquals(
            listOf(MetadataFieldTitle, MetadataFieldCoverImageUrl, MetadataFieldTags),
            fiction.overriddenFields,
        )
    }

    @Test
    fun `a server that never mentions overrides is not read as an unedited one`() {
        // Absent and empty mean different things: the first is a server that cannot hold a
        // hand-edited synopsis at all, and offering the field would be offering a lie.
        val fiction = fiction(overrides = null)

        assertFalse(fiction.supportsMetadataEditing)
        assertEquals(emptyList<String>(), fiction.overriddenFields)
        assertFalse(fiction.isMetadataOverridden(MetadataFieldDescription))
    }

    @Test
    fun `a field name this build has never heard of is ignored rather than shown as a known one`() {
        val fiction = fiction(overrides = listOf("rating", "title"))

        assertEquals(listOf(MetadataFieldTitle), fiction.overriddenFields)
    }

    @Test
    fun `the accepted image types are the ones the server stores`() {
        assertNull(coverRejectionReason("image/jpeg", 1024))
        assertNull(coverRejectionReason("image/png", 1024))
        assertNull(coverRejectionReason("image/webp", 1024))
        assertNull(coverRejectionReason("image/gif", 1024))
        assertNotNull(coverRejectionReason("application/pdf", 1024))
        assertNotNull(coverRejectionReason("image/heic", 1024))
    }

    @Test
    fun `a content type with a charset parameter is still an image`() {
        assertNull(coverRejectionReason("image/jpeg; charset=binary", 1024))
        assertNull(coverRejectionReason("IMAGE/PNG", 1024))
    }

    @Test
    fun `a type nobody could work out is refused rather than uploaded hopefully`() {
        assertNotNull(coverRejectionReason(null, 1024))
        assertNotNull(coverRejectionReason("  ", 1024))
    }

    @Test
    fun `the size ceiling matches the server's, and an unknown size is not a refusal`() {
        assertNull(coverRejectionReason("image/jpeg", MaxCoverUploadBytes))
        assertNotNull(coverRejectionReason("image/jpeg", MaxCoverUploadBytes + 1))
        assertNotNull(coverRejectionReason("image/jpeg", 0))
        // A content provider is entitled not to know how big a file is; that is what reading with a
        // ceiling is for, not a reason to refuse the picture.
        assertNull(coverRejectionReason("image/jpeg", null))
    }

    @Test
    fun `reading stops at the ceiling instead of pulling a whole video into memory`() {
        val oversized = ByteArrayInputStream(ByteArray(64))

        assertNull(readAtMost(oversized, limit = 32))
    }

    @Test
    fun `a file exactly at the ceiling is read whole`() {
        val bytes = ByteArray(32) { it.toByte() }

        assertArrayEqualsNotNull(bytes, readAtMost(ByteArrayInputStream(bytes), limit = 32))
    }

    @Test
    fun `the part filename carries the type's extension, since that is what the server hashes`() {
        assertEquals("cover.jpg", coverFilename("image/jpeg"))
        assertEquals("cover.png", coverFilename("image/png"))
        assertEquals("cover.webp", coverFilename("image/webp"))
        assertEquals("cover.gif", coverFilename("image/gif"))
    }

    private fun assertArrayEqualsNotNull(expected: ByteArray, actual: ByteArray?) {
        assertNotNull(actual)
        assertEquals(expected.toList(), actual?.toList())
    }
}
