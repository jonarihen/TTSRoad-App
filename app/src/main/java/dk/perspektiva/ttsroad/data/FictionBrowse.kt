package dk.perspektiva.ttsroad.data

/**
 * Everything the browse grid narrows itself by, before [sortedForBrowsing] puts what is left in
 * order.
 *
 * Pure functions over a list rather than logic inside the composable, for the reason every other
 * `data/` view helper is: what a filter *does* is the whole behaviour, it is invisible from the
 * outside when it is wrong, and a Compose test that has to build a grid to find out whether "two
 * tags" means "either" or "both" is testing the wrong thing.
 *
 * The web console's library controls are the contract being matched here (`ttsroadApplyLibrary` in
 * `app/static/app.js`): a text field over title/author/tags, a tag multi-select that ANDs, and a
 * followed/everything split. Where the two could reasonably differ they are kept the same on
 * purpose — someone who has learned the shelf in a browser should not have to learn it again on
 * the phone.
 */

/** Which half of the server the grid is showing. */
enum class BrowseScope(val label: String) {
    /** The caller's own shelf: the fictions they follow. */
    Following("FOLLOWING"),

    /** Every fiction on the server, followed or not — where a book gets followed *from*. */
    All("ALL"),
    ;

    companion object {
        /**
         * What browse opens on: everything.
         *
         * Deliberately the opposite of the web console, whose `/` defaults to `scope=followed` —
         * and the difference is navigation, not taste. There, Library *is* the home page, so the
         * shelf is what home should show. Here HOME is already the shelf, and BROWSE is the
         * separate screen a fiction is found and followed *from*; opening it on the shelf would
         * make it a second copy of home and hide the only list a new book can be discovered in.
         * [Following] is the narrowing this adds, not the starting point.
         */
        val Default: BrowseScope = All
    }
}

/**
 * Reads a stored scope name back, falling back to [BrowseScope.Default].
 *
 * Same rule as [chapterFilterFromStored]: an unrecognised stored value must land on the *showing*
 * end of the setting rather than the hiding end, because a list that looks empty for reasons
 * nothing on screen explains is the worst outcome available here.
 */
fun browseScopeFromStored(stored: String?): BrowseScope =
    BrowseScope.entries.firstOrNull { it.name == stored } ?: BrowseScope.Default

/**
 * Every tag in [this], lower-cased, de-duplicated and alphabetical — the choices a tag filter can
 * offer.
 *
 * Lower-cased because that is what the filter compares against, and because a server that has
 * "LitRPG" on one book and "litrpg" on another would otherwise offer two boxes that do the same
 * thing. The *display* casing is lost by that, deliberately: AARIS draws tags uppercase anyway.
 */
fun List<FictionSummary>.availableTags(): List<String> =
    flatMap { fiction -> fiction.tags.map { it.trim().lowercase() } }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

/**
 * What the shelf calls the place its books came from: the choices a source filter can offer.
 *
 * A fiction the server said nothing about is counted as [UnknownSourceLabel] rather than dropped.
 * Silently omitting those would make the filter lie by subtraction — tick every box and the grid
 * would still be missing rows, with no box on screen accounting for them.
 *
 * Alphabetical, with the unknown group last wherever it appears: it is not a source, it is the
 * absence of one, and sorting it under "U" would put it in the middle of real names.
 */
fun List<FictionSummary>.availableSources(): List<String> {
    val named = mapNotNull { it.sourceFilterLabel?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val hasUnknown = any { it.sourceFilterLabel.isNullOrBlank() }
    return if (hasUnknown) named + UnknownSourceLabel else named
}

/**
 * The bucket for a fiction whose source the server never reported.
 *
 * A visible name rather than a null: see [availableSources] for why the group has to exist at all.
 */
const val UnknownSourceLabel: String = "Unknown"

/**
 * Whether [this] came from any of [sources].
 *
 * **OR, unlike the tag filter's AND** — and the difference is not an inconsistency. A book carries
 * many tags at once, so "both of these" is a meaningful narrowing; it has exactly one source, so
 * ANDing two sources would always match nothing. Ticking two boxes here means "either", which is
 * the only reading that leaves the control usable. The web console's source filter behaves the
 * same way for the same reason.
 */
fun FictionSummary.hasAnySource(sources: Set<String>): Boolean {
    if (sources.isEmpty()) return true
    val own = sourceFilterLabel?.trim()?.takeIf(String::isNotEmpty) ?: UnknownSourceLabel
    return sources.any { it.equals(own, ignoreCase = true) }
}

/**
 * Drop source selections that nothing on the shelf carries any more.
 *
 * The counterpart to [retainingKnownTags], and needed for a sharper reason: a stored source
 * survives signing into a *different server*, where "Patreon" may name nothing at all. Without
 * this the grid would open empty against a perfectly good shelf.
 */
fun Set<String>.retainingKnownSources(available: Collection<String>): Set<String> {
    if (isEmpty()) return this
    val known = available.toSet()
    return filterTo(mutableSetOf()) { stored -> known.any { it.equals(stored, ignoreCase = true) } }
}

/**
 * Whether [this] carries every one of [tags].
 *
 * AND, not OR, matching the web console. Two tags selected means "books that are both", which is
 * the reading that makes a second tag useful: ORing them widens the list, and a filter that grows
 * the list when you add to it is a filter nobody uses twice.
 */
fun FictionSummary.hasAllTags(tags: Set<String>): Boolean {
    if (tags.isEmpty()) return true
    val own = this.tags.mapTo(mutableSetOf()) { it.trim().lowercase() }
    return own.containsAll(tags)
}

/** Whether [this] matches free text typed into the browse field: title, author or any tag. */
fun FictionSummary.matchesBrowseQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    if (title.lowercase().contains(needle)) return true
    if (author?.lowercase()?.contains(needle) == true) return true
    return tags.any { it.lowercase().contains(needle) }
}

/**
 * Whether [this] belongs in [scope].
 *
 * [FictionSummary.following] defaults to true, which is what makes this correct on a server without
 * per-user libraries: there, every fiction is effectively followed, both tabs hold the same list,
 * and the caller does not draw the tabs at all.
 */
fun FictionSummary.inScope(scope: BrowseScope): Boolean = when (scope) {
    BrowseScope.All -> true
    BrowseScope.Following -> following
}

/**
 * The rows the browse grid should draw: narrowed by scope, tags and text, then ordered by [sort].
 *
 * Filter first, then order — the other way round sorts rows that are about to be discarded, which
 * on a large shelf means sorting the whole catalogue to show twelve of it.
 */
fun List<FictionSummary>.browseView(
    scope: BrowseScope = BrowseScope.All,
    tags: Set<String> = emptySet(),
    sources: Set<String> = emptySet(),
    query: String = "",
    sort: FictionSort = FictionSort.Default,
): List<FictionSummary> = filter { fiction ->
    fiction.inScope(scope) &&
        fiction.hasAllTags(tags) &&
        fiction.hasAnySource(sources) &&
        fiction.matchesBrowseQuery(query)
}.sortedForBrowsing(sort)

/**
 * How many fictions each scope tab would show, for the counts on the tabs themselves.
 *
 * Counted over the *unfiltered* list on purpose. A tab saying "FOLLOWING 12" while a tag filter is
 * narrowing the grid to three is telling the truth about what the tab switches to; recomputing it
 * against the active filter would make both tabs read as whatever is on screen, which answers a
 * question nobody asked.
 */
fun List<FictionSummary>.browseScopeCount(scope: BrowseScope): Int = count { it.inScope(scope) }

/**
 * Drop selections that nothing on the shelf carries any more.
 *
 * A tag lives on fictions, not on the account, so unfollowing the only book with `xianxia` — or
 * an admin retagging it — leaves a stored selection that matches nothing. Without this the grid
 * would come back empty after a restart with no visible cause, since the filter sheet cannot show
 * a box for a tag that is no longer in the list.
 */
fun Set<String>.retainingKnownTags(available: Collection<String>): Set<String> {
    if (isEmpty()) return this
    val known = available.toSet()
    return filterTo(mutableSetOf()) { it in known }
}

/**
 * What an empty browse grid says, naming the narrowing that emptied it.
 *
 * "No fictions found" was the only message before, and it is a lie in three of the four ways the
 * grid can come up empty: with a tag ticked, or on the FOLLOWING tab of a server full of books,
 * the shelf is fine and a filter is doing it. Naming the cause is what turns a dead end into a
 * control the user knows to go and undo — the web console draws the same distinction with its
 * "not following anything yet" state.
 *
 */
fun browseEmptyMessage(
    query: String,
    tags: Set<String>,
    scope: BrowseScope,
    sources: Set<String> = emptySet(),
): String {
    val trimmed = query.trim()
    // Most specific cause first: with both a search and a tag on, the search is the one just
    // typed, so it is the one to mention.
    return when {
        trimmed.isNotEmpty() -> "No matches for \"$trimmed\""
        tags.size == 1 -> "Nothing tagged ${tags.first()}"
        tags.size > 1 -> "Nothing carries all ${tags.size} tags"
        // Below tags, because a tag filter is the likelier of the two to have just been touched,
        // and above scope for the same reason the others are: a filter the user set beats a
        // standing condition of the shelf.
        sources.size == 1 -> "Nothing from ${sources.first()}"
        sources.size > 1 -> "Nothing from the ${sources.size} selected sources"
        scope == BrowseScope.Following -> "You are not following anything yet"
        else -> "No fictions found"
    }
}
