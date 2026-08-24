package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.StreamingCacheUnlimited
import java.util.Locale

/** Binary units, because that is what the filesystem and Media3's cache actually count in. */
private val StorageUnits = listOf("KB", "MB", "GB", "TB", "PB", "EB")

/**
 * Bytes as the settings readout shows them.
 *
 * Locale is pinned to US so the decimal separator matches the rest of the app's English chrome and
 * so the tests do not depend on the machine's locale.
 */
fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024L) return "${bytes.coerceAtLeast(0L)} B"

    var value = bytes.toDouble() / 1024.0
    var unit = StorageUnits.first()
    for (candidate in StorageUnits) {
        unit = candidate
        if (value < 1024.0 || candidate == StorageUnits.last()) break
        value /= 1024.0
    }

    // One decimal is what makes a download visibly move; past ten units it is just noise.
    return if (value < 10.0) {
        String.format(Locale.US, "%.1f %s", value, unit)
    } else {
        String.format(Locale.US, "%.0f %s", value, unit)
    }
}

/**
 * Total disk taken by [downloads].
 *
 * Counts part-downloaded chapters too: those bytes are on disk whether or not the chapter is
 * playable yet, and a storage readout that ignored them would understate the damage.
 */
fun downloadedBytes(downloads: Collection<ChapterDownload>): Long =
    downloads.sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

/**
 * A streaming-cache cap as its button in Settings reads.
 *
 * Not [formatStorageSize]: these are chosen sizes, not measured ones, so "1 GB" is the honest label
 * where "1.0 GB" reads like a reading off a disk. [StreamingCacheUnlimited] has no size to print at
 * all — it is the absence of a cap, and saying so is the point.
 */
fun streamingCacheChoiceLabel(bytes: Long): String = when {
    bytes == StreamingCacheUnlimited -> "NO LIMIT"
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    else -> "${bytes / (1024L * 1024L)} MB"
}
