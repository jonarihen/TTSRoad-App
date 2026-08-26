package dk.perspektiva.ttsroad.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Picking a book off the phone and getting it to the server.
 *
 * EPUBs arrive on phones — bought in a shop app, mailed to yourself, sideloaded — and until now the
 * only way into the library was a laptop with a browser open. The server has taken multipart
 * uploads on `POST /api/mobile/fictions/upload-epub` all along.
 *
 * Two rules are worth stating because both are the server's and neither is obvious from here:
 *
 * - **The server validates the file *name*, not its type.** `upload_epub` refuses anything whose
 *   filename does not end in `.epub` and never looks at the content type — so that, and not the
 *   MIME type a document provider happens to report, is what this file checks. A provider calling a
 *   perfectly good book `application/octet-stream` is common enough that typing on it would refuse
 *   files the server would have taken.
 * - **The ceiling is advertised.** `/api/mobile/capabilities` publishes `max_epub_bytes` precisely
 *   so a client can say no before spending a mobile connection on a hundred megabytes that come
 *   back as a 413. [DefaultMaxEpubBytes] is the fallback for a server that has the route but does
 *   not publish the limit.
 */

/**
 * The ceiling the server compiles in (`EPUB_MAX_BYTES`), used when it does not advertise one.
 *
 * A guess, but not an arbitrary one: it is the documented limit, the number the web console's own
 * upload form prints, and refusing at it is the same answer the server would give.
 */
const val DefaultMaxEpubBytes: Long = 100L * 1024 * 1024

/**
 * What to hint the document picker with.
 *
 * `application/octet-stream` is in the list on purpose. Half the providers on a phone — downloads,
 * a file manager over SMB, a cloud drive — hand an EPUB back under the generic type, and filtering
 * on the precise one hides the very files this button exists to reach. The filename check below is
 * what actually decides, so a loose filter costs nothing but a longer list to scroll.
 */
val EpubPickerMimeTypes: Array<String> = arrayOf(
    "application/epub+zip",
    "application/x-epub+zip",
    "application/octet-stream",
)

/** Long enough for any real book title, short enough that no header field has to think about it. */
private const val MaxEpubFilenameLength = 120

private const val EpubExtension = ".epub"

private val EpubMediaType: MediaType = "application/epub+zip".toMediaType()

/**
 * Why this file cannot be uploaded, or null when it can.
 *
 * [sizeBytes] is null when the provider declines to say how big the file is, which it is entitled
 * to do — a cloud-backed document may not know until it has been fetched. That is not a refusal:
 * the server still enforces its own ceiling, and refusing a book because a provider was vague would
 * block an upload that would have worked.
 */
fun epubRejectionReason(
    filename: String?,
    sizeBytes: Long?,
    maxBytes: Long = DefaultMaxEpubBytes,
): String? {
    val name = filename?.trim()
    return when {
        name.isNullOrEmpty() ->
            "Could not tell what that file is called. Pick a file whose name ends in .epub."

        !name.endsWith(EpubExtension, ignoreCase = true) ->
            "The server only takes files ending in .epub."

        sizeBytes != null && sizeBytes <= 0L -> "That file is empty."

        sizeBytes != null && sizeBytes > maxBytes ->
            "That book is ${megabyteLabel(sizeBytes)}, over this server's " +
                "${megabyteLabel(maxBytes)} limit."

        else -> null
    }
}

/** A byte count as a person would say it. Whole megabytes over ten; one decimal under. */
fun megabyteLabel(bytes: Long): String {
    val megabytes = bytes.toDouble() / (1024 * 1024)
    if (megabytes >= 10) return "${Math.round(megabytes)} MB"
    // Rounded down to a tenth rather than to the nearest one: a 9.99 MB file described as "10 MB"
    // next to a "10 MB limit" reads as the reason it was refused, and it is not.
    val tenths = (megabytes * 10).toLong()
    if (tenths % 10 == 0L) return "${tenths / 10} MB"
    return "${tenths / 10}.${tenths % 10} MB"
}

/**
 * A name for the multipart part, derived from what the user's file is actually called.
 *
 * Sanitised rather than passed through: a display name is arbitrary text from a provider, and a
 * newline or a quote in it does not make a bad upload — it makes OkHttp throw while building the
 * header, in front of a picker the user has just used. Any directory part is dropped for the same
 * reason it is on a web form: the server has no use for it, and it is not this client's to send.
 *
 * The extension survives everything, including truncation, because it is the one part of the name
 * the server actually reads.
 */
fun epubUploadFilename(displayName: String?): String {
    val bare = displayName.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() || it == '"' }
        .trim()
    if (!bare.endsWith(EpubExtension, ignoreCase = true) || bare.length == EpubExtension.length) {
        return "book$EpubExtension"
    }
    if (bare.length <= MaxEpubFilenameLength) return bare
    return bare.take(MaxEpubFilenameLength - EpubExtension.length) + EpubExtension
}

/** A book chosen on the device, ready to send — or the reason it cannot be. */
sealed interface PickedEpub {
    /**
     * Not the bytes: a *way to read* them, plus what the picker said about them.
     *
     * A hundred-megabyte `ByteArray` on a phone is an out-of-memory crash waiting for the one
     * illustrated book that reaches the limit, so the file is streamed off the content provider
     * straight into the socket and never exists in the heap at once. That is also why this is not a
     * data class — two picks of the same file are not usefully "equal".
     */
    class Ready(
        val filename: String,
        /** As the provider reported it, or null when it would not say. */
        val sizeBytes: Long?,
        val open: () -> InputStream,
    ) : PickedEpub

    data class Rejected(val message: String) : PickedEpub
}

/**
 * Look at what the user picked and decide whether it is worth uploading.
 *
 * Nothing is read here beyond the provider's own metadata: the point of the check is to refuse an
 * oversized or misnamed file *before* a byte leaves the phone. The stream is opened once and closed
 * again, which is cheap and turns "that document is gone" into a message in front of the picker
 * rather than a failure halfway through an upload.
 *
 * Never throws. A provider that has gone away, a permission that lapsed on rotation and a file that
 * disappeared between the picker and here are all ordinary on this path.
 */
fun readPickedEpub(
    resolver: ContentResolver,
    uri: Uri,
    maxBytes: Long = DefaultMaxEpubBytes,
): PickedEpub {
    val (displayName, sizeBytes) = pickedFileMetadata(resolver, uri)
    epubRejectionReason(displayName, sizeBytes, maxBytes)?.let { return PickedEpub.Rejected(it) }
    val opened = try {
        resolver.openInputStream(uri)
    } catch (e: Exception) {
        return PickedEpub.Rejected(e.message?.takeIf { it.isNotBlank() } ?: "Could not open that book.")
    } ?: return PickedEpub.Rejected("Could not open that book.")
    runCatching { opened.close() }
    return PickedEpub.Ready(
        filename = epubUploadFilename(displayName),
        sizeBytes = sizeBytes,
        open = {
            resolver.openInputStream(uri) ?: throw IOException("Could not open that book.")
        },
    )
}

/**
 * The picked book as a request body that streams.
 *
 * The length is deliberately left unknown, so OkHttp sends the file chunked. The size the picker
 * reported is good enough to refuse an oversized book with and not good enough to *promise* on the
 * wire: a provider whose file changed since the query would make OkHttp fail the request after the
 * bytes had already been spent, which is the one failure this whole path exists to avoid.
 */
fun PickedEpub.Ready.requestBody(): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType = EpubMediaType

    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        open().use { input -> sink.writeAll(input.source()) }
    }
}

/**
 * The display name and size a document provider will admit to.
 *
 * `OpenableColumns` is the documented pair for a SAF document, and every column of it is optional.
 * The URI's last path segment is the fallback for the name, which is right often enough to be worth
 * trying and is checked against the same rule either way.
 */
private fun pickedFileMetadata(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/')
    val cursor = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
    }.getOrNull() ?: return fallbackName to null
    return cursor.use {
        if (!it.moveToFirst()) return@use fallbackName to null
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
        val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
        (name?.takeIf { value -> value.isNotBlank() } ?: fallbackName) to size
    }
}
