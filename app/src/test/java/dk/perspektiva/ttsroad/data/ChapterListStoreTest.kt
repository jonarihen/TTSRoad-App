package dk.perspektiva.ttsroad.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #486: chapter lists survive a dead process, per fiction and per account. */
class ChapterListStoreTest {
    private val dir = Files.createTempDirectory("chapter-lists").toFile()
    private val store = ChapterListStore(File(dir, ChapterListStore.DirName))

    private fun response(vararg ids: Int) = ChaptersResponse(
        fiction = FictionSummary(id = 3, title = "Ashes"),
        total = ids.size,
        chapters = ids.map { ChapterSummary(id = it, fictionId = 3, title = "Ch $it", playable = true) },
        serverTime = "t1",
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved list reads back whole for its own account`() {
        store.write("a", 3, playableOnly = false, response = response(1, 2), savedAt = 5L)

        val stored = store.read("a", 3, playableOnly = false)

        assertEquals(response(1, 2), stored?.response)
        assertEquals(5L, stored?.savedAt)
    }

    @Test
    fun `another account, another fiction and the other variant see nothing`() {
        store.write("a", 3, playableOnly = false, response = response(1))

        assertNull(store.read("b", 3, playableOnly = false))
        assertNull(store.read(null, 3, playableOnly = false))
        assertNull(store.read("a", 4, playableOnly = false))
        assertNull(store.read("a", 3, playableOnly = true))
    }

    @Test
    fun `a former key of the same account still reads it`() {
        store.write("old", 3, playableOnly = true, response = response(1))

        assertNotNull(store.read("new", 3, playableOnly = true, formerKeys = listOf("old", "new")))
    }

    @Test
    fun `clear removes every list`() {
        store.write("a", 3, playableOnly = false, response = response(1))
        store.write("a", 3, playableOnly = true, response = response(1))

        store.clear()

        assertNull(store.read("a", 3, playableOnly = false))
        assertNull(store.read("a", 3, playableOnly = true))
    }
}
