package dk.perspektiva.ttsroad.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JumpBackReloadTest {
    private val snap = HistorySnapshot(
        timestamp = 1_700_000_000_000L,
        mediaId = "chapter:7",
        fictionId = 3,
        chapterId = 7,
        title = "Chapter 7",
        fictionTitle = "Ashes of Aether",
        positionMs = 120_000L,
    )

    @Test
    fun `a moment still in the queue is a seek, with no reload`() = runBlocking {
        var reloaded = false
        val result = jumpBackTo(snap, seekInQueue = { _, _ -> true }, reload = { reloaded = true; true })

        assertNull(result)
        assertFalse(reloaded)
    }

    @Test
    fun `a cleared queue is reloaded at the historical moment`() = runBlocking {
        var reloadedWith: HistorySnapshot? = null
        val result = jumpBackTo(snap, seekInQueue = { _, _ -> false }, reload = { reloadedWith = it; true })

        assertNull(result)
        assertEquals(snap, reloadedWith)
    }

    @Test
    fun `an unreachable server is reported rather than swallowed`() = runBlocking {
        val result = jumpBackTo(
            snap,
            seekInQueue = { _, _ -> false },
            reload = { throw IOException("timeout") },
        )

        assertEquals("Could not load Ashes of Aether: timeout", result)
    }

    @Test
    fun `a queue that could not be installed is reported`() = runBlocking {
        val result = jumpBackTo(snap, seekInQueue = { _, _ -> false }, reload = { false })

        assertTrue(result!!.startsWith("Could not start playback"))
    }

    @Test
    fun `a moment with nothing to reload from says so without calling the server`() = runBlocking {
        var reloaded = false
        val result = jumpBackTo(
            snap.copy(fictionId = 0),
            seekInQueue = { _, _ -> false },
            reload = { reloaded = true; true },
        )

        assertEquals("This moment can no longer be reloaded", result)
        assertFalse(reloaded)
    }
}
