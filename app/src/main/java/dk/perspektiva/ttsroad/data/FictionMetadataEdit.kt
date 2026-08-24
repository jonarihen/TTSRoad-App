package dk.perspektiva.ttsroad.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Editing scraped metadata by hand: what the form holds, what of it is worth sending, and what
 * counts as a usable cover image.
 *
 * The rules live here rather than in the screen because two of them are easy to get quietly wrong.
 * A `PATCH` marks every field it *sets* as hand-edited, so sending a field nobody touched freezes it
 * against the source forever — the diff below is what stops a form that was opened and closed from
 * doing that. And tags have to be normalised the same way the server normalises them, or the chips
 * under the field are not what ends up stored.
 */

/** How many tags the server keeps, and how long each may be. Mirrored so the UI can say no first. */
const val MaxFictionTags: Int = 50
const val MaxFictionTagLength: Int = 100

/** The ceiling on a cover upload, matching the server's — over this it answers 413. */
const val MaxCoverUploadBytes: Long = 10L * 1024 * 1024

/** The image types the server will store. Anything else is a 400, so it is refused here instead. */
val AcceptedCoverMimeTypes: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
)

/**
 * The editable fields, as text being typed into.
 *
 * Strings rather than nulls throughout: a text field holds "" when it is empty, and the difference
 * between "" and null is decided when the patch is built, not while typing.
 */
data class FictionMetadataDraft(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    /** Raw text of the tags field — comma-separated, because that is how it is typed. */
    val tags: String = "",
) {
    /** The tags as they will be stored, which is what the chips under the field should show. */
    val parsedTags: List<String>
        get() = parseFictionTags(tags)

    /**
     * The server rejects a blank title with a 400, and it is the one field with no "clear it"
     * meaning: a fiction has to be called something.
     */
    val hasUsableTitle: Boolean
        get() = title.isNotBlank()

    companion object {
        fun of(fiction: FictionSummary): FictionMetadataDraft = FictionMetadataDraft(
            title = fiction.title,
            author = fiction.author.orEmpty(),
            description = fiction.description.orEmpty(),
            tags = formatFictionTags(fiction.tags),
        )
    }
}

/**
 * Split a typed tag field into the list the server will store.
 *
 * Trimmed, blanks dropped, over-long tags cut, duplicates removed case-insensitively — the first
 * spelling wins — and capped. The same rules the server applies, applied here so the chips under
 * the field are a preview rather than a guess.
 *
 * Newlines separate as well as commas, because pasting a tag list off a web page brings them.
 */
fun parseFictionTags(input: String): List<String> {
    val seen = HashSet<String>()
    val tags = ArrayList<String>()
    for (raw in input.split(',', '\n')) {
        val tag = raw.trim().take(MaxFictionTagLength).trim()
        if (tag.isEmpty()) continue
        if (!seen.add(tag.lowercase())) continue
        tags.add(tag)
        if (tags.size == MaxFictionTags) break
    }
    return tags
}

/** The inverse, for filling the field from a loaded fiction. */
fun formatFictionTags(tags: List<String>): String = tags.joinToString(", ")

/**
 * What of [draft] is worth sending for [fiction], or null when nothing is.
 *
 * Only changed fields go on the wire. That is not an optimisation: the server records every field a
 * `PATCH` sets as hand-edited and stops refreshing it from the source, so sending the whole form
 * would silently freeze the author and description of a fiction whose title was the only thing
 * anyone meant to fix.
 *
 * An emptied author or description is sent as `""`, which is how they are cleared. An emptied title
 * is not sent at all — see [FictionMetadataDraft.hasUsableTitle], which is what should stop a save
 * from being offered in the first place.
 */
fun fictionMetadataPatch(
    fiction: FictionSummary,
    draft: FictionMetadataDraft,
): FictionUpdateRequest? {
    val title = draft.title.trim()
    val author = draft.author.trim()
    val description = draft.description.trim()
    val tags = draft.parsedTags
    // Compared against the stored tags put through the same field the draft came out of, so
    // opening the editor and saving it untouched is never itself a change. A stored tag containing
    // a comma cannot survive that round trip — it also cannot be typed into this field — and
    // comparing this way leaves it alone rather than rewriting it on an unrelated save.
    val currentTags = parseFictionTags(formatFictionTags(fiction.tags))
    val request = FictionUpdateRequest(
        title = title.takeIf { it.isNotEmpty() && it != fiction.title.trim() },
        author = author.takeIf { it != fiction.author.orEmpty().trim() },
        description = description.takeIf { it != fiction.description.orEmpty().trim() },
        tags = tags.takeIf { it != currentTags },
    )
    return request.takeIf { it != FictionUpdateRequest() }
}

/** A content type as it should be compared: no `; charset=…` parameter, no case, no padding. */
private fun normalisedType(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

/**
 * Why this image cannot be sent as a cover, or null when it can.
 *
 * Checked before the upload rather than after: the server answers 400 for a file it cannot decode
 * and 413 for one over the ceiling, and both of those are worth saying without first pushing ten
 * megabytes up a mobile connection.
 */
fun coverRejectionReason(mimeType: String?, sizeBytes: Long?): String? = when {
    mimeType.isNullOrBlank() ->
        "Could not tell what kind of file that is. Pick a JPEG, PNG, WEBP or GIF."

    normalisedType(mimeType) !in AcceptedCoverMimeTypes ->
        "Covers have to be a JPEG, PNG, WEBP or GIF."

    sizeBytes != null && sizeBytes <= 0L -> "That file is empty."
    sizeBytes != null && sizeBytes > MaxCoverUploadBytes ->
        "That image is over the 10 MB limit. Pick a smaller one."

    else -> null
}

/**
 * Read at most [limit] bytes, or null when the stream holds more than that.
 *
 * A picked image is read into memory to be uploaded, and "how big is it" is a question the content
 * resolver is allowed to shrug at — so the limit is enforced by refusing to read past it rather
 * than by trusting a size that may not be there. One byte over the limit is enough to know.
 */
fun readAtMost(input: InputStream, limit: Long): ByteArray? {
    val ceiling = limit + 1
    val buffer = ByteArray(16 * 1024)
    val collected = ByteArrayOutputStream()
    while (collected.size() < ceiling) {
        val read = input.read(buffer)
        if (read < 0) break
        collected.write(buffer, 0, read)
    }
    return collected.toByteArray().takeIf { it.size <= limit }
}

/** A name for the multipart part. The extension is what the server hashes the cover under. */
fun coverFilename(mimeType: String): String = when (normalisedType(mimeType)) {
    "image/png" -> "cover.png"
    "image/webp" -> "cover.webp"
    "image/gif" -> "cover.gif"
    else -> "cover.jpg"
}

/** An image chosen on the device, read and ready to upload — or the reason it cannot be. */
sealed interface PickedCover {
    /** Not a data class on purpose: two identical images are not usefully "equal". */
    class Ready(val bytes: ByteArray, val mimeType: String) : PickedCover

    data class Rejected(val message: String) : PickedCover
}

/**
 * Read an image the user picked into memory, refusing anything the server would refuse.
 *
 * The type is checked before a byte is read, and the size is enforced by declining to read past the
 * ceiling rather than by asking how big the file is — a document provider is entitled not to know,
 * and a cloud-backed picture is streamed from somewhere else entirely.
 *
 * Never throws. A provider that has gone away, a permission that lapsed on rotation, or a file that
 * disappeared between the picker and here are all ordinary things on this path, and none of them is
 * worth a crash in front of a form the user has just filled in.
 */
fun readPickedCover(resolver: ContentResolver, uri: Uri): PickedCover {
    val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
    coverRejectionReason(mimeType, null)?.let { return PickedCover.Rejected(it) }
    val bytes = try {
        val stream = resolver.openInputStream(uri)
            ?: return PickedCover.Rejected("Could not open that image.")
        stream.use { readAtMost(it, MaxCoverUploadBytes) }
    } catch (e: Exception) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "Could not read that image."
        return PickedCover.Rejected(reason)
    } ?: return PickedCover.Rejected(
        // readAtMost answers null only for a file past the ceiling, which is a different thing to
        // tell someone than "could not read it": the picture is fine, it is just too big to send.
        coverRejectionReason(mimeType, MaxCoverUploadBytes + 1) ?: "That image is too large.",
    )
    if (bytes.isEmpty()) return PickedCover.Rejected("That file is empty.")
    return PickedCover.Ready(bytes, checkNotNull(mimeType))
}
