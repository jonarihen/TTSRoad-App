package dk.perspektiva.ttsroad.data

import java.io.ByteArrayInputStream
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind importing a book from the phone.
 *
 * Two of them are the server's and are easy to get quietly wrong from this side. `upload_epub`
 * validates the *filename* — anything not ending in `.epub` is a 400 and the content type is never
 * looked at — so typing the check on the MIME a document provider reports would refuse files the
 * server would have taken. And the ceiling is advertised in `max_epub_bytes` precisely so a client
 * can refuse first: the alternative is spending a hundred megabytes of someone's data allowance to
 * be told 413.
 *
 * The third is this client's own: a book that size is not something to hold in a `ByteArray` on a
 * phone, so the body streams and the last test here is what says so.
 */
class EpubUploadTest {
    @Test
    fun `a book with an epub name and a size under the ceiling is accepted`() {
        assertNull(epubRejectionReason("Ashfall.epub", 2L * 1024 * 1024, DefaultMaxEpubBytes))
    }

    @Test
    fun `the extension is what decides, because it is what the server reads`() {
        assertNotNull(epubRejectionReason("Ashfall.mobi", 1024L))
        assertNotNull(epubRejectionReason("Ashfall.epub.txt", 1024L))
        assertNotNull(epubRejectionReason("Ashfall", 1024L))
        // Case is not a difference the server makes: it lowercases the name before comparing.
        assertNull(epubRejectionReason("Ashfall.EPUB", 1024L))
        // Nor is the padding a display name can arrive with.
        assertNull(epubRejectionReason("  Ashfall.epub  ", 1024L))
    }

    @Test
    fun `a nameless pick is refused rather than guessed at`() {
        assertNotNull(epubRejectionReason(null, 1024L))
        assertNotNull(epubRejectionReason("   ", 1024L))
    }

    @Test
    fun `the advertised ceiling is the one enforced, not the built-in default`() {
        // A server is free to publish a smaller limit than the one this client assumes, and a file
        // that fits the default has to be refused against the server's own number instead.
        val serverLimit = 5L * 1024 * 1024
        assertNull(epubRejectionReason("Ashfall.epub", serverLimit, serverLimit))
        assertNotNull(epubRejectionReason("Ashfall.epub", serverLimit + 1, serverLimit))
        assertNull(epubRejectionReason("Ashfall.epub", serverLimit + 1, DefaultMaxEpubBytes))
    }

    @Test
    fun `the refusal names both sizes, so it reads as an explanation rather than a verdict`() {
        val reason = epubRejectionReason("Ashfall.epub", 210L * 1024 * 1024, DefaultMaxEpubBytes)

        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("210 MB"))
        assertTrue(reason, reason.contains("100 MB"))
    }

    @Test
    fun `an empty file is refused, and an unknown size is not`() {
        assertNotNull(epubRejectionReason("Ashfall.epub", 0L))
        // A provider is entitled not to know how big a document is — a cloud-backed one may not
        // until it has been fetched. Refusing on that would block an upload that would have worked,
        // and the server still enforces its own ceiling.
        assertNull(epubRejectionReason("Ashfall.epub", null))
    }

    @Test
    fun `sizes are described the way a person would say them`() {
        assertEquals("100 MB", megabyteLabel(DefaultMaxEpubBytes))
        assertEquals("12 MB", megabyteLabel(12L * 1024 * 1024))
        assertEquals("1.5 MB", megabyteLabel(3L * 512 * 1024))
        assertEquals("4 MB", megabyteLabel(4L * 1024 * 1024))
        assertEquals("0 MB", megabyteLabel(64L))
        // Rounded down under ten, so a file just short of the limit is not described as being at it.
        assertEquals("9.9 MB", megabyteLabel(10L * 1024 * 1024 - 1))
    }

    @Test
    fun `a display name is sanitised into something a multipart header can hold`() {
        // A newline or a quote in a provider's display name is not a bad upload — it is OkHttp
        // throwing while building the header, in front of a picker the user has just used.
        assertEquals("Ashfall.epub", epubUploadFilename("Ash\nfall.epub"))
        assertEquals("Ashfall.epub", epubUploadFilename("Ashfall\".epub"))
        assertEquals("Ashfall.epub", epubUploadFilename("/storage/emulated/0/Books/Ashfall.epub"))
        assertEquals("Ashfall.epub", epubUploadFilename("C:\\Books\\Ashfall.epub"))
        assertEquals("Ashfall.epub", epubUploadFilename("  Ashfall.epub "))
    }

    @Test
    fun `a name the server would refuse is replaced rather than sent`() {
        // The upload is already gated on [epubRejectionReason]; this is the belt to that braces —
        // whatever the part is called, it ends in the extension the server checks for.
        assertEquals("book.epub", epubUploadFilename(null))
        assertEquals("book.epub", epubUploadFilename(""))
        assertEquals("book.epub", epubUploadFilename(".epub"))
        assertEquals("book.epub", epubUploadFilename("Ashfall.mobi"))
    }

    @Test
    fun `an absurdly long name is truncated with its extension intact`() {
        val name = "a".repeat(400) + ".epub"

        val filename = epubUploadFilename(name)

        assertTrue(filename, filename.length <= 120)
        assertTrue(filename, filename.endsWith(".epub"))
    }

    @Test
    fun `the body streams the file rather than holding it, and can be written twice`() {
        // Twice matters: OkHttp may re-send a body — a redirect, a retried connection — and a
        // one-shot source would send an empty second attempt rather than failing loudly.
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        var opened = 0
        val book = PickedEpub.Ready(
            filename = "Ashfall.epub",
            sizeBytes = bytes.size.toLong(),
            open = {
                opened++
                ByteArrayInputStream(bytes)
            },
        )
        val body = book.requestBody()

        val first = Buffer().also { body.writeTo(it) }
        val second = Buffer().also { body.writeTo(it) }

        assertEquals("application/epub+zip", body.contentType().toString())
        // Deliberately unknown, so OkHttp chunks it: the size a picker reported is good enough to
        // refuse an oversized book with and not good enough to promise on the wire.
        assertEquals(-1L, body.contentLength())
        assertEquals(2, opened)
        assertTrue(first.readByteArray().contentEquals(bytes))
        assertTrue(second.readByteArray().contentEquals(bytes))
    }
}
