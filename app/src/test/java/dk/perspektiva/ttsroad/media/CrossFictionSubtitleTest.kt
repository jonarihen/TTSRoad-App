package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #331: Continue and Recent rows in the car read "Chapter 212 · 41 min" with no book, because the
 * fiction only rode on the album title, which many car UIs never draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossFictionSubtitleTest {

    private val fiction = FictionSummary(id = 3, title = "Ashes of Aether")
    private val unstarted = ChapterSummary(
        id = 42,
        fictionId = 3,
        title = "Chapter 212",
        audioDuration = 2_460.0,
        audioDurationLabel = "41 min",
        audio = AudioInfo(url = "https://server.example.com/audio/42.mp3"),
    )
    private val started = unstarted.copy(
        playback = PlaybackInfo(positionSeconds = 1_740.0),
    )

    private fun subtitle(chapter: ChapterSummary, crossFiction: Boolean) =
        TtsRoadMediaItems.chapter(chapter, fiction, "https://server.example.com", crossFiction)
            ?.mediaMetadata?.subtitle?.toString()

    @Test
    fun `a continue row names the fiction and the time left`() {
        assertEquals("Ashes of Aether · 12 min left", subtitle(started, crossFiction = true))
    }

    @Test
    fun `an unstarted cross-book row names the fiction and the length`() {
        assertEquals("Ashes of Aether · 41 min", subtitle(unstarted, crossFiction = true))
    }

    @Test
    fun `a row inside its own fiction keeps the duration`() {
        assertEquals("41 min", subtitle(started, crossFiction = false))
    }

    @Test
    fun `without a fiction title the duration is still shown`() {
        val orphan = unstarted.copy(fictionId = 9)
        val item = TtsRoadMediaItems.chapter(
            orphan,
            fiction = null,
            serverUrl = "https://server.example.com",
            crossFiction = true,
        )
        assertEquals("41 min", item?.mediaMetadata?.subtitle?.toString())
        assertEquals(null, TtsRoadMediaItems.crossFictionSubtitle(" ", unstarted))
    }

    @Test
    fun `long remainders read in hours`() {
        val long = unstarted.copy(audioDuration = 8_000.0, playback = PlaybackInfo(positionSeconds = 100.0))
        assertEquals("Ashes of Aether · 2 h 11 min left", TtsRoadMediaItems.crossFictionSubtitle("Ashes of Aether", long))
    }
}
