package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterNotificationChapter
import dk.perspektiva.ttsroad.data.ChapterNotificationEntry
import dk.perspektiva.ttsroad.data.ChapterNotificationFiction
import dk.perspektiva.ttsroad.data.ChapterSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class NewChapterAutoDownloadTest {
    private fun notice(fictionId: Int, chapterId: Int, playable: Boolean = true) = ChapterNotificationEntry(
        id = chapterId,
        state = "ready",
        playable = playable,
        fiction = ChapterNotificationFiction(id = fictionId),
        chapter = ChapterNotificationChapter(id = chapterId),
    )

    private fun chapter(id: Int, audio: Boolean = true) = ChapterSummary(
        id = id,
        fictionId = 7,
        title = "C$id",
        audio = if (audio) AudioInfo(url = "/audio/$id.mp3") else null,
    )

    @Test
    fun `targets are the playable fresh notices grouped per fiction, and nothing when off`() {
        val fresh = listOf(notice(7, 1), notice(7, 2), notice(9, 5), notice(9, 6, playable = false), notice(0, 8))

        assertEquals(mapOf(7 to setOf(1, 2), 9 to setOf(5)), newChapterDownloadTargets(fresh, enabled = true))
        assertEquals(emptyMap<Int, Set<Int>>(), newChapterDownloadTargets(fresh, enabled = false))
    }

    @Test
    fun `only wanted chapters with audio that are not already downloaded or queued are fetched`() {
        val chapters = listOf(chapter(1), chapter(2), chapter(3, audio = false), chapter(4), chapter(5))
        val downloads = mapOf(
            "chapter:2" to ChapterDownload(ChapterDownloadState.Downloaded),
            "chapter:4" to ChapterDownload(ChapterDownloadState.Failed),
        )

        // A failed download is retried; one already on disk is left exactly as it is.
        assertEquals(
            listOf(1, 4),
            newChaptersToDownload(chapters, setOf(1, 2, 3, 4), downloads).map { it.resolvedChapterId },
        )
    }
}
