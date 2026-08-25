package dk.perspektiva.ttsroad.download

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dk.perspektiva.ttsroad.data.AudioHash
import dk.perspektiva.ttsroad.data.AudioHashesResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The scan around [staleDownloadCheck]: what it asks for, what it records, and what it never does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleDownloadScannerTest {
    private lateinit var record: AudioHashRecord
    private var answer: AudioHashesResponse? = null
    private var requests = 0

    private fun scanner() = StaleDownloadScanner(
        record = record,
        fetchHashes = { requests++; answer },
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.listFiles()?.forEach { it.delete() }
        record = AudioHashRecord(context)
        answer = null
        requests = 0
    }

    @Test
    fun `the first scan adopts, the second catches a re-convert`() = runTest {
        val scanner = scanner()
        answer = response(AudioHash(chapterId = 1, audioSha256 = "first"))
        scanner.scan(fictionId = 9, downloaded = setOf(1))

        assertTrue("adoption must not report anything stale", scanner.staleChapters.value.isEmpty())
        assertEquals(mapOf(1 to "first"), record.current())

        answer = response(AudioHash(chapterId = 1, audioSha256 = "second"))
        scanner.scan(fictionId = 9, downloaded = setOf(1))

        assertEquals(setOf(1), scanner.staleChapters.value)
    }

    /**
     * Not an optimisation. Every fiction screen would otherwise spend a request to be told about
     * downloads it does not have, and the whole point of this endpoint over the chapter list is
     * that a freshness check should be nearly free.
     */
    @Test
    fun `a fiction with no downloads costs no request`() = runTest {
        scanner().scan(fictionId = 9, downloaded = emptySet())

        assertEquals(0, requests)
    }

    /**
     * A server that cannot answer — no capability, or the request failed — must leave the download
     * exactly as it was. The scan runs behind a screen the user opened to read a book on.
     */
    @Test
    fun `a server that cannot answer changes nothing`() = runTest {
        record.merge(mapOf(1 to "recorded"))
        val scanner = scanner()
        answer = null

        scanner.scan(fictionId = 9, downloaded = setOf(1))

        assertTrue(scanner.staleChapters.value.isEmpty())
        assertEquals(mapOf(1 to "recorded"), record.current())
    }

    /**
     * The verdict for a fiction is replaced by each scan, not merged into. Without that a chapter
     * that has been updated could never stop being stale, and the notice would nag forever.
     */
    @Test
    fun `a chapter stops being stale once it is fetched again`() = runTest {
        val scanner = scanner()
        record.merge(mapOf(1 to "old"))
        answer = response(AudioHash(chapterId = 1, audioSha256 = "new"))
        scanner.scan(fictionId = 9, downloaded = setOf(1))
        assertEquals(setOf(1), scanner.staleChapters.value)

        // What the row's action does: forget the old hash and queue the download again.
        scanner.markUpdating(listOf(1))
        assertTrue(scanner.staleChapters.value.isEmpty())

        scanner.scan(fictionId = 9, downloaded = setOf(1))

        assertTrue("a re-download must adopt, not re-report", scanner.staleChapters.value.isEmpty())
        assertEquals(mapOf(1 to "new"), record.current())
    }

    /** One fiction's scan must not clear another's verdict — the flow is keyed by chapter, not book. */
    @Test
    fun `scanning one fiction leaves another fiction's stale chapters alone`() = runTest {
        val scanner = scanner()
        record.merge(mapOf(1 to "old-a", 50 to "old-b"))

        answer = response(AudioHash(chapterId = 1, audioSha256 = "new-a"))
        scanner.scan(fictionId = 1, downloaded = setOf(1, 50))
        answer = response(AudioHash(chapterId = 50, audioSha256 = "new-b"))
        scanner.scan(fictionId = 2, downloaded = setOf(1, 50))

        assertEquals(setOf(1, 50), scanner.staleChapters.value)
    }

    @Test
    fun `deleting a download forgets what it held`() = runTest {
        val scanner = scanner()
        record.merge(mapOf(1 to "old"))
        answer = response(AudioHash(chapterId = 1, audioSha256 = "new"))
        scanner.scan(fictionId = 9, downloaded = setOf(1))

        scanner.forget(1)

        assertTrue(scanner.staleChapters.value.isEmpty())
        assertTrue(record.current().isEmpty())
    }

    /**
     * A record for bytes that are not on disk is worse than no record: the next download of that
     * chapter would be compared against the *previous* copy's hash, and reported stale or fresh on
     * no evidence at all. Most deletes forget their own hash, but a download can leave the index
     * without passing through that path, so the scan prunes what it can see.
     */
    @Test
    fun `a hash for a chapter that is no longer downloaded is dropped`() = runTest {
        val scanner = scanner()
        record.merge(mapOf(1 to "kept", 2 to "gone"))
        answer = response(AudioHash(chapterId = 1, audioSha256 = "kept"))

        scanner.scan(fictionId = 9, downloaded = setOf(1))

        assertEquals(mapOf(1 to "kept"), record.current())
    }

    @Test
    fun `the record survives being rebuilt from disk`() = runTest {
        record.merge(mapOf(1 to "a", 2 to "b"))

        val reopened = AudioHashRecord(ApplicationProvider.getApplicationContext())

        assertEquals(mapOf(1 to "a", 2 to "b"), reopened.current())
    }

    private fun response(vararg chapters: AudioHash) = AudioHashesResponse(
        fictionId = 9,
        total = chapters.size,
        chapters = chapters.toList(),
    )
}
