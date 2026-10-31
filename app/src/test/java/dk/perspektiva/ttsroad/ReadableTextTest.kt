package dk.perspektiva.ttsroad

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.data.PlaybackPreferences
import dk.perspektiva.ttsroad.data.PlaybackPrefs
import dk.perspektiva.ttsroad.data.readableTextDefault
import kotlinx.coroutines.runBlocking
import dk.perspektiva.ttsroad.nav.AppRoot
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.SectionHeader
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.ui.spokenMetaText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Readable text" setting (#359): sentence case, sans-serif, labels and errors at 14 sp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ReadableTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the setting is off until asked for`() {
        assertNull(PlaybackPrefs().readableText)
    }

    /** The cache is what the activity's first frame is drawn from; it must follow the store. */
    @Test
    fun `the remembered setting follows writes and reads, for the first frame`() {
        val preferences = PlaybackPreferences(ApplicationProvider.getApplicationContext())
        runBlocking {
            preferences.setReadableText(true)
            assertEquals(true, PlaybackPreferences.lastKnownReadableText)
            preferences.setReadableText(false)
            assertEquals(false, PlaybackPreferences.lastKnownReadableText)
            preferences.setReadableText(true)
            assertEquals(true, preferences.current().readableText)
            assertEquals(true, PlaybackPreferences.lastKnownReadableText)
            preferences.setReadableText(false)
        }
    }

    @Test
    fun `by default labels keep the AARIS mono capitals`() {
        compose.setContent {
            TtsRoadTheme {
                MetaText(text = "Server address")
            }
        }

        val style = compose.onNodeWithText("SERVER ADDRESS").textLayout().layoutInput.style
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(11.sp, style.fontSize)
    }

    @Test
    fun `readable labels are sentence case, sans-serif and at least 14 sp`() {
        compose.setContent {
            TtsRoadTheme(readableText = true) {
                MetaText(text = "Server address")
            }
        }

        compose.onNodeWithText("SERVER ADDRESS").assertDoesNotExist()
        val style = compose.onNodeWithText("Server address").textLayout().layoutInput.style
        assertNotEquals(FontFamily.Monospace, style.fontFamily)
        assertTrue("label is ${style.fontSize}", style.fontSize.value >= 14f)
        assertEquals(0f, style.letterSpacing.value, 0f)
    }

    @Test
    fun `tags, section headers and tabs follow the setting too`() {
        compose.setContent {
            TtsRoadTheme(readableText = true) {
                androidx.compose.foundation.layout.Column {
                    AarisTag(text = "Ongoing")
                    SectionHeader(kicker = "01", title = "Session")
                    PrimaryNavigationBar(selected = AppRoot.Home, onSelect = {})
                }
            }
        }

        compose.onNodeWithText("Ongoing").assertExists()
        compose.onNodeWithText("Session").assertExists()
        for (label in listOf("Home", "Browse", "Listening", "Settings")) {
            compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
        }
        val tag = compose.onNodeWithText("Ongoing").textLayout().layoutInput.style
        assertTrue("tag is ${tag.fontSize}", tag.fontSize.value >= 14f)
    }

    @Test
    fun `a readable kicker is spoken as written, without its mark`() {
        assertEquals("Now playing", spokenMetaText("// Now playing", uppercase = false))
        assertEquals("NOW PLAYING", spokenMetaText("// Now playing"))
    }

    @Test
    fun `the switch reports its state and flips the preference`() {
        var enabled = false
        compose.setContent {
            TtsRoadTheme {
                ReadableTextSetting(enabled = enabled, onEnabledChange = { enabled = it })
            }
        }

        compose.onNodeWithText("Readable text", substring = true).assertIsOff().performClick()
        assertTrue(enabled)
    }

    @Test
    fun `an unchosen setting follows the system font scale, a choice always wins`() {
        assertEquals(false, readableTextDefault(null, 1.0f))
        assertEquals(false, readableTextDefault(null, 1.29f))
        assertEquals(true, readableTextDefault(null, 1.3f))
        assertEquals(false, readableTextDefault(false, 2.0f))
        assertEquals(true, readableTextDefault(true, 1.0f))
    }

    @Test
    fun `the switch label is readable even while the rest is capitals`() {
        compose.setContent {
            TtsRoadTheme {
                androidx.compose.foundation.layout.Column {
                    ReadableTextSetting(enabled = false, onEnabledChange = {})
                    MetaText(text = "Other label")
                }
            }
        }

        compose.onNodeWithText("Readable text").assertExists()
        compose.onNodeWithText("OTHER LABEL").assertExists()
    }

    @Test
    fun `an enabled switch reads as on`() {
        compose.setContent {
            TtsRoadTheme(readableText = true) {
                ReadableTextSetting(enabled = true, onEnabledChange = {})
            }
        }

        compose.onNodeWithText("Readable text", substring = true).assertIsOn()
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("no text layout", action?.invoke(results) == true)
        return results.single()
    }
}
