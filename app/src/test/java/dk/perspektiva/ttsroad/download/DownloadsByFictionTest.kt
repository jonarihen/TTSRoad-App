package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsByFictionTest {
    private fun done(fictionId: Int, bytes: Long) =
        ChapterDownload(ChapterDownloadState.Downloaded, bytesDownloaded = bytes, fictionId = fictionId)

    private fun chapter(id: Int, played: Boolean) =
        ChapterSummary(id = id, fictionId = 1, title = "C$id", playback = PlaybackInfo(isPlayed = played))

    @Test
    fun `complete downloads are grouped per fiction, biggest first, and unfinished ones are left out`() {
        val groups = downloadsByFiction(
            mapOf(
                "chapter:1" to done(7, 100),
                "chapter:2" to done(7, 50),
                "chapter:3" to done(9, 400),
                "chapter:4" to ChapterDownload(ChapterDownloadState.Downloading, fictionId = 7, bytesDownloaded = 999),
                "other" to done(7, 1),
            ),
        )

        assertEquals(listOf(9, 7), groups.map { it.fictionId })
        assertEquals(FictionDownloads(7, listOf(1, 2), 150), groups[1])
    }

    @Test
    fun `only downloaded chapters that were played are offered, and an unknown list offers nothing`() {
        val chapters = listOf(chapter(1, played = true), chapter(2, played = false), chapter(3, played = true))

        assertEquals(listOf(1, 3), playedAmong(listOf(1, 2, 3, 4), chapters))
        assertEquals(emptyList<Int>(), playedAmong(listOf(1, 2), null))
    }
}
