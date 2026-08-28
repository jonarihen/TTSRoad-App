package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary

/** Destinations the phone UI can show. */
sealed interface AppScreen {
    data object Library : AppScreen
    data object Fictions : AppScreen
    /** Stable hub for playback-adjacent content: queue, marks, reports, stats and history. */
    data object Listening : AppScreen
    data class Fiction(val fiction: FictionSummary) : AppScreen

    /**
     * The metadata editor for one fiction — admin-only, reached from the fiction screen.
     *
     * Carries the whole [FictionSummary] rather than an id because the form is filled from it, and
     * the screen it was opened from already holds the same row. [withFiction] is what keeps the two
     * copies in step after a save.
     */
    data class FictionEdit(val fiction: FictionSummary) : AppScreen

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

    /** Every bookmark on the account, newest first. Reached from Listening and from the player. */
    data object Bookmarks : AppScreen

    /**
     * The mispronunciations captured from the player and the car, newest first. Reached from
     * Listening.
     *
     * Exists because the capture action creates open work for somebody: a press that files a report
     * and offers no way to see or unfile it is a press nobody makes twice. Read-only apart from
     * delete — resolving one, and reading a whole fiction's, are admin jobs and live on the web.
     */
    data object PronunciationReports : AppScreen

    /**
     * The cross-library Up Next queue. Reached from the player and from Listening.
     *
     * The queue has been writable since 0.11.0 and had nowhere to be looked at: a chapter could be
     * added from the long-press sheet to a list that could not be seen, corrected or emptied
     * without a car or a browser (#108).
     */
    data object Queue : AppScreen

    /**
     * Listening statistics, reached from Listening.
     *
     * Its own destination rather than a Settings card because it is the one screen here that is
     * read rather than operated, and because it holds two independent sources — what this device
     * recorded, and what the account's server has aggregated — neither of which fits in a row.
     */
    data object Stats : AppScreen

    /**
     * The server's own pipeline log, reached from Listening. Admin-only and read-only (#124).
     *
     * Its own destination rather than a Settings card for the same reason [Stats] is one: it is
     * read rather than operated, it pages, and it carries filters of its own. A card holding fifty
     * log lines inside a page that already scrolls is not a card.
     */
    data object Logs : AppScreen
}

/** Stable key for per-entry saved UI state (scroll offsets, search text). */
val AppScreen.saveKey: String
    get() = when (this) {
        AppScreen.Library -> "Library"
        AppScreen.Fictions -> "Fictions"
        AppScreen.Listening -> "Listening"
        is AppScreen.Fiction -> "Fiction:${fiction.id}"
        // Keyed by fiction and not by its contents, so saving an edit does not throw away the form
        // state of the screen that is still open behind the save.
        is AppScreen.FictionEdit -> "FictionEdit:${fiction.id}"
        AppScreen.Player -> "Player"
        // Keyed by chapter, so reading on to the next one starts at the top of the new chapter
        // rather than restoring the scroll position of the one just finished.
        is AppScreen.Reader -> "Reader:$chapterId"
        AppScreen.Settings -> "Settings"
        AppScreen.Devices -> "Devices"
        AppScreen.Bookmarks -> "Bookmarks"
        AppScreen.PronunciationReports -> "PronunciationReports"
        AppScreen.Queue -> "Queue"
        AppScreen.Stats -> "Stats"
        AppScreen.Logs -> "Logs"
    }

/** The stack every session starts from. */
val rootBackStack: List<AppScreen> = listOf(AppScreen.Library)

/** The four stable destinations in the persistent bottom navigation (#161). */
enum class AppRoot(val screen: AppScreen, val label: String) {
    Home(AppScreen.Library, "HOME"),
    Browse(AppScreen.Fictions, "BROWSE"),
    Listening(AppScreen.Listening, "LISTENING"),
    Settings(AppScreen.Settings, "SETTINGS"),
}

/** The stable root each destination belongs to, including drill-down and capability-gated screens. */
val AppScreen.appRoot: AppRoot
    get() = when (this) {
        AppScreen.Library -> AppRoot.Home
        AppScreen.Fictions, is AppScreen.Fiction, is AppScreen.FictionEdit -> AppRoot.Browse
        AppScreen.Listening,
        AppScreen.Player,
        is AppScreen.Reader,
        AppScreen.Bookmarks,
        AppScreen.PronunciationReports,
        AppScreen.Queue,
        AppScreen.Stats,
        AppScreen.Logs,
        -> AppRoot.Listening

        AppScreen.Settings, AppScreen.Devices -> AppRoot.Settings
    }

/** Which tab owns the destination currently on screen. */
val List<AppScreen>.activeRoot: AppRoot
    get() = lastOrNull()?.appRoot ?: AppRoot.Home

/**
 * Select a stable root without putting the previous tab behind BACK.
 *
 * Selecting the active tab also returns to its root, which is the conventional escape from a
 * drill-down. Saved Compose state is keyed by destination rather than stack position, so the root's
 * scroll/search state survives leaving it and coming back.
 */
fun List<AppScreen>.switchToRoot(root: AppRoot): List<AppScreen> {
    val target = listOf(root.screen)
    return if (this == target) this else target
}

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

/**
 * Swap the top entry for [screen], leaving everything under it alone.
 *
 * For a screen that re-targets itself rather than being navigated to — the reader following
 * playback into the next chapter. [navigateTo] would be wrong twice over: BACK would walk back
 * through every chapter an overnight listen advanced through, and the entry it returned to would
 * be one nobody chose to open. Replacing keeps BACK meaning "whatever I opened the reader from",
 * while still changing [saveKey], which is what resets the scroll to the top of the new chapter.
 *
 * Replacing the root entry is a no-op: the stack always has a root, and nothing that replaces
 * itself is ever the root.
 */
fun List<AppScreen>.replaceTop(screen: AppScreen): List<AppScreen> =
    if (size > 1) subList(0, size - 1) + screen else this

/**
 * Rewrite every entry carrying [fiction] so an edit is visible everywhere it is already on screen.
 *
 * A fiction travels *in* the back stack, so after a title is changed the screen underneath the
 * editor — and the top bar, which reads its title from the entry — would otherwise keep showing the
 * old one until the user navigated away and back. Entries for other fictions are returned by
 * identity, and [saveKey] does not change, so nothing scrolls or reloads.
 */
fun List<AppScreen>.withFiction(fiction: FictionSummary): List<AppScreen> = map { screen ->
    when {
        screen is AppScreen.Fiction && screen.fiction.id == fiction.id ->
            AppScreen.Fiction(fiction)

        screen is AppScreen.FictionEdit && screen.fiction.id == fiction.id ->
            AppScreen.FictionEdit(fiction)

        else -> screen
    }
}
