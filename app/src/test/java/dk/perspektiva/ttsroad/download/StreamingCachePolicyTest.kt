package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.DefaultStreamingCacheBytes
import dk.perspektiva.ttsroad.data.StreamingCacheChoices
import dk.perspektiva.ttsroad.data.StreamingCacheUnlimited
import dk.perspektiva.ttsroad.data.normalisedStreamingCacheBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap, as arithmetic and as a stored preference.
 *
 * [MediaCachesTest] proves the eviction actually happens against a real cache; what is here is the
 * two places the number can be wrong before it ever reaches one — the overflow sum, which has a
 * `Long.MAX_VALUE` in it, and what a stored value out of a corrupted or older store is read as.
 */
class StreamingCachePolicyTest {

    @Test
    fun `a write that fits needs nothing evicted`() {
        assertEquals(0L, overflowBytes(currentSize = 100, requiredSpace = 50, maxBytes = 200))
    }

    @Test
    fun `a write exactly filling the cap needs nothing evicted`() {
        assertEquals(0L, overflowBytes(currentSize = 150, requiredSpace = 50, maxBytes = 200))
    }

    @Test
    fun `a write over the cap asks for exactly the shortfall`() {
        assertEquals(30L, overflowBytes(currentSize = 180, requiredSpace = 50, maxBytes = 200))
    }

    @Test
    fun `a cache already over its cap is trimmed with no write pending`() {
        // What lowering the cap in Settings looks like: nothing is being written, and 300 bytes
        // still have to go.
        assertEquals(300L, overflowBytes(currentSize = 500, requiredSpace = 0, maxBytes = 200))
    }

    @Test
    fun `no limit never evicts, however large the write`() {
        // The case the subtraction exists for: currentSize + requiredSpace against Long.MAX_VALUE
        // overflows to a negative number, which would read as "plenty of room" by accident.
        assertEquals(
            0L,
            overflowBytes(
                currentSize = Long.MAX_VALUE / 2,
                requiredSpace = Long.MAX_VALUE / 2,
                maxBytes = StreamingCacheUnlimited,
            ),
        )
    }

    @Test
    fun `a negative length is treated as no length rather than as credit`() {
        // C.LENGTH_UNSET is -1. Counting it as -1 bytes would let a write past a full cap through.
        assertEquals(0L, overflowBytes(currentSize = 100, requiredSpace = -1, maxBytes = 200))
        assertEquals(100L, overflowBytes(currentSize = 300, requiredSpace = -1, maxBytes = 200))
    }

    @Test
    fun `an install from before the cap gets the default`() {
        assertEquals(DefaultStreamingCacheBytes, normalisedStreamingCacheBytes(null))
    }

    @Test
    fun `a zero or negative stored cap is read as the default, not as evict-everything`() {
        assertEquals(DefaultStreamingCacheBytes, normalisedStreamingCacheBytes(0L))
        assertEquals(DefaultStreamingCacheBytes, normalisedStreamingCacheBytes(-1L))
    }

    @Test
    fun `a cap this build no longer offers is honoured rather than rounded`() {
        val odd = 777L * 1024L * 1024L
        assertTrue(odd !in StreamingCacheChoices)
        assertEquals(odd, normalisedStreamingCacheBytes(odd))
    }

    @Test
    fun `no limit survives being stored and read back`() {
        assertEquals(StreamingCacheUnlimited, normalisedStreamingCacheBytes(StreamingCacheUnlimited))
    }

    @Test
    fun `the default is one of the choices, so Settings can show it as selected`() {
        assertTrue(DefaultStreamingCacheBytes in StreamingCacheChoices)
    }

    @Test
    fun `the caps read as chosen sizes rather than as measurements`() {
        assertEquals("256 MB", streamingCacheChoiceLabel(256L * 1024L * 1024L))
        assertEquals("1 GB", streamingCacheChoiceLabel(1024L * 1024L * 1024L))
        assertEquals("5 GB", streamingCacheChoiceLabel(5120L * 1024L * 1024L))
        assertEquals("NO LIMIT", streamingCacheChoiceLabel(StreamingCacheUnlimited))
    }

    @Test
    fun `every offered cap has a label`() {
        StreamingCacheChoices.forEach { choice ->
            assertTrue(
                "no label for $choice",
                streamingCacheChoiceLabel(choice).isNotBlank(),
            )
        }
    }
}
