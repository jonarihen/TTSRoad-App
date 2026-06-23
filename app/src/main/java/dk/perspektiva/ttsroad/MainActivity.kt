package dk.perspektiva.ttsroad

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LibraryResponse
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import kotlinx.coroutines.launch

private sealed interface AppScreen {
    data object Library : AppScreen
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    TtsRoadApp()
                }
            }
        }
    }
}

@Composable
private fun TtsRoadApp() {
    val context = LocalContext.current
    val tokenStore = remember { ServiceLocator.tokenStore(context) }
    val repository = remember { ServiceLocator.repository(context) }
    val playbackController = remember { ServiceLocator.playbackController(context) }
    val session by tokenStore.session.collectAsStateWithLifecycle(initialValue = SessionState())
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Library) }

    LaunchedEffect(session.isLoggedIn) {
        if (!session.isLoggedIn) {
            screen = AppScreen.Library
        }
    }

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
}

@Composable
private fun LoginScreen(repository: TtsRoadRepository, session: SessionState) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(session.serverUrl.ifBlank { "https://" }) }
    var username by remember { mutableStateOf(session.username.orEmpty().ifBlank { "admin" }) }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TTSRoad",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Connect to your private server",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
                    runCatching {
                        repository.login(
                            baseUrl = serverUrl,
                            username = username,
                            password = password,
                            deviceName = deviceName,
                        )
                    }.onFailure {
                        error = it.message ?: "Login failed"
                    }
                    isBusy = false
                }
            },
            enabled = !isBusy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isBusy) "Signing in" else "Sign in")
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
    val playerState by playbackController.state.collectAsStateWithLifecycle()
    val title = when (screen) {
        is AppScreen.Fiction -> screen.fiction.title
        AppScreen.Library -> session.serverName
        AppScreen.Player -> "Now playing"
        AppScreen.Settings -> "Settings"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (screen is AppScreen.Fiction || screen == AppScreen.Player || screen == AppScreen.Settings) {
                        TextButton(onClick = { onScreenChange(AppScreen.Library) }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (playerState.hasMedia && screen != AppScreen.Player) {
                        TextButton(onClick = { onScreenChange(AppScreen.Player) }) {
                            Text("Player")
                        }
                    }
                    if (screen != AppScreen.Settings) {
                        TextButton(onClick = { onScreenChange(AppScreen.Settings) }) {
                            Text("Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when (screen) {
            AppScreen.Library -> LibraryScreen(
                padding = padding,
                repository = repository,
                playbackController = playbackController,
                onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
                onOpenPlayer = { onScreenChange(AppScreen.Player) },
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
) {
    val scope = rememberCoroutineScope()
    var libraryState by remember { mutableStateOf<LoadState<LibraryResponse>>(LoadState.Loading) }

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionHeader(
                        title = "Continue listening",
                        actionLabel = "Refresh",
                        onAction = ::refresh,
                    )
                }
                if (library.continueListening.isEmpty()) {
                    item { EmptyCard("No active chapters") }
                } else {
                    itemsIndexed(library.continueListening, key = { index, chapter -> "continue-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" }) { _, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId },
                            playbackController = playbackController,
                            onOpenPlayer = onOpenPlayer,
                        )
                    }
                }

                item { SectionHeader(title = "Fictions") }
                if (library.fictions.isEmpty()) {
                    item { EmptyCard("No fictions found") }
                } else {
                    itemsIndexed(library.fictions, key = { index, fiction -> "fiction-${fiction.id}-$index" }) { _, fiction ->
                        FictionRow(fiction = fiction, onClick = { onOpenFiction(fiction) })
                    }
                }

                item { SectionHeader(title = "Recent") }
                if (library.recentChapters.isEmpty()) {
                    item { EmptyCard("No recent chapters") }
                } else {
                    itemsIndexed(library.recentChapters, key = { index, chapter -> "recent-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" }) { _, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId },
                            playbackController = playbackController,
                            onOpenPlayer = onOpenPlayer,
                        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FictionHeader(fiction = fiction)
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
            itemsIndexed(state.value, key = { index, chapter -> "chapter-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" }) { _, chapter ->
                ChapterRow(
                    chapter = chapter,
                    fiction = fiction,
                    playbackController = playbackController,
                    onOpenPlayer = onOpenPlayer,
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

@Composable
private fun PlayerScreen(
    padding: PaddingValues,
    playerState: PlayerUiState,
    playbackController: PlaybackController,
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
            text = playerState.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        playerState.fictionTitle?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Slider(
            value = playerState.positionMs.coerceAtMost(playerState.durationMs).toFloat(),
            onValueChange = { playbackController.seekTo(it.toLong()) },
            valueRange = 0f..playerState.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = playerState.durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatDuration(playerState.positionMs))
            Text(formatDuration(playerState.durationMs))
        }
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { playerState.bufferedPercentage / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { playbackController.skipBy(-30_000) },
                enabled = playerState.hasMedia,
            ) {
                Text("-30")
            }
            Button(
                onClick = { playbackController.togglePlayPause() },
                enabled = playerState.hasMedia,
            ) {
                Text(if (playerState.isPlaying) "Pause" else "Play")
            }
            OutlinedButton(
                onClick = { playbackController.skipBy(30_000) },
                enabled = playerState.hasMedia,
            ) {
                Text("+30")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    session: SessionState,
    repository: TtsRoadRepository,
) {
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsItem(label = "Server", value = session.serverUrl)
        SettingsItem(label = "User", value = session.username.orEmpty())
        SettingsItem(label = "Role", value = if (session.isAdmin) "Admin" else "User")
        Button(
            onClick = {
                scope.launch {
                    isBusy = true
                    repository.logout()
                    isBusy = false
                }
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isBusy) "Signing out" else "Sign out")
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    playbackController: PlaybackController,
    onOpenPlayer: () -> Unit,
    onMarkPlayed: ((Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverThumb(
                imageUrl = fiction?.coverImageUrl ?: chapter.resolvedCoverUrl,
                fallback = fiction?.title ?: chapter.resolvedFictionTitle ?: chapter.resolvedTitle,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.resolvedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        fiction?.title ?: chapter.resolvedFictionTitle,
                        chapter.audioDurationLabel,
                        chapter.playback?.remainingLabel?.let { "$it left" } ?: chapter.resumeTimeLabel?.let { "$it in" },
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                onMarkPlayed?.let { mark ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { mark(true) }) {
                            Text("Played")
                        }
                        OutlinedButton(onClick = { mark(false) }) {
                            Text("Unplayed")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        playbackController.play(chapter, fiction)
                        onOpenPlayer()
                    }
                },
                enabled = chapter.audio != null,
            ) {
                Text("Play")
            }
        }
    }
}

@Composable
private fun FictionRow(fiction: FictionSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverThumb(imageUrl = fiction.coverImageUrl, fallback = fiction.title)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fiction.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        fiction.author,
                        "${fiction.doneChapters}/${fiction.totalChapters} ready",
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FictionHeader(fiction: FictionSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumb(
            imageUrl = fiction.coverImageUrl,
            fallback = fiction.title,
            size = 84,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fiction.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    fiction.author,
                    "${fiction.doneChapters}/${fiction.totalChapters} ready",
                ).joinToString(" - "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverThumb(imageUrl: String?, fallback: String, size: Int = 64) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                text = fallback.trim().take(1).ifBlank { "T" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        CircularProgressIndicator()
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
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun SettingsItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

