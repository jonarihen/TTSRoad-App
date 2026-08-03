package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader preferences.
 *
 * Issue #32 asks for these to be account preferences shared with the web reader. The server has no
 * preferences endpoint — it is not in `build.md` and not implemented — so they are local, behind
 * [ReaderPreferenceStore], and swapping in a server-backed implementation later is one constructor
 * argument rather than a rewrite of the reader.
 *
 * As with [PlaybackPreferences], the DataStore itself needs an Android context; what can actually
 * be wrong is the sanitising, so that is what is covered here.
 */
class ReaderPreferencesTest {

    @Test
    fun `every offered font scale round-trips`() {
        for (option in ReaderFontScales) {
            assertEquals(option, sanitizeReaderFontScale(option), 0.0001f)
        }
    }

    @Test
    fun `the default font scale is one of the offered steps`() {
        assertTrue(ReaderFontScales.any { kotlin.math.abs(it - DefaultReaderFontScale) < 0.0001f })
    }

    @Test
    fun `an out-of-range font scale is clamped rather than reset`() {
        // Clamped, not snapped: a value written by a future build with finer steps should survive a
        // downgrade instead of jumping back to the default mid-chapter.
        assertEquals(0.8f, sanitizeReaderFontScale(0.1f), 0.0001f)
        assertEquals(2.4f, sanitizeReaderFontScale(9f), 0.0001f)
        assertEquals(1.35f, sanitizeReaderFontScale(1.35f), 0.0001f)
    }

    @Test
    fun `a zero or NaN font scale can never reach the reader`() {
        // A scale of zero renders an invisible chapter, which looks exactly like a failed load.
        assertTrue(sanitizeReaderFontScale(0f) > 0f)
        assertTrue(sanitizeReaderFontScale(-3f) > 0f)
        assertEquals(DefaultReaderFontScale, sanitizeReaderFontScale(Float.NaN), 0.0001f)
    }

    @Test
    fun `a stored theme name round-trips`() {
        for (theme in ReaderTheme.entries) {
            assertEquals(theme, readerThemeOf(theme.name))
        }
    }

    @Test
    fun `an unknown or missing theme falls back to the app's own look`() {
        assertEquals(DefaultReaderTheme, readerThemeOf(null))
        assertEquals(DefaultReaderTheme, readerThemeOf(""))
        assertEquals(DefaultReaderTheme, readerThemeOf("Sepia"))
        assertEquals(DefaultReaderTheme, readerThemeOf("console"))
    }

    @Test
    fun `a stored highlight granularity round-trips`() {
        for (granularity in HighlightGranularity.entries) {
            assertEquals(granularity, highlightGranularityOf(granularity.name))
        }
    }

    @Test
    fun `an unknown highlight granularity falls back to the readable default`() {
        assertEquals(DefaultHighlightGranularity, highlightGranularityOf(null))
        assertEquals(DefaultHighlightGranularity, highlightGranularityOf("Syllable"))
    }

    @Test
    fun `the default highlight shows a sentence band, not a lone word`() {
        // A single moving word is unreadable at the 1.5x-2x this app is actually used at, so
        // word-only is offered but is deliberately not what a new install gets.
        assertEquals(HighlightGranularity.SentenceAndWord, DefaultHighlightGranularity)
        assertTrue(DefaultHighlightGranularity.showsSentence)
        assertTrue(DefaultHighlightGranularity.showsWord)
    }

    @Test
    fun `each granularity says exactly what it draws`() {
        assertTrue(HighlightGranularity.SentenceOnly.showsSentence)
        assertTrue(!HighlightGranularity.SentenceOnly.showsWord)
        assertTrue(!HighlightGranularity.WordOnly.showsSentence)
        assertTrue(HighlightGranularity.WordOnly.showsWord)
        assertTrue(!HighlightGranularity.Off.showsSentence)
        assertTrue(!HighlightGranularity.Off.showsWord)
    }

    @Test
    fun `every option has a label to put in the picker`() {
        for (theme in ReaderTheme.entries) assertTrue(theme.label.isNotBlank())
        for (granularity in HighlightGranularity.entries) assertTrue(granularity.label.isNotBlank())
    }

    @Test
    fun `the defaults are what a fresh install reads with`() {
        val prefs = ReaderPrefs()

        assertEquals(DefaultReaderFontScale, prefs.fontScale, 0.0001f)
        assertEquals(DefaultReaderTheme, prefs.theme)
        assertEquals(DefaultHighlightGranularity, prefs.highlight)
        assertTrue("reading without the screen dimming out is the whole point", prefs.keepScreenOn)
    }

    @Test
    fun `font scale labels are readable percentages`() {
        assertEquals("100%", formatReaderFontScale(1.0f))
        assertEquals("120%", formatReaderFontScale(1.2f))
        assertEquals("80%", formatReaderFontScale(0.8f))
    }

    /**
     * The seam that makes the deferred server-backed store a small change: anything satisfying
     * [ReaderPreferenceStore] can be handed to the reader, local or remote.
     */
    @Test
    fun `an alternative store implementation drives the same reader state`() = runTest {
        val store: ReaderPreferenceStore = InMemoryReaderPreferences(
            ReaderPrefs(fontScale = 1.6f, theme = ReaderTheme.Paper),
        )

        assertEquals(1.6f, store.current().fontScale, 0.0001f)
        assertEquals(ReaderTheme.Paper, store.prefs.first().theme)

        store.setHighlight(HighlightGranularity.SentenceOnly)
        store.setFontScale(1.2f)

        assertEquals(HighlightGranularity.SentenceOnly, store.current().highlight)
        assertEquals(1.2f, store.current().fontScale, 0.0001f)
        assertNotEquals(DefaultReaderTheme, store.current().theme)
    }

    @Test
    fun `an alternative store sanitises what it is given, like the local one`() = runTest {
        val store: ReaderPreferenceStore = InMemoryReaderPreferences()

        store.setFontScale(Float.NaN)

        assertEquals(DefaultReaderFontScale, store.current().fontScale, 0.0001f)
    }
}
