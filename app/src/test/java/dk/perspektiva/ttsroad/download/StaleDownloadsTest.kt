package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #109: a downloaded chapter could be the *old* narration forever, and nothing said so.
 *
 * Two of the three rules here exist to stop the fix being worse than the bug it fixes. Marking a
 * download stale costs the user a re-download over whatever connection they are on, so a false
 * positive is not a cosmetic error — and both of the ways to produce one at scale are cases this
 * feature meets on its very first run.
 */
class StaleDownloadsTest {
    @Test
    fun `a hash that changed since the download is stale`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1),
            recorded = mapOf(1 to "old"),
            server = listOf(AudioHash(chapterId = 1, audioSha256 = "new")),
        )

        assertEquals(setOf(1), check.stale)
        assertTrue(check.adopt.isEmpty())
    }

    @Test
    fun `a hash that still matches is left alone`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1),
            recorded = mapOf(1 to "same"),
            server = listOf(AudioHash(chapterId = 1, audioSha256 = "same")),
        )

        assertTrue(check.isEmpty)
    }

    /**
     * The first way to get this catastrophically wrong. Every download made before this shipped has
     * no recorded hash, so reading "unknown to us" as "changed" would re-download an entire offline
     * library on upgrade, over mobile data, to replace files that are almost certainly correct.
     */
    @Test
    fun `a download with no recorded hash is adopted, never called stale`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1, 2),
            recorded = emptyMap(),
            server = listOf(
                AudioHash(chapterId = 1, audioSha256 = "a"),
                AudioHash(chapterId = 2, audioSha256 = "b"),
            ),
        )

        assertTrue(check.stale.isEmpty())
        assertEquals(mapOf(1 to "a", 2 to "b"), check.adopt)
    }

    /**
     * The second. The backend sends null for chapters converted before hashing shipped and for rows
     * its startup backfill has not reached yet, and its docstring is explicit that this means
     * *unknown*. A server mid-backfill would otherwise report a whole library as stale.
     */
    @Test
    fun `a null server hash is unknown, not changed`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1),
            recorded = mapOf(1 to "recorded"),
            server = listOf(AudioHash(chapterId = 1, audioSha256 = null)),
        )

        assertTrue(check.isEmpty)
    }

    @Test
    fun `a blank server hash is treated as null rather than as a difference`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1),
            recorded = mapOf(1 to "recorded"),
            server = listOf(AudioHash(chapterId = 1, audioSha256 = "   ")),
        )

        assertTrue(check.isEmpty)
    }

    /**
     * Nothing is claimed about a chapter this device does not have. Recording a hash for one would
     * also leave an entry behind that a later download would be compared against.
     */
    @Test
    fun `a chapter that is not downloaded is not considered at all`() {
        val check = staleDownloadCheck(
            downloaded = emptySet(),
            recorded = mapOf(7 to "old"),
            server = listOf(AudioHash(chapterId = 7, audioSha256 = "new")),
        )

        assertTrue(check.isEmpty)
    }

    @Test
    fun `stale and fresh chapters are separated within one fiction`() {
        val check = staleDownloadCheck(
            downloaded = setOf(1, 2, 3),
            recorded = mapOf(1 to "a", 2 to "b-old", 3 to "c"),
            server = listOf(
                AudioHash(chapterId = 1, audioSha256 = "a"),
                AudioHash(chapterId = 2, audioSha256 = "b-new"),
                AudioHash(chapterId = 3, audioSha256 = "c"),
            ),
        )

        assertEquals(setOf(2), check.stale)
        assertTrue(check.adopt.isEmpty())
    }

    @Test
    fun `pruning drops hashes for chapters no longer downloaded`() {
        val pruned = pruneRecordedHashes(
            recorded = mapOf(1 to "a", 2 to "b", 3 to "c"),
            downloaded = setOf(1, 3),
        )

        assertEquals(mapOf(1 to "a", 3 to "c"), pruned)
    }
}
