package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The remembered chapter filter.
 *
 * As with [ReaderPreferencesTest] and [PlaybackPreferencesTest], the DataStore itself needs an
 * Android context. What can actually be wrong is reading a stored name back, so that is what is
 * covered here — and it is worth covering, because the failure is not a crash. An unrecognised
 * value that resolved to `Unplayed` would hide rows for a reason nothing on screen explains.
 */
class ChapterListPreferencesTest {

    @Test
    fun `every filter round-trips through its stored name`() {
        for (option in ChapterFilter.entries) {
            assertEquals(option, chapterFilterFromStored(option.name))
        }
    }

    @Test
    fun `an empty store shows everything`() {
        assertEquals(ChapterFilter.All, chapterFilterFromStored(null))
    }

    @Test
    fun `an unrecognised filter falls back to showing everything`() {
        // A filter this build no longer has, or a corrupt store. Falling back to Unplayed would
        // hide chapters on launch with nothing to explain why; falling back to All is merely the
        // default someone can change back.
        assertEquals(ChapterFilter.All, chapterFilterFromStored("Downloaded"))
        assertEquals(ChapterFilter.All, chapterFilterFromStored(""))
    }

    @Test
    fun `the stored name is the enum name, not the label`() {
        // The label is display text and will get reworded; the persisted key must not move with it
        // or everyone's saved filter silently resets on upgrade.
        assertEquals(ChapterFilter.Unplayed, chapterFilterFromStored("Unplayed"))
    }
}
