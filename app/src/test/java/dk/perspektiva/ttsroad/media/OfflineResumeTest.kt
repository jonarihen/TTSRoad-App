package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import dk.perspektiva.ttsroad.widget.NowPlayingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #327: pressing play in an underground car park, with the server out of reach. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineResumeTest {
    private val fiction = FictionSummary(id = 3, title = "Ashes of Aether")
    private val chapter = ChapterSummary(
        id = 42,
        fictionId = 3,
        title = "Chapter 212",
        audio = AudioInfo(url = "https://server.example.com/audio/42.mp3"),
        playback = PlaybackInfo(positionSeconds = 100.0),
    )
    private val server = "https://server.example.com"

    private fun resume(downloaded: Boolean, note: NowPlayingSnapshot? = null) =
        offlineResume(chapter, fiction, server, isDownloaded = { downloaded && it == "chapter:42" }, note = note)

    @Test
    fun `a downloaded chapter plays from where the library left it`() {
        val play = resume(downloaded = true) as OfflineResume.Play

        assertEquals("chapter:42", play.item.mediaId)
        assertEquals(100_000L, play.startPositionMs)
        assertEquals(42, play.item.mediaMetadata.extras?.getInt("chapter_id"))
    }

    @Test
    fun `the widget's newer note for the same chapter wins`() {
        val note = NowPlayingSnapshot(mediaId = "chapter:42", fictionId = 3, chapterId = 42, positionMs = 130_000L)

        assertEquals(130_000L, (resume(downloaded = true, note = note) as OfflineResume.Play).startPositionMs)
    }

    @Test
    fun `a near-end unplayed chapter from a timer restarts offline instead of advancing immediately`() {
        for (noteDuration in listOf(300_000L, 0L)) {
            val stopped = chapter.copy(audioDuration = 300.0, playback = PlaybackInfo(positionSeconds = 300.0))
            val next = ChapterSummary(id = 43, fictionId = 3, title = "Chapter 213")
            val note = NowPlayingSnapshot(chapterId = 42, positionMs = 300_000L, durationMs = noteDuration)
            val target = resumeChapterIn(listOf(stopped, next))!!
            assertEquals(42, target.resolvedChapterId)
            assertEquals(0.0, target.startPositionSeconds, 0.0)
            val play = offlineResume(target, fiction, server, { true }, note) as OfflineResume.Play
            assertEquals("chapter:42", play.item.mediaId)
            assertEquals(0L, play.startPositionMs)
        }
    }

    @Test
    fun `a note about another chapter is ignored`() {
        val note = NowPlayingSnapshot(mediaId = "chapter:7", fictionId = 3, chapterId = 7, positionMs = 130_000L)

        assertEquals(100_000L, (resume(downloaded = true, note = note) as OfflineResume.Play).startPositionMs)
    }

    @Test
    fun `a chapter that is not downloaded says so by name`() {
        val unavailable = resume(downloaded = false)

        assertTrue(unavailable is OfflineResume.Unavailable)
        assertEquals(
            "Can't reach the server, and \"Chapter 212\" is not downloaded",
            (unavailable as OfflineResume.Unavailable).message,
        )
    }
}
