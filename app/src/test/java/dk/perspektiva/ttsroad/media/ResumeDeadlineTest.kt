package dk.perspektiva.ttsroad.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #483: a cold-start resume on weak signal falls back to what is on disk after a short deadline. */
class ResumeDeadlineTest {
    @Test
    fun `a slow network is abandoned at the deadline and the saved copy is served`() = runTest {
        val result = withinResumeDeadline(
            deadlineMs = 5_000,
            online = { delay(60_000); "online" },
            offline = { "saved" },
        )

        assertEquals("saved", result)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun `an answer inside the deadline is used and the saved copy is not touched`() = runTest {
        val result = withinResumeDeadline(
            deadlineMs = 5_000,
            online = { delay(1_000); "online" },
            offline = { error("offline path must not run") },
        )

        assertEquals("online", result)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `a network that answers nothing falls back at once`() = runTest {
        val result = withinResumeDeadline(5_000, online = { null }, offline = { "saved" })

        assertEquals("saved", result)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `nothing online and nothing saved is nothing to resume`() = runTest {
        assertNull(withinResumeDeadline<String>(5_000, online = { null }, offline = { null }))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `the offline path can still say why it cannot play`() = runTest {
        withinResumeDeadline<String>(
            5_000,
            online = { delay(60_000); "online" },
            offline = { throw UnsupportedOperationException("not downloaded") },
        )
    }

    @Test
    fun `resume never builds a queue from the saved chapter list`() = runTest {
        var consulted = false
        val result = chaptersOrSaved<String>(allowSaved = false, fetch = { null }, saved = { consulted = true; "saved" })

        assertNull(result)
        assertEquals(false, consulted)
    }

    @Test
    fun `browsing falls back to the saved chapter list, but only when the fetch fails`() = runTest {
        assertEquals("saved", chaptersOrSaved(allowSaved = true, fetch = { null }, saved = { "saved" }))
        assertEquals("live", chaptersOrSaved(allowSaved = true, fetch = { "live" }, saved = { error("no") }))
    }
}
