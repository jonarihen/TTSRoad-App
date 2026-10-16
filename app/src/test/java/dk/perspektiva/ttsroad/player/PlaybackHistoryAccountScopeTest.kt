package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.SessionState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jump-back history belongs to the server and account that recorded it (#368): a snapshot names a
 * fiction id, and the same id on another server is a different book or none.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackHistoryAccountScopeTest {
    private class MemoryPersistence(var content: String? = null) : HistoryPersistence {
        override fun read(): String? = content
        override fun write(json: String) {
            content = json
        }
        override fun delete() {
            content = null
        }
    }

    private val home = SessionState(serverUrl = "https://home.example", token = "a", username = "anna")
    private val work = SessionState(serverUrl = "https://work.example", token = "b", username = "anna")

    private fun PlaybackHistoryStore.recordChapter(chapterId: Int, at: Long) = record(
        timestamp = at,
        mediaId = "chapter:$chapterId",
        fictionId = 7,
        chapterId = chapterId,
        title = "Chapter $chapterId",
        fictionTitle = "Book",
        positionMs = 1_000L,
    )

    @Test
    fun `the key follows server and username, not the token, and is null signed out`() {
        assertEquals(historyAccountKey(home), historyAccountKey(home.copy(token = "renewed")))
        assertEquals(historyAccountKey(home), historyAccountKey(home.copy(serverUrl = "https://home.example/")))
        assertNotEquals(historyAccountKey(home), historyAccountKey(work))
        assertNotEquals(historyAccountKey(home), historyAccountKey(home.copy(username = "bo")))
        assertNull(historyAccountKey(SessionState()))
    }

    @Test
    fun `switching to another account drops the previous account's snapshots, on disk too`() = runTest {
        val persistence = MemoryPersistence()
        val store = PlaybackHistoryStore(persistence, this)
        store.bindAccount(historyAccountKey(home))
        store.recordChapter(1, at = 10_000L)
        store.flush()

        store.bindAccount(historyAccountKey(work))
        advanceUntilIdle()

        assertTrue(store.snapshots.value.isEmpty())
        assertNull(persistence.content)
        assertTrue(PlaybackHistoryStore(MemoryPersistence(persistence.content), this).snapshots.value.isEmpty())
    }

    @Test
    fun `signing out and back in to the same account keeps the history`() = runTest {
        val store = PlaybackHistoryStore(MemoryPersistence(), this)
        store.bindAccount(historyAccountKey(home))
        store.recordChapter(1, at = 10_000L)

        store.bindAccount(null)
        store.bindAccount(historyAccountKey(home.copy(token = "after-expiry")))
        advanceUntilIdle()

        assertEquals(listOf(1), store.snapshots.value.map { it.chapterId })
    }

    @Test
    fun `history from before the key existed is adopted by the first account, then scoped`() = runTest {
        val legacy = """[{"timestamp":1,"mediaId":"chapter:3","fictionId":7,"chapterId":3,""" +
            """"title":"Three","fictionTitle":"Book","positionMs":5}]"""
        val persistence = MemoryPersistence(legacy)
        val store = PlaybackHistoryStore(persistence, this)
        assertNull(store.snapshots.value.single().account)

        store.bindAccount(historyAccountKey(home))
        advanceUntilIdle()
        assertEquals(historyAccountKey(home), store.snapshots.value.single().account)
        assertFalse(persistence.content.isNullOrEmpty())

        store.bindAccount(historyAccountKey(work))
        advanceUntilIdle()
        assertTrue(store.snapshots.value.isEmpty())
    }

    @Test
    fun `new snapshots are stamped with the bound account`() = runTest {
        val store = PlaybackHistoryStore(MemoryPersistence(), this)
        store.bindAccount(historyAccountKey(work))
        store.recordChapter(2, at = 10_000L)
        assertEquals(historyAccountKey(work), store.snapshots.value.single().account)
        store.clear()
        advanceUntilIdle()
    }
}
