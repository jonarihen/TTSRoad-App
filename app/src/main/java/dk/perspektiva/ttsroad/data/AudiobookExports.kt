package dk.perspektiva.ttsroad.data

import java.time.ZoneId
import java.util.Locale

/**
 * Turning `GET /api/mobile/exports` into the lines Settings actually shows (#113).
 *
 * The surface is read-only on purpose — starting an export and deleting one stay on the web — so
 * everything here is presentation: a name, one line of numbers, when it finished, and the URL to
 * hand somewhere else. There is no state to hold and nothing to write back.
 */

/** One finished M4B file, as a row of the Settings list. */
data class AudiobookExportRow(
    val id: Int,
    val title: String,
    /** Size, running time and chapter range on one line — the three things worth comparing. */
    val detail: String,
    /** When the encode finished, in the phone's zone, or null when the server did not say. */
    val finished: String?,
    /** Absolute and pointed at the host this phone signed in to, or null when there is none. */
    val downloadUrl: String?,
)

/**
 * The finished exports as rows, in the order the server listed them (newest batch first, parts in
 * order).
 *
 * [resolveUrl] is how the caller points a download at the host the phone actually reached. The
 * backend builds the URL from its own configured `BASE_URL`, which may be an address this device
 * has never been able to resolve — and is a *relative* path when `BASE_URL` is unset, which is
 * worse than useless in a share sheet. Passed in rather than done here so this file stays free of
 * the rest of the app; `ServerUrls.rewriteHost` is what the caller supplies.
 */
fun audiobookExportRows(
    response: AudiobookExportsResponse?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    resolveUrl: (String) -> String = { it },
): List<AudiobookExportRow> =
    response?.exports.orEmpty().map { export ->
        AudiobookExportRow(
            id = export.id,
            title = exportTitle(export),
            detail = exportDetail(export),
            // The completion time is the one that answers "did the export I started finish?".
            // Falling back to when it was queued keeps a row from looking undated on a server that
            // never wrote one.
            finished = formatServerTimestamp(export.completedAt ?: export.createdAt, zone, locale),
            downloadUrl = export.downloadUrl?.takeIf { it.isNotBlank() }?.let(resolveUrl),
        )
    }

/**
 * What to say about a server that cannot currently encode, or null when it can.
 *
 * Reported per request rather than as a capability, and the distinction matters: `audiobook_export`
 * says the route exists, `ffmpeg_available` says the machine behind it has the tool. Without this
 * line an ffmpeg-less server is indistinguishable from one nobody has exported anything from yet.
 */
fun audiobookExportEncoderNote(response: AudiobookExportsResponse?): String? {
    if (response == null || response.ffmpegAvailable) return null
    return "This server cannot make new exports: ffmpeg is not installed on it. Anything it " +
        "already encoded still downloads."
}

/**
 * The name to put on the row.
 *
 * The fiction's title first, because that is what someone is looking for. The export's own title is
 * the fallback and already carries a part suffix of its own on a split batch, which is why the part
 * label below is only appended to a fiction title.
 */
private fun exportTitle(export: AudiobookExport): String {
    val fiction = export.fictionTitle?.takeIf { it.isNotBlank() }
        ?: return export.title?.takeIf { it.isNotBlank() }
            ?: export.filename?.takeIf { it.isNotBlank() }
            ?: "Export ${export.id}"
    // A row is a file, not a request: a long serial exports as several volumes sharing a batch, and
    // without this every one of them would be the same line repeated.
    return if (export.partCount > 1) {
        "$fiction — part ${export.partIndex} of ${export.partCount}"
    } else {
        fiction
    }
}

/**
 * Size, running time and how much of the book is in the file.
 *
 * The server's own [AudiobookExport.sizeLabel] and [AudiobookExport.durationLabel] are used as sent.
 * They are the strings the web storage page quotes, and a phone that rounded the same bytes
 * differently would turn "which file is this" into a question with two answers.
 */
private fun exportDetail(export: AudiobookExport): String =
    listOfNotNull(
        export.sizeLabel?.takeIf { it.isNotBlank() },
        export.durationLabel?.takeIf { it.isNotBlank() },
        chapterSpan(export),
    ).joinToString(" · ")

/**
 * "chapters 1–120", or a plain count when the chapters are not numbered.
 *
 * `chapter_number` is nullable server-side — an unnumbered interlude is a real thing — so the range
 * is only shown when both ends of it exist.
 */
private fun chapterSpan(export: AudiobookExport): String? {
    val first = export.firstChapterNumber
    val last = export.lastChapterNumber
    return when {
        first != null && last != null && first != last -> "chapters $first–$last"
        first != null && last != null -> "chapter $first"
        export.chapterCount > 0 -> "${export.chapterCount} chapters"
        else -> null
    }
}
