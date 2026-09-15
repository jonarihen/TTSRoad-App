package dk.perspektiva.ttsroad.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what reached the disk, and in what order, without needing a filesystem. */
private class RecordingSnapshotPersistence(initial: String? = null) : SnapshotPersistence {
    private var content: String? = initial
    val operations = mutableListOf<String>()

    override fun read(): String? = content

    override fun write(json: String) {
        operations += "write"
        content = json
    }

    override fun delete() {
        operations += "delete"
        content = null
    }
}

/**
 * The ordering the widget's note depends on.
 *
 * The service captures on the main thread and persists off it, from five different paths. These
 * tests drive the interleavings explicitly — a persist is applied out of capture order on purpose —
 * rather than launching coroutines and hoping a race shows up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingStoreOrderingTest {

    private val playing = NowPlayingSnapshot(
        mediaId = "chapter:10",
        fictionId = 1,
        chapterId = 10,
        chapterTitle = "Chapter 4",
        fictionTitle = "A Test Serial",
        positionMs = 90_000L,
        durationMs = 600_000L,
        isPlaying = true,
        speed = 1f,
        updatedAt = 1_000L,
    )

    @Test
    fun `a publish that reaches the disk late cannot undo a newer one`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)

        // Capture order: the tick first, then the pause. Persist order: reversed, which is what a
        // busy IO dispatcher produces.
        val tick = store.nextGeneration()
        val paused = store.nextGeneration()
        store.publish(paused, playing.copy(isPlaying = false, updatedAt = 2_000L), stoppedAt = 2_000L)
        store.publish(tick, playing, stoppedAt = 1_000L)

        val read = store.read()!!
        assertFalse("a pause must not be overwritten by a tick captured before it", read.isPlaying)
        assertEquals(2_000L, read.updatedAt)
        assertEquals(listOf("write"), persistence.operations)
    }

    @Test
    fun `a publish in flight at sign-out cannot put the book back on the home screen`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)
        val tick = store.nextGeneration()
        val signOut = store.nextGeneration()

        store.clearAt(signOut)
        store.publish(tick, playing, stoppedAt = 1_000L)

        assertNull("the previous account's chapter must stay gone", store.read())
        assertEquals(listOf("delete"), persistence.operations)
    }

    @Test
    fun `a sign-out still clears a note written before it`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)

        store.publish(store.nextGeneration(), playing, stoppedAt = 1_000L)
        store.clearAt(store.nextGeneration())

        assertNull(store.read())
        assertEquals(listOf("write", "delete"), persistence.operations)
    }

    @Test
    fun `an empty queue keeps the last chapter but marks it stopped`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)
        store.publish(store.nextGeneration(), playing, stoppedAt = 1_000L)

        store.publish(store.nextGeneration(), null, stoppedAt = 5_000L)

        val read = store.read()!!
        assertEquals("chapter:10", read.mediaId)
        assertFalse(read.isPlaying)
        assertEquals(5_000L, read.updatedAt)
    }

    @Test
    fun `an empty-queue publish that arrives after a sign-out does not resurrect the record`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)
        store.publish(store.nextGeneration(), playing, stoppedAt = 1_000L)

        val transition = store.nextGeneration()
        store.clearAt(store.nextGeneration())
        // The read-modify-write of "keep the last chapter, mark it stopped" is exactly the path
        // that could re-create a file sign-out had just deleted.
        store.publish(transition, null, stoppedAt = 6_000L)

        assertNull(store.read())
    }

    @Test
    fun `concurrent publishes settle on the newest capture`() = runTest {
        val persistence = RecordingSnapshotPersistence()
        val store = NowPlayingStore(persistence)

        // Generations are claimed in order, as the service does on the main thread; the persists
        // then run concurrently and may arrive in any order at all.
        val captures = (1..30).map { index ->
            index to store.nextGeneration()
        }
        captures.shuffled().map { (index, generation) ->
            async(Dispatchers.Default) {
                store.publish(
                    generation,
                    playing.copy(positionMs = index * 1_000L, updatedAt = index * 1_000L),
                    stoppedAt = index * 1_000L,
                )
            }
        }.awaitAll()

        val read = store.read()!!
        assertEquals("the last capture must be what the widget draws", 30_000L, read.updatedAt)
        assertEquals(30_000L, read.positionMs)
        assertTrue(persistence.operations.isNotEmpty())
    }

    @Test
    fun `a half-written file is discarded rather than drawn`() = runTest {
        val store = NowPlayingStore(RecordingSnapshotPersistence("{ this is not json"))

        assertNull(store.read())
    }
}
