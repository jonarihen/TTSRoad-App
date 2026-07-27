package dk.perspektiva.ttsroad

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LibraryResponse
import dk.perspektiva.ttsroad.data.LoginResult
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.player.HistorySnapshot
import dk.perspektiva.ttsroad.player.lastHeardSnapshot
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.ThinProgress
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import kotlinx.coroutines.launch

private sealed interface AppScreen {
    data object Library : AppScreen
    data object Fictions : AppScreen
    data class Fiction(val fiction: FictionSummary) : AppScreen
    data object Player : AppScreen
    data object Settings : AppScreen
}

private sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class Error(val message: String) : LoadState<Nothing>
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TtsRoadTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AarisColor.Bg,
                ) {
                    TtsRoadApp()
                }
            }
        }
    }
}

@Composable
private fun TtsRoadApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { ServiceLocator.tokenStore(context) }
    val repository = remember { ServiceLocator.repository(context) }
    val playbackController = remember { ServiceLocator.playbackController(context) }
    val updateManager = remember { ServiceLocator.updateManager() }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val session by tokenStore.session.collectAsStateWithLifecycle(initialValue = SessionState())
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Library) }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            playbackController.connect()
        } else {
            screen = AppScreen.Library
            playbackController.stop()
        }
    }

    // Quietly check GitHub Releases for a newer build once per launch.
    LaunchedEffect(Unit) { updateManager.check(BuildConfig.VERSION_NAME) }

    if (!session.isLoggedIn) {
        LoginScreen(repository = repository, session = session)
    } else {
        MainScaffold(
            session = session,
            screen = screen,
            onScreenChange = { screen = it },
            repository = repository,
            playbackController = playbackController,
        )
    }

    UpdateOverlay(
        state = updateState,
        onDownload = { release -> scope.launch { updateManager.downloadAndInstall(context, release) } },
        onDismiss = { updateManager.dismiss() },
    )
}

@Composable
private fun UpdateOverlay(
    state: UpdateState,
    onDownload: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = AarisColor.BgRaise,
            title = { Text("UPDATE AVAILABLE", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaText(text = "Version ${state.release.versionName}", color = AarisColor.Accent)
                    if (state.release.notes.isNotBlank()) {
                        Text(
                            text = state.release.notes.take(400),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AarisColor.Muted,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onDownload(state.release) }, shape = RectangleShape) {
                    Text("DOWNLOAD & INSTALL")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("LATER") }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = AarisColor.BgRaise,
            title = { Text("DOWNLOADING UPDATE", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        color = AarisColor.Accent,
                        trackColor = AarisColor.Line,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MetaText(text = "${state.percent}%")
                }
            },
            confirmButton = {},
        )

        else -> Unit // Idle / Checking / UpToDate / Failed surface in Settings instead
    }
}

@Composable
private fun LoginScreen(repository: TtsRoadRepository, session: SessionState) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(session.serverUrl.ifBlank { "https://" }) }
    var username by remember { mutableStateOf(session.username.orEmpty().ifBlank { "admin" }) }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var totpCode by remember { mutableStateOf("") }
    var twoFactorRequired by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        MetaText(text = "// Operator Console", color = AarisColor.Accent)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "TTSROAD",
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        MetaText(text = "Connect to your private server")
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("SERVER URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("USERNAME") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("PASSWORD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("DEVICE NAME") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (twoFactorRequired) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = totpCode,
                onValueChange = { totpCode = it },
                label = { Text("2FA CODE") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { MetaText(text = "From your authenticator app, or a recovery code") },
            )
        }
        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                scope.launch {
                    isBusy = true
                    error = null
                    val result = repository.login(
                        baseUrl = serverUrl,
                        username = username,
                        password = password,
                        deviceName = deviceName,
                        totpCode = if (twoFactorRequired) totpCode else null,
                    )
                    when (result) {
                        LoginResult.Success -> Unit // session change navigates away
                        LoginResult.TotpRequired -> {
                            error = if (twoFactorRequired && totpCode.isNotBlank()) {
                                "Invalid authentication code"
                            } else {
                                null
                            }
                            twoFactorRequired = true
                        }

                        is LoginResult.Failure -> error = result.message
                    }
                    isBusy = false
                }
            },
            enabled = !isBusy && serverUrl.isNotBlank() && username.isNotBlank() &&
                password.isNotBlank() && (!twoFactorRequired || totpCode.isNotBlank()),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isBusy && twoFactorRequired -> "VERIFYING"
                    isBusy -> "SIGNING IN"
                    twoFactorRequired -> "VERIFY"
                    else -> "SIGN IN"
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    session: SessionState,
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    repository: TtsRoadRepository,
    playbackController: PlaybackController,
) {
    val context = LocalContext.current
    val playerState by playbackController.state.collectAsStateWithLifecycle()
    val historyStore = remember { ServiceLocator.playbackHistory(context) }
    val hasHistory by remember(historyStore) {
        historyStore.snapshots.map { it.isNotEmpty() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val title = when (screen) {
        is AppScreen.Fiction -> screen.fiction.title
        AppScreen.Library -> session.serverName
        AppScreen.Fictions -> "All fictions"
        AppScreen.Player -> "Now playing"
        AppScreen.Settings -> "Settings"
    }

    Scaffold(
        containerColor = AarisColor.Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = title.uppercase(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        if (screen != AppScreen.Library) {
                            TextButton(onClick = { onScreenChange(AppScreen.Library) }) {
                                Text("BACK")
                            }
                        }
                    },
                    actions = {
                        // With media loaded the mini player bar is the way in; this action only
                        // covers reaching "jump back" history when nothing is playing.
                        if (!playerState.hasMedia && hasHistory && screen != AppScreen.Player) {
                            TextButton(onClick = { onScreenChange(AppScreen.Player) }) {
                                Text("PLAYER")
                            }
                        }
                        if (screen != AppScreen.Settings) {
                            TextButton(onClick = { onScreenChange(AppScreen.Settings) }) {
                                Text("SETTINGS")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AarisColor.Bg,
                        titleContentColor = AarisColor.Ink,
                        navigationIconContentColor = AarisColor.Accent,
                        actionIconContentColor = AarisColor.Muted,
                    ),
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            }
        },
        bottomBar = {
            if (playerState.hasMedia && screen != AppScreen.Player) {
                MiniPlayerBar(
                    state = playerState,
                    playbackController = playbackController,
                    onExpand = { onScreenChange(AppScreen.Player) },
                )
            }
        },
    ) { padding ->
        when (screen) {
            AppScreen.Library -> LibraryScreen(
                padding = padding,
                repository = repository,
                playbackController = playbackController,
                onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
                onOpenPlayer = { onScreenChange(AppScreen.Player) },
                onBrowseFictions = { onScreenChange(AppScreen.Fictions) },
            )

            AppScreen.Fictions -> FictionsScreen(
                padding = padding,
                repository = repository,
                onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
            )

            is AppScreen.Fiction -> FictionScreen(
                padding = padding,
                fiction = screen.fiction,
                repository = repository,
                playbackController = playbackController,
                onOpenPlayer = { onScreenChange(AppScreen.Player) },
            )

            AppScreen.Player -> PlayerScreen(
                padding = padding,
                playerState = playerState,
                playbackController = playbackController,
            )

            AppScreen.Settings -> SettingsScreen(
                padding = padding,
                session = session,
                repository = repository,
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    playbackController: PlaybackController,
    onOpenFiction: (FictionSummary) -> Unit,
    onOpenPlayer: () -> Unit,
    onBrowseFictions: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var libraryState by remember { mutableStateOf<LoadState<LibraryResponse>>(LoadState.Loading) }

    // "You fell asleep at 23:49" — the app already knows when playback was last heard, so offer the
    // jump instead of making the user open the jump-back sheet and hunt for the time.
    val historyStore = remember { ServiceLocator.playbackHistory(context) }
    val history by historyStore.snapshots.collectAsStateWithLifecycle()
    val playerState by playbackController.state.collectAsStateWithLifecycle()
    // Survives the screen swap, so leaving and returning does not resurrect a dismissed banner.
    var dismissedLastHeard by rememberSaveable { mutableStateOf(0L) }
    val lastHeard = remember(history, playerState.isPlaying, dismissedLastHeard) {
        if (playerState.isPlaying) {
            null
        } else {
            lastHeardSnapshot(history, System.currentTimeMillis())
                ?.takeIf { it.timestamp != dismissedLastHeard }
        }
    }

    fun refresh() {
        scope.launch {
            libraryState = LoadState.Loading
            libraryState = runCatching { repository.library() }
                .fold(
                    onSuccess = { LoadState.Loaded(it) },
                    onFailure = { LoadState.Error(it.message ?: "Could not load library") },
                )
        }
    }

    LaunchedEffect(Unit) { refresh() }

    when (val state = libraryState) {
        LoadState.Loading -> LoadingPane(padding)
        is LoadState.Error -> ErrorPane(
            padding = padding,
            message = state.message,
            onRetry = ::refresh,
        )

        is LoadState.Loaded -> {
            val library = state.value
            val fictionForChapter: (ChapterSummary) -> FictionSummary? = { chapter ->
                chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                if (lastHeard != null) {
                    item {
                        LastHeardBanner(
                            snapshot = lastHeard,
                            onResume = {
                                scope.launch {
                                    jumpToSnapshot(lastHeard, playbackController, repository)
                                    onOpenPlayer()
                                }
                            },
                            onDismiss = { dismissedLastHeard = lastHeard.timestamp },
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(
                            kicker = "01",
                            title = "Continue listening",
                            actionLabel = "Refresh",
                            onAction = ::refresh,
                        )
                        if (library.continueListening.isEmpty()) {
                            EmptyCard("No active chapters")
                        } else {
                            val hero = library.continueListening.first()
                            ContinueHero(
                                chapter = hero,
                                fiction = fictionForChapter(hero),
                                onResume = {
                                    scope.launch {
                                        playbackController.play(hero, fictionForChapter(hero))
                                        onOpenPlayer()
                                    }
                                },
                            )
                            if (library.continueListening.size > 1) {
                                HorizontalChapterRail(
                                    chapters = library.continueListening.drop(1),
                                    fictionForChapter = fictionForChapter,
                                    keyPrefix = "continue",
                                    playbackController = playbackController,
                                    onOpenPlayer = onOpenPlayer,
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(
                            kicker = "02",
                            title = "Fictions",
                            actionLabel = if (library.fictions.isEmpty()) null else "Browse all",
                            onAction = onBrowseFictions.takeIf { library.fictions.isNotEmpty() },
                        )
                        if (library.fictions.isEmpty()) {
                            EmptyCard("No fictions found")
                        } else {
                            HorizontalFictionRail(
                                fictions = library.fictions,
                                onOpenFiction = onOpenFiction,
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(kicker = "03", title = "Recent")
                        if (library.recentChapters.isEmpty()) {
                            EmptyCard("No recent chapters")
                        } else {
                            HorizontalChapterRail(
                                chapters = library.recentChapters,
                                fictionForChapter = fictionForChapter,
                                keyPrefix = "recent",
                                playbackController = playbackController,
                                onOpenPlayer = onOpenPlayer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FictionScreen(
    padding: PaddingValues,
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    playbackController: PlaybackController,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var chapterState by remember(fiction.id) { mutableStateOf<LoadState<List<ChapterSummary>>>(LoadState.Loading) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            chapterState = LoadState.Loading
            chapterState = runCatching {
                repository.chapters(fiction.id, playableOnly = false).chapters
            }.fold(
                onSuccess = { LoadState.Loaded(it) },
                onFailure = { LoadState.Error(it.message ?: "Could not load chapters") },
            )
        }
    }

    LaunchedEffect(fiction.id) { refresh() }

    when (val state = chapterState) {
        LoadState.Loading -> LoadingPane(padding)
        is LoadState.Error -> ErrorPane(
            padding = padding,
            message = state.message,
            onRetry = ::refresh,
        )

        is LoadState.Loaded -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                FictionDetailHeader(
                    fiction = fiction,
                    chapters = state.value,
                    onPlay = { chapter ->
                        scope.launch {
                            playbackController.playQueue(state.value, chapter.resolvedChapterId, fiction)
                            onOpenPlayer()
                        }
                    },
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(kicker = "CH", title = "Chapters")
            }
            itemsIndexed(state.value, key = { index, chapter -> "chapter-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" }) { _, chapter ->
                ChapterRow(
                    chapter = chapter,
                    fiction = fiction,
                    onPlay = {
                        scope.launch {
                            playbackController.playQueue(state.value, chapter.resolvedChapterId, fiction)
                            onOpenPlayer()
                        }
                    },
                    onMarkPlayed = { played ->
                        scope.launch {
                            error = null
                            runCatching {
                                repository.markPlayed(listOf(chapter.resolvedChapterId), played)
                                refresh()
                            }.onFailure {
                                error = it.message ?: "Could not update chapter"
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(
    padding: PaddingValues,
    playerState: PlayerUiState,
    playbackController: PlaybackController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ServiceLocator.repository(context) }
    val historyStore = remember { ServiceLocator.playbackHistory(context) }
    val history by historyStore.snapshots.collectAsStateWithLifecycle()
    val jumpBackOptions = remember(history) { jumpBackOptions(history, System.currentTimeMillis()) }
    var showChapters by remember { mutableStateOf(false) }
    var showJumpBack by remember { mutableStateOf(false) }
    // Track the drag locally and only seek on release, so scrubbing doesn't spam the player.
    var dragMs by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetaText(text = "// Now Playing", color = AarisColor.Accent)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Largest portrait cover that fits both the width and the leftover height.
            val coverWidth = minOf(maxWidth, maxHeight * 0.7f)
            CoverFill(
                imageUrl = playerState.coverImageUrl,
                fallback = playerState.fictionTitle ?: playerState.title,
                modifier = Modifier
                    .width(coverWidth)
                    .aspectRatio(0.7f),
            )
        }
        Text(
            text = playerState.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        playerState.fictionTitle?.let {
            Spacer(modifier = Modifier.height(10.dp))
            MetaText(text = it, modifier = Modifier)
        }
        Spacer(modifier = Modifier.height(28.dp))
        Slider(
            value = dragMs ?: playerState.positionMs.coerceAtMost(playerState.durationMs).toFloat(),
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { playbackController.seekTo(it.toLong()) }
                dragMs = null
            },
            valueRange = 0f..playerState.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = playerState.durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText(text = formatDuration(dragMs?.toLong() ?: playerState.positionMs))
            MetaText(text = formatDuration(playerState.durationMs))
        }
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { playerState.bufferedPercentage / 100f },
            trackColor = AarisColor.Line,
            color = AarisColor.Dim,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        // Single transport row: chapter skips outside, fine seek inside, primary in the middle.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIconButton(
                icon = Icons.Default.SkipPrevious,
                contentDescription = "Previous chapter",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { playbackController.skipToPreviousChapter() }
            TransportIconButton(
                icon = Icons.Default.Replay30,
                contentDescription = "Back 30 seconds",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { playbackController.skipBy(-30_000) }
            TransportIconButton(
                icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                enabled = playerState.hasMedia,
                size = 68.dp,
                filled = true,
            ) { playbackController.togglePlayPause() }
            TransportIconButton(
                icon = Icons.Default.Forward30,
                contentDescription = "Forward 30 seconds",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { playbackController.skipBy(30_000) }
            TransportIconButton(
                icon = Icons.Default.SkipNext,
                contentDescription = "Next chapter",
                enabled = playerState.hasNext,
                size = 46.dp,
            ) { playbackController.skipToNextChapter() }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Tertiary: playback speed and the chapter list.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { playbackController.setSpeed(nextSpeed(playerState.speed)) },
                enabled = playerState.hasMedia,
            ) {
                Text("SPEED ${formatSpeed(playerState.speed)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (jumpBackOptions.isNotEmpty()) {
                    TextButton(onClick = { showJumpBack = true }) {
                        Text("JUMP BACK")
                    }
                }
                if (playerState.queue.size > 1) {
                    TextButton(onClick = { showChapters = true }) {
                        Text("CHAPTERS ${playerState.currentIndex + 1}/${playerState.queue.size}")
                    }
                }
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            containerColor = AarisColor.BgRaise,
        ) {
            MetaText(
                text = "// Chapters",
                color = AarisColor.Accent,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(
                    playerState.queue,
                    key = { index, item -> "${item.mediaId}-$index" },
                ) { index, item ->
                    val isCurrent = index == playerState.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playbackController.skipToQueueIndex(index)
                                showChapters = false
                            }
                            .background(if (isCurrent) AarisColor.BgHover else AarisColor.BgRaise)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaText(
                            text = "%02d".format(index + 1),
                            color = if (isCurrent) AarisColor.Accent else AarisColor.Dim,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isCurrent) AarisColor.Accent else AarisColor.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Spacer(modifier = Modifier.width(8.dp))
                            MetaText(
                                text = if (playerState.isPlaying) "Playing" else "Paused",
                                color = AarisColor.Accent,
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                }
            }
        }
    }

    if (showJumpBack) {
        val now = System.currentTimeMillis()
        var sleepTimeInput by remember { mutableStateOf("") }
        var sleepTimeError by remember { mutableStateOf<String?>(null) }
        ModalBottomSheet(
            onDismissRequest = { showJumpBack = false },
            containerColor = AarisColor.BgRaise,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText(text = "// Jump back to where you were", color = AarisColor.Accent)
                TextButton(onClick = {
                    historyStore.clear()
                    showJumpBack = false
                }) {
                    Text("CLEAR")
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                MetaText(text = "// Fell asleep at (check your health app)", color = AarisColor.Muted)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = sleepTimeInput,
                        onValueChange = {
                            sleepTimeInput = it
                            sleepTimeError = null
                        },
                        placeholder = { Text("23:49") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val parsed = parseClockTime(sleepTimeInput)
                            val target = parsed?.let { (h, m) -> resolveSleepTimestamp(h, m, now) }
                            val nearest = target?.let { t -> history.minByOrNull { kotlin.math.abs(it.timestamp - t) } }
                            when {
                                parsed == null -> sleepTimeError = "Use 24h HH:MM, e.g. 23:49"
                                nearest == null -> sleepTimeError = "No playback history to match"
                                else -> {
                                    scope.launch {
                                        jumpToSnapshot(nearest, playbackController, repository)
                                    }
                                    showJumpBack = false
                                }
                            }
                        },
                        shape = RectangleShape,
                    ) {
                        Text("JUMP")
                    }
                }
                sleepTimeError?.let {
                    MetaText(text = it, color = AarisColor.Danger, modifier = Modifier.padding(top = 4.dp))
                }
                MetaText(
                    text = "// Or pick a moment",
                    color = AarisColor.Muted,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(
                    jumpBackOptions,
                    key = { _, snap -> "${snap.timestamp}-${snap.mediaId}" },
                ) { _, snap ->
                    val inQueue = playerState.queue.any { it.mediaId == snap.mediaId }
                    val canJump = inQueue || (snap.fictionId > 0 && snap.chapterId > 0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canJump) {
                                scope.launch {
                                    jumpToSnapshot(snap, playbackController, repository)
                                }
                                showJumpBack = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatClockTime(context, snap.timestamp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (canJump) AarisColor.Ink else AarisColor.Dim,
                            )
                            MetaText(
                                text = "${relativeAgo(now - snap.timestamp)}  ·  " +
                                    listOfNotNull(snap.fictionTitle, snap.title).joinToString("  ·  "),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        MetaText(text = formatDuration(snap.positionMs))
                    }
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                }
            }
        }
    }
}

/**
 * "Last heard 23:49 — Ashes of Aether — Ch 7 — 1:12:34", above the library's continue-listening
 * section. Shown only when nothing is playing and the last snapshot is old enough to be a different
 * sitting, so it reads as catching up after a night rather than a rewind offer mid-listen.
 */
@Composable
private fun LastHeardBanner(
    snapshot: HistorySnapshot,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaText(text = "// Last heard", color = AarisColor.Accent)
            Text(
                text = formatClockTime(context, snapshot.timestamp),
                style = MaterialTheme.typography.titleLarge,
            )
            MetaText(
                text = listOfNotNull(
                    snapshot.fictionTitle,
                    snapshot.title,
                    formatDuration(snapshot.positionMs),
                ).joinToString(" · "),
                color = AarisColor.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume, shape = RectangleShape) {
                    Text("RESUME THERE")
                }
                TextButton(onClick = onDismiss) {
                    Text("DISMISS")
                }
            }
        }
    }
}

/**
 * Fast path: seek within the loaded queue. Otherwise (queue was cleared — e.g. a
 * sleep-tracker stopped playback overnight) reload the fiction and start at the exact
 * historical position.
 */
private suspend fun jumpToSnapshot(
    snap: HistorySnapshot,
    playbackController: PlaybackController,
    repository: TtsRoadRepository,
) {
    if (!playbackController.seekToMediaId(snap.mediaId, snap.positionMs)) {
        runCatching {
            val resp = repository.chapters(snap.fictionId, playableOnly = false)
            playbackController.playQueue(
                chapters = resp.chapters,
                startChapterId = snap.chapterId,
                fiction = resp.fiction,
                startPositionMsOverride = snap.positionMs,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    session: SessionState,
    repository: TtsRoadRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { ServiceLocator.updateManager() }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetaText(text = "// Session", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsItem(label = "Server", value = session.serverUrl)
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(label = "User", value = session.username.orEmpty())
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(label = "Role", value = if (session.isAdmin) "Admin" else "User")
            }
        }

        MetaText(text = "// App", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsItem(label = "Version", value = BuildConfig.VERSION_NAME)
                updateStatusText(updateState)?.let { (text, isError) ->
                    MetaText(text = text, color = if (isError) AarisColor.Danger else AarisColor.Muted)
                }
                OutlinedButton(
                    onClick = { scope.launch { updateManager.check(BuildConfig.VERSION_NAME, manual = true) } },
                    enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                    shape = RectangleShape,
                ) {
                    Text(if (updateState is UpdateState.Checking) "CHECKING…" else "CHECK FOR UPDATES")
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    isBusy = true
                    repository.logout()
                    isBusy = false
                }
            },
            enabled = !isBusy,
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isBusy) "SIGNING OUT" else "SIGN OUT")
        }
    }
}

private fun updateStatusText(state: UpdateState): Pair<String, Boolean>? = when (state) {
    UpdateState.UpToDate -> "You're on the latest version" to false
    is UpdateState.Available -> "Version ${state.release.versionName} available" to false
    is UpdateState.Downloading -> "Downloading ${state.percent}%" to false
    is UpdateState.Failed -> state.message to true
    else -> null
}

/**
 * Persistent bottom bar (Audible-style): playback keeps its place in the UI while the user
 * browses. Tapping the track info expands to the full player.
 */
@Composable
private fun MiniPlayerBar(
    state: PlayerUiState,
    playbackController: PlaybackController,
    onExpand: () -> Unit,
) {
    val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AarisColor.BgRaise)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
        ThinProgress(fraction = fraction, modifier = Modifier.fillMaxWidth(), height = 2.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverThumb(
                    imageUrl = state.coverImageUrl,
                    fallback = state.fictionTitle ?: state.title,
                    size = 46,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AarisColor.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.fictionTitle?.let {
                        MetaText(text = it, color = AarisColor.Dim)
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            TransportIconButton(
                icon = Icons.Default.Replay30,
                contentDescription = "Back 30 seconds",
                enabled = state.hasMedia,
                size = 42.dp,
            ) { playbackController.skipBy(-30_000) }
            Spacer(modifier = Modifier.width(8.dp))
            TransportIconButton(
                icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                enabled = state.hasMedia,
                size = 42.dp,
                filled = true,
            ) { playbackController.togglePlayPause() }
        }
    }
}

/** Square AARIS transport control: outlined by default, accent-filled for the primary action. */
@Composable
private fun TransportIconButton(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    size: androidx.compose.ui.unit.Dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                when {
                    filled && enabled -> AarisColor.Accent
                    filled -> AarisColor.Line
                    else -> AarisColor.BgRaise
                },
            )
            .let { if (filled) it else it.border(1.dp, AarisColor.Line) }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                filled -> AarisColor.Bg
                enabled -> AarisColor.Muted
                else -> AarisColor.Dim
            },
            modifier = Modifier.size(size / 2),
        )
    }
}

/** Fraction of the chapter already listened to, for progress-on-artwork. */
private fun listenedFraction(chapter: ChapterSummary): Float {
    val duration = chapter.audioDuration ?: return 0f
    if (duration <= 0.0) return 0f
    return (chapter.resolvedPositionSeconds / duration).toFloat().coerceIn(0f, 1f)
}

/**
 * Netflix-style billboard for the most recent in-progress chapter: large cover, gradient panel,
 * one prominent resume action.
 */
@Composable
private fun ContinueHero(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    onResume: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .border(1.dp, AarisColor.Line)
            .background(Brush.horizontalGradient(listOf(AarisColor.BgHover, AarisColor.Bg))),
    ) {
        CoverFill(
            imageUrl = fiction?.coverImageUrl ?: chapter.resolvedCoverUrl,
            fallback = fiction?.title ?: chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(2f / 3f),
            bordered = false,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp),
        ) {
            Text(
                text = chapter.resolvedTitle,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (fiction?.title ?: chapter.resolvedFictionTitle)?.let {
                Spacer(modifier = Modifier.height(4.dp))
                MetaText(text = it)
            }
            Spacer(modifier = Modifier.weight(1f))
            ThinProgress(fraction = listenedFraction(chapter), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onResume,
                    enabled = chapter.audio != null,
                    shape = RectangleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (chapter.resolvedPositionSeconds > 0.0) "RESUME" else "PLAY")
                }
                Spacer(modifier = Modifier.width(12.dp))
                (chapter.playback?.remainingLabel?.let { "$it left" } ?: chapter.audioDurationLabel)
                    ?.let { MetaText(text = it, color = AarisColor.Dim) }
            }
        }
    }
}

@Composable
private fun HorizontalChapterRail(
    chapters: List<ChapterSummary>,
    fictionForChapter: (ChapterSummary) -> FictionSummary?,
    keyPrefix: String,
    playbackController: PlaybackController,
    onOpenPlayer: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            chapters,
            key = { index, chapter -> "$keyPrefix-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" },
        ) { _, chapter ->
            ChapterTile(
                chapter = chapter,
                fiction = fictionForChapter(chapter),
                playbackController = playbackController,
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

@Composable
private fun HorizontalFictionRail(
    fictions: List<FictionSummary>,
    onOpenFiction: (FictionSummary) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(fictions, key = { index, fiction -> "fiction-${fiction.id}-$index" }) { _, fiction ->
            FictionTile(fiction = fiction, onClick = { onOpenFiction(fiction) })
        }
    }
}

/**
 * Cover-forward rail tile (Netflix-style): the art carries the tile, listening progress is drawn
 * directly on it, and the whole tile is the tap target — no inline PLAY button.
 */
@Composable
private fun ChapterTile(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    playbackController: PlaybackController,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(enabled = chapter.audio != null) {
                scope.launch {
                    playbackController.play(chapter, fiction)
                    onOpenPlayer()
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(1.dp, AarisColor.Line),
        ) {
            CoverFill(
                imageUrl = fiction?.coverImageUrl ?: chapter.resolvedCoverUrl,
                fallback = fiction?.title ?: chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
                modifier = Modifier.fillMaxSize(),
                bordered = false,
            )
            ThinProgress(
                fraction = listenedFraction(chapter),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = chapter.resolvedTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        MetaText(
            text = listOfNotNull(
                fiction?.title ?: chapter.resolvedFictionTitle,
                chapter.playback?.remainingLabel?.let { "$it left" },
            ).joinToString("  ·  ").ifBlank { chapter.audioDurationLabel.orEmpty() },
            color = AarisColor.Dim,
        )
    }
}

@Composable
private fun FictionTile(fiction: FictionSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(1.dp, AarisColor.Line),
        ) {
            CoverFill(
                imageUrl = fiction.coverImageUrl,
                fallback = fiction.title,
                modifier = Modifier.fillMaxSize(),
                bordered = false,
            )
            ThinProgress(
                fraction = fiction.readyFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = fiction.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        MetaText(
            text = listOfNotNull(
                fiction.author?.takeIf { it.isNotBlank() },
                "${fiction.doneChapters}/${fiction.totalChapters}",
            ).joinToString("  ·  "),
            color = AarisColor.Dim,
        )
    }
}

/**
 * Flat chapter list row (Audible-style): the row itself is the play target, the trailing check
 * toggles played state, and unplayable chapters surface their pipeline status as a tag.
 */
@Composable
private fun ChapterRow(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    onPlay: () -> Unit,
    onMarkPlayed: ((Boolean) -> Unit)? = null,
) {
    val playable = chapter.audio != null
    val isPlayed = chapter.playback?.isPlayed == true
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = playable, onClick = onPlay)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(
                text = chapterNumberLabel(chapter),
                color = AarisColor.Dim,
                modifier = Modifier.width(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.resolvedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (playable) AarisColor.Ink else AarisColor.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    chapter.audioDurationLabel,
                    chapter.playback?.remainingLabel?.let { "$it left" }
                        ?: chapter.resumeTimeLabel?.let { "$it in" },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(text = meta, color = AarisColor.Dim)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (!playable) {
                AarisTag(text = chapter.status ?: "pending")
            } else {
                onMarkPlayed?.let { mark ->
                    TransportIconButton(
                        icon = Icons.Default.Check,
                        contentDescription = if (isPlayed) "Mark unplayed" else "Mark played",
                        enabled = true,
                        size = 36.dp,
                        filled = isPlayed,
                    ) { mark(!isPlayed) }
                }
                Spacer(modifier = Modifier.width(8.dp))
                TransportIconButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    enabled = true,
                    size = 36.dp,
                ) { onPlay() }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.LineSoft)
    }
}

private fun chapterNumberLabel(chapter: ChapterSummary): String {
    val n = chapter.displayNumber ?: return "—"
    return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
}

@Composable
private fun FictionsScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    onOpenFiction: (FictionSummary) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState<List<FictionSummary>>>(LoadState.Loading) }
    var query by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            state = LoadState.Loading
            state = runCatching { repository.library().fictions }
                .fold(
                    onSuccess = { LoadState.Loaded(it) },
                    onFailure = { LoadState.Error(it.message ?: "Could not load fictions") },
                )
        }
    }

    LaunchedEffect(Unit) { refresh() }

    when (val s = state) {
        LoadState.Loading -> LoadingPane(padding)
        is LoadState.Error -> ErrorPane(padding = padding, message = s.message, onRetry = ::refresh)
        is LoadState.Loaded -> {
            val fictions = s.value
            val filtered = remember(fictions, query) {
                val q = query.trim().lowercase()
                if (q.isBlank()) {
                    fictions
                } else {
                    fictions.filter { fiction ->
                        fiction.title.lowercase().contains(q) ||
                            fiction.author?.lowercase()?.contains(q) == true ||
                            fiction.tags.any { it.lowercase().contains(q) }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("SEARCH TITLE, AUTHOR OR TAG") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        EmptyCard(
                            if (query.isBlank()) "No fictions found" else "No matches for \"$query\"",
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 158.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { fiction ->
                            FictionGridCard(fiction = fiction, onClick = { onOpenFiction(fiction) })
                        }
                    }
                }
            }
        }
    }
}

/** Cover-forward grid card: art bleeds to the card edge, TTS-ready progress sits on the art. */
@Composable
private fun FictionGridCard(fiction: FictionSummary, onClick: () -> Unit) {
    AarisCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                CoverFill(
                    imageUrl = fiction.coverImageUrl,
                    fallback = fiction.title,
                    modifier = Modifier.fillMaxSize(),
                    bordered = false,
                )
                ThinProgress(
                    fraction = fiction.readyFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = fiction.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaText(
                    text = listOfNotNull(
                        fiction.author?.takeIf { it.isNotBlank() },
                        "${fiction.doneChapters}/${fiction.totalChapters} ready",
                    ).joinToString("  ·  "),
                    color = AarisColor.Dim,
                )
            }
        }
    }
}

@Composable
private fun FictionDetailHeader(
    fiction: FictionSummary,
    chapters: List<ChapterSummary>,
    onPlay: (ChapterSummary) -> Unit,
) {
    var descExpanded by remember(fiction.id) { mutableStateOf(false) }
    var descCanExpand by remember(fiction.id) { mutableStateOf(false) }
    val target = remember(chapters) {
        chapters.filter { it.audio != null && it.resolvedPositionSeconds > 0.0 }
            .maxByOrNull { it.resolvedPositionSeconds }
            ?: chapters.firstOrNull { it.audio != null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            CoverThumb(
                imageUrl = fiction.coverImageUrl,
                fallback = fiction.title,
                size = 120,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = fiction.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                fiction.author?.takeIf { it.isNotBlank() }?.let { MetaText(text = it) }
                fiction.rating?.let { rating ->
                    MetaText(
                        text = buildString {
                            append("★ ")
                            append("%.2f".format(rating))
                            fiction.ratingCount?.takeIf { it > 0 }?.let { append("  ·  $it ratings") }
                        },
                        color = AarisColor.Warning,
                    )
                }
            }
        }

        target?.let { chapter ->
            Button(
                onClick = { onPlay(chapter) },
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (chapter.resolvedPositionSeconds > 0.0) "RESUME" else "PLAY")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { fiction.readyFraction },
                color = AarisColor.Accent,
                trackColor = AarisColor.Line,
                modifier = Modifier.fillMaxWidth(),
            )
            MetaText(
                text = buildString {
                    append("${fiction.doneChapters} / ${fiction.totalChapters} chapters ready")
                    if (fiction.processingChapters > 0) append("  ·  ${fiction.processingChapters} processing")
                    if (fiction.errorChapters > 0) append("  ·  ${fiction.errorChapters} failed")
                },
            )
        }

        if (fiction.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fiction.tags.forEach { tag -> AarisTag(text = tag) }
            }
        }

        fiction.description?.takeIf { it.isNotBlank() }?.let { description ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaText(text = "Synopsis", color = AarisColor.Accent)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AarisColor.Muted,
                    maxLines = if (descExpanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!descExpanded) descCanExpand = it.hasVisualOverflow },
                    modifier = Modifier.clickable(enabled = descCanExpand) { descExpanded = !descExpanded },
                )
                if (descCanExpand) {
                    Text(
                        text = if (descExpanded) "SHOW LESS" else "SHOW MORE",
                        style = MaterialTheme.typography.labelLarge,
                        color = AarisColor.Accent,
                        modifier = Modifier.clickable { descExpanded = !descExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverFill(imageUrl: String?, fallback: String, modifier: Modifier, bordered: Boolean = true) {
    Box(
        modifier = modifier
            .background(AarisColor.BgInput)
            .let { if (bordered) it.border(1.dp, AarisColor.Line) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallback.trim().take(1).uppercase().ifBlank { "T" },
                style = MaterialTheme.typography.displaySmall,
                color = AarisColor.Accent,
            )
        }
    }
}

@Composable
private fun CoverThumb(imageUrl: String?, fallback: String, size: Int = 64) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(AarisColor.BgInput)
            .border(1.dp, AarisColor.Line),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallback.trim().take(1).uppercase().ifBlank { "T" },
                style = MaterialTheme.typography.headlineSmall,
                color = AarisColor.Accent,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    kicker: String,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(text = "§ $kicker", color = AarisColor.Accent)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel.uppercase())
                }
            }
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

@Composable
private fun EmptyCard(message: String) {
    AarisCard {
        MetaText(
            text = message,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun LoadingPane(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = AarisColor.Accent)
    }
}

@Composable
private fun ErrorPane(
    padding: PaddingValues,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry, shape = RectangleShape) {
            Text("RETRY")
        }
    }
}

@Composable
private fun SettingsItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetaText(text = label)
        Text(
            text = value.ifBlank { "-" },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private val SpeedPresets = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f)

private fun nextSpeed(current: Float): Float {
    val index = SpeedPresets.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return if (index < 0) 1.0f else SpeedPresets[(index + 1) % SpeedPresets.size]
}

private fun formatSpeed(speed: Float): String {
    val text = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "${text}×"
}

/**
 * Thin the recorded position history into a short, evenly-spaced list of "jump back" targets
 * (newest first, at least 5 minutes apart, skipping the last minute) for the player sheet.
 */
private fun jumpBackOptions(history: List<HistorySnapshot>, now: Long): List<HistorySnapshot> {
    val out = mutableListOf<HistorySnapshot>()
    var lastTs = Long.MAX_VALUE
    for (snap in history.asReversed()) {
        if (now - snap.timestamp < 60_000L) continue
        if (lastTs - snap.timestamp < 5 * 60_000L) continue
        out.add(snap)
        lastTs = snap.timestamp
        if (out.size >= 24) break
    }
    return out
}

private fun relativeAgo(deltaMs: Long): String {
    val minutes = (deltaMs / 60_000L).toInt().coerceAtLeast(0)
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m ago"
        minutes >= 1 -> "${minutes}m ago"
        else -> "just now"
    }
}

private fun formatClockTime(context: android.content.Context, epochMillis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMillis))

/** Parses a "HH:MM" (24h) clock time typed by the user, e.g. from a health app's sleep log. */
private fun parseClockTime(input: String): Pair<Int, Int>? {
    val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(input) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/**
 * Resolves a typed clock time to the most recent wall-clock instant it could refer to: today at
 * that time, or yesterday if today-at-that-time hasn't happened yet (the usual case — you check
 * your health app's sleep time after waking, so e.g. "23:49" means last night).
 */
private fun resolveSleepTimestamp(hour: Int, minute: Int, now: Long): Long {
    val zone = ZoneId.systemDefault()
    val nowInstant = Instant.ofEpochMilli(now)
    val candidate = nowInstant.atZone(zone).toLocalDate().atTime(hour, minute).atZone(zone)
    val resolved = if (!candidate.toInstant().isBefore(nowInstant)) candidate.minusDays(1) else candidate
    return resolved.toInstant().toEpochMilli()
}
