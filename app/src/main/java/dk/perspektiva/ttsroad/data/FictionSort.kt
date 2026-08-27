package dk.perspektiva.ttsroad.data

/**
 * How the browse grid is ordered (#164).
 *
 * The shelf could not be ordered by anything until this existed: "All fictions" offered a text
 * filter and rendered the server's own order, so finding the book that gained a chapter yesterday
 * meant remembering its name.
 *
 * ## Why the dates are trustworthy as strings
 *
 * [FictionSummary.createdAt] and [FictionSummary.updatedAt] arrive from the backend's `_utc_iso`,
 * which is `isoformat(timespec="seconds") + "Z"` — always UTC, always second precision, always the
 * same width, e.g. `2026-07-01T09:15:00Z`. For that shape, lexicographic order *is* chronological
 * order, so these compare as strings rather than being parsed. That is a deliberate choice and not
 * a shortcut: parsing introduces a failure path on every row of a list that has to scroll smoothly,
 * to answer a question the format already answers. It holds only while every value comes from that
 * one formatter, which is why it is written down here rather than left to be rediscovered.
 *
 * A value that is null — an older server that never sent the field — sorts **last** in every date
 * order, rather than first or wherever a null happens to land. "We were not told" is not "a long
 * time ago", and letting it read as the latter would put a whole shelf of unknowns above the book
 * that actually just updated.
 */
enum class FictionSort(val label: String) {
    /**
     * Most recently written fiction row first.
     *
     * Deliberately **not** called "New chapters". [FictionSummary.updatedAt] is an `onupdate` on the
     * fiction row and the poller touches that row when it records a poll, so this moves even when a
     * check found nothing. It is the best "recently active" signal the payload carries; a true
     * last-chapter-added order needs `max(chapters.created_at)` from the backend.
     */
    RecentlyUpdated("Recently updated"),

    /** Most recently tracked first — the shelf in the order it was built, newest end first. */
    RecentlyAdded("Recently added"),

    /** The default, and the order everything before #164 was stuck with. */
    Title("Title A–Z"),

    /** Author name, with the unattributed sorted last rather than under an empty heading. */
    Author("Author A–Z"),

    /** Highest rated first. Unrated books sort last, for the same reason nulls do everywhere here. */
    Rating("Rating"),
    ;

    companion object {
        /** What a fresh install browses with. Alphabetical is the order nobody has to be taught. */
        val Default: FictionSort = Title
    }
}

/**
 * Order [this] by [sort], leaving the caller's list untouched.
 *
 * Ties keep the order they arrived in: `sortedWith` is stable, so two books added in the same
 * second, or two unrated books, stay in the server's order instead of shuffling between
 * recompositions. That matters more than it sounds — an unstable comparator here would make a grid
 * appear to reorder itself while the user was looking at it.
 */
fun List<FictionSummary>.sortedForBrowsing(sort: FictionSort): List<FictionSummary> =
    when (sort) {
        FictionSort.RecentlyUpdated -> sortedWith(descendingNullsLast { it.updatedAt })
        FictionSort.RecentlyAdded -> sortedWith(descendingNullsLast { it.createdAt })
        FictionSort.Title -> sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

        FictionSort.Author -> sortedWith(
            ascendingNullsLast { it.author?.takeIf(String::isNotBlank) },
        )

        FictionSort.Rating -> sortedWith(descendingNullsLast { it.rating })
    }

/** Biggest first, with anything the server did not tell us about at the end. */
private fun <T : Comparable<T>> descendingNullsLast(
    key: (FictionSummary) -> T?,
): Comparator<FictionSummary> = Comparator { left, right ->
    val a = key(left)
    val b = key(right)
    when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> b.compareTo(a)
    }
}

/** Smallest first — used for names — with the unknown at the end rather than at the top. */
private fun ascendingNullsLast(
    key: (FictionSummary) -> String?,
): Comparator<FictionSummary> = Comparator { left, right ->
    val a = key(left)
    val b = key(right)
    when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> String.CASE_INSENSITIVE_ORDER.compare(a, b)
    }
}
