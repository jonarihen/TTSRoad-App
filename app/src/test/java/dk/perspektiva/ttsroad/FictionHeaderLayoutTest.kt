package dk.perspektiva.ttsroad

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionNotificationSettings
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.NotificationModeBacklog
import dk.perspektiva.ttsroad.data.NotificationModeEvery
import dk.perspektiva.ttsroad.data.NotificationModeOff
import dk.perspektiva.ttsroad.data.PlaybackInfo
import dk.perspektiva.ttsroad.data.PollScope
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

    private val armed = FictionNotificationSettings(
        mode = NotificationModeBacklog,
        backlogHours = 2.0,
        remainingSeconds = 5400.0,
        backlogArmed = true,
    )

    private fun render(
        errorChapters: Int = 0,
        canFollow: Boolean = true,
        canDownload: Boolean = true,
        hasMore: Boolean = true,
        onMore: () -> Unit = {},
        onRetryFailed: (() -> Unit)? = null,
        isFollowing: Boolean = true,
        notificationSettings: FictionNotificationSettings? = null,
        onNotificationSettings: (() -> Unit)? = null,
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
                    isFollowing = isFollowing,
                    notificationSettings = notificationSettings,
                    onNotificationSettings = onNotificationSettings,
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
    fun `unfollowed fiction hides notification status even when settings are supported`() {
        render(
            isFollowing = false,
            notificationSettings = armed,
            onNotificationSettings = {},
        )

        compose.onNodeWithTag("fiction-notification-status").assertDoesNotExist()
        compose.onNodeWithText("FOLLOW").assertIsDisplayed()
        compose.onNodeWithText("RESUME").assertIsDisplayed()
    }

    @Test
    fun `followed fiction hides notification status without a supported callback`() {
        render(notificationSettings = armed, onNotificationSettings = null)

        compose.onNodeWithTag("fiction-notification-status").assertDoesNotExist()
        compose.onNodeWithText("FOLLOWING").assertIsDisplayed()
        compose.onNodeWithText("RESUME").assertIsDisplayed()
    }

    @Test
    fun `notification states stay clickable below resume at narrow width and large font`() {
        var settings by mutableStateOf<FictionNotificationSettings?>(armed)
        var fontScale by mutableStateOf(1f)
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                TtsRoadTheme {
                    FictionDetailHeader(
                        fiction = fiction,
                        chapters = chapters,
                        onPlay = {},
                        downloadSummary = FictionDownloadSummary(downloaded = 4, remaining = 12),
                        onDownloadNext = {},
                        onSetFollowing = {},
                        isFollowing = true,
                        notificationSettings = settings,
                        onNotificationSettings = { opened++ },
                        onMore = {},
                    )
                }
            }
        }
        val cases = listOf(
            armed to "ARMED",
            armed.copy(backlogArmed = false) to "WAITING",
            armed.copy(mode = NotificationModeOff) to "OFF",
            armed.copy(mode = NotificationModeEvery) to "EVERY CHAPTER",
            null to "STATUS UNAVAILABLE",
        )

        for (scale in listOf(1f, 1.5f)) {
            for ((value, label) in cases) {
                compose.runOnIdle {
                    settings = value
                    fontScale = scale
                }
                val button = compose.onNodeWithTag("fiction-notification-status")
                button.assertIsDisplayed().assertIsEnabled().assertTextEquals(label)
                    .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                val bounds = button.getUnclippedBoundsInRoot()
                assertTrue(
                    "$label must fit the narrow viewport at $scale font scale",
                    bounds.left >= 0.dp && bounds.right <= 320.dp,
                )
                val resume = compose.onNodeWithText("RESUME").assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue("RESUME must stay above $label", resume.bottom <= bounds.top)
                for (secondary in listOf("FOLLOWING", "DOWNLOAD", "MORE")) {
                    val secondaryBounds = compose.onNodeWithText(secondary)
                        .assertIsDisplayed().getUnclippedBoundsInRoot()
                    assertTrue("$secondary must stay below RESUME", resume.bottom <= secondaryBounds.top)
                    assertTrue(
                        "$secondary must fit the narrow viewport",
                        secondaryBounds.left >= 0.dp && secondaryBounds.right <= 320.dp,
                    )
                    assertTrue(
                        "$label must not overlap $secondary",
                        bounds.right <= secondaryBounds.left || secondaryBounds.right <= bounds.left ||
                            bounds.bottom <= secondaryBounds.top || secondaryBounds.bottom <= bounds.top,
                    )
                }
                if (scale == 1f && label == "ARMED") {
                    val following = compose.onNodeWithText("FOLLOWING").getUnclippedBoundsInRoot()
                    assertTrue("ARMED belongs next to FOLLOWING", following.right <= bounds.left)
                    assertTrue(
                        "ARMED and FOLLOWING share a row",
                        following.top < bounds.bottom && bounds.top < following.bottom,
                    )
                }
                button.performClick()
            }
        }
        assertEquals(cases.size * 2, opened)
    }

    @Test
    fun `header forwards loading and stale notification status without losing the settings action`() {
        var settings by mutableStateOf<FictionNotificationSettings?>(null)
        var stale by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            TtsRoadTheme {
                FictionDetailHeader(
                    fiction = fiction,
                    chapters = chapters,
                    onPlay = {},
                    onSetFollowing = {},
                    notificationSettings = settings,
                    notificationStatusStale = stale,
                    notificationStatusLoading = true,
                    onNotificationSettings = { opened++ },
                )
            }
        }

        compose.onNodeWithTag("fiction-notification-status")
            .assertIsDisplayed().assertIsEnabled().assertTextEquals("CHECKING").performClick()
        compose.runOnIdle {
            settings = armed
            stale = true
        }
        compose.onNodeWithTag("fiction-notification-status")
            .assertIsDisplayed().assertIsEnabled().assertTextEquals("ARMED", "LAST KNOWN").performClick()
        assertEquals(2, opened)
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
                    onChoosePollScope = {},
                    feedUrl = "https://example.test/feed/fiction.xml",
                    onShareFeed = {},
                )
            }
        }

        compose.onNodeWithText("Check for new chapters").assertIsDisplayed()
        compose.onNodeWithText("Fetch chapters").assertIsDisplayed()
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
    fun `fetch chapters sheet offers presets and blocks invalid custom count`() {
        var selected: PollScope? = null
        compose.setContent {
            TtsRoadTheme {
                FictionPollScopeSheet(onDismiss = {}, onPoll = { selected = it })
            }
        }

        compose.onNodeWithText("All chapters").assertIsDisplayed()
        compose.onNodeWithText("100").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Custom chapter count").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("FETCH LAST").assertIsNotEnabled()
        compose.onNodeWithText("Custom chapter count").performTextReplacement("50")
        compose.onNodeWithText("First").performClick()
        compose.onNodeWithText("FETCH FIRST").performClick()
        assertEquals(PollScope.First(50), selected)
    }

    @Test
    fun `ebook export appears in the reader band only when offered`() {
        compose.setContent {
            TtsRoadTheme {
                FictionMaintenanceSheet(
                    fiction = fiction,
                    isBusy = false,
                    onDismiss = {},
                    onExportEbook = {},
                )
            }
        }

        compose.onNodeWithText("Download EPUB").assertExists()
        compose.onNodeWithText("// Admin").assertDoesNotExist()
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
