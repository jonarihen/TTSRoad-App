package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class LibraryChapterNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `home chapter entries open their fiction instead of starting playback`() {
        val fiction = FictionSummary(id = 7, title = "Test Serial")
        val hero = ChapterSummary(
            id = 101,
            fictionId = fiction.id,
            title = "Chapter 101",
            audio = AudioInfo(url = "https://example.test/101.mp3"),
        )
        val tile = hero.copy(id = 102, title = "Chapter 102")
        var opened = 0
        var playbackRequests = 0

        compose.setContent {
            TtsRoadTheme {
                Column {
                    ContinueHero(
                        chapter = hero,
                        fiction = fiction,
                        onOpenFiction = { opened++ },
                        onResume = { playbackRequests++ },
                    )
                    ChapterTile(
                        chapter = tile,
                        fiction = fiction,
                        onOpenFiction = { opened++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Chapter 101").performClick()
        compose.onNodeWithText("Chapter 102").performClick()

        assertEquals(2, opened)
        assertEquals(0, playbackRequests)
    }
}
