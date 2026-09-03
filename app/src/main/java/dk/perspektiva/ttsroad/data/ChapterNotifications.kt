package dk.perspektiva.ttsroad.data

/**
 * New chapters on the serials this account follows, from pulled to playable (#175).
 *
 * The rule the whole feature is shaped around lives on the server: a notice is raised when a
 * chapter is pulled and stays open until it is listenable, and a dismissal of a converting chapter
 * is answered with a 409. Nothing here re-derives that — [ChapterNotificationEntry.dismissible] and
 * [ChapterNotificationEntry.playable] come off the wire.
 *
 * What *is* decided here is the one thing the server cannot know: which notices are **news to this
 * handset**, and therefore worth a system notification. See [newlyReady].
 */
enum class ChapterNotificationState(val wire: String) {
    Pulled("pulled"),
    Stalled("stalled"),
    Ready("ready"),
    Dismissed("dismissed"),
    ;

    companion object {
        /**
         * An unrecognised state reads as [Pulled], never [Ready].
         *
         * A server newer than this build may name a state this one has never heard of. "Something
         * is happening to this chapter" keeps the notice on screen and refuses to dismiss it, which
         * is the safe reading; guessing [Ready] would offer Play for audio that may not exist.
         */
        fun fromWire(value: String?): ChapterNotificationState =
            entries.firstOrNull { it.wire == value } ?: Pulled
    }
}

val ChapterNotificationEntry.presentation: ChapterNotificationState
    get() = ChapterNotificationState.fromWire(state)

/** "Chapter 412 · converting 62%", or what happened instead. */
fun ChapterNotificationEntry.detailLabel(): String {
    val chapterLabel = chapter.chapterNumber?.let { "Chapter $it" } ?: chapter.title
    val state = when (presentation) {
        ChapterNotificationState.Ready -> "ready to listen"
        ChapterNotificationState.Stalled -> "conversion failed"
        ChapterNotificationState.Dismissed -> "dismissed"
        ChapterNotificationState.Pulled -> chapter.ttsProgress?.let { "converting $it%" } ?: "converting"
    }
    return "$chapterLabel  ·  $state"
}

/**
 * Which notices have *become* ready since [alreadySeen], and the set to remember next time.
 *
 * The first look of a session announces **nothing**: `alreadySeen` is null then, and a chapter that
 * was already ready when the app started is not news — the app was closed when it happened. Without
 * that, every cold start would re-announce the whole backlog, which is exactly how a notification
 * channel gets muted.
 *
 * Pure, so the rule is assertable without a notification manager or a network.
 */
fun newlyReady(
    notifications: List<ChapterNotificationEntry>,
    alreadySeen: Set<Int>?,
): Pair<List<ChapterNotificationEntry>, Set<Int>> {
    val readyNow = notifications.filter { it.presentation == ChapterNotificationState.Ready }
    val ids = readyNow.map { it.id }.toSet()
    if (alreadySeen == null) return emptyList<ChapterNotificationEntry>() to ids
    return readyNow.filter { it.id !in alreadySeen } to ids
}

/**
 * Title and body for a batch that just became ready, or null for nothing.
 *
 * Collapsed above a single chapter, because a serial converting a backlog would otherwise post a
 * dozen notifications at once. On a phone that is not merely noisy — it buries everything else in
 * the shade.
 */
fun readyNotificationText(fresh: List<ChapterNotificationEntry>): Pair<String, String>? = when {
    fresh.isEmpty() -> null
    fresh.size == 1 -> {
        val item = fresh.single()
        item.fiction.title to "${item.chapter.title} is ready to listen"
    }
    else -> {
        val serials = fresh.map { it.fiction.title }.distinct()
        val where = if (serials.size == 1) serials.single() else "${serials.size} serials"
        "${fresh.size} chapters ready" to "New audio in $where"
    }
}

/** Rows worth drawing. Dismissed ones are not requested, but a stale list can still hold one. */
fun visibleNotifications(
    notifications: List<ChapterNotificationEntry>,
): List<ChapterNotificationEntry> =
    notifications.filter { it.presentation != ChapterNotificationState.Dismissed }

/**
 * What an empty list means, which is usually good news.
 *
 * Same judgement as `serverLogsEmptyNote`: on most screens an empty result is a disappointment,
 * here it means every serial you follow is up to date.
 */
fun chapterNotificationsEmptyNote(followsAnything: Boolean): String =
    if (followsAnything) {
        "Every serial you follow is up to date."
    } else {
        "Follow a serial and its next chapter will appear here while it converts."
    }
