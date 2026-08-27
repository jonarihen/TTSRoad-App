package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dk.perspektiva.ttsroad.data.FictionStorageRow
import dk.perspektiva.ttsroad.data.ServerStorageResponse
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server-storage card in Settings (#124).
 *
 * Two things are worth drawing rather than reasoning about. The first is the admin gate: this is a
 * card on a page about something else, so a regular account gets *nothing* — not an explanation of a
 * permission it has no way of having expected. The second is that every size on the card is a string
 * the server sent. A phone that re-rounded the same bytes would let it and the browser describe one
 * file two different ways, and the way that shows up is a label on screen that the payload never
 * contained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ServerStorageSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: Int, title: String, audio: Long, label: String) = FictionStorageRow(
        id = id,
        title = title,
        slug = "fiction-$id",
        audioBytes = audio,
        audioLabel = label,
        excludedBytes = 0L,
        excludedLabel = "0 B",
    )

    private fun response(
        perFiction: List<FictionStorageRow> = listOf(
            row(1, "Mother of Learning", 400_000_000_000L, "373 GB"),
        ),
        ffmpeg: Boolean = true,
    ) = ServerStorageResponse(
        totalAudioBytes = 512_000_000_000L,
        totalAudioLabel = "477 GB",
        excludedAudioBytes = 2_147_483_648L,
        excludedAudioLabel = "2.0 GB",
        epubBytes = 104_857_600L,
        epubLabel = "100 MB",
        coverBytes = 20_971_520L,
        coverLabel = "20 MB",
        voiceSampleBytes = 1_048_576L,
        voiceSampleLabel = "1.0 MB",
        voiceSampleCount = 14,
        exportBytes = 32_212_254_720L,
        exportLabel = "30 GB",
        ffmpegAvailable = ffmpeg,
        volumeTotalBytes = 1_000_000_000_000L,
        volumeTotalLabel = "931 GB",
        volumeFreeBytes = 250_000_000_000L,
        volumeFreeLabel = "233 GB",
        perFiction = perFiction,
    )

    private fun render(
        allowed: Boolean = true,
        storage: ServerStorageResponse? = response(),
        isLoading: Boolean = false,
        error: String? = null,
        showEveryFiction: Boolean = false,
        onToggleEveryFiction: () -> Unit = {},
    ) {
        compose.setContent {
            TtsRoadTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    ServerStorageSection(
                        allowed = allowed,
                        storage = storage,
                        isLoading = isLoading,
                        error = error,
                        showEveryFiction = showEveryFiction,
                        onToggleEveryFiction = onToggleEveryFiction,
                        onRefresh = {},
                    )
                }
            }
        }
    }

    @Test
    fun `a regular account gets no card at all`() {
        render(allowed = false)

        // Not a paragraph about being refused: nothing on the Settings page suggested this card
        // exists, so explaining its absence is noise. Both halves of the section rule, because a
        // band that took its heading away and left the kicker would still be a landmark to nothing.
        compose.onNodeWithText("§ DISK").assertDoesNotExist()
        compose.onNodeWithText("SERVER STORAGE").assertDoesNotExist()
    }

    @Test
    fun `an admin gets the band headed by the section rule, not an accent caption`() {
        render()

        // A mnemonic and not an ordinal, because this whole band disappears on a server without
        // the capability and on an account without the flag (#162). A number here would count
        // something the reader cannot see.
        compose.onNodeWithText("§ DISK").assertIsDisplayed()
        compose.onNodeWithText("SERVER STORAGE").assertIsDisplayed()
    }

    @Test
    fun `every size shown is the string the server rendered`() {
        render()

        compose.onNodeWithText("477 GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30 GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("100 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("20 MB").performScrollTo().assertIsDisplayed()
        // Free-of-total, as two labels rather than one derived remainder.
        compose.onNodeWithText("233 GB FREE OF 931 GB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a server without ffmpeg says so where the export total is`() {
        render(storage = response(ffmpeg = false))

        compose.onNodeWithText("THIS SERVER HAS NO FFMPEG", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the table shows the largest few and offers the rest`() {
        val many = (1..9).map { row(it, "Fiction $it", (10 - it) * 1_000_000_000L, "${10 - it} GB") }
        render(storage = response(perFiction = many))

        // Scrolled to rather than asserted in place: the card is taller than a 320x640 phone, and
        // a node below the fold is still on the page.
        compose.onNodeWithText("FICTION 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FICTION 6").performScrollTo().assertIsDisplayed()
        // Seven onwards is behind the button: this card lives inside a page that already scrolls.
        compose.onNodeWithText("FICTION 7").assertDoesNotExist()
        compose.onNodeWithText("SHOW ALL 9").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `asking for the rest is what expands it`() {
        var toggled = 0
        val many = (1..9).map { row(it, "Fiction $it", (10 - it) * 1_000_000_000L, "${10 - it} GB") }
        render(storage = response(perFiction = many), onToggleEveryFiction = { toggled++ })

        compose.onNodeWithText("SHOW ALL 9").performScrollTo().performClick()

        assertEquals(1, toggled)
    }

    @Test
    fun `a server whose books hold no audio says that, rather than showing nothing`() {
        render(storage = response(perFiction = emptyList()))

        // A blank space under "per fiction" would read as a broken card. This is a real answer:
        // the fictions exist and nothing has been narrated yet.
        compose.onNodeWithText("NO FICTION ON THIS SERVER HAS ANY AUDIO ON DISK YET.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a failed request shows the reason instead of a permanent Loading`() {
        render(storage = null, error = "Could not read the server's disk usage")

        compose.onNodeWithText("COULD NOT READ THE SERVER'S DISK USAGE").assertIsDisplayed()
        compose.onNodeWithText("LOADING…").assertDoesNotExist()
    }
}
