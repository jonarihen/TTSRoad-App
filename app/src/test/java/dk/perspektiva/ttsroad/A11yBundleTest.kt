package dk.perspektiva.ttsroad

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FakeSessionStore
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The accessibility bundle of #494: tile roles and spoken progress, password reveal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class A11yBundleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tile progress is spoken in words, not as 3 slash 40`() {
        assertEquals("Ada, 3 of 40 chapters ready", spokenTileMeta("Ada", 3, 40))
        assertEquals("3 of 40 chapters ready", spokenTileMeta(" ", 3, 40))
    }

    @Test
    fun `a clickable shelf tile is a button`() {
        compose.setContent {
            TtsRoadTheme {
                ChapterTile(
                    chapter = ChapterSummary(id = 1, fictionId = 7, title = "One"),
                    fiction = FictionSummary(id = 7, title = "Serial"),
                    onOpenFiction = {},
                )
            }
        }

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertExists()
    }

    @Test
    fun `the password can be shown and hidden again`() {
        compose.setContent {
            TtsRoadTheme {
                LoginScreen(TtsRoadRepository(FakeSessionStore(SessionState())), SessionState())
            }
        }

        compose.onNodeWithContentDescription("Show password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        ).performClick()
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onNodeWithContentDescription("Show password").assertExists()
    }
}
