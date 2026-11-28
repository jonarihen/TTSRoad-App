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
import java.time.Instant

/** #328: a fiction in the car can be played as a whole, and its list opens on where you are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FictionResumeTest {

    private fun chapter(
        id: Int,
        position: Double = 0.0,
        played: Boolean = false,
        duration: Double = 600.0,
        heardAt: String? = null,
    ) = ChapterSummary(
        id = id,
        fictionId = 3,
        title = "Chapter $id",
        audioDuration = duration,
        audio = AudioInfo(url = "https://server.example.com/audio/$id.mp3"),
        playback = PlaybackInfo(positionSeconds = position, isPlayed = played, lastListenedAt = heardAt),
    )

    /** A server stamp [hours] after a fixed start, so tests can say which was heard later. */
    private fun heard(hours: Long): String = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(hours * 3600).toString()

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
    fun `a played chapter is not resumed for being the longest one heard`() {
        // #558, the reported screen: everything up to 39 played, each saved a few seconds short of
        // its end, and chapter 34 — 17:23 against ~10 minutes — holding the largest position by far.
        // RESUME used to start the chapter with the largest position, i.e. chapter 34, from the top.
        val chapters = (1..39).map { id ->
            val duration = if (id == 34) 1043.0 else 600.0
            chapter(id, position = duration - 10.0, played = true, duration = duration, heardAt = heard(id.toLong()))
        } + chapter(40) + chapter(41)

        assertEquals(40, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `replaying a played chapter does not drag resume back to it`() {
        // What the old RESUME left behind: chapter 34 started again, heard most recently, but still
        // played, because the flag is sticky. It is heard, not somewhere to pick up.
        val chapters = listOf(
            chapter(33, position = 590.0, played = true, heardAt = heard(1)),
            chapter(34, position = 12.0, played = true, heardAt = heard(9)),
            chapter(35, position = 590.0, played = true, heardAt = heard(3)),
            chapter(36),
        )

        assertEquals(36, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `of two chapters left part-way, the one heard last is resumed`() {
        val back = listOf(
            chapter(5, position = 300.0, heardAt = heard(1)),
            chapter(6, played = true, heardAt = heard(2)),
            chapter(7, position = 100.0, heardAt = heard(3)),
            chapter(8),
        )
        // Recency, not order: going back to an earlier chapter is where you left off too.
        val returned = back.map { if (it.id == 5) chapter(5, position = 300.0, heardAt = heard(4)) else it }

        assertEquals(7, resumeChapterIn(back)?.resolvedChapterId)
        assertEquals(5, resumeChapterIn(returned)?.resolvedChapterId)
    }

    @Test
    fun `with no stamps the furthest chapter left part-way is resumed`() {
        val chapters = listOf(chapter(5, position = 300.0), chapter(6, played = true), chapter(7, position = 100.0), chapter(8))

        assertEquals(7, resumeChapterIn(chapters)?.resolvedChapterId)
    }

    @Test
    fun `a chapter stopped in its last twenty seconds counts as heard, not part-way`() {
        // It would start from the top (#451), so resuming it would replay something just finished.
        val chapters = listOf(chapter(1, played = true), chapter(2, position = 590.0), chapter(3))

        assertEquals(3, resumeChapterIn(chapters)?.resolvedChapterId)
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
