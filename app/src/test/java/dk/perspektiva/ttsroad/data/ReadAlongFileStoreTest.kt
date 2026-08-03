package dk.perspektiva.ttsroad.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The on-disk half of the read-along cache — what makes a chapter you have already opened readable
 * with the phone in flight mode.
 *
 * Uses a real directory rather than a mock: the failures worth catching here are the filesystem
 * ones (a truncated write, a file from an older build), and a mock cannot produce those.
 */
class ReadAlongFileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(maxEntries: Int = 40) = ReadAlongFileStore(folder.root, maxEntries)

    private fun entry(text: String, etag: String? = "\"v1\"") = CachedReadAlong(
        etag = etag,
        response = ReadAlongResponse(
            chapter = ReadAlongChapter(id = 10, fictionId = 1, title = "Chapter 1", audioDuration = 60.0),
            text = text,
            paragraphs = listOf(listOf(0.0, text.length.toDouble())),
            cues = listOf(listOf(0.0, 3.0, 0.0)),
        ),
    )

    @Test
    fun `nothing is returned for a chapter that was never written`() {
        assertNull(store().read(chapterId = 10))
    }

    @Test
    fun `a written chapter round-trips through the filesystem`() {
        val written = entry("The knight rode north.")
        store().write(chapterId = 10, entry = written)

        // A separate instance, as after a process restart.
        val read = store().read(chapterId = 10)

        assertNotNull(read)
        assertEquals("\"v1\"", read!!.etag)
        assertEquals("The knight rode north.", read.response.text)
        assertEquals(1, read.response.cues.size)
        assertEquals(listOf(0.0, 3.0, 0.0), read.response.cues[0])
    }

    @Test
    fun `a restored entry rebuilds the same document`() {
        store().write(chapterId = 10, entry = entry("The knight rode north."))

        val document = ReadAlongDocument.from(store().read(chapterId = 10)!!.response)

        assertEquals(10, document.chapterId)
        assertEquals("The knight rode north.", document.text)
        assertEquals(TextSpan(0, 3), document.cues[0].span)
        assertTrue(document.hasTimings)
    }

    @Test
    fun `writing the same chapter twice replaces it rather than accumulating`() {
        val store = store()
        store.write(chapterId = 10, entry = entry("First."))
        store.write(chapterId = 10, entry = entry("Second.", etag = "\"v2\""))

        assertEquals("Second.", store.read(chapterId = 10)!!.response.text)
        assertEquals("\"v2\"", store.read(chapterId = 10)!!.etag)
    }

    @Test
    fun `an entry with no ETag is still usable offline`() {
        store().write(chapterId = 10, entry = entry("No tag.", etag = null))

        val read = store().read(chapterId = 10)

        assertNull(read!!.etag)
        assertEquals("No tag.", read.response.text)
    }

    @Test
    fun `a corrupted file reads as a miss instead of taking the reader down`() {
        val store = store()
        store.write(chapterId = 10, entry = entry("The knight rode north."))
        folder.root.listFiles()!!.forEach { it.writeText("{ this is not json") }

        assertNull(store.read(chapterId = 10))
    }

    @Test
    fun `a file left by an unrelated build is ignored`() {
        File(folder.root, "notes.txt").writeText("hello")

        assertNull(store().read(chapterId = 10))
    }

    @Test
    fun `chapters are cached independently`() {
        val store = store()
        store.write(chapterId = 10, entry = entry("Ten."))
        store.write(chapterId = 11, entry = entry("Eleven."))

        assertEquals("Ten.", store.read(chapterId = 10)!!.response.text)
        assertEquals("Eleven.", store.read(chapterId = 11)!!.response.text)
    }

    @Test
    fun `the cache is bounded, so a long series does not fill the phone`() {
        val store = store(maxEntries = 3)

        for (chapterId in 1..6) {
            store.write(chapterId = chapterId, entry = entry("Chapter $chapterId."))
        }

        assertTrue("kept ${store.size()} entries", store.size() <= 3)
        assertNotNull("the most recent chapter must survive eviction", store.read(chapterId = 6))
    }

    @Test
    fun `clearing removes every cached chapter`() {
        val store = store()
        store.write(chapterId = 10, entry = entry("Ten."))
        store.write(chapterId = 11, entry = entry("Eleven."))

        store.clear()

        assertNull(store.read(chapterId = 10))
        assertNull(store.read(chapterId = 11))
        assertEquals(0, store.size())
    }

    @Test
    fun `a store pointed at a directory that does not exist yet creates it on write`() {
        val nested = File(folder.root, "does/not/exist")
        val store = ReadAlongFileStore(nested)

        store.write(chapterId = 10, entry = entry("Made it."))

        assertTrue(nested.isDirectory)
        assertEquals("Made it.", store.read(chapterId = 10)!!.response.text)
    }

    @Test
    fun `a store that cannot write does not throw at the reader`() {
        // filesDir can be unavailable during a restore or an upgrade; the reader should degrade to
        // online-only, not crash on the way into a chapter.
        val blocked = File(folder.root, "blocked")
        blocked.writeText("I am a file, not a directory")
        val store = ReadAlongFileStore(blocked)

        store.write(chapterId = 10, entry = entry("Nowhere to go."))

        assertNull(store.read(chapterId = 10))
        assertFalse(blocked.isDirectory)
    }
}
