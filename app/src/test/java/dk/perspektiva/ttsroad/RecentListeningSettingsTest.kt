package dk.perspektiva.ttsroad

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dk.perspektiva.ttsroad.player.FictionListeningTime
import dk.perspektiva.ttsroad.player.HistorySnapshotCapacity
import dk.perspektiva.ttsroad.player.RecentListeningSummary
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Recent listening (#117): the copy, and that it fits the narrowest phone Android ships.
 *
 * 320 dp because that is where the Settings page's 24 dp gutters and the card's 16 dp padding leave
 * the least room, and a book title is the one string here whose length nobody controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class RecentListeningSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/Copenhagen")
    private val today = LocalDate.of(2026, 3, 12)

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalTime.of(hour, minute).atDate(date).atZone(zone).toInstant().toEpochMilli()

    private val now = at(today, 20, 0)

    private fun summary(
        todayMs: Long = 4_320_000L,
        retainedMs: Long = 13_200_000L,
        todayChapters: Int = 4,
        todaySittings: Int = 3,
        fictions: List<FictionListeningTime> = listOf(
            FictionListeningTime(7, "Worm", 2_880_000L, 3),
            FictionListeningTime(9, "Pale", 1_440_000L, 1),
        ),
        snapshots: Int = 900,
        atCapacity: Boolean = false,
    ) = RecentListeningSummary(
        todayMs = todayMs,
        retainedMs = retainedMs,
        todayChapters = todayChapters,
        todaySittings = todaySittings,
        todayFictions = fictions,
        oldestAt = at(today, 11, 40),
        newestAt = now,
        snapshots = snapshots,
        atCapacity = atCapacity,
    )

    @Test
    fun `an empty log says nothing was recorded rather than showing zero`() {
        compose.setContent {
            TtsRoadTheme { RecentListeningCard(summary = RecentListeningSummary(), now = now) }
        }

        compose.onNodeWithText("NOTHING RECORDED YET").assertIsDisplayed()
    }

    @Test
    fun `today's time, its breakdown and the books behind it are all on screen`() {
        compose.setContent { TtsRoadTheme { RecentListeningCard(summary = summary(), now = now) } }

        compose.onNodeWithText("1h 12m").assertIsDisplayed()
        compose.onNodeWithText("4 CHAPTERS · 2 BOOKS · 3 SITTINGS").assertIsDisplayed()
        compose.onNodeWithText("WORM").assertIsDisplayed()
        compose.onNodeWithText("48M").assertIsDisplayed()
        compose.onNodeWithText("PALE").assertIsDisplayed()
        compose.onNodeWithText("24M").assertIsDisplayed()
        // The window total, alongside today's — the number that makes the honesty line meaningful.
        compose.onNodeWithText("3h 40m").assertIsDisplayed()
    }

    @Test
    fun `a book title nobody controls does not push its duration off a 320 dp screen`() {
        val long = FictionListeningTime(
            fictionId = 7,
            title = "The Wandering Inn, Volume Nine, In Which Rather A Lot Continues To Happen",
            listenedMs = 2_880_000L,
            chapters = 2,
        )
        compose.setContent {
            TtsRoadTheme { RecentListeningCard(summary = summary(fictions = listOf(long)), now = now) }
        }

        val duration = compose.onNodeWithText("48M")
        duration.assertIsDisplayed()
        // The title takes the remaining width and ellipsises; the duration keeps its own.
        val durationBounds = duration.getUnclippedBoundsInRoot()
        assertTrue("duration ran past the viewport: ${durationBounds.right}", durationBounds.right <= 320.dp)
        val titleBounds = compose.onNodeWithText(long.title!!.uppercase()).getUnclippedBoundsInRoot()
        assertTrue("the title overran the duration", titleBounds.right <= durationBounds.left)
    }

    @Test
    fun `only the first few books get a row`() {
        val many = (1..6).map { FictionListeningTime(it, "Book $it", (7 - it) * 600_000L, 1) }
        compose.setContent {
            TtsRoadTheme { RecentListeningCard(summary = summary(fictions = many), now = now) }
        }

        compose.onNodeWithText("BOOK 1").assertIsDisplayed()
        compose.onNodeWithText("BOOK 4").assertIsDisplayed()
        compose.onNodeWithText("AND 2 MORE").assertIsDisplayed()
    }

    @Test
    fun `a day with nothing on it says so instead of listing books`() {
        assertEquals(
            "No playback recorded today yet",
            todayBreakdown(summary(todayChapters = 0, todaySittings = 0, fictions = emptyList())),
        )
    }

    @Test
    fun `counts read as English at one`() {
        val single = summary(
            todayChapters = 1,
            todaySittings = 1,
            fictions = listOf(FictionListeningTime(7, "Worm", 600_000L, 1)),
        )

        assertEquals("1 chapter · 1 book · 1 sitting", todayBreakdown(single))
    }

    @Test
    fun `neither log state claims an all-time total`() {
        val partial = keptLogExplanation(summary(atCapacity = false))
        val full = keptLogExplanation(summary(snapshots = HistorySnapshotCapacity, atCapacity = true))

        assertTrue(partial.contains("not an all-time total"))
        assertTrue(full.contains("not an all-time total"))
        // A full log has already lost its far end, and a partly full one has not. Saying the same
        // thing about both would misrepresent one of them.
        assertTrue(full.contains("already been dropped"))
        assertTrue(partial.contains("drops the oldest as it fills"))
        // Nothing here is a streak or a lifetime figure, and the copy must not imply either.
        for (text in listOf(partial, full)) {
            assertTrue(!text.contains("streak", ignoreCase = true))
            assertTrue(!text.contains("all time", ignoreCase = true))
        }
    }

    @Test
    fun `the window label names the day when the oldest entry is not today`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val sameDay = listeningWindowLabel(context, at(today, 11, 40), now, zone)
        val previousDay = listeningWindowLabel(context, at(today.minusDays(1), 23, 40), now, zone)
        val older = listeningWindowLabel(context, at(today.minusDays(4), 23, 40), now, zone)

        // Asserted on shape rather than on a rendered clock string: the time format follows the
        // device's locale and 24-hour setting, and this is not a test of either.
        assertTrue(sameDay.startsWith("since "))
        assertTrue(!sameDay.contains("yesterday"))
        assertTrue(previousDay.endsWith(" yesterday"))
        assertTrue(older.contains(" on "))
    }
}
