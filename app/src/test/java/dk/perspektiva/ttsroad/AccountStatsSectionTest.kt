package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dk.perspektiva.ttsroad.data.ActivityDay
import dk.perspektiva.ttsroad.data.BusiestListeningDay
import dk.perspektiva.ttsroad.data.ListeningComparison
import dk.perspektiva.ttsroad.data.ListeningMilestone
import dk.perspektiva.ttsroad.data.ListeningStats
import dk.perspektiva.ttsroad.data.TopListenedFiction
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server-backed half of the Stats screen (#117).
 *
 * Most of what is worth asserting here is *which silence is being shown*. There are three, they mean
 * different things, and the failure mode of collapsing them is not cosmetic: telling someone on a
 * server too old to publish the endpoint that they have never listened to anything is a lie the
 * screen would have no way of correcting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AccountStatsSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun stats(
        hasData: Boolean = true,
        activityWeeks: List<List<ActivityDay>> = listOf(
            listOf(
                ActivityDay(date = "2026-03-09", chapters = 2, level = 4, label = "a heavy Monday"),
                ActivityDay(date = "2026-03-10", chapters = 0, level = 0, label = "a quiet Tuesday"),
                ActivityDay(date = "2026-03-15", future = true, label = "a day yet to happen"),
            ),
        ),
    ) = ListeningStats(
        hasData = hasData,
        timeLabel = "97h 30m",
        chaptersFinished = 412,
        chaptersFinishedLabel = "412",
        chaptersInProgress = 3,
        booksStarted = 9,
        booksFinished = 2,
        words = 1_204_331L,
        wordsLabel = "1.20M",
        pages = 4_817L,
        pagesLabel = "4,817",
        uncountedChapters = 6,
        currentStreak = 4,
        longestStreak = 31,
        dailyAverageLabel = "17m",
        busiestDay = BusiestListeningDay(date = "2025-12-27", timeLabel = "6h 12m", chapters = 11),
        activityWeeks = activityWeeks,
        topFictions = listOf(
            TopListenedFiction(
                id = 7,
                title = "Mother of Learning",
                seconds = 180_000.0,
                timeLabel = "50h 0m",
                chaptersFinished = 108,
                totalChapters = 108,
                percent = 100,
                complete = true,
            ),
        ),
        comparisons = listOf(
            ListeningComparison(
                value = "1.6×",
                label = "the Lord of the Rings, unabridged",
                detail = "≈ 60 hours",
            ),
        ),
        milestones = listOf(
            ListeningMilestone(
                group = "Hours",
                title = "100 hours listened",
                earned = false,
                progress = 97,
                detail = "3 to go",
            ),
        ),
    )

    private fun render(
        supported: Boolean = true,
        stats: ListeningStats? = stats(),
        isLoading: Boolean = false,
        error: String? = null,
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            TtsRoadTheme {
                // The same container the real screen uses. Without it every card would be laid out
                // at the origin on top of the last, and a width assertion would measure nothing.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AccountStatsSection(
                        supported = supported,
                        stats = stats,
                        isLoading = isLoading,
                        error = error,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    @Test
    fun `a server without the endpoint says so, and does not say you have listened to nothing`() {
        render(supported = false, stats = null)

        compose.onNodeWithText(
            "THIS SERVER DOES NOT PUBLISH LISTENING STATISTICS. EVERYTHING ABOVE IS WORKED OUT " +
                "ON THE PHONE; THE TOTALS, STREAKS AND ACTIVITY GRID ARE THE SERVER'S TO COUNT, " +
                "AND THIS ONE IS TOO OLD TO SEND THEM.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an account that has listened to nothing says that instead`() {
        render(stats = stats(hasData = false))

        compose.onNodeWithText(
            "NOTHING COUNTED ON THIS ACCOUNT YET. THESE ARE WORKED OUT FROM YOUR SAVED " +
                "POSITIONS, SO LISTENING ON ANY DEVICE — PHONE, CAR OR BROWSER — FILLS THEM IN.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a failed request offers a retry rather than an empty account`() {
        var retried = 0
        render(stats = null, error = "Could not reach the server", onRetry = { retried++ })

        compose.onNodeWithText("COULD NOT REACH THE SERVER").assertIsDisplayed()
        compose.onNodeWithText("RETRY").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `the server's own labels are what reach the screen`() {
        render()

        // Every one of these is pre-formatted server-side. If the client ever starts deriving them
        // it will disagree with the browser about the same account.
        compose.onNodeWithText("97h 30m").assertExists()
        compose.onNodeWithText("1.6× THE LORD OF THE RINGS, UNABRIDGED").assertExists()
        compose.onNodeWithText("1.20M").assertExists()
        compose.onNodeWithText("4,817").assertExists()
        compose.onNodeWithText("17m").assertExists()
        compose.onNodeWithText("50H 0M").assertExists()
        compose.onNodeWithText("100 HOURS LISTENED").assertExists()
        compose.onNodeWithText("3 TO GO").assertExists()
    }

    @Test
    fun `streaks are counted in days and read as English at one`() {
        render(stats = stats().copy(currentStreak = 1, longestStreak = 31))

        compose.onNodeWithText("1 day").assertExists()
        compose.onNodeWithText("31 days").assertExists()
    }

    @Test
    fun `an incomplete word count is declared a floor, not a total`() {
        render()

        compose.onNodeWithText(
            "6 CHAPTERS YOU HAVE HEARD HAVE NO WORD COUNT ON THE SERVER YET, SO THOSE TWO ARE A " +
                "FLOOR RATHER THAN A TOTAL.",
        ).assertExists()
    }

    /** The grid is squares; without these it says nothing at all to a screen reader. */
    @Test
    fun `every day in the grid carries the server's sentence for it`() {
        render()

        compose.onNodeWithContentDescription("a heavy Monday").assertExists()
        compose.onNodeWithContentDescription("a quiet Tuesday").assertExists()
        compose.onNodeWithContentDescription("a day yet to happen").assertExists()
    }

    @Test
    fun `a full quarter of squares still fits a 320 dp phone`() {
        // Twelve weeks is the server's default and the widest the grid gets without being asked.
        val weeks = (0 until 12).map { week ->
            (0 until 7).map { day ->
                ActivityDay(date = "2026-01-0$day", level = day % 5, label = "week $week day $day")
            }
        }
        render(stats = stats(activityWeeks = weeks))

        val corner = compose.onNodeWithContentDescription("week 11 day 6")
        corner.assertExists()
        // The last column of the grid. Its right edge is the whole question: the row divides the
        // card's width twelve ways, and a fixed cell size would push this one off the screen.
        val bounds = corner.getUnclippedBoundsInRoot()
        assertTrue("the last square ran past the viewport: ${bounds.right}", bounds.right <= 320.dp)
    }

    @Test
    fun `a day that has not happened is drawn as absent, not as a quiet day`() {
        val quiet = activityLevelColor(ActivityDay(level = 0))
        val future = activityLevelColor(ActivityDay(level = 0, future = true))

        assertNotEquals(quiet, future)
        // And the shades climb rather than repeating, or the calendar reports one busy day as four.
        val shades = (0..4).map { activityLevelColor(ActivityDay(level = it)) }
        assertEquals(shades.size, shades.distinct().size)
        assertEquals(AarisColor.Accent, shades.last())
    }
}
