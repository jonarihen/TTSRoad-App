package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary

/**
 * Matching for "Hey Google, play Ashes of Aether on TTSRoad".
 *
 * Voice queries arrive transcribed, so they carry no punctuation, inconsistent casing, and often a
 * stray leading verb the assistant did not strip. Matching therefore happens on a normalised form
 * rather than the raw string, and ranks candidates instead of taking the first hit — with one
 * result the car plays it immediately, so getting the *order* right matters more than the filter.
 */
private fun normalize(value: String): String =
    value.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** Words a spoken query picks up that carry no meaning for matching. */
private val FillerWords = setOf("play", "the", "a", "an", "on", "listen", "to", "continue", "resume")

private fun queryTokens(query: String): List<String> {
    val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
    // Only drop filler if something survives: "The Wandering Inn" must still be findable when the
    // user says exactly that and nothing else.
    val meaningful = tokens.filterNot { it in FillerWords }
    return meaningful.ifEmpty { tokens }
}

/**
 * Score a fiction against a query. Higher is better; zero means "do not offer this at all".
 *
 * The tiers exist so an exact title beats a fiction that merely shares a tag — otherwise asking for
 * a specific book by name could start a different one, which is the worst possible failure while
 * driving.
 */
internal fun scoreFiction(fiction: FictionSummary, query: String): Int {
    val tokens = queryTokens(query)
    if (tokens.isEmpty()) return 0
    val needle = tokens.joinToString(" ")
    val title = normalize(fiction.title)
    val author = fiction.author?.let(::normalize).orEmpty()
    val tags = fiction.tags.map(::normalize)

    return when {
        title == needle -> 100
        title.startsWith(needle) -> 80
        title.contains(needle) -> 70
        // Every spoken word appears in the title, in any order: "aether ashes" still finds it.
        tokens.all { token -> title.split(' ').any { it.startsWith(token) } } -> 60
        author == needle -> 50
        author.contains(needle) -> 40
        tags.any { it == needle } -> 30
        tags.any { it.contains(needle) } -> 20
        else -> 0
    }
}

/** Fictions matching [query], best first. */
fun searchFictions(fictions: List<FictionSummary>, query: String): List<FictionSummary> =
    fictions
        .map { it to scoreFiction(it, query) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<FictionSummary, Int>> { it.second }.thenBy { it.first.title })
        .map { it.first }

/**
 * The single fiction a "play X" request means, or null when nothing is a convincing match.
 *
 * Requires a title-level hit: starting an unrelated book because it happened to share a tag with a
 * misheard query is worse than admitting the query failed.
 */
fun resolveSpokenFiction(fictions: List<FictionSummary>, query: String): FictionSummary? =
    fictions
        .map { it to scoreFiction(it, query) }
        .filter { it.second >= 60 }
        .maxByOrNull { it.second }
        ?.first

/** Chapters whose title matches [query], for the car's search results list. */
fun searchChapters(chapters: List<ChapterSummary>, query: String): List<ChapterSummary> {
    val tokens = queryTokens(query)
    if (tokens.isEmpty()) return emptyList()
    val needle = tokens.joinToString(" ")
    return chapters.filter { chapter ->
        chapter.audio != null && normalize(chapter.resolvedTitle).contains(needle)
    }
}
