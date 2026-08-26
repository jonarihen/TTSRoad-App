package dk.perspektiva.ttsroad.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/**
 * What comes back from the document picker, and what is made of it before anything is uploaded.
 *
 * A picked URI is not a file: it is a handle onto a content provider that may be a download, a file
 * manager over SMB, or a cloud drive that has not fetched the document yet. Every column of
 * `OpenableColumns` is optional, the provider can go away between the picker and here, and none of
 * that is exotic enough to deserve a crash in front of someone who has just chosen a book.
 *
 * The size check is the point of the whole exercise: the server publishes `max_epub_bytes` so a
 * client can refuse before spending a mobile connection, and these are the tests that say it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubPickerTest {
    private lateinit var resolver: ContentResolver

    private val uri: Uri = Uri.parse("content://documents/book-1")

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    }

    private fun provider(displayName: String?, size: Long?, bytes: ByteArray = ByteArray(8)) {
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.setResults(arrayOf(arrayOf<Any?>(displayName, size)))
        shadowOf(resolver).setCursor(uri, cursor)
        shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
    }

    @Test
    fun `a book under the ceiling comes back ready, with its own name`() {
        val bytes = ByteArray(2048) { it.toByte() }
        provider("Ashfall.epub", bytes.size.toLong(), bytes)

        val picked = readPickedEpub(resolver, uri, DefaultMaxEpubBytes)

        assertTrue(picked.toString(), picked is PickedEpub.Ready)
        val ready = picked as PickedEpub.Ready
        assertEquals("Ashfall.epub", ready.filename)
        assertEquals(bytes.size.toLong(), ready.sizeBytes)
        // The stream is opened on demand rather than held: the book never exists in the heap.
        assertTrue(ready.open().use { it.readBytes() }.contentEquals(bytes))
    }

    @Test
    fun `an oversized book is refused here, before a byte leaves the phone`() {
        provider("Ashfall.epub", 8L * 1024 * 1024)

        val picked = readPickedEpub(resolver, uri, maxBytes = 4L * 1024 * 1024)

        val rejected = picked as PickedEpub.Rejected
        assertTrue(rejected.message, rejected.message.contains("4 MB"))
    }

    @Test
    fun `something that is not an epub is refused on its name, which is what the server reads`() {
        provider("Ashfall.mobi", 2048L)

        val picked = readPickedEpub(resolver, uri)

        assertTrue(picked is PickedEpub.Rejected)
    }

    @Test
    fun `a provider that will not say how big the document is does not block the upload`() {
        // Perfectly ordinary for a cloud-backed document. The server enforces its own ceiling, and
        // refusing here would stop an upload that would have worked.
        provider("Ashfall.epub", null)

        val picked = readPickedEpub(resolver, uri)

        assertTrue(picked is PickedEpub.Ready)
        assertNull((picked as PickedEpub.Ready).sizeBytes)
    }

    @Test
    fun `a provider that answers nothing at all falls back to the uri's own last segment`() {
        // No cursor registered, so `query` answers null — which a provider is allowed to do. The
        // last path segment is right often enough to be worth trying, and is checked by the same
        // rule either way, so a URI that carries no usable name is still refused.
        val named = Uri.parse("content://documents/Ashfall.epub")
        val unnamed = Uri.parse("content://documents/12345")
        shadowOf(resolver).registerInputStreamSupplier(named) { ByteArrayInputStream(ByteArray(4)) }
        shadowOf(resolver).registerInputStreamSupplier(unnamed) { ByteArrayInputStream(ByteArray(4)) }

        assertEquals(
            "Ashfall.epub",
            (readPickedEpub(resolver, named) as PickedEpub.Ready).filename,
        )
        assertTrue(readPickedEpub(resolver, unnamed) is PickedEpub.Rejected)
    }

    @Test
    fun `a document that has gone missing is a message, not an exception`() {
        provider("Ashfall.epub", 2048L)
        // The document is described but cannot be opened — a permission that lapsed on rotation, a
        // provider that has gone away, a file deleted between the picker and here.
        shadowOf(resolver).registerInputStreamSupplier(uri) {
            throw FileNotFoundException("No such document")
        }

        val picked = readPickedEpub(resolver, uri)

        assertTrue(picked is PickedEpub.Rejected)
    }
}
