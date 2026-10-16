package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import dk.perspektiva.ttsroad.nav.AppRoot
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home and navigation at 200 % font on a narrow phone (#354, WCAG 1.4.4).
 *
 * The 2x cases say so per test: Robolectric merges a method's `fontScale = 1f` over a class's 2f
 * as "unset", so the ordinary-size checks cannot be the overrides.
 *
 * The window is 320 dp wide at mdpi, so a pixel is a dp and a quarter of the bar is 80 px. Native
 * graphics, because the legacy shadows do not measure real glyphs and every title fits one line.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class LargeFontLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(fontScale = 2f)
    fun `the continue card grows to keep its resume button whole`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    ContinueHero(chapter = chapter, fiction = fiction, onOpenFiction = {}, onResume = {})
                }
            }
        }

        val cardPx = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("card is ${cardPx}px, it should have grown past 196", cardPx > 196)
        // Bounds in root are clipped by every ancestor, so a button cut off by the card would
        // report less than its full 48 dp here.
        compose.onNodeWithText("RESUME").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val resume = compose.onNodeWithText("RESUME").fetchSemanticsNode().boundsInRoot
        assertTrue("RESUME ends at ${resume.bottom}, card at $cardPx", resume.bottom <= cardPx)
        compose.onNodeWithText("14:00 LEFT").assertIsDisplayed()
    }

    /**
     * On a typical phone the floor still sets the height, so nothing moves for most people. (On
     * a 320 dp phone these titles already needed 205 dp at ordinary font, and were being clipped.)
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `at ordinary font the continue card keeps its 196 dp height`() {
        compose.setContent {
            TtsRoadTheme {
                Column {
                    ContinueHero(chapter = chapter, fiction = fiction, onOpenFiction = {}, onResume = {})
                }
            }
        }

        assertEquals(196, compose.onRoot().fetchSemanticsNode().size.height)
    }

    @Test
    @Config(fontScale = 2f)
    fun `every tab label fits its quarter of the bar`() {
        compose.setContent {
            TtsRoadTheme {
                PrimaryNavigationBar(
                    selected = AppRoot.Home,
                    onSelect = {},
                    badges = mapOf(AppRoot.Listening to 3),
                )
            }
        }

        for (root in AppRoot.entries) {
            val label = compose.onNodeWithText(root.label, useUnmergedTree = true)
            val layout = label.textLayout()
            assertFalse("${root.label} overflows its tab", layout.hasVisualOverflow)
            assertTrue(
                "${root.label} is ${layout.size.width}px wide in an 80px tab",
                layout.size.width <= 80,
            )
        }
    }

    @Test
    fun `at ordinary font the tab labels are not shrunk`() {
        compose.setContent {
            TtsRoadTheme {
                PrimaryNavigationBar(selected = AppRoot.Home, onSelect = {})
            }
        }

        for (root in AppRoot.entries) {
            val layout = compose.onNodeWithText(root.label, useUnmergedTree = true).textLayout()
            assertEquals(root.label, 12.sp, layout.layoutInput.style.fontSize)
        }
    }

    @Test
    @Config(fontScale = 2f)
    fun `a shelf card title wraps to a second line instead of stopping at a word`() {
        compose.setContent {
            TtsRoadTheme {
                ChapterTile(chapter = chapter, fiction = fiction, onOpenFiction = {})
            }
        }

        val layout = compose.onNodeWithText(chapter.title!!, useUnmergedTree = true).textLayout()
        assertEquals(2, layout.lineCount)
    }

    @Test
    @Config(fontScale = 2f)
    fun `a chapter row title wraps to a second line`() {
        compose.setContent {
            TtsRoadTheme {
                ChapterRow(chapter = chapter, fiction = fiction, onPlay = {})
            }
        }

        val layout = compose.onNodeWithText(chapter.title!!, useUnmergedTree = true).textLayout()
        assertEquals(2, layout.lineCount)
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("no text layout", action?.invoke(results) == true)
        return results.single()
    }

    private companion object {
        val fiction = FictionSummary(id = 7, title = "Some Very Long Serial Indeed")
        val chapter = ChapterSummary(
            id = 47,
            fictionId = 7,
            title = "Chapter 47: The Lighthouse Keeper Does Not Sleep",
            displayNumber = 47.0,
            status = "ready",
            audio = AudioInfo(url = "https://example.invalid/audio/47.mp3"),
            playback = PlaybackInfo(positionSeconds = 120.0, remainingLabel = "14:00"),
        )
    }
}
