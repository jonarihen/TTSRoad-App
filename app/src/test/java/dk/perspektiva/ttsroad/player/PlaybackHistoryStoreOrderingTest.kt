package dk.perspektiva.ttsroad.player

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun `a full history batches 240 playback ticks without postponing writes indefinitely`() = runTest {
        val initial = (0 until 2000).joinToString(prefix = "[", postfix = "]") { index ->
            """{"timestamp":${index * 15_000L},"mediaId":"chapter:1","fictionId":10,"chapterId":1,"title":"C1","fictionTitle":"F","positionMs":${index * 15_000L}}"""
        }
        val persistence = RecordingPersistence(initial)
        val store = store(persistence, this)
        assertEquals(2000, store.snapshots.value.size)

        repeat(240) { tick ->
            store.record(
                timestamp = 30_000_000L + tick * 15_000L,
                mediaId = "chapter:1",
                fictionId = 10,
                chapterId = 1,
                title = "C1",
                fictionTitle = "F",
                positionMs = tick * 15_000L,
            )
            runCurrent()
            advanceTimeBy(15_000L)
            runCurrent()
        }

        assertEquals(12, persistence.operations.count { it == "write" })
        assertEquals(2000, store.snapshots.value.size)
        val restarted = store(persistence, this)
        assertEquals(store.snapshots.value, restarted.snapshots.value)
        assertEquals(239 * 15_000L, restarted.snapshots.value.last().positionMs)
    }

    @Test
    fun `a stop flush commits the latest position before restart without waiting for the batch`() = runTest {
        val file = File(temporaryFolder.root, "playback_history.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recordingScope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = store(FileHistoryPersistence(file), recordingScope)
        store.record(15_000L, "chapter:1", 10, 1, "C1", "F", 15_000L)
        runCurrent()
        advanceTimeBy(15_000L)
        store.record(30_000L, "chapter:1", 10, 1, "C1", "F", 30_000L)
        assertNull(FileHistoryPersistence(file).read())
        store.record(31_000L, "chapter:1", 10, 1, "C1", "F", 31_000L)

        store.flush()
        recordingScope.cancel()
        val restarted = store(FileHistoryPersistence(file), this)

        assertEquals(store.snapshots.value, restarted.snapshots.value)
        assertEquals(31_000L, restarted.snapshots.value.last().positionMs)
        advanceUntilIdle()
        assertEquals(restarted.snapshots.value, store(FileHistoryPersistence(file), this).snapshots.value)
    }

    @Test
    fun `flush orders a clear then record ahead of every delayed persist`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)
        store.record(15_000L, "chapter:1", 10, 1, "C1", "F", 15_000L)
        runCurrent()
        store.flush()
        persistence.operations.clear()

        store.clear()
        store.record(30_000L, "chapter:9", 10, 9, "C9", "F", 9_000L)
        store.flush()
        advanceUntilIdle()

        assertEquals(listOf("write"), persistence.operations)
        assertEquals(store.snapshots.value, store(persistence, this).snapshots.value)
    }

    @Test
    fun `flush skips unchanged history and schedules the next batch after playback resumes`() = runTest {
        val persistence = RecordingPersistence()
        val store = store(persistence, this)
        store.record(15_000L, "chapter:1", 10, 1, "C1", "F", 15_000L)
        runCurrent()
        store.flush()
        store.flush()
        advanceUntilIdle()
        assertEquals(listOf("write"), persistence.operations)

        store.record(30_000L, "chapter:1", 10, 1, "C1", "F", 30_000L)
        advanceUntilIdle()

        assertEquals(listOf("write", "write"), persistence.operations)
        assertEquals(store.snapshots.value, store(persistence, this).snapshots.value)
    }
}
