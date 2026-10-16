package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #328: a fiction in the car can be played as a whole, and its list opens on where you are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FictionResumeTest {

    private fun chapter(id: Int, position: Double = 0.0, played: Boolean = false) = ChapterSummary(
        id = id,
        fictionId = 3,
        title = "Chapter $id",
        audioDuration = 600.0,
        audio = AudioInfo(url = "https://server.example.com/audio/$id.mp3"),
        playback = PlaybackInfo(positionSeconds = position, isPlayed = played),
    )

    @Test
    fun `the continue-listening chapter wins when the fiction has it`() {
        val chapters = listOf(chapter(1, played = true), chapter(2, position = 30.0), chapter(3))

        assertEquals(3, resumeChapterIn(chapters, continueChapterId = 3)?.resolvedChapterId)
    }

    @Test
    fun `a started chapter is resumed`() {
        val chapters = listOf(chapter(1, played = true), chapter(2, position = 30.0), chapter(3))

        assertEquals(2, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `a finished chapter hands over to the next one`() {
        val chapters = listOf(chapter(1, played = true), chapter(2, played = true), chapter(3))

        assertEquals(3, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `an untouched fiction has no resume point`() {
        assertNull(resumeChapterIn(listOf(chapter(1), chapter(2))))
    }

    @Test
    fun `a caught-up fiction stays on its last chapter instead of restarting`() {
        val chapters = listOf(chapter(1, played = true), chapter(2, played = true))

        assertEquals(2, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `a continue id that is not in this fiction falls back to the list`() {
        val chapters = listOf(chapter(1, played = true), chapter(2))

        assertEquals(2, resumeChapterIn(chapters, continueChapterId = 99)?.resolvedChapterId)
    }

    @Test
    fun `fiction and resume ids name a fiction to start, a chapter id does not`() {
        assertEquals(3, TtsRoadMediaIds.playableFictionId("fiction:3"))
        assertEquals(3, TtsRoadMediaIds.playableFictionId(TtsRoadMediaIds.resume(3)))
        assertNull(TtsRoadMediaIds.playableFictionId("chapter:3"))
        assertNull(TtsRoadMediaIds.playableFictionId("continue"))
    }

    @Test
    fun `a fiction node is playable as well as browsable`() {
        val metadata = TtsRoadMediaItems.fictionFolder(FictionSummary(id = 3, title = "Ashes")).mediaMetadata

        assertTrue(metadata.isBrowsable == true)
        assertTrue(metadata.isPlayable == true)
    }

    @Test
    fun `the resume row keeps the chapter's contract under its own id`() {
        val item = TtsRoadMediaItems.chapter(chapter(2, position = 30.0), serverUrl = "https://server.example.com")!!

        val row = TtsRoadMediaItems.resumeRow(3, item)

        assertEquals("resume:3", row.mediaId)
        assertEquals("Resume: Chapter 2", row.mediaMetadata.title.toString())
        assertEquals(2, row.mediaMetadata.extras?.getInt("chapter_id"))
        assertEquals(3, row.mediaMetadata.extras?.getInt("fiction_id"))
        assertEquals(item.requestMetadata.mediaUri, row.requestMetadata.mediaUri)
        assertFalse(row.mediaId == item.mediaId)
    }

    @Test
    fun `a caught-up fiction replays its last chapter from the top, not from its end`() {
        // #451: auto-advance saves a finished chapter at its duration. Resuming a caught-up serial
        // lands on that chapter, and starting there would end it at once and skip on.
        val last = resumeChapterIn(listOf(chapter(1, played = true), chapter(2, position = 600.0, played = true)))!!

        assertEquals(2, last.resolvedChapterId)
        assertEquals(0.0, last.startPositionSeconds, 0.0)
        assertNull(TtsRoadMediaItems.chapter(last, serverUrl = "https://server.example.com")!!.mediaMetadata.extras?.get("position_seconds"))
    }

    @Test
    fun `a chapter left in its last twenty seconds starts over, an ordinary one resumes`() {
        assertEquals(0.0, chapter(1, position = 585.0).startPositionSeconds, 0.0)
        assertEquals(300.0, chapter(1, position = 300.0).startPositionSeconds, 0.0)
        assertEquals(
            300.0,
            TtsRoadMediaItems.chapter(chapter(1, position = 300.0), serverUrl = "https://server.example.com")!!.mediaMetadata.extras!!.getDouble("position_seconds"),
            0.0,
        )
    }
}
