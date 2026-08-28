package dk.perspektiva.ttsroad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import dk.perspektiva.ttsroad.download.FictionDownloadSummary
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fiction header after #160, which is the screen the "too many buttons" complaint was really
 * about: ten full-width bands of identical weight above the chapter list, for an admin.
 *
 * The header had no layout test at all before this, which is part of how it reached ten. These
 * assertions are about *rank* rather than appearance — one primary, the rest in a row, and the
 * housekeeping behind a door — because rank is the thing that regressed one reasonable addition at
 * a time and the thing that will regress again the next time an action needs somewhere to live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FictionHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val fiction = FictionSummary(
        id = 1,
        title = "Ashes of the Sun",
        author = "A. Wong",
        totalChapters = 240,
        doneChapters = 238,
    )

    private val chapters = listOf(
        ChapterSummary(
            id = 10,
            fictionId = 1,
            title = "Chapter 1",
            audio = AudioInfo(url = "https://example.test/1.mp3"),
            playback = PlaybackInfo(positionSeconds = 120.0),
        ),
    )

    private fun render(
        errorChapters: Int = 0,
        canFollow: Boolean = true,
        canDownload: Boolean = true,
        hasMore: Boolean = true,
        onMore: () -> Unit = {},
        onRetryFailed: (() -> Unit)? = null,
    ) {
        compose.setContent {
            TtsRoadTheme {
                FictionDetailHeader(
                    fiction = fiction.copy(errorChapters = errorChapters),
                    chapters = chapters,
                    onPlay = {},
                    downloadSummary = FictionDownloadSummary(downloaded = 4, remaining = 12),
                    onDownloadNext = if (canDownload) ({}) else null,
                    onSetFollowing = if (canFollow) ({ _: Boolean -> }) else null,
                    onRetryFailed = onRetryFailed,
                    onMore = if (hasMore) onMore else null,
                )
            }
        }
    }

    @Test
    fun `the control the screen exists for is the one that stands out`() {
        render()

        // Resume rather than Play: the only chapter has a saved position.
        compose.onNodeWithText("RESUME").assertIsDisplayed()
    }

    @Test
    fun `follow, download and more sit on one line instead of three bands`() {
        render()

        val tops = listOf("FOLLOWING", "DOWNLOAD", "MORE")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }

        // All three on the same row: at 320 dp these three short labels fit, and the whole point of
        // #160 is that they stopped being three full-width bands stacked down the page.
        assertEquals(1, tops.map { it.toInt() }.toSet().size)
    }

    @Test
    fun `the secondary row sits below the primary, not beside it`() {
        render()

        val resume = compose.onNodeWithText("RESUME").fetchSemanticsNode().boundsInRoot
        val follow = compose.onNodeWithText("FOLLOWING").fetchSemanticsNode().boundsInRoot

        assertTrue("RESUME must be above the secondary row", resume.bottom <= follow.top)
    }

    @Test
    fun `housekeeping is behind the door, not on the header`() {
        render()

        // The six that moved into the sheet. A header that grows any of these back is the
        // regression this test exists for.
        for (gone in listOf(
            "CHECK FOR NEW CHAPTERS",
            "SHARE PODCAST FEED",
            "REGENERATE FEED LINK",
            "EDIT DETAILS",
            "DELETE FICTION",
            "MAINTENANCE",
        )) {
            compose.onNodeWithText(gone).assertDoesNotExist()
        }
    }

    @Test
    fun `the door reports being opened`() {
        var opened = 0
        render(onMore = { opened++ })

        compose.onNodeWithText("MORE").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a fiction with nothing failing offers no retry`() {
        render(errorChapters = 0, onRetryFailed = {})

        // The caller passes null when there is nothing to requeue; this is the other half — the
        // header must not draw a retry for a fiction reporting no failures.
        compose.onNodeWithText("RETRY 0 FAILED").assertDoesNotExist()
    }

    @Test
    fun `a failure earns a control, which is why it is allowed on the header at all`() {
        render(errorChapters = 3, onRetryFailed = {})

        compose.onNodeWithText("RETRY 3 FAILED").assertIsDisplayed()
    }

    @Test
    fun `a reader on a server with none of the extras gets no empty door`() {
        // Non-admin, no maintenance capability, no feed: the sheet would hold nothing, so the
        // caller passes null and the header must not offer a button onto an empty sheet.
        render(canFollow = false, canDownload = false, hasMore = false)

        compose.onNodeWithText("MORE").assertDoesNotExist()
        compose.onNodeWithText("RESUME").assertIsDisplayed()
    }

    @Test
    fun `the reader sheet does not expose admin maintenance`() {
        compose.setContent {
            TtsRoadTheme {
                FictionMaintenanceSheet(
                    fiction = fiction,
                    isBusy = false,
                    onDismiss = {},
                    onPoll = {},
                    feedUrl = "https://example.test/feed/fiction.xml",
                    onShareFeed = {},
                )
            }
        }

        compose.onNodeWithText("Check for new chapters").assertIsDisplayed()
        compose.onNodeWithText("Share podcast feed").assertIsDisplayed()
        compose.onNodeWithText("// Admin").assertDoesNotExist()
        for (adminOnly in listOf(
            "Fetch all chapters",
            "Re-apply chapter filter",
            "Refresh MP3 tags",
            "Re-narrate every chapter",
            "Regenerate feed link",
            "Edit details",
            "Delete fiction",
        )) {
            compose.onNodeWithText(adminOnly).assertDoesNotExist()
        }
    }

    @Test
    fun `a blank feed url does not create an empty reader band`() {
        compose.setContent {
            TtsRoadTheme {
                FictionMaintenanceSheet(
                    fiction = fiction,
                    isBusy = false,
                    onDismiss = {},
                    feedUrl = "   ",
                    onShareFeed = {},
                )
            }
        }

        compose.onNodeWithText("// This book").assertDoesNotExist()
        compose.onNodeWithText("Share podcast feed").assertDoesNotExist()
    }
}
