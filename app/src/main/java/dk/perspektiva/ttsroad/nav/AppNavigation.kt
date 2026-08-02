package dk.perspektiva.ttsroad.nav

import dk.perspektiva.ttsroad.data.FictionSummary

/** Destinations the phone UI can show. */
sealed interface AppScreen {
    data object Library : AppScreen
    data object Fictions : AppScreen
    data class Fiction(val fiction: FictionSummary) : AppScreen
    data object Player : AppScreen
    data object Settings : AppScreen

    /** The account's other mobile sign-ins, reached from Settings. */
    data object Devices : AppScreen
}

/** Stable key for per-entry saved UI state (scroll offsets, search text). */
val AppScreen.saveKey: String
    get() = when (this) {
        AppScreen.Library -> "Library"
        AppScreen.Fictions -> "Fictions"
        is AppScreen.Fiction -> "Fiction:${fiction.id}"
        AppScreen.Player -> "Player"
        AppScreen.Settings -> "Settings"
        AppScreen.Devices -> "Devices"
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
