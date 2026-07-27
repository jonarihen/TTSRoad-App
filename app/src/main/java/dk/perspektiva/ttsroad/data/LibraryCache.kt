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

    private val chapterStates = mutableMapOf<Int, MutableStateFlow<Cached<List<ChapterSummary>>>>()

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
            _library.value = runCatching { repository.library() }.fold(
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

    fun ensureChapters(fictionId: Int) {
        if (chapterState(fictionId).value.hasContent || chapterJobs[fictionId]?.isActive == true) return
        refreshChapters(fictionId)
    }

    fun refreshChapters(fictionId: Int) {
        chapterJobs[fictionId]?.cancel()
        val state = chapterState(fictionId)
        chapterJobs[fictionId] = scope.launch {
            state.value = state.value.copy(isRefreshing = true, error = null)
            state.value = runCatching { repository.chapters(fictionId) }.fold(
                onSuccess = { Cached(value = it.chapters) },
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
        chapterJobs.values.forEach(Job::cancel)
        chapterJobs.clear()
        _library.value = Cached()
        chapterStates.values.forEach { it.value = Cached() }
        chapterStates.clear()
    }
}
