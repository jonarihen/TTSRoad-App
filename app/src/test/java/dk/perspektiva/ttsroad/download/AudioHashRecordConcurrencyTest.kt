package dk.perspektiva.ttsroad.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two fictions' scans recording at the same moment must both survive (#325).
 *
 * Each `merge` only knows about one fiction, so an unguarded read-modify-write lets the later writer
 * replace the map the earlier one just extended — losing a hash means the next scan adopts whatever
 * the server has, and a re-convert in between is never reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioHashRecordConcurrencyTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `concurrent merges from different threads keep every hash, in memory and on disk`() {
        val record = AudioHashRecord(context)
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { worker ->
            pool.execute {
                start.await()
                repeat(perThread) { n ->
                    val chapterId = worker * 1_000 + n
                    record.merge(mapOf(chapterId to "hash-$chapterId"))
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(threads * perThread, record.current().size)
        assertEquals(record.current(), AudioHashRecord(context).current())
    }

    @Test
    fun `a forget racing merges removes only the chapter it names`() {
        val record = AudioHashRecord(context)
        record.merge(mapOf(1 to "one"))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        pool.execute {
            start.await()
            repeat(200) { record.merge(mapOf(100 + it to "h$it")) }
            done.countDown()
        }
        pool.execute {
            start.await()
            record.forget(1)
            done.countDown()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals((100 until 300).toSet(), record.current().keys)
    }
}
