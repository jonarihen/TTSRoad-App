package dk.perspektiva.ttsroad.download

import org.junit.Assert.assertEquals
import org.junit.Test

private const val KB = 1024L
private const val MB = 1024L * KB
private const val GB = 1024L * MB
private const val TB = 1024L * GB

class StorageSizeTest {
    @Test
    fun `nothing downloaded reads as zero, not as an empty string`() {
        assertEquals("0 B", formatStorageSize(0))
    }

    @Test
    fun `raw bytes below a kilobyte are shown as bytes`() {
        assertEquals("512 B", formatStorageSize(512))
        assertEquals("1023 B", formatStorageSize(1023))
    }

    @Test
    fun `small values keep one decimal so a part-downloaded chapter still moves`() {
        assertEquals("1.0 KB", formatStorageSize(KB))
        assertEquals("1.5 KB", formatStorageSize(KB + 512))
        assertEquals("1.0 MB", formatStorageSize(MB))
        assertEquals("2.5 GB", formatStorageSize(GB * 5 / 2))
    }

    @Test
    fun `larger values drop the decimal, which is noise at that scale`() {
        assertEquals("24 MB", formatStorageSize(MB * 24))
        assertEquals("512 MB", formatStorageSize(MB * 512))
        assertEquals("64 GB", formatStorageSize(GB * 64))
    }

    @Test
    fun `a very large library still formats rather than overflowing`() {
        assertEquals("4.0 TB", formatStorageSize(TB * 4))
        assertEquals("8.0 EB", formatStorageSize(Long.MAX_VALUE))
    }

    @Test
    fun `a negative size cannot be rendered and reads as zero`() {
        assertEquals("0 B", formatStorageSize(-1))
    }

    @Test
    fun `the total counts part-downloaded chapters, because they occupy the disk too`() {
        val downloads = listOf(
            ChapterDownload(ChapterDownloadState.Downloaded, percent = 100, bytesDownloaded = 20 * MB),
            ChapterDownload(ChapterDownloadState.Downloading, percent = 50, bytesDownloaded = 5 * MB),
            ChapterDownload(ChapterDownloadState.None),
        )

        assertEquals(25 * MB, downloadedBytes(downloads))
    }

    @Test
    fun `an empty set of downloads totals zero`() {
        assertEquals(0L, downloadedBytes(emptyList()))
    }
}
