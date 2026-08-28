package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.data.LibraryProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryProgressPresentationTest {
    @Test
    fun `the grid uses the server label so its rounding matches the web`() {
        val progress = LibraryProgress(
            chaptersUnplayed = 73,
            remainingSeconds = 196_692.0,
            remainingLabel = "54h 39m",
        )

        assertEquals("54h 39m remaining  ·  73 left", libraryProgressMeta(progress))
    }

    @Test
    fun `a partial aggregate still has a local label fallback`() {
        val progress = LibraryProgress(chaptersUnplayed = 2, remainingSeconds = 1800.0)

        assertEquals("30m remaining  ·  2 left", libraryProgressMeta(progress))
    }
}
