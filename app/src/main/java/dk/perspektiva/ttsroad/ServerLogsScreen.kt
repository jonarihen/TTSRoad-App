package dk.perspektiva.ttsroad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ServerLogLevels
import dk.perspektiva.ttsroad.data.ServerLogRow
import dk.perspektiva.ttsroad.data.ServerLogsResponse
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.canReadServerLogs
import dk.perspektiva.ttsroad.data.mergeServerLogPages
import dk.perspektiva.ttsroad.data.serverLogRows
import dk.perspektiva.ttsroad.data.serverLogsEmptyNote
import dk.perspektiva.ttsroad.data.serverLogsUnavailableNote
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisChoiceRow
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.SectionHeader
import dk.perspektiva.ttsroad.ui.ThinProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The server's own pipeline log, read from the phone (#124).
 *
 * "Why did that chapter fail" and "is the poller running" are questions you have while looking at
 * the app, and until now the answer was a laptop away — the log lived only on the web console, and
 * the client that made you want to ask was the one client that could not tell you.
 *
 * Three things here are deliberate rather than incidental:
 *
 * - **An empty list is not a shrug.** Everywhere else in the app an empty result is a mild
 *   disappointment; here it is usually the good news you came for. [serverLogsEmptyNote] says which
 *   emptiness this is, in terms of the filters that produced it.
 * - **The level filter never sends a value the server does not know.** An unrecognised `level` is a
 *   400, not an empty list — the server refuses to let a typo invent "nothing has gone wrong" — so
 *   the filter is a fixed choice of the three levels the log column holds.
 * - **Paging walks a cursor, not an offset.** Each page carries `next_before_id`, and it is null
 *   once there is nothing older. Ids are a monotonic primary key, so a page boundary holds still
 *   while the pipeline keeps writing rows above it; an offset over a table that only grows would
 *   show the same failure twice.
 *
 * Read-only, and there is nothing here to make it otherwise: the mobile contract publishes no route
 * that writes or clears a log.
 */
@Composable
internal fun ServerLogsScreen(
    padding: PaddingValues,
    session: SessionState,
    repository: TtsRoadRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    val allowed = canReadServerLogs(capabilities, session.isAdmin)

    // Names for the fiction ids the log carries. The payload has ids only, and the shelf the app has
    // already loaded is the cheapest place to resolve them — no request, and a fiction that is not
    // on the shelf still shows its id rather than being dropped.
    val libraryCache = remember { ServiceLocator.libraryCache(context) }
    val library by libraryCache.library.collectAsStateWithLifecycle()
    val titles = remember(library) {
        library.value?.fictions.orEmpty().associate { it.id to it.title }
    }

    val state = remember(repository) {
        ServerLogsState(scope) { level, fictionId, beforeId ->
            repository.serverLogs(level = level, fictionId = fictionId, beforeId = beforeId)
        }
    }
    state.updateAccess(session, allowed)
    state.titles = titles
    DisposableEffect(state) { onDispose { state.invalidate() } }
    LaunchedEffect(state, session, allowed) { if (allowed) state.refresh() }

    val rows = state.rows
    val hasMore = state.hasMore
    val level = state.level
    val fictionId = state.fictionId
    val fictionLabel = state.fictionLabel
    val isLoading = state.isLoading
    val isLoadingMore = state.isLoadingMore
    val error = state.error
    val loadedOnce = state.loadedOnce

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        serverLogsUnavailableNote(capabilities, session.isAdmin)?.let { note ->
            // Two silences that are not interchangeable — see the note itself for which is which.
            SectionHeader(kicker = "LOG", title = "Not available")
            EmptyCard(note)
            return@Column
        }

        SectionHeader(kicker = "FLT", title = "Filter")
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A fixed choice of the three levels the column holds. Anything else is a 400
                // server-side rather than an empty list, which is the whole reason this is not a
                // free-text field.
                AarisChoiceRow(
                    options = LogLevelChoices,
                    selected = level,
                    label = { it ?: "ALL" },
                    onSelect = { state.applyFilters(it, fictionId, fictionLabel) },
                )
                if (fictionId != null) {
                    MetaText(
                        text = "Showing only ${fictionLabel ?: "fiction $fictionId"}",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(
                        onClick = { state.applyFilters(level, null, null) },
                        modifier = Modifier.heightIn(min = MinTouchTargetSize),
                        shape = RectangleShape,
                    ) {
                        Text("SHOW EVERY FICTION")
                    }
                } else {
                    MetaText(
                        text = "Tap the book on any line to narrow the log to it. Fifty " +
                            "undifferentiated rows are most of a phone screen.",
                        color = AarisColor.Dim,
                    )
                }
            }
        }

        error?.let { MetaText(text = it, color = AarisColor.Danger) }

        when {
            // A refresh over rows already on screen is a hairline above them, not a blank pane.
            rows.isNotEmpty() -> {
                if (isLoading) {
                    ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
                }
                SectionHeader(
                    kicker = "LOG",
                    title = if (rows.size == 1) "1 line" else "${rows.size} lines",
                )
                AarisCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                            }
                            ServerLogListItem(
                                row = row,
                                onFilterFiction = { id, label ->
                                    state.applyFilters(level, id, label)
                                },
                            )
                        }
                    }
                }
                if (hasMore) {
                    OutlinedButton(
                        onClick = state::loadMore,
                        enabled = !isLoadingMore && !isLoading,
                        modifier = Modifier.heightIn(min = MinTouchTargetSize),
                        shape = RectangleShape,
                    ) {
                        Text(if (isLoadingMore) "LOADING…" else "OLDER LINES")
                    }
                } else {
                    MetaText(text = "That is the whole log.", color = AarisColor.Muted)
                }
            }

            isLoading -> ThinProgress(
                fraction = 1f,
                modifier = Modifier.fillMaxWidth(),
                height = 2.dp,
            )

            // Only once a page has actually come back. Before that an empty list is "not loaded
            // yet", and saying "no errors" about a request still in flight would be a guess.
            loadedOnce && error == null -> EmptyCard(serverLogsEmptyNote(level, fictionId))
        }

        OutlinedButton(
            onClick = state::refresh,
            enabled = !isLoading && !isLoadingMore,
            modifier = Modifier.heightIn(min = MinTouchTargetSize),
            shape = RectangleShape,
        ) {
            Text("REFRESH")
        }
    }
}

@Stable
internal class ServerLogsState(
    private val scope: CoroutineScope,
    private val loadPage: suspend (String?, Int?, Int?) -> ServerLogsResponse?,
) {
    var rows by mutableStateOf<List<ServerLogRow>>(emptyList())
        private set
    var nextBeforeId by mutableStateOf<Int?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var level by mutableStateOf<String?>(null)
        private set
    var fictionId by mutableStateOf<Int?>(null)
        private set
    var fictionLabel by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadedOnce by mutableStateOf(false)
        private set
    var titles: Map<Int, String> = emptyMap()

    private var session: SessionState? = null
    private var allowed = false
    private var generation = 0L
    private var activeRequest: Any? = null

    fun updateAccess(nextSession: SessionState, nextAllowed: Boolean) {
        val canRead = nextAllowed && nextSession.isLoggedIn
        if (session == nextSession && allowed == canRead) return
        val sessionChanged = session != nextSession
        session = nextSession
        allowed = canRead
        resetWalk()
        if (sessionChanged) {
            level = null
            fictionId = null
            fictionLabel = null
        }
    }

    fun invalidate() {
        allowed = false
        resetWalk()
    }

    private fun resetWalk() {
        generation++
        activeRequest = null
        rows = emptyList()
        nextBeforeId = null
        hasMore = false
        isLoading = false
        isLoadingMore = false
        error = null
        loadedOnce = false
    }

    fun applyFilters(nextLevel: String?, nextFiction: Int?, nextFictionLabel: String?) {
        level = nextLevel
        fictionId = nextFiction
        fictionLabel = nextFictionLabel
        resetWalk()
        refresh()
    }

    fun refresh() {
        if (!allowed) return
        generation++
        nextBeforeId = null
        hasMore = false
        loadedOnce = false
        requestPage(null)
    }

    fun loadMore() {
        if (!allowed || isLoading || isLoadingMore || !hasMore) return
        val cursor = nextBeforeId ?: return
        requestPage(cursor)
    }

    private fun requestPage(cursor: Int?) {
        val request = Any()
        val requestGeneration = generation
        val requestLevel = level
        val requestFiction = fictionId
        activeRequest = request
        isLoading = cursor == null
        isLoadingMore = cursor != null
        error = null
        fun isCurrent() = allowed && generation == requestGeneration && activeRequest === request
        scope.launch {
            if (!isCurrent()) return@launch
            val result = runCatching { loadPage(requestLevel, requestFiction, cursor) }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return@launch
            result.onSuccess { page ->
                val pageRows = serverLogRows(page, titles)
                rows = if (cursor == null) pageRows else mergeServerLogPages(rows, pageRows)
                hasMore = page?.hasMore == true
                nextBeforeId = page?.nextBeforeId
                loadedOnce = true
            }.onFailure {
                error = it.message ?: if (cursor == null) {
                    "Could not load the server log"
                } else {
                    "Could not load more of the log"
                }
            }
            isLoading = false
            isLoadingMore = false
            activeRequest = null
        }
    }
}

/** ALL first, then the three levels most severe first — the order someone scans them in. */
private val LogLevelChoices: List<String?> = listOf(null) + ServerLogLevels

/**
 * One line: when, at what level, about what, and what it said.
 *
 * The level is a tag rather than a coloured message, so a wall of errors still reads as text. The
 * source is a button because the second question after "what failed" is always "what else has this
 * book done", and the server takes a `fiction_id` filter precisely for that.
 */
@Composable
private fun ServerLogListItem(row: ServerLogRow, onFilterFiction: (Int, String?) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AarisTag(text = row.level, color = logLevelColor(row.level))
            Spacer(modifier = Modifier.width(12.dp))
            // A line whose timestamp will not parse still says what happened; the date is context,
            // not the point.
            MetaText(text = row.time ?: "Undated", color = AarisColor.Muted)
        }
        Text(
            text = row.message.ifBlank { "(no message)" },
            style = MaterialTheme.typography.bodyMedium,
            color = AarisColor.Ink,
        )
        val fiction = row.fictionId
        if (fiction != null) {
            OutlinedButton(
                onClick = { onFilterFiction(fiction, row.source) },
                modifier = Modifier.heightIn(min = MinTouchTargetSize),
                shape = RectangleShape,
            ) {
                Text(row.source ?: "FICTION $fiction")
            }
        } else if (row.source != null) {
            MetaText(text = row.source, color = AarisColor.Dim)
        }
    }
}

/** Severity as colour, using the theme's own three. Anything unrecognised stays neutral. */
private fun logLevelColor(level: String): Color = when (level) {
    "ERROR" -> AarisColor.Danger
    "WARNING" -> AarisColor.Warning
    else -> AarisColor.Muted
}
