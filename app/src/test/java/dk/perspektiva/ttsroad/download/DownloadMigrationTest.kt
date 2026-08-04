package dk.perspektiva.ttsroad.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.8.0 → 0.9.0 cache re-key, which runs once and silently on first contact with a server that
 * names itself. It deletes cache spans and re-queues every download on the phone, so the two ways
 * it can be wrong are "wiped the library" and "handed one server's audio to another".
 */
class DownloadMigrationTest {

    private val serverA = DownloadCacheKeys.serverIdentity("https://a.example")!!
    private val serverB = DownloadCacheKeys.serverIdentity("https://b.example")!!

    @Test
    fun `a first identity is adopted`() {
        assertTrue(shouldAdoptIdentity(current = null, incoming = serverA))
    }

    @Test
    fun `re-adopting the same identity does nothing`() {
        // Capabilities are re-fetched on a six-hour cache interval. If this returned true the whole
        // library would re-download every time it refreshed.
        assertFalse(shouldAdoptIdentity(current = serverA, incoming = serverA))
    }

    @Test
    fun `an absent identity never undoes a completed migration`() {
        // The regression this exists to prevent: an unreachable server answers as Baseline, which
        // carries no base_url. Treating that as "go back to unscoped keys" would re-key everything
        // a second time and restart the entire library download.
        assertFalse(shouldAdoptIdentity(current = serverA, incoming = null))
        assertFalse(shouldAdoptIdentity(current = null, incoming = null))
    }

    @Test
    fun `switching to a genuinely different server does migrate`() {
        assertTrue(shouldAdoptIdentity(current = serverA, incoming = serverB))
    }

    @Test
    fun `orphaned streaming leftovers are dropped`() {
        // Unscoped, and no download record behind it: nothing will ever read it again.
        val orphans = orphanedCacheKeys(
            cacheKeys = listOf("/audio/fic/ch1.mp3", "/audio/fic/ch2.mp3"),
            indexedKeys = emptySet(),
        )

        assertEquals(listOf("/audio/fic/ch1.mp3", "/audio/fic/ch2.mp3"), orphans)
    }

    @Test
    fun `a real download is kept, because it gets re-keyed rather than deleted`() {
        val orphans = orphanedCacheKeys(
            cacheKeys = listOf("/audio/fic/ch1.mp3", "/audio/fic/ch2.mp3"),
            indexedKeys = setOf("/audio/fic/ch1.mp3"),
        )

        assertEquals(listOf("/audio/fic/ch2.mp3"), orphans)
    }

    @Test
    fun `another server's entries are never touched`() {
        // Deleting these would wipe downloads belonging to a second TTSRoad instance the user also
        // uses — the exact cross-server damage the identity was introduced to prevent.
        val theirs = "$serverB /audio/fic/ch1.mp3"

        val orphans = orphanedCacheKeys(cacheKeys = listOf(theirs), indexedKeys = emptySet())

        assertTrue("expected $theirs to be left alone, got $orphans", orphans.isEmpty())
    }

    @Test
    fun `a mixed cache drops only the unattributed strays`() {
        val mine = "$serverA /audio/fic/ch1.mp3"
        val theirs = "$serverB /audio/fic/ch9.mp3"
        val downloaded = "/audio/fic/ch2.mp3"
        val stray = "/audio/fic/ch3.mp3"

        val orphans = orphanedCacheKeys(
            cacheKeys = listOf(mine, theirs, downloaded, stray),
            indexedKeys = setOf(downloaded),
        )

        assertEquals(listOf(stray), orphans)
    }

    @Test
    fun `a null custom cache key in the index does not make everything an orphan`() {
        // DownloadRequest.customCacheKey is nullable, so the indexed set really can contain null.
        val orphans = orphanedCacheKeys(
            cacheKeys = listOf("/audio/fic/ch1.mp3"),
            indexedKeys = setOf(null, "/audio/fic/ch1.mp3"),
        )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `an empty cache is not a special case`() {
        assertTrue(orphanedCacheKeys(emptyList(), emptySet()).isEmpty())
    }
}
