package dk.perspektiva.ttsroad.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesSplitTest {
    @Test
    fun `the prose before the first heading is the summary`() {
        val parts = splitReleaseNotes("Bug-fix release.\n\nPlayback is steadier.\n\n### Fixed\n- One (#1)\n- Two (#2)")
        assertEquals("Bug-fix release.\n\nPlayback is steadier.", parts.summary)
        assertEquals("### Fixed\n- One (#1)\n- Two (#2)", parts.details)
    }

    @Test
    fun `a list straight away has no summary`() {
        val notes = "- One\n- Two"
        assertEquals(ReleaseNotesParts(summary = null, details = notes), splitReleaseNotes(notes))
    }

    @Test
    fun `prose only has nothing to put a summary above`() {
        val notes = "Just a short note.\n\nAnd another."
        assertEquals(ReleaseNotesParts(summary = null, details = notes), splitReleaseNotes(notes))
    }

    @Test
    fun `windows line endings split the same`() {
        assertEquals("Lead.", splitReleaseNotes("Lead.\r\n\r\n## Changes\r\n- x").summary)
    }
}
