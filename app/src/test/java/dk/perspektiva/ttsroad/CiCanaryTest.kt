package dk.perspektiva.ttsroad

import org.junit.Assert.assertEquals
import org.junit.Test

/** Throwaway: proves CI fails a red build. Never merged. */
class CiCanaryTest {
    @Test
    fun `deliberately failing so CI has something to catch`() {
        assertEquals(1, 2)
    }
}
