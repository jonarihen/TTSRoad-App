package dk.perspektiva.ttsroad.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The queue that makes an offline position survive.
 *
 * Before it existed the write was a bare `runCatching` around the post: a position recorded with no
 * connection was dropped on the floor. The cases here are the ones that decide whether the backlog
 * is correct — coalescing, ordering, the cap, and not discarding a newer entry because an older
 * copy of the same chapter was acknowledged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingProgressStoreTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        file = File(context.filesDir, "pending_progress.json")
        if (file.exists()) file.delete()
    }

    private fun store(clock: () -> Long = System::currentTimeMillis) =
        PendingProgressStore(RuntimeEnvironment.getApplication(), clock)

    @Test
    fun `a recorded position is kept`() {
        val store = store { 1_700_000_000_000L }
        store.record(fictionId = 1, chapterId = 7, positionSeconds = 12.5, isPlayed = false)

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals(7, pending[0].chapterId)
        assertEquals(12.5, pending[0].positionSeconds, 0.001)
    }

    @Test
    fun `later ticks on the same chapter replace the earlier one`() {
        // Fifteen-second ticks over an eight-hour night would otherwise be two thousand entries,
        // all but the last of them superseded.
        var now = 1_000L
        val store = store { now }
        store.record(1, 7, 10.0, false)
        now += 15_000
        store.record(1, 7, 25.0, false)
        now += 15_000
        store.record(1, 7, 40.0, false)

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals(40.0, pending[0].positionSeconds, 0.001)
    }

    @Test
    fun `different chapters are kept separately`() {
        val store = store()
        store.record(1, 7, 10.0, false)
        store.record(1, 8, 20.0, false)
        store.record(2, 9, 30.0, false)

        assertEquals(3, store.pending().size)
    }

    @Test
    fun `a replaced entry moves to the back, so order is by recency`() {
        var now = 1_000L
        val store = store { now }
        store.record(1, 7, 10.0, false)
        now += 1_000
        store.record(1, 8, 20.0, false)
        now += 1_000
        store.record(1, 7, 30.0, false)

        assertEquals(listOf(8, 7), store.pending().map { it.chapterId })
    }

    @Test
    fun `the stamp is the moment of recording, not of sending`() {
        // The whole ordering mechanism rests on this: a phone that listened offline for two hours
        // must be ordered by when the listening happened, not by when it reconnected.
        val store = store { 1_700_000_000_000L }
        val entry = store.record(1, 7, 10.0, false)

        assertEquals(1_700_000_000_000L, entry.recordedAtMillis)
        assertEquals(iso8601Utc(1_700_000_000_000L), entry.clientUpdatedAt)
    }

    @Test
    fun `stamps are ISO-8601 in UTC with an explicit Z`() {
        // The backend parses this string. A local-zone stamp would be silently wrong by hours.
        assertEquals("2023-11-14T22:13:20Z", iso8601Utc(1_700_000_000_000L))
        assertEquals("1970-01-01T00:00:00Z", iso8601Utc(0L))
    }

    @Test
    fun `stamps carry at most three fractional-second digits`() {
        // The trap in issue #72. `datetime.fromisoformat` before Python 3.11 accepts only 3 or 6
        // fractional digits, and the backend's floor is 3.10; `Instant.now().toString()` emits as
        // many as the clock has, which on Android is frequently 9. A stamp the server cannot parse
        // is rejected as `invalid_client_updated_at`, and a rejected item is dropped rather than
        // retried — a silently lost position, which is what /playback/sync exists to prevent.
        //
        // It would also not fail against a 3.11+ deployment, so this is pinned here rather than
        // discovered in the field.
        assertEquals("2023-11-14T22:13:20.123Z", iso8601Utc(1_700_000_000_123L))
        assertEquals("2023-11-14T22:13:20.100Z", iso8601Utc(1_700_000_000_100L))
    }

    @Test
    fun `the real clock never produces an unparseable stamp`() {
        // Over repeated calls, because the digit count varies with what the clock happens to read
        // and a single sample can pass by luck. The desktop client pins the same shape.
        val parseable = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$""")
        repeat(200) {
            val stamp = iso8601Utc(System.currentTimeMillis())
            assertTrue("unparseable stamp: $stamp", parseable.matches(stamp))
        }
    }

    @Test
    fun `a recorded entry's stamp keeps sub-second ordering`() {
        // Seconds precision would round two positions in the same second to a tie. The server
        // applies last-write-wins on this field, and the web client stamps in milliseconds, so a
        // phone truncating to seconds could stamp a genuinely newer position as older than a
        // browser write from earlier in the same second and lose to it.
        val store = store { 1_700_000_000_900L }
        val entry = store.record(1, 7, 10.0, false)

        assertEquals("2023-11-14T22:13:20.900Z", entry.clientUpdatedAt)
    }

    @Test
    fun `the queue is capped, dropping the oldest first`() {
        val store = store()
        for (chapter in 1..(MaxPendingProgress + 10)) {
            store.record(1, chapter, 1.0, false)
        }

        val pending = store.pending()
        assertEquals(MaxPendingProgress, pending.size)
        // The first ten chapters fell off the front; the newest are all still there.
        assertEquals(11, pending.first().chapterId)
        assertEquals(MaxPendingProgress + 10, pending.last().chapterId)
    }

    @Test
    fun `the cap matches the server's own batch limit`() {
        // A full queue should still be exactly one batch.
        assertEquals(500, MaxPendingProgress)
    }

    @Test
    fun `resolving removes only what was acknowledged`() {
        val store = store()
        store.record(1, 7, 10.0, false)
        val eight = store.record(1, 8, 20.0, false)

        store.resolve(listOf(eight))

        assertEquals(listOf(7), store.pending().map { it.chapterId })
    }

    @Test
    fun `a tick that landed during the flush is not discarded`() {
        // The dangerous case: chapter 7 is sent, a new position for chapter 7 is recorded while
        // the request is in flight, and the response acknowledges the *older* one. Removing by
        // chapter id alone would throw away the newer position.
        var now = 1_000L
        val store = store { now }
        val inFlight = store.record(1, 7, 10.0, false)
        now += 5_000
        store.record(1, 7, 99.0, false)

        store.resolve(listOf(inFlight))

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals(99.0, pending[0].positionSeconds, 0.001)
    }

    @Test
    fun `the backlog survives the process being killed`() {
        // This queue exists for the case where the phone is offline and stays offline, which is
        // also when the process is most likely to be killed before it ever reconnects.
        store().record(1, 7, 42.0, false)

        val reopened = store()
        assertEquals(1, reopened.pending().size)
        assertEquals(42.0, reopened.pending()[0].positionSeconds, 0.001)
    }

    @Test
    fun `a corrupt file reads as an empty queue rather than throwing`() {
        file.writeText("{ not json at all")

        val store = store()
        assertTrue(store.isEmpty())
        // And it recovers: a fresh record still works.
        store.record(1, 7, 5.0, false)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a negative position cannot be queued`() {
        val store = store()
        val entry = store.record(1, 7, -5.0, false)

        assertEquals(0.0, entry.positionSeconds, 0.001)
    }

    @Test
    fun `clear empties the queue and the file`() {
        val store = store()
        store.record(1, 7, 10.0, false)
        store.clear()

        assertTrue(store.isEmpty())
        assertFalse(file.exists())
    }
}
