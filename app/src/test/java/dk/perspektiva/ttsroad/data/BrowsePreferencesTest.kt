package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored half of the browse settings.
 *
 * As with [ChapterListPreferencesTest], the DataStore itself needs an Android context, so what is
 * covered here is the part that can actually be wrong: reading a stored name back. The failure
 * mode is not a crash — an order this build no longer has, resolving to something arbitrary, would
 * silently reorder everyone's shelf on upgrade.
 */
class BrowsePreferencesTest {

    @Test
    fun `every order round-trips through its stored name`() {
        for (option in FictionSort.entries) {
            assertEquals(option, fictionSortFromStored(option.name))
        }
    }

    @Test
    fun `every scope round-trips through its stored name`() {
        for (option in BrowseScope.entries) {
            assertEquals(option, browseScopeFromStored(option.name))
        }
    }

    @Test
    fun `an empty store browses with the defaults`() {
        assertEquals(FictionSort.Default, fictionSortFromStored(null))
        assertEquals(BrowseScope.Default, browseScopeFromStored(null))
        assertEquals(BrowseSettings(), BrowseSettings(FictionSort.Default, emptySet(), BrowseScope.Default))
    }

    @Test
    fun `an unrecognised value falls back rather than hiding rows`() {
        // A scope removed in an upgrade, or a corrupt store. Landing on FOLLOWING would show a
        // narrowed grid on launch with nothing on screen explaining it.
        assertEquals(FictionSort.Default, fictionSortFromStored("MostDownloaded"))
        assertEquals(FictionSort.Default, fictionSortFromStored(""))
        assertEquals(BrowseScope.All, browseScopeFromStored("Unread"))
        assertEquals(BrowseScope.All, browseScopeFromStored(""))
    }

    @Test
    fun `the stored name is the enum name, not the label`() {
        // The labels are display text and will get reworded — "% converted" especially. The
        // persisted key must not move with them or everyone's saved order resets on upgrade.
        assertEquals(FictionSort.PercentConverted, fictionSortFromStored("PercentConverted"))
        assertEquals(FictionSort.MostChapters, fictionSortFromStored("MostChapters"))
        assertEquals(BrowseScope.Following, browseScopeFromStored("Following"))
    }
}
