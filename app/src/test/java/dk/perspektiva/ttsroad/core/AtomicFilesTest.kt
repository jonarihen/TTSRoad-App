package dk.perspektiva.ttsroad.core

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The write path every small JSON store in `filesDir` goes through (#317). */
class AtomicFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `round trips UTF-8 text`() {
        val file = AtomicFile(File(folder.root, "store.json"))
        file.writeUtf8("""{"title":"Æblegrød — 42"}""")
        assertEquals("""{"title":"Æblegrød — 42"}""", file.readUtf8OrNull())
    }

    @Test
    fun `reads null when nothing was written`() {
        assertNull(AtomicFile(File(folder.root, "absent.json")).readUtf8OrNull())
    }

    @Test
    fun `creates a missing parent directory`() {
        val file = AtomicFile(File(folder.root, "nested/store.json"))
        file.writeUtf8("[]")
        assertEquals("[]", file.readUtf8OrNull())
    }

    @Test
    fun `a failed write leaves the previous file readable`() {
        val base = File(folder.root, "store.json")
        val file = AtomicFile(base)
        file.writeUtf8("previous")
        // A directory that refuses new files makes startWrite fail, as a full disk would.
        assumeTrue(folder.root.setWritable(false))
        try {
            file.writeUtf8("next")
            fail("expected the write to fail")
        } catch (_: IOException) {
        } finally {
            folder.root.setWritable(true)
        }

        assertEquals("previous", file.readUtf8OrNull())
    }

    @Test
    fun `a read racing a write never discards it`() {
        val base = File(folder.root, "store.json")
        val writer = AtomicFile(base)
        // A second instance over the same path, as the widget and the service each build their own.
        val reader = AtomicFile(base)
        val done = AtomicBoolean(false)
        // Assertions inside the thread would only kill the thread, not the test.
        val torn = AtomicReference<String?>(null)
        val reads = thread {
            while (!done.get()) {
                val seen = reader.readUtf8OrNull()
                if (seen != null && !seen.startsWith("write-")) torn.compareAndSet(null, seen)
            }
        }
        repeat(500) { writer.writeUtf8("write-$it") }
        done.set(true)
        reads.join()

        assertNull(torn.get())

        assertEquals("write-499", reader.readUtf8OrNull())
    }

    @Test
    fun `a write interrupted before it was finished is never read`() {
        val base = File(folder.root, "store.json")
        val file = AtomicFile(base)
        file.writeUtf8("""{"complete":true}""")
        // What a process kill between startWrite and finishWrite leaves behind.
        File(folder.root, "store.json.new").writeText("""{"compl""")

        assertEquals("""{"complete":true}""", AtomicFile(base).readUtf8OrNull())
        assertFalse(File(folder.root, "store.json.new").exists())
    }

    @Test
    fun `delete also removes a temp file left by the writes before AtomicFile`() {
        val base = File(folder.root, "store.json")
        val file = AtomicFile(base)
        file.writeUtf8("current")
        // What a kill mid-write left behind under the hand-rolled temp-and-rename (#317).
        val legacyTemp = File(folder.root, "store.json.tmp").apply { writeText("previous account") }

        file.deleteUnderLock()

        assertFalse(base.exists())
        assertFalse(legacyTemp.exists())
    }
}
