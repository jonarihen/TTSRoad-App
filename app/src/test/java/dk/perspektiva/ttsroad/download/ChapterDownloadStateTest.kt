package dk.perspektiva.ttsroad.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapping is checked against Media3's own constants rather than hard-coded ints, so a future
 * media3 bump that renumbers them fails here instead of drawing every download as "not downloaded".
 */
@OptIn(UnstableApi::class)
class ChapterDownloadStateTest {
    @Test
    fun `a completed download reads as downloaded`() {
        assertEquals(
            ChapterDownloadState.Downloaded,
            chapterDownloadState(Download.STATE_COMPLETED),
        )
    }

    @Test
    fun `queued and restarting both read as in-flight work`() {
        assertEquals(ChapterDownloadState.Queued, chapterDownloadState(Download.STATE_QUEUED))
        assertEquals(ChapterDownloadState.Downloading, chapterDownloadState(Download.STATE_RESTARTING))
    }

    @Test
    fun `downloading reads as downloading`() {
        assertEquals(
            ChapterDownloadState.Downloading,
            chapterDownloadState(Download.STATE_DOWNLOADING),
        )
    }

    @Test
    fun `a stopped download reads as queued, not as failure`() {
        // STATE_STOPPED is what Media3 reports while the network requirement is unmet. The row must
        // not accuse the user of a failure for standing in a lift.
        assertEquals(ChapterDownloadState.Queued, chapterDownloadState(Download.STATE_STOPPED))
    }

    @Test
    fun `failed and removing keep their own states`() {
        assertEquals(ChapterDownloadState.Failed, chapterDownloadState(Download.STATE_FAILED))
        assertEquals(ChapterDownloadState.Removing, chapterDownloadState(Download.STATE_REMOVING))
    }

    @Test
    fun `an unrecognised state falls back to not downloaded`() {
        assertEquals(ChapterDownloadState.None, chapterDownloadState(999))
    }

    @Test
    fun `only a completed download counts as playable offline`() {
        assertEquals(
            setOf(ChapterDownloadState.Downloaded),
            ChapterDownloadState.entries.filter { it.isAvailableOffline }.toSet(),
        )
    }

    @Test
    fun `queued, downloading and removing are the states with work in flight`() {
        assertEquals(
            setOf(
                ChapterDownloadState.Queued,
                ChapterDownloadState.Downloading,
                ChapterDownloadState.Removing,
            ),
            ChapterDownloadState.entries.filter { it.isBusy }.toSet(),
        )
    }

    @Test
    fun `an unknown percent shows as zero rather than minus one`() {
        // Media3 reports C.PERCENTAGE_UNSET until the content length is known.
        assertEquals(0, downloadPercent(C.PERCENTAGE_UNSET.toFloat()))
    }

    @Test
    fun `percent is rounded down and clamped`() {
        assertEquals(0, downloadPercent(0f))
        assertEquals(41, downloadPercent(41.9f))
        assertEquals(100, downloadPercent(100f))
        assertEquals(100, downloadPercent(140f))
    }

    @Test
    fun `a nan percent does not crash the row`() {
        assertEquals(0, downloadPercent(Float.NaN))
    }
}
