package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import org.junit.Assert.assertEquals
import org.junit.Test

private fun chapter(id: Int, playable: Boolean = true) = ChapterSummary(
    id = id,
    fictionId = 1,
    title = "Chapter $id",
    audio = if (playable) AudioInfo(url = "/audio/f/%04d.mp3".format(id)) else null,
)

private fun downloads(vararg entries: Pair<Int, ChapterDownload>) =
    entries.associate { (id, download) -> TtsRoadMediaIds.chapter(id) to download }

private fun done(bytes: Long) =
    ChapterDownload(ChapterDownloadState.Downloaded, percent = 100, bytesDownloaded = bytes)

class FictionDownloadSummaryTest {
    private val fiction = (1..5).map { chapter(it) }

    @Test
    fun `nothing downloaded means every playable chapter is still remaining`() {
        val summary = fictionDownloadSummary(fiction, emptyMap())

        assertEquals(FictionDownloadSummary(downloaded = 0, inFlight = 0, remaining = 5, bytes = 0), summary)
    }

    @Test
    fun `counts what is on disk, what is moving, and what is left`() {
        val summary = fictionDownloadSummary(
            fiction,
            downloads(
                1 to done(bytes = 10),
                2 to done(bytes = 20),
                3 to ChapterDownload(ChapterDownloadState.Downloading, percent = 40, bytesDownloaded = 5),
                4 to ChapterDownload(ChapterDownloadState.Queued),
            ),
        )

        assertEquals(2, summary.downloaded)
        assertEquals(2, summary.inFlight)
        assertEquals(1, summary.remaining)
        assertEquals(35L, summary.bytes)
    }

    @Test
    fun `a failed download counts as remaining, so the batch action retries it`() {
        val summary = fictionDownloadSummary(
            fiction,
            downloads(1 to ChapterDownload(ChapterDownloadState.Failed)),
        )

        assertEquals(0, summary.downloaded)
        assertEquals(0, summary.inFlight)
        assertEquals(5, summary.remaining)
    }

    @Test
    fun `chapters with no audio are not counted as anything`() {
        val summary = fictionDownloadSummary(
            listOf(chapter(1), chapter(2, playable = false), chapter(3, playable = false)),
            emptyMap(),
        )

        assertEquals(1, summary.remaining)
    }

    @Test
    fun `downloads belonging to other fictions are ignored`() {
        val summary = fictionDownloadSummary(fiction, downloads(99 to done(bytes = 4_000)))

        assertEquals(0, summary.downloaded)
        assertEquals(0L, summary.bytes)
        assertEquals(5, summary.remaining)
    }

    @Test
    fun `the handled set is what the batch selection skips`() {
        val map = downloads(
            1 to done(bytes = 1),
            2 to ChapterDownload(ChapterDownloadState.Queued),
            3 to ChapterDownload(ChapterDownloadState.Failed),
        )

        assertEquals(setOf(1, 2), handledChapterIds(fiction, map))
    }
}
