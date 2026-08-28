package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A loaded thing, plus whether it is currently being reloaded.
 *
 * The distinction that matters is [hasContent]: it separates "there is nothing to show, so a
 * spinner is the only option" from "we have last time's data, so show it and refresh underneath".
 * Screen-level state used to collapse those two, which is why every back-navigation blanked the
 * screen it returned to.
 */
data class Cached<T>(
    val value: T? = null,
    val error: String? = null,
    val isRefreshing: Boolean = false,
) {
    val hasContent: Boolean get() = value != null

    /** Nothing loaded and nothing failed yet — the only state that justifies a full-screen spinner. */
    val isInitialLoad: Boolean get() = value == null && error == null
}

/**
 * Library and chapter data held above the screens that show it.
 *
 * Screen state lived in `remember` inside each composable, and navigation swaps the composable — so
 * leaving a screen destroyed its state and returning re-ran the initial load, replacing the whole
 * screen with a spinner. Hoisting it here means returning to a screen shows the previous data
 * immediately and refreshes in place.
 *
 * Lifetime is the process, not the composition, so it is a [dk.perspektiva.ttsroad.core.ServiceLocator]
 * singleton like the other shared state. [clear] exists because that lifetime outlives a session.
 */
class LibraryCache(private val repository: TtsRoadRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _library = MutableStateFlow(Cached<LibraryResponse>())
    val library: StateFlow<Cached<LibraryResponse>> = _library.asStateFlow()

    /**
     * Everything on the server, for the browse screen — a separate list from [library], not a
     * widened one.
     *
     * They answer different questions. [library] is "my shelf", which is what the home screen and
     * the car's browse tree show; this is "everything there is", which is where a fiction gets
     * followed *from*. Sharing one cache would mean opening browse silently replaced the shelf.
     */
    private val _browseAll = MutableStateFlow(Cached<LibraryResponse>())
    val browseAll: StateFlow<Cached<LibraryResponse>> = _browseAll.asStateFlow()

    private var browseAllJob: Job? = null

    private val chapterStates = mutableMapOf<Int, MutableStateFlow<Cached<List<ChapterSummary>>>>()

    // Server-issued cursors only. A device clock can be wrong, and advancing a cursor before every
    // sparse pull succeeds would skip whatever the failed pull was meant to carry (#110).
    private var libraryCursor: String? = null
    private var browseAllCursor: String? = null
    private val chapterCursors = mutableMapOf<Int, String>()

    // One in-flight load per key. A pull-to-refresh landing on top of the initial load should not
    // produce two requests racing to write the same state.
    private var libraryJob: Job? = null
    private val chapterJobs = mutableMapOf<Int, Job>()

    fun chapters(fictionId: Int): StateFlow<Cached<List<ChapterSummary>>> =
        chapterState(fictionId).asStateFlow()

    private fun chapterState(fictionId: Int) =
        chapterStates.getOrPut(fictionId) { MutableStateFlow(Cached()) }

    /** Load only if nothing has been loaded yet — the "opening this screen" path. */
    fun ensureLibrary() {
        if (_library.value.hasContent || libraryJob?.isActive == true) return
        refreshLibrary()
    }

    /** Refetch, keeping whatever is on screen until the new data arrives. */
    fun refreshLibrary() {
        libraryJob?.cancel()
        libraryJob = scope.launch {
            _library.value = _library.value.copy(isRefreshing = true, error = null)
            _library.value = runCatching { refreshedLibrary() }.fold(
                onSuccess = { Cached(value = it) },
                onFailure = { failure ->
                    // Keep the stale content: a failed refresh should not throw away a library the
                    // user can still read and play from.
                    _library.value.copy(
                        isRefreshing = false,
                        error = failure.message ?: "Could not load library",
                    )
                },
            )
        }
    }

    /**
     * Use the sync index to update the shelf and every chapter list already held in memory.
     *
     * The cursor is committed last. Sparse merges are idempotent, so a failure after one chapter
     * list was updated simply repeats that row next time instead of advancing past data another
     * list never received.
     */
    private suspend fun refreshedLibrary(): LibraryResponse {
        val existing = _library.value.value
        val cursor = libraryCursor
        if (existing == null || cursor == null || !repository.currentCapabilities.value.deltaSync) {
            return repository.library().also { libraryCursor = it.serverTime }
        }

        val index = repository.deltaSync(cursor)
            ?: return repository.library().also { libraryCursor = it.serverTime }

        index.fictionsWithChapterChanges().forEach { fictionId ->
            val state = chapterStates[fictionId] ?: return@forEach
            val chapters = state.value.value ?: return@forEach
            // Each chapter list carries its own watermark, and it is not the library's. A list
            // loaded before the shelf last synced is behind that cursor, so asking with the
            // library's reading would skip everything that moved in between and then advance past
            // it. No watermark at all means no baseline to be sparse against, so that is a full
            // pull rather than a guess.
            val update = repository.chapters(
                fictionId = fictionId,
                updatedSince = chapterCursors[fictionId],
            )
            state.value = Cached(value = mergeChapterDelta(chapters, update))
            update.serverTime?.let { chapterCursors[fictionId] = it }
        }

        index.deleted.fictions.forEach { fictionId ->
            chapterStates[fictionId]?.value = Cached(value = emptyList())
            chapterCursors.remove(fictionId)
        }

        val refreshed = if (index.libraryMoved()) {
            mergeLibraryDelta(existing, repository.library(updatedSince = cursor))
        } else {
            existing
        }
        libraryCursor = index.serverTime
        return refreshed.copy(serverTime = index.serverTime)
    }

    fun ensureBrowseAll() {
        if (_browseAll.value.hasContent || browseAllJob?.isActive == true) return
        refreshBrowseAll()
    }

    fun refreshBrowseAll() {
        browseAllJob?.cancel()
        browseAllJob = scope.launch {
            _browseAll.value = _browseAll.value.copy(isRefreshing = true, error = null)
            _browseAll.value = runCatching { refreshedBrowseAll() }.fold(
                onSuccess = { Cached(value = it) },
                onFailure = { failure ->
                    _browseAll.value.copy(
                        isRefreshing = false,
                        error = failure.message ?: "Could not load fictions",
                    )
                },
            )
        }
    }

    /** Browse-all is one payload, so its delta is already the cheapest possible change check. */
    private suspend fun refreshedBrowseAll(): LibraryResponse {
        val existing = _browseAll.value.value
        val cursor = browseAllCursor
        val refreshed = if (
            existing != null && cursor != null && repository.currentCapabilities.value.deltaSync
        ) {
            mergeLibraryDelta(
                existing,
                repository.library(scope = LibraryScopeAll, updatedSince = cursor),
            )
        } else {
            repository.library(LibraryScopeAll)
        }
        browseAllCursor = refreshed.serverTime ?: browseAllCursor
        return refreshed
    }

    /**
     * Reflect a follow change without refetching either list.
     *
     * Both are updated: the fiction's row in browse has to show its new state, and the shelf has to
     * gain or lose it — otherwise following something from browse leaves the home screen wrong
     * until the next refresh.
     */
    fun applyFollowing(fictionId: Int, following: Boolean) {
        _browseAll.value = _browseAll.value.copy(
            value = _browseAll.value.value?.let { response ->
                response.copy(
                    fictions = response.fictions.map {
                        if (it.id == fictionId) it.copy(following = following) else it
                    },
                    followingIds = if (following) {
                        (response.followingIds + fictionId).distinct()
                    } else {
                        response.followingIds - fictionId
                    },
                )
            },
        )
        // The shelf cannot gain a fiction it has never loaded, so an unfollow is applied in place
        // and a follow falls back to a refetch — the server decides what the shelf contains.
        val shelf = _library.value.value
        if (shelf != null && !following) {
            _library.value = _library.value.copy(
                value = shelf.copy(
                    fictions = shelf.fictions.filterNot { it.id == fictionId },
                    followingIds = shelf.followingIds - fictionId,
                ),
            )
        } else if (shelf != null) {
            refreshLibrary()
        }
    }

    /**
     * Put an edited fiction into both lists, keeping each list's own view of the shelf.
     *
     * [fiction] is the server's copy, so its title, cover, description and tags are authoritative —
     * but `following` is not: the browse list and the shelf disagree about it by design, and a
     * PATCH answers with whatever the fiction's row happens to say. Overwriting it here would have
     * an edit made from the browse screen quietly unfollow the book.
     *
     * A fiction neither list holds is left alone rather than inserted: what the shelf contains is
     * the server's decision, not an editor's.
     */
    fun applyFiction(fiction: FictionSummary) {
        _library.value = _library.value.copy(
            value = _library.value.value?.let { response ->
                response.copy(fictions = response.fictions.replacing(fiction))
            },
        )
        _browseAll.value = _browseAll.value.copy(
            value = _browseAll.value.value?.let { response ->
                response.copy(fictions = response.fictions.replacing(fiction))
            },
        )
    }

    /** Untouched rows come back by identity, so Compose skips redrawing them. */
    private fun List<FictionSummary>.replacing(fiction: FictionSummary): List<FictionSummary> =
        map { row ->
            if (row.id == fiction.id) {
                // PATCH responses carry the fiction payload but not the library-only per-user
                // aggregate. Preserve the loaded answer just as we preserve this list's shelf
                // membership, or editing a title would make its progress disappear until refresh.
                fiction.copy(
                    following = row.following,
                    progress = fiction.progress ?: row.progress,
                )
            } else {
                row
            }
        }

    fun ensureChapters(fictionId: Int) {
        if (chapterState(fictionId).value.hasContent || chapterJobs[fictionId]?.isActive == true) return
        refreshChapters(fictionId)
    }

    fun refreshChapters(fictionId: Int) {
        chapterJobs[fictionId]?.cancel()
        val state = chapterState(fictionId)
        chapterJobs[fictionId] = scope.launch {
            state.value = state.value.copy(isRefreshing = true, error = null)
            state.value = runCatching {
                val current = state.value.value
                val cursor = chapterCursors[fictionId]
                val response = if (
                    current != null && cursor != null && repository.currentCapabilities.value.deltaSync
                ) {
                    repository.chapters(fictionId, updatedSince = cursor)
                } else {
                    repository.chapters(fictionId)
                }
                val chapters = if (current == null) {
                    response.chapters
                } else {
                    mergeChapterDelta(current, response)
                }
                response.serverTime?.let { chapterCursors[fictionId] = it }
                chapters
            }.fold(
                onSuccess = { Cached(value = it) },
                onFailure = { failure ->
                    state.value.copy(
                        isRefreshing = false,
                        error = failure.message ?: "Could not load chapters",
                    )
                },
            )
        }
    }

    /**
     * Apply a played/unplayed change locally instead of refetching.
     *
     * Marking one chapter used to reload the whole list, which tore down a 500-row list and dropped
     * the user back at the top — for the sake of one checkmark. Untouched rows are returned by
     * identity so Compose skips redrawing them.
     */
    fun applyPlayed(fictionId: Int, chapterIds: Collection<Int>, played: Boolean) {
        if (chapterIds.isEmpty()) return
        val state = chapterState(fictionId)
        val current = state.value.value ?: return
        val ids = chapterIds.toSet()
        state.value = state.value.copy(
            value = current.map { chapter ->
                if (chapter.resolvedChapterId !in ids) {
                    chapter
                } else {
                    chapter.copy(playback = (chapter.playback ?: PlaybackInfo()).copy(isPlayed = played))
                }
            },
        )
    }

    /** Drop everything on sign-out, so the next account does not see the previous one's library. */
    fun clear() {
        libraryJob?.cancel()
        browseAllJob?.cancel()
        libraryCursor = null
        browseAllCursor = null
        _browseAll.value = Cached()
        chapterJobs.values.forEach(Job::cancel)
        chapterJobs.clear()
        chapterCursors.clear()
        _library.value = Cached()
        chapterStates.values.forEach { it.value = Cached() }
        chapterStates.clear()
    }
}
