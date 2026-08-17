package dk.perspektiva.ttsroad.media

/**
 * The moment a "bookmark this" press should record.
 *
 * @param chapterId the chapter to hang the mark on.
 * @param positionSeconds how far into that chapter the press landed.
 */
data class BookmarkTarget(
    val chapterId: Int,
    val positionSeconds: Double,
)

/**
 * What a press resolved to, and what to say about it.
 *
 * Only the failures carry anywhere to be shown: Media3 surfaces a [androidx.media3.session.SessionError]
 * to the controller — a toast in the car, a message on the notification — and has no success
 * equivalent. That asymmetry is the right way round here. The press itself is the acknowledgement
 * a driver needs, and an interruption that says "saved" is worth less than one that says "not
 * saved" at the moment it still matters.
 */
enum class BookmarkOutcome(val message: String) {
    Written("Bookmark saved"),
    NothingPlaying("Nothing playing to bookmark"),
    Unsupported("This server does not support bookmarks"),
    Failed("Could not save the bookmark"),
}

/**
 * Resolve the moment to mark from what the player is holding, or null if there is nothing to mark.
 *
 * Deliberately takes the chapter id and position rather than the player: this has to be read
 * synchronously, at the instant of the press, before the network write suspends. The whole point of
 * the button is *when* it was pressed — a second of listening is most of a sentence, and resolving
 * the position after the round trip would mark the wrong line.
 *
 * A missing or non-positive chapter id is the "nothing to mark" case rather than an error. It covers
 * an empty player, and equally a queue entry that is not a chapter — the `chapter_id` extra is what
 * the whole progress path keys on, and an item without one is not something the server can hold a
 * bookmark against.
 */
fun bookmarkTargetFor(chapterId: Int?, positionMs: Long): BookmarkTarget? {
    if (chapterId == null || chapterId <= 0) return null
    // Media3 can report a negative position around a seek or a discontinuity, and the server takes
    // seconds. Clamping here keeps a press during that window on the chapter rather than before it.
    return BookmarkTarget(
        chapterId = chapterId,
        positionSeconds = positionMs.coerceAtLeast(0L) / 1000.0,
    )
}
