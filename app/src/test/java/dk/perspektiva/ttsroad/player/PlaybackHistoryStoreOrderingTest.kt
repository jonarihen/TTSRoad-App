package dk.perspektiva.ttsroad.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A [HistoryPersistence] that records the order of what reached it, and can be held open.
 *
 * Holding a write open is what makes the interleaving deterministic: the tests below do not race
 * two IO threads and hope, they stop one persist mid-flight, run another mutation to completion,
 * and then let the first one continue.
 */
private class RecordingPersistence(initial: String? = null) : HistoryPersistence {
    private var content: String? = initial
    val operations = mutableListOf<String>()
    var onWrite: (() -> Unit)? = null

    override fun read(): String? = content

    override fun write(json: String) {
        onWrite?.invoke()
        operations += "write"
        content = json
    }

    override fun delete() {
        operations += "delete"
        content = null
    }

    fun stored(): String? = content
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackHistoryStoreOrderingTest {

    private fun store(persistence: HistoryPersistence, scope: CoroutineScope) =
        PlaybackHistoryStore(persistence, scope)

    @Test
    fun `a persist that starts late writes the newest history, not the one it was queued with`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)

        // Both mutations are ordered before either persist runs — exactly what two ticks arriving
        // while the IO dispatcher is busy looks like.
        store.record(1000L, "chapter:1", 10, 1, "C1", "F", 1_000L)
        store.record(20_000L, "chapter:2", 10, 2, "C2", "F", 2_000L)
        advanceUntilIdle()

        val written = persistence.stored()
        assertTrue("the newer chapter must survive", written!!.contains("chapter:2"))
        assertEquals(2, store.snapshots.value.size)
        // The second persist has nothing left to add, so it must not rewrite an older list.
        assertEquals(listOf("write"), persistence.operations)
    }

    @Test
    fun `a record after a clear is not deleted by the clear's own persist`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)
        store.record(1000L, "chapter:1", 10, 1, "C1", "F", 1_000L)
        advanceUntilIdle()
        persistence.operations.clear()

        // The clear's delete and the record's write are ordered; whichever job runs, the file must
        // end up holding the record, because that is the newer intent.
        store.clear()
        store.record(60_000L, "chapter:9", 10, 9, "C9", "F", 9_000L)
        advanceUntilIdle()

        val written = persistence.stored()
        assertTrue("the post-clear record must be on disk", written!!.contains("chapter:9"))
        assertEquals(1, store.snapshots.value.size)
        assertEquals("delete must not outlive the newer write", listOf("write"), persistence.operations)
    }

    @Test
    fun `a clear after a record deletes rather than leaving the record behind`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)

        store.record(1000L, "chapter:1", 10, 1, "C1", "F", 1_000L)
        store.clear()
        advanceUntilIdle()

        assertNull("the newest intent was the clear", persistence.stored())
        assertEquals(emptyList<HistorySnapshot>(), store.snapshots.value)
        assertEquals(listOf("delete"), persistence.operations)
    }

    @Test
    fun `a write held open cannot overwrite the state a later clear established`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)
        lateinit var clearDuringWrite: () -> Unit
        persistence.onWrite = {
            // Runs inside the first persist, standing in for a slow disk: the user signs out while
            // the bytes for the previous state are on their way down.
            clearDuringWrite()
        }
        clearDuringWrite = {
            persistence.onWrite = null
            store.clear()
        }

        store.record(1000L, "chapter:1", 10, 1, "C1", "F", 1_000L)
        advanceUntilIdle()

        assertNull("the clear is the newest intent and must win", persistence.stored())
        assertEquals(listOf("write", "delete"), persistence.operations)
        assertEquals(emptyList<HistorySnapshot>(), store.snapshots.value)
    }

    @Test
    fun `concurrent recorders leave the file agreeing with the published state`() = runTest {
        val persistence = RecordingPersistence()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(persistence, CoroutineScope(dispatcher))

        (0 until 25).map { index ->
            async(Dispatchers.Default) {
                store.record(
                    timestamp = index * 10_000L,
                    mediaId = "chapter:$index",
                    fictionId = 10,
                    chapterId = index,
                    title = "C$index",
                    fictionTitle = "F",
                    positionMs = index * 100L,
                )
            }
        }.awaitAll()
        advanceUntilIdle()

        val published = store.snapshots.value
        assertEquals(25, published.size)
        // The last thing written must be the state the store reports — not some earlier capture.
        val written = persistence.stored()
        for (snapshot in published) {
            assertTrue(
                "the persisted history must contain ${snapshot.mediaId}",
                written!!.contains(snapshot.mediaId),
            )
        }
    }

    @Test
    fun `an existing history is loaded and then extended rather than replaced wholesale`() = runTest {
        val persistence = RecordingPersistence(
            """[{"timestamp":1,"mediaId":"chapter:1","fictionId":10,"chapterId":1,"title":"C1","fictionTitle":"F","positionMs":5}]""",
        )
        val store = store(persistence, this)

        assertEquals(1, store.snapshots.value.size)

        store.record(100_000L, "chapter:2", 10, 2, "C2", "F", 2_000L)
        advanceUntilIdle()

        assertEquals(2, store.snapshots.value.size)
        assertTrue(persistence.stored()!!.contains("chapter:1"))
        assertTrue(persistence.stored()!!.contains("chapter:2"))
    }
}
