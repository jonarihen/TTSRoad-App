package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary

/** Destinations the phone UI can show. */
sealed interface AppScreen {
    data object Library : AppScreen
    data object Fictions : AppScreen
    data class Fiction(val fiction: FictionSummary) : AppScreen
    data object Player : AppScreen

    /**
     * The read-along reader for one chapter.
     *
     * Carries the chapter id rather than a [dk.perspektiva.ttsroad.data.ChapterSummary] because the
     * reader is reachable from the player, where all that is known is the media id of what is
     * playing — there is no loaded chapter row to hand over. For the same reason there is no fiction
     * id here: the loaded document carries one, and a placeholder would only be wrong. [title] is
     * what the top bar shows until the document arrives.
     */
    data class Reader(
        val chapterId: Int,
        val title: String,
    ) : AppScreen

    data object Settings : AppScreen

    /** The account's other mobile sign-ins, reached from Settings. */
    data object Devices : AppScreen

    /** Every bookmark on the account, newest first. Reached from Settings and from the player. */
    data object Bookmarks : AppScreen
}

/** Stable key for per-entry saved UI state (scroll offsets, search text). */
val AppScreen.saveKey: String
    get() = when (this) {
        AppScreen.Library -> "Library"
        AppScreen.Fictions -> "Fictions"
        is AppScreen.Fiction -> "Fiction:${fiction.id}"
        AppScreen.Player -> "Player"
        // Keyed by chapter, so reading on to the next one starts at the top of the new chapter
        // rather than restoring the scroll position of the one just finished.
        is AppScreen.Reader -> "Reader:$chapterId"
        AppScreen.Settings -> "Settings"
        AppScreen.Devices -> "Devices"
        AppScreen.Bookmarks -> "Bookmarks"
    }

/** The stack every session starts from. */
val rootBackStack: List<AppScreen> = listOf(AppScreen.Library)

/**
 * Push [screen] onto the stack. Re-navigating to a destination that is already open pops back to
 * it instead of stacking a second copy, so loops such as Fiction -> Player -> Fiction stay bounded.
 */
fun List<AppScreen>.navigateTo(screen: AppScreen): List<AppScreen> {
    val existing = indexOf(screen)
    return when {
        existing == lastIndex -> this
        existing >= 0 -> subList(0, existing + 1).toList()
        else -> this + screen
    }
}

/** Pop the top entry. The root entry is never popped, so the stack is never empty. */
fun List<AppScreen>.popScreen(): List<AppScreen> =
    if (size > 1) subList(0, size - 1).toList() else this
