package dk.perspektiva.ttsroad

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import dk.perspektiva.ttsroad.core.ServerUrls
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterFilter
import dk.perspektiva.ttsroad.data.Bookmark
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.DeviceSession
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.HighlightGranularity
import dk.perspektiva.ttsroad.data.formatExpiresIn
import dk.perspektiva.ttsroad.data.formatServerTimestamp
import dk.perspektiva.ttsroad.data.LoginResult
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.ReadAlongHighlight
import dk.perspektiva.ttsroad.data.ReaderFontScales
import dk.perspektiva.ttsroad.data.ReaderPrefs
import dk.perspektiva.ttsroad.data.ReaderTheme
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.DefaultSkipIntervalMs
import dk.perspektiva.ttsroad.data.DefaultSkipSilence
import dk.perspektiva.ttsroad.data.DownloadPrefs
import dk.perspektiva.ttsroad.data.PlaybackPrefs
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SkipIntervalOptionsMs
import dk.perspektiva.ttsroad.data.speedOptions
import dk.perspektiva.ttsroad.data.TextSpan
import dk.perspektiva.ttsroad.data.VolumeBoost
import dk.perspektiva.ttsroad.data.formatReaderFontScale
import dk.perspektiva.ttsroad.data.formatSkipInterval
import dk.perspektiva.ttsroad.data.readAlongAvailability
import dk.perspektiva.ttsroad.data.readerAutoScrollOffsetPx
import dk.perspektiva.ttsroad.data.shouldKeepReaderScreenOn
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.allChapterIds
import dk.perspektiva.ttsroad.data.chapterIdsBefore
import dk.perspektiva.ttsroad.data.chapterView
import dk.perspektiva.ttsroad.download.ChapterDownload
import dk.perspektiva.ttsroad.download.ChapterDownloadState
import dk.perspektiva.ttsroad.download.DownloadBatchSize
import dk.perspektiva.ttsroad.download.FictionDownloadSummary
import dk.perspektiva.ttsroad.download.fictionDownloadSummary
import dk.perspektiva.ttsroad.download.formatStorageSize
import dk.perspektiva.ttsroad.download.handledChapterIds
import dk.perspektiva.ttsroad.download.nextChaptersToDownload
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import dk.perspektiva.ttsroad.nav.AppScreen
import dk.perspektiva.ttsroad.nav.navigateTo
import dk.perspektiva.ttsroad.nav.popScreen
import dk.perspektiva.ttsroad.nav.rootBackStack
import dk.perspektiva.ttsroad.nav.saveKey
import dk.perspektiva.ttsroad.player.FictionListeningSummary
import dk.perspektiva.ttsroad.player.fictionListeningSummary
import dk.perspektiva.ttsroad.player.formatListeningSpan
import dk.perspektiva.ttsroad.player.HistorySnapshot
import dk.perspektiva.ttsroad.player.breadcrumbSnapshot
import dk.perspektiva.ttsroad.player.mergeBreadcrumbs
import dk.perspektiva.ttsroad.player.lastHeardSnapshot
import dk.perspektiva.ttsroad.player.listeningSpanAtSpeed
import dk.perspektiva.ttsroad.player.remainingMs
import dk.perspektiva.ttsroad.player.remainingMsAtSpeed
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerMode
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.ReaderPalette
import dk.perspektiva.ttsroad.ui.readerPalette
import kotlin.math.roundToLong
import dk.perspektiva.ttsroad.ui.ThinProgress
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Server the user signed in to, so cover URLs built from the backend's BASE_URL can be pointed at
 * the address the phone can actually reach. See [ServerUrls.rewriteHost].
 */
private val LocalServerUrl = staticCompositionLocalOf { "" }

class MainActivity : ComponentActivity() {
    // Notification taps that arrive while the activity is already running come through
    // onNewIntent, so they are relayed to the composition rather than read from the start intent.
    private val openPlayerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pick up any download that was still running when the app was last killed. Done from the
        // activity because the process is in the foreground here, so starting the service is allowed.
        ServiceLocator.offlineDownloads(this).resumeUnfinished()
        val startOnPlayer = consumeOpenPlayer(intent)
        setContent {
            TtsRoadTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AarisColor.Bg,
                ) {
                    TtsRoadApp(
                        startOnPlayer = startOnPlayer,
                        openPlayerRequests = openPlayerRequests,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeOpenPlayer(intent)) openPlayerRequests.tryEmit(Unit)
    }

    companion object {
        private const val EXTRA_OPEN_PLAYER = "dk.perspektiva.ttsroad.extra.OPEN_PLAYER"

        /** Start intent used by the media session so a notification tap lands on the player. */
        fun playerIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_PLAYER, true)

        // Read once and clear, so an activity recreation (rotation, theme change) does not bounce
        // the user back to the player after they have navigated away.
        @VisibleForTesting
        internal fun consumeOpenPlayer(intent: Intent?): Boolean {
            if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) != true) return false
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            return true
        }
    }
}

@Composable
private fun TtsRoadApp(
    startOnPlayer: Boolean = false,
    openPlayerRequests: Flow<Unit> = emptyFlow(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { ServiceLocator.tokenStore(context) }
    val repository = remember { ServiceLocator.repository(context) }
    val playbackController = remember { ServiceLocator.playbackController(context) }
    val updateManager = remember { ServiceLocator.updateManager() }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val session by tokenStore.session.collectAsStateWithLifecycle(initialValue = SessionState())
    var backStack by remember { mutableStateOf(rootBackStack) }
    var openPlayerPending by remember { mutableStateOf(startOnPlayer) }

    // Denial is not an error path: playback still works, and Settings explains what is missing.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            // Ask what this server supports before the library renders, so optional affordances are
            // gated by the time there is anything to gate. Never throws; an older or unreachable
            // server resolves to the baseline and the ordinary flow continues.
            repository.refreshCurrentCapabilities()
            playbackController.connect()
            // Ask once playback becomes possible, so the rationale for the prompt is obvious.
            if (needsNotificationPermission(context)) {
                notificationPermissionLauncher.launch(PostNotificationsPermission)
            }
        } else {
            backStack = rootBackStack
            playbackController.stop()
            // The cache outlives the composition, so signing out has to empty it explicitly —
            // otherwise the next account is shown the previous one's library.
            ServiceLocator.libraryCache(context).clear()
        }
    }

    LaunchedEffect(openPlayerRequests) {
        openPlayerRequests.collect { openPlayerPending = true }
    }

    // The stored session loads asynchronously, so a notification tap can land before isLoggedIn is
    // known. Holding the request until then keeps the reset above from swallowing it.
    LaunchedEffect(openPlayerPending, session.isLoggedIn) {
        if (openPlayerPending && session.isLoggedIn) {
            backStack = backStack.navigateTo(AppScreen.Player)
            openPlayerPending = false
        }
    }

    // Quietly check GitHub Releases for a newer build once per launch.
    LaunchedEffect(Unit) { updateManager.check(BuildConfig.VERSION_NAME) }

    CompositionLocalProvider(LocalServerUrl provides session.serverUrl) {
        if (!session.isLoggedIn) {
            LoginScreen(repository = repository, session = session)
        } else {
            MainScaffold(
                session = session,
                screen = backStack.last(),
                canGoBack = backStack.size > 1,
                onScreenChange = { backStack = backStack.navigateTo(it) },
                onBack = { backStack = backStack.popScreen() },
                repository = repository,
                playbackController = playbackController,
            )
        }
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
    val sessionEnd by repository.sessionEnd.collectAsStateWithLifecycle()
    // A failed attempt has more to say than "your old token went stale", so it wins. Otherwise the
    // server's own wording explains which of expiry, revocation or a reset put the user here.
    val notice = error ?: sessionEnd?.message
    var probed by remember { mutableStateOf<ServerCapabilities?>(null) }

    // Capability discovery is public, so the URL can be checked before any credentials are typed.
    // Debounced because this runs on every keystroke in the URL field; a failure stays silent since
    // an unreachable server here is usually just a half-typed address.
    LaunchedEffect(serverUrl) {
        probed = null
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) return@LaunchedEffect
        delay(600)
        probed = repository.capabilities(serverUrl).takeIf { it.serverVersion != null }
    }

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
            supportingText = probed?.let { found ->
                { MetaText(text = "${found.serverName} ${found.serverVersion}", color = AarisColor.Accent) }
            },
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
        notice?.let {
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
    canGoBack: Boolean,
    onScreenChange: (AppScreen) -> Unit,
    onBack: () -> Unit,
    repository: TtsRoadRepository,
    playbackController: PlaybackController,
) {
    val context = LocalContext.current
    // Saved UI state (scroll offsets, search text) is kept per stack entry, so returning to a
    // screen lands where it was left. Dropping an entry drops its state with it.
    val stateHolder = rememberSaveableStateHolder()
    val popBackStack = {
        stateHolder.removeState(screen.saveKey)
        onBack()
    }
    BackHandler(enabled = canGoBack, onBack = popBackStack)
    val playerState by playbackController.state.collectAsStateWithLifecycle()
    val preferences = remember { ServiceLocator.playbackPreferences(context) }
    val skipIntervalMs by remember(preferences) {
        preferences.prefs.map { it.skipIntervalMs }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = DefaultSkipIntervalMs)
    val historyStore = remember { ServiceLocator.playbackHistory(context) }
    val hasHistory by remember(historyStore) {
        historyStore.snapshots.map { it.isNotEmpty() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val title = when (screen) {
        is AppScreen.Fiction -> screen.fiction.title
        AppScreen.Library -> session.serverName
        AppScreen.Fictions -> "All fictions"
        AppScreen.Player -> "Now playing"
        is AppScreen.Reader -> screen.title
        AppScreen.Settings -> "Settings"
        AppScreen.Devices -> "Device sessions"
        AppScreen.Bookmarks -> "Bookmarks"
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
                        if (canGoBack) {
                            TextButton(onClick = popBackStack) {
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
                    skipIntervalMs = skipIntervalMs,
                    onExpand = { onScreenChange(AppScreen.Player) },
                )
            }
        },
    ) { padding ->
        stateHolder.SaveableStateProvider(screen.saveKey) {
            when (screen) {
                AppScreen.Library -> LibraryScreen(
                    padding = padding,
                    playbackController = playbackController,
                    onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
                    onOpenPlayer = { onScreenChange(AppScreen.Player) },
                    onBrowseFictions = { onScreenChange(AppScreen.Fictions) },
                )

                AppScreen.Fictions -> FictionsScreen(
                    padding = padding,
                    onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
                )

                is AppScreen.Fiction -> FictionScreen(
                    padding = padding,
                    fiction = screen.fiction,
                    repository = repository,
                    playbackController = playbackController,
                    onOpenPlayer = { onScreenChange(AppScreen.Player) },
                    onOpenReader = { onScreenChange(it) },
                )

                AppScreen.Player -> PlayerScreen(
                    padding = padding,
                    playerState = playerState,
                    playbackController = playbackController,
                    skipIntervalMs = skipIntervalMs,
                    onOpenReader = { onScreenChange(it) },
                )

                is AppScreen.Reader -> ReaderScreen(
                    padding = padding,
                    screen = screen,
                    playerState = playerState,
                    playbackController = playbackController,
                    repository = repository,
                )

                AppScreen.Settings -> SettingsScreen(
                    padding = padding,
                    session = session,
                    repository = repository,
                    onOpenDevices = { onScreenChange(AppScreen.Devices) },
                    onOpenBookmarks = { onScreenChange(AppScreen.Bookmarks) },
                )

                AppScreen.Devices -> DevicesScreen(
                    padding = padding,
                    session = session,
                    repository = repository,
                )

                AppScreen.Bookmarks -> BookmarksScreen(
                    padding = padding,
                    repository = repository,
                    onOpenReader = { onScreenChange(it) },
                )
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    padding: PaddingValues,
    playbackController: PlaybackController,
    onOpenFiction: (FictionSummary) -> Unit,
    onOpenPlayer: () -> Unit,
    onBrowseFictions: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cache = remember { ServiceLocator.libraryCache(context) }
    val state by cache.library.collectAsStateWithLifecycle()
    // Only for the jump-back reload path: resuming a snapshot whose queue was cleared overnight
    // has to refetch the fiction, which the cache does not cover.
    val repository = remember { ServiceLocator.repository(context) }

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

    // Loads once; returning to this screen shows what was already there instead of a spinner.
    LaunchedEffect(Unit) { cache.ensureLibrary() }

    val library = state.value
    when {
        library == null && state.isInitialLoad -> LoadingPane(padding)
        library == null -> ErrorPane(
            padding = padding,
            message = state.error ?: "Could not load library",
            onRetry = cache::refreshLibrary,
        )

        else -> {
            val fictionForChapter: (ChapterSummary) -> FictionSummary? = { chapter ->
                chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId }
            }

            RefreshablePane(
                padding = padding,
                isRefreshing = state.isRefreshing,
                error = state.error,
                onRefresh = cache::refreshLibrary,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
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
                                onAction = cache::refreshLibrary,
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
}

/**
 * Wraps a screen's content with pull-to-refresh and an in-place refresh indicator.
 *
 * The point is that [content] stays on screen throughout. A refresh over data the user can already
 * read must not blank the screen — that was the whole complaint — so a background reload shows a
 * hairline progress strip, and a *failed* one shows a one-line notice above content that is still
 * perfectly usable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshablePane(
    padding: PaddingValues,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isRefreshing) {
                ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            }
            // A refresh that failed while content is already loaded is a notice, not a takeover.
            error?.let {
                MetaText(
                    text = it,
                    color = AarisColor.Danger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            content()
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
    onOpenReader: (AppScreen.Reader) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cache = remember { ServiceLocator.libraryCache(context) }
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    val chapterState by remember(fiction.id) { cache.chapters(fiction.id) }
        .collectAsStateWithLifecycle()
    val downloads = remember { ServiceLocator.offlineDownloads(context) }
    val downloadState by downloads.downloads.collectAsStateWithLifecycle()
    val serverUrl = LocalServerUrl.current
    var error by remember { mutableStateOf<String?>(null) }
    // Not keyed on fiction.id, unlike everything around it: the filter is a library-wide setting
    // that outlives this screen. It used to reset to All on every open, so anyone working through
    // a series in order re-picked "Unplayed" on each book, every time.
    val chapterListPrefs = remember { ServiceLocator.chapterListPreferences(context) }
    val filter by chapterListPrefs.filter
        .collectAsStateWithLifecycle(initialValue = ChapterFilter.All)
    var ascending by remember(fiction.id) { mutableStateOf(true) }
    var bulkTarget by remember(fiction.id) { mutableStateOf<ChapterSummary?>(null) }
    var didAutoScroll by remember(fiction.id) { mutableStateOf(false) }
    // Held here so an in-place row update cannot scroll a 500-row list back to the top.
    val listState = rememberLazyListState()
    val playerState by playbackController.state.collectAsStateWithLifecycle()

    LaunchedEffect(fiction.id) { cache.ensureChapters(fiction.id) }

    /**
     * Mark [ids] played/unplayed in one request and patch the loaded rows in place — bulk marking a
     * few hundred chapters should not cost a reload of the whole list. The patch goes through the
     * shared cache, so the rows stay updated after navigating away and back.
     */
    fun mark(ids: List<Int>, played: Boolean) {
        if (ids.isEmpty()) return
        scope.launch {
            error = null
            runCatching { repository.markPlayed(ids, played) }
                .onSuccess { cache.applyPlayed(fiction.id, ids, played) }
                .onFailure { error = it.message ?: "Could not update chapter" }
        }
    }

    val chapters = chapterState.value
    when {
        chapters == null && chapterState.isInitialLoad -> LoadingPane(padding)
        chapters == null -> ErrorPane(
            padding = padding,
            message = chapterState.error ?: "Could not load chapters",
            onRetry = { cache.refreshChapters(fiction.id) },
        )

        else -> {
            // Filtering and sorting are client-side: the full list is already loaded, and a 500+
            // chapter fiction is still cheap to re-derive whenever the view options change.
            val visible = remember(chapters, filter, ascending) {
                chapters.chapterView(filter, ascending)
            }
            val currentChapterId = playerState.queue.getOrNull(playerState.currentIndex)
                ?.let { TtsRoadMediaIds.chapterId(it.mediaId) }
            // Where a batch download starts: what is playing, else the furthest-progressed chapter,
            // else the top of the fiction. Same reasoning as the header's RESUME button.
            val downloadStartId = currentChapterId ?: remember(chapters) {
                chapters.filter { it.audio != null && it.resolvedPositionSeconds > 0.0 }
                    .maxByOrNull { it.resolvedPositionSeconds }
                    ?.resolvedChapterId
            }
            // Row index inside [visible]; the header occupies list index 0, so rows are offset by 1.
            val currentRow = remember(visible, currentChapterId) {
                if (currentChapterId == null) -1 else visible.indexOfFirst { it.resolvedChapterId == currentChapterId }
            }
            val currentOffScreen by remember(currentRow) {
                derivedStateOf {
                    currentRow >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == currentRow + 1 }
                }
            }

            // Land on the chapter that is playing when the fiction is opened, then leave the list
            // alone — re-scrolling on every chapter change would fight the user.
            LaunchedEffect(currentRow) {
                if (!didAutoScroll && currentRow >= 0) {
                    didAutoScroll = true
                    listState.scrollToItem(currentRow + 1)
                }
            }

            RefreshablePane(
                padding = padding,
                isRefreshing = chapterState.isRefreshing,
                error = chapterState.error,
                onRefresh = { cache.refreshChapters(fiction.id) },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        FictionDetailHeader(
                            fiction = fiction,
                            chapters = chapters,
                            onPlay = { chapter ->
                                scope.launch {
                                    playbackController.playQueue(chapters, chapter.resolvedChapterId, fiction)
                                    onOpenPlayer()
                                }
                            },
                            downloadSummary = remember(chapters, downloadState) {
                                fictionDownloadSummary(chapters, downloadState)
                            },
                            listeningSummary = remember(chapters) {
                                fictionListeningSummary(chapters)
                            },
                            playbackSpeed = playerState.speed,
                            onDownloadNext = {
                                // Start where the listener is, not at chapter one — the point of the
                                // batch is the drive ahead of them.
                                downloads.download(
                                    nextChaptersToDownload(
                                        chapters = chapters,
                                        alreadyHandled = handledChapterIds(chapters, downloadState),
                                        limit = DownloadBatchSize,
                                        startChapterId = downloadStartId,
                                    ),
                                    serverUrl,
                                )
                            },
                        )
                        error?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(kicker = "CH", title = "Chapters")
                        ChapterListControls(
                            filter = filter,
                            ascending = ascending,
                            showJumpToCurrent = currentOffScreen,
                            onFilter = { scope.launch { chapterListPrefs.setFilter(it) } },
                            onToggleSort = { ascending = !ascending },
                            onJumpToCurrent = {
                                scope.launch { listState.animateScrollToItem(currentRow + 1) }
                            },
                        )
                    }
                    itemsIndexed(visible, key = { index, chapter -> "chapter-${chapter.resolvedChapterId}-${chapter.resolvedFictionId}-$index" }) { _, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            fiction = fiction,
                            isCurrent = chapter.resolvedChapterId == currentChapterId,
                            onPlay = {
                                scope.launch {
                                    // Queue the fiction in reading order, not the filtered/sorted view.
                                    playbackController.playQueue(chapters, chapter.resolvedChapterId, fiction)
                                    onOpenPlayer()
                                }
                            },
                            onMarkPlayed = { played -> mark(listOf(chapter.resolvedChapterId), played) },
                            onLongPress = { bulkTarget = chapter },
                            download = downloadState[TtsRoadMediaIds.chapter(chapter.resolvedChapterId)],
                            onToggleDownload = {
                                val current = downloadState[
                                    TtsRoadMediaIds.chapter(chapter.resolvedChapterId),
                                ]?.state ?: ChapterDownloadState.None
                                // Anything already on disk or in flight is removed; anything else
                                // (including a previous failure) is started.
                                if (current.isAvailableOffline || current.isBusy) {
                                    downloads.remove(chapter.resolvedChapterId)
                                } else {
                                    downloads.download(chapter, serverUrl)
                                }
                            },
                            // Two gates: the server has read-along, and this chapter is not known
                            // to be untimed. Both live in readAlongAvailability.
                            onOpenReader = readAlongAvailability(capabilities, chapter)
                                .takeIf { it.offersReader }
                                ?.let {
                                    {
                                        onOpenReader(
                                            AppScreen.Reader(
                                                chapterId = chapter.resolvedChapterId,
                                                title = chapter.resolvedTitle,
                                            ),
                                        )
                                    }
                                },
                        )
                    }
                }
            }

            bulkTarget?.let { target ->
                ChapterBulkSheet(
                    chapter = target,
                    previousIds = remember(chapters, target) {
                        chapters.chapterIdsBefore(target.resolvedChapterId)
                    },
                    allIds = remember(chapters) { chapters.allChapterIds() },
                    onDismiss = { bulkTarget = null },
                    onMark = { ids ->
                        bulkTarget = null
                        mark(ids, played = true)
                    },
                )
            }
        }
    }
}

/** Filter chips, sort direction and the "jump to current" affordance above the chapter list. */
@Composable
private fun ChapterListControls(
    filter: ChapterFilter,
    ascending: Boolean,
    showJumpToCurrent: Boolean,
    onFilter: (ChapterFilter) -> Unit,
    onToggleSort: () -> Unit,
    onJumpToCurrent: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChapterFilter.entries.forEach { option ->
            FilterChip(
                selected = option == filter,
                onClick = { onFilter(option) },
                label = { Text(option.label) },
                shape = RectangleShape,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleSort) {
            Text(if (ascending) "OLDEST" else "NEWEST")
        }
    }
    if (showJumpToCurrent) {
        TextButton(onClick = onJumpToCurrent) { Text("JUMP TO CURRENT") }
    }
}

/** Long-press actions for catching up on chapters read elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterBulkSheet(
    chapter: ChapterSummary,
    previousIds: List<Int>,
    allIds: List<Int>,
    onDismiss: () -> Unit,
    onMark: (List<Int>) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AarisColor.BgRaise) {
        MetaText(
            text = "// ${chapter.resolvedTitle}",
            color = AarisColor.Accent,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        BulkAction(
            title = "Mark all previous as played",
            subtitle = if (previousIds.isEmpty()) {
                "Nothing before this chapter"
            } else {
                "${previousIds.size} chapters"
            },
            enabled = previousIds.isNotEmpty(),
            onClick = { onMark(previousIds) },
        )
        BulkAction(
            title = "Mark all as played",
            subtitle = "${allIds.size} chapters",
            enabled = allIds.isNotEmpty(),
            onClick = { onMark(allIds) },
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BulkAction(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) AarisColor.Ink else AarisColor.Dim,
            )
            Spacer(modifier = Modifier.height(2.dp))
            MetaText(text = subtitle, color = AarisColor.Dim)
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(
    padding: PaddingValues,
    playerState: PlayerUiState,
    playbackController: PlaybackController,
    skipIntervalMs: Long,
    onOpenReader: (AppScreen.Reader) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ServiceLocator.repository(context) }
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    // Only the chapter id is known here — the player has no chapter row, so whether this one has
    // timings is settled by the document once the reader loads it.
    val playingChapterId = playerState.queue.getOrNull(playerState.currentIndex)
        ?.let { TtsRoadMediaIds.chapterId(it.mediaId) }
    val historyStore = remember { ServiceLocator.playbackHistory(context) }
    val history by historyStore.snapshots.collectAsStateWithLifecycle()
    // Breadcrumbs the account recorded elsewhere — the browser, or another phone. Fetched when the
    // sheet is opened rather than on every player composition: it is a request, and it is only ever
    // read by that sheet. A failure leaves the local trail on its own, which is what this always was.
    var remoteBreadcrumbs by remember { mutableStateOf<List<HistorySnapshot>>(emptyList()) }
    val jumpBackOptions = remember(history, remoteBreadcrumbs) {
        jumpBackOptions(mergeBreadcrumbs(history, remoteBreadcrumbs), System.currentTimeMillis())
    }
    val sleepTimer = remember { ServiceLocator.sleepTimer() }
    val sleepTimerState by sleepTimer.state.collectAsStateWithLifecycle()
    val preferences = remember { ServiceLocator.playbackPreferences(context) }
    val skipSilence by remember(preferences) {
        preferences.prefs.map { it.skipSilence }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = DefaultSkipSilence)
    var showChapters by remember { mutableStateOf(false) }
    var showJumpBack by remember { mutableStateOf(false) }
    LaunchedEffect(showJumpBack) {
        if (!showJumpBack) return@LaunchedEffect
        remoteBreadcrumbs = runCatching {
            repository.breadcrumbs().orEmpty().mapNotNull { breadcrumbSnapshot(it) }
        }.getOrDefault(emptyList())
    }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    // Confirmation for the bookmark button. A mark made while listening gives no other sign that
    // anything happened, and the alternative — opening the list — is the thing this avoids.
    var bookmarkFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookmarkFeedback) {
        // Clears itself: it is a confirmation, not a state the screen should settle into.
        if (bookmarkFeedback != null) {
            delay(4_000)
            bookmarkFeedback = null
        }
    }
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
        playerState.error?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            PlaybackErrorBanner(message = message, onRetry = playbackController::retry)
        }
        bookmarkFeedback?.let { message ->
            MetaText(
                text = "// $message",
                color = AarisColor.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
            // Time left beats total duration here: the scrubber already shows how far in this is,
            // and "how much longer" is the thing being asked. At anything but 1x the wall-clock
            // answer differs from the audio one, so say both rather than the misleading one.
            if (playerState.durationMs > 0L) {
                val position = dragMs?.toLong() ?: playerState.positionMs
                MetaText(
                    text = buildString {
                        append("-")
                        append(formatDuration(remainingMs(position, playerState.durationMs)))
                        if (playerState.speed != 1f) {
                            append("  ·  ")
                            append(
                                formatDuration(
                                    remainingMsAtSpeed(
                                        position,
                                        playerState.durationMs,
                                        playerState.speed,
                                    ),
                                ),
                            )
                            append(" at ${formatSpeed(playerState.speed)}")
                        }
                    },
                )
            } else {
                MetaText(text = formatDuration(playerState.durationMs))
            }
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
                icon = skipBackIcon(skipIntervalMs),
                contentDescription = "Back ${formatSkipInterval(skipIntervalMs)}",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { playbackController.skipBy(-skipIntervalMs) }
            TransportIconButton(
                icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                enabled = playerState.hasMedia,
                size = 68.dp,
                filled = true,
            ) { playbackController.togglePlayPause() }
            TransportIconButton(
                icon = skipForwardIcon(skipIntervalMs),
                contentDescription = "Forward ${formatSkipInterval(skipIntervalMs)}",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { playbackController.skipBy(skipIntervalMs) }
            TransportIconButton(
                icon = Icons.Default.SkipNext,
                contentDescription = "Next chapter",
                enabled = playerState.hasNext,
                size = 46.dp,
            ) { playbackController.skipToNextChapter() }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Tertiary: playback speed and the chapter list. FlowRow, not Row: on a narrow phone the
        // two groups don't fit side by side, and a Row squeezed "CHAPTERS 53/246" down to a column
        // one character wide. Wrapping happens between buttons; never inside a label.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Tap to pick directly; getting from 2.0x back to 1.5x used to be five taps of a
                // cycle-only button.
                TextButton(onClick = { showSpeed = true }) {
                    Text("SPEED ${formatSpeed(playerState.speed)}", maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = { showSleepTimer = true },
                    enabled = playerState.hasMedia || sleepTimerState.isArmed,
                ) {
                    Text(
                        text = if (sleepTimerState.isArmed) {
                            "SLEEP ${formatDuration(sleepTimerState.remainingMs)}"
                        } else {
                            "SLEEP"
                        },
                        color = if (sleepTimerState.isArmed) AarisColor.Accent else Color.Unspecified,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Hidden entirely on a server without read-along, rather than shown and then 404ing.
                if (capabilities.readAlong && playingChapterId != null) {
                    TextButton(
                        onClick = {
                            onOpenReader(
                                AppScreen.Reader(
                                    chapterId = playingChapterId,
                                    title = playerState.title,
                                ),
                            )
                        },
                    ) {
                        Text("READ", maxLines = 1, softWrap = false)
                    }
                }
                // Same gating as READ: hidden outright on a server without bookmarks, rather than
                // offered and then failing.
                if (capabilities.bookmarks && playingChapterId != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                // Deliberately does not touch playback: marking a line worth
                                // keeping is something you do *while* listening.
                                bookmarkFeedback = runCatching {
                                    repository.createBookmark(
                                        chapterId = playingChapterId,
                                        positionSeconds = playerState.positionMs / 1000.0,
                                        label = playerState.title.takeIf { it.isNotBlank() },
                                    )
                                }.fold(
                                    onSuccess = { "Bookmarked at ${formatDuration(playerState.positionMs)}" },
                                    onFailure = { "Could not save the bookmark" },
                                )
                            }
                        },
                    ) {
                        Text("BOOKMARK", maxLines = 1, softWrap = false)
                    }
                }
                if (jumpBackOptions.isNotEmpty()) {
                    TextButton(onClick = { showJumpBack = true }) {
                        Text("JUMP BACK", maxLines = 1, softWrap = false)
                    }
                }
                if (playerState.queue.size > 1) {
                    TextButton(onClick = { showChapters = true }) {
                        Text(
                            text = "CHAPTERS ${playerState.currentIndex + 1}/${playerState.queue.size}",
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }

    if (showSpeed) {
        ModalBottomSheet(
            onDismissRequest = { showSpeed = false },
            containerColor = AarisColor.BgRaise,
        ) {
            MetaText(
                text = "// Playback speed",
                color = AarisColor.Accent,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            speedOptions(playerState.speed).forEach { preset ->
                val selected = kotlin.math.abs(preset - playerState.speed) < 0.01f
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playbackController.setSpeed(preset)
                                showSpeed = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatSpeed(preset),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) AarisColor.Accent else AarisColor.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            MetaText(text = "Current", color = AarisColor.Accent)
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                }
            }
            // Skip silence belongs next to speed, not only in Settings: it changes how fast a
            // chapter gets through itself, so this is where someone comes looking when playback
            // feels quicker than the web player's.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Skip silence",
                        style = MaterialTheme.typography.titleMedium,
                        color = AarisColor.Ink,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(
                        text = "Shortens synthesised pauses. Off matches the web player.",
                        color = AarisColor.Dim,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = skipSilence,
                    onCheckedChange = { scope.launch { preferences.setSkipSilence(it) } },
                )
            }
            MetaText(
                text = "// Kept across restarts and reboots",
                color = AarisColor.Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
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
            // The sheet is composed fresh each time it opens, so this lands on the playing chapter
            // instead of the top of a several-hundred-entry queue.
            val chapterListState = rememberLazyListState()
            LaunchedEffect(playerState.currentIndex, playerState.queue.size) {
                if (playerState.queue.isNotEmpty()) {
                    chapterListState.scrollToItem(
                        playerState.currentIndex.coerceIn(0, playerState.queue.lastIndex),
                    )
                }
            }
            LazyColumn(state = chapterListState, modifier = Modifier.heightIn(max = 440.dp)) {
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

    if (showSleepTimer) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimer = false },
            containerColor = AarisColor.BgRaise,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText(text = "// Sleep timer", color = AarisColor.Accent)
                if (sleepTimerState.isArmed) {
                    TextButton(onClick = {
                        sleepTimer.cancel()
                        showSleepTimer = false
                    }) {
                        Text("CANCEL")
                    }
                }
            }
            if (sleepTimerState.isArmed) {
                MetaText(
                    text = when (sleepTimerState.mode) {
                        SleepTimerMode.EndOfChapter ->
                            "// Stopping at the end of this chapter · " +
                                "${formatDuration(sleepTimerState.remainingMs)} left"
                        else -> "// Stopping in ${formatDuration(sleepTimerState.remainingMs)}"
                    },
                    color = AarisColor.Muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            // Only offered once the chapter's duration is known — without it there is no boundary
            // to stop at, and the timer would fire the moment it was armed.
            if (playerState.durationMs > 0L) {
                SleepTimerOption(label = "End of current chapter") {
                    sleepTimer.armEndOfChapter(
                        (playerState.durationMs - playerState.positionMs).coerceAtLeast(0L),
                    )
                    showSleepTimer = false
                }
            }
            SleepTimerController.DurationOptionsMinutes.forEach { minutes ->
                SleepTimerOption(label = "$minutes minutes") {
                    sleepTimer.armDuration(minutes * 60_000L)
                    showSleepTimer = false
                }
            }
            MetaText(
                text = "// Fades out over the last 30s — shake to add 5 minutes",
                color = AarisColor.Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
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

/** One row of the sleep-timer sheet, styled like the chapter rows above it. */
@Composable
private fun SleepTimerOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = AarisColor.Ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
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
    onOpenDevices: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { ServiceLocator.updateManager() }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    val preferences = remember { ServiceLocator.playbackPreferences(context) }
    val prefs by preferences.prefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val downloads = remember { ServiceLocator.offlineDownloads(context) }
    val downloadPreferences = remember { ServiceLocator.downloadPreferences(context) }
    val downloadPrefs by downloadPreferences.prefs
        .collectAsStateWithLifecycle(initialValue = DownloadPrefs())
    val cacheBytes by downloads.cacheBytes.collectAsStateWithLifecycle()
    var confirmDeleteDownloads by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    // The cache is only measured on demand: it means walking the cache index off the main thread.
    LaunchedEffect(Unit) { downloads.refreshCacheBytes() }

    // Re-read on resume so returning from system settings reflects the new state.
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = notificationsEnabled(context)
        onPauseOrDispose { }
    }

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
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                MetaText(
                    text = "Every phone, tablet or car head unit signed in to this account.",
                    color = AarisColor.Dim,
                )
                OutlinedButton(onClick = onOpenDevices, shape = RectangleShape) {
                    Text("DEVICE SESSIONS")
                }
                // Hidden on a server without bookmarks, rather than opening a screen that 404s.
                if (capabilities.bookmarks) {
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    MetaText(
                        text = "Marks you made in the player. The same ones the browser shows.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(onClick = onOpenBookmarks, shape = RectangleShape) {
                        Text("BOOKMARKS")
                    }
                }
            }
        }

        if (!notificationsOn) {
            MetaText(text = "// Notifications", color = AarisColor.Accent)
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetaText(
                        text = "Notifications are off — lockscreen and shade controls " +
                            "will not appear during playback.",
                        color = AarisColor.Danger,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(appNotificationSettingsIntent(context.packageName))
                        },
                        shape = RectangleShape,
                    ) {
                        Text("OPEN NOTIFICATION SETTINGS")
                    }
                }
            }
        }

        MetaText(text = "// Playback", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaText(text = "Skip interval")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkipIntervalOptionsMs.forEach { option ->
                        val selected = option == prefs.skipIntervalMs
                        OutlinedButton(
                            onClick = { scope.launch { preferences.setSkipIntervalMs(option) } },
                            shape = RectangleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected) AarisColor.Accent else AarisColor.Muted,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(formatSkipInterval(option))
                        }
                    }
                }
                MetaText(
                    text = "Used by the player, the mini player, and the lockscreen buttons.",
                    color = AarisColor.Dim,
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(label = "Playback speed", value = formatSpeed(prefs.speed))
                MetaText(
                    text = "Change it from the player; it is kept across restarts and reboots.",
                    color = AarisColor.Dim,
                )
            }
        }

        MetaText(text = "// Audio", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Skip silence")
                        Spacer(modifier = Modifier.height(2.dp))
                        MetaText(
                            text = "Shortens the long pauses synthesised speech leaves around " +
                                "headings and scene breaks. Off by default, because the web " +
                                "player has no equivalent and leaving it on makes the same " +
                                "chapter finish sooner here.",
                            color = AarisColor.Dim,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = prefs.skipSilence,
                        onCheckedChange = { scope.launch { preferences.setSkipSilence(it) } },
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

                MetaText(text = "Volume boost")
                MetaText(
                    text = "Lifts chapters converted at a lower level, so a quiet one does not " +
                        "mean reaching for the volume.",
                    color = AarisColor.Dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VolumeBoost.entries.forEach { option ->
                        val selected = option == prefs.volumeBoost
                        OutlinedButton(
                            onClick = { scope.launch { preferences.setVolumeBoost(option) } },
                            shape = RectangleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected) AarisColor.Accent else AarisColor.Muted,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(option.label)
                        }
                    }
                }
            }
        }

        MetaText(text = "// Offline", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Download on Wi-Fi only")
                        Spacer(modifier = Modifier.height(2.dp))
                        MetaText(
                            text = "A chapter is tens of megabytes. Off lets downloads run on " +
                                "mobile data. Queued chapters wait for a connection either way, " +
                                "so nothing is lost by leaving this on.",
                            color = AarisColor.Dim,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = downloadPrefs.wifiOnly,
                        onCheckedChange = { scope.launch { downloadPreferences.setWifiOnly(it) } },
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

                SettingsItem(label = "Storage used", value = formatStorageSize(cacheBytes))
                MetaText(
                    text = "Covers both chapters you downloaded and chapters kept from streaming. " +
                        "Nothing is deleted automatically.",
                    color = AarisColor.Dim,
                )
                OutlinedButton(
                    onClick = { confirmDeleteDownloads = true },
                    enabled = cacheBytes > 0,
                    shape = RectangleShape,
                ) {
                    Text("DELETE ALL DOWNLOADS")
                }
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

    if (confirmDeleteDownloads) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownloads = false },
            containerColor = AarisColor.BgRaise,
            title = { Text("DELETE ALL DOWNLOADS", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaText(text = formatStorageSize(cacheBytes), color = AarisColor.Accent)
                    Text(
                        text = "Every downloaded chapter is removed, along with the audio kept " +
                            "from streaming. Playback progress is stored on the server and is " +
                            "not affected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AarisColor.Muted,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteDownloads = false
                        downloads.removeAll()
                    },
                    shape = RectangleShape,
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDownloads = false }) { Text("CANCEL") }
            },
        )
    }
}

/**
 * Every bookmark on the account, newest first.
 *
 * Loaded on entry rather than cached, for the same reason the devices list is: it is only ever
 * opened deliberately, and it is shared with the browser, so a stale copy would be showing marks
 * that may have been edited or deleted somewhere else.
 *
 * Only `manual` marks are listed. The `auto` rows in the same table are the jump-back breadcrumbs
 * the web player writes, and a list of chosen marks drowned in them would be useless.
 */
@Composable
private fun BookmarksScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    onOpenReader: (AppScreen.Reader) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    var bookmarks by remember { mutableStateOf<List<Bookmark>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Bookmark?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.bookmarks() }
                // Null means the server cannot do bookmarks at all, which the capability gate
                // should already have caught; empty means none have been made yet.
                .onSuccess { bookmarks = it.orEmpty() }
                .onFailure { error = it.message ?: "Could not load bookmarks" }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val loaded = bookmarks
    when {
        isLoading && loaded == null && error == null -> LoadingPane(padding)
        loaded == null -> ErrorPane(
            padding = padding,
            message = error ?: "Could not load bookmarks",
            onRetry = ::load,
        )

        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isLoading) {
                ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            }
            error?.let { MetaText(text = it, color = AarisColor.Danger) }

            if (loaded.isEmpty()) {
                MetaText(text = "// No bookmarks", color = AarisColor.Accent)
                EmptyCard(
                    "Tap BOOKMARK in the player to mark where you are. Marks made here show up " +
                        "in the browser too.",
                )
            } else {
                MetaText(text = "// ${loaded.size} bookmarks", color = AarisColor.Accent)
                loaded.forEach { bookmark ->
                    BookmarkCard(
                        bookmark = bookmark,
                        // Read-along is where a bookmark actually leads: the point of marking a
                        // line is going back to read it.
                        onOpen = if (capabilities.readAlong && bookmark.chapterId > 0) {
                            {
                                onOpenReader(
                                    AppScreen.Reader(
                                        chapterId = bookmark.chapterId,
                                        title = bookmark.chapterTitle ?: bookmark.resolvedLabel,
                                    ),
                                )
                            }
                        } else {
                            null
                        },
                        onDelete = { confirmDelete = bookmark }.takeIf { !isBusy },
                    )
                }
            }

            OutlinedButton(
                onClick = ::load,
                enabled = !isLoading && !isBusy,
                shape = RectangleShape,
            ) {
                Text("REFRESH")
            }
        }
    }

    confirmDelete?.let { bookmark ->
        ConfirmDialog(
            title = "DELETE BOOKMARK",
            body = "\"${bookmark.resolvedLabel}\" will be removed here and in the browser.",
            confirmLabel = "DELETE IT",
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    isBusy = true
                    error = null
                    runCatching { repository.deleteBookmark(bookmark.id) }
                        .onFailure { error = it.message ?: "Could not delete the bookmark" }
                    isBusy = false
                    // Reload rather than removing locally: the server owns the list, and it is
                    // shared with the browser.
                    load()
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    onOpen: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = bookmark.resolvedLabel,
                style = MaterialTheme.typography.titleMedium,
                color = AarisColor.Ink,
            )
            // The payload carries the fiction and chapter titles precisely so a list like this
            // needs no extra request per row.
            bookmark.fictionTitle?.let { MetaText(text = it, color = AarisColor.Muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bookmark.positionLabel?.let { AarisTag(text = it) }
                bookmark.chapterNumber?.let { AarisTag(text = "CH ${chapterNumberLabel(it)}") }
            }
            bookmark.note?.let { MetaText(text = it, color = AarisColor.Dim) }
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("DELETE", color = AarisColor.Danger)
                }
            }
        }
    }
}

/**
 * Every mobile sign-in on this account, and the two ways to end one.
 *
 * Loaded on entry rather than through [dk.perspektiva.ttsroad.data.LibraryCache]: this list is only
 * ever looked at deliberately, and a stale one is worse than a short spinner — the whole point is
 * seeing what is signed in *now*.
 */
@Composable
private fun DevicesScreen(
    padding: PaddingValues,
    session: SessionState,
    repository: TtsRoadRepository,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceSession>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Servers older than the devices endpoints answer 404; that is a missing feature, not a fault.
    var unsupported by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<DeviceSession?>(null) }
    var confirmRevokeOthers by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.devices() }
                .onSuccess { loaded ->
                    unsupported = loaded == null
                    devices = loaded
                }
                .onFailure { error = it.message ?: "Could not load device sessions" }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    /** Run a revoke, then reload — the server decides what survived, so never guess locally. */
    fun revoke(action: suspend () -> Boolean) {
        scope.launch {
            isBusy = true
            error = null
            runCatching { action() }
                .onSuccess { supported -> if (!supported) unsupported = true }
                .onFailure { error = it.message ?: "Could not revoke the session" }
            isBusy = false
            load()
        }
    }

    val loaded = devices
    when {
        isLoading && loaded == null && error == null -> LoadingPane(padding)
        loaded == null && !unsupported -> ErrorPane(
            padding = padding,
            message = error ?: "Could not load device sessions",
            onRetry = ::load,
        )

        else -> {
            // The current session is the one the user is holding, so it leads and is never offered
            // for revocation from here — signing this device out is what Settings > Sign out is for.
            val others = loaded.orEmpty().filterNot { it.isCurrent(session) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (unsupported) {
                    MetaText(text = "// Not available", color = AarisColor.Accent)
                    EmptyCard(
                        "This server is older than the device-session API. Update the backend to " +
                            "manage sign-ins from here.",
                    )
                } else {
                    if (isLoading) {
                        ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
                    }
                    error?.let { MetaText(text = it, color = AarisColor.Danger) }

                    MetaText(text = "// This device", color = AarisColor.Accent)
                    val current = loaded.orEmpty().firstOrNull { it.isCurrent(session) }
                    if (current == null) {
                        EmptyCard("This session is not in the list yet")
                    } else {
                        DeviceCard(device = current, isCurrent = true, onRevoke = null)
                    }

                    MetaText(text = "// Other sessions", color = AarisColor.Accent)
                    if (others.isEmpty()) {
                        EmptyCard("Nothing else is signed in")
                    } else {
                        others.forEach { device ->
                            DeviceCard(
                                device = device,
                                isCurrent = false,
                                onRevoke = { confirmRevoke = device }.takeIf { !isBusy },
                            )
                        }
                        Button(
                            onClick = { confirmRevokeOthers = true },
                            enabled = !isBusy,
                            shape = RectangleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (isBusy) "WORKING" else "SIGN OUT ALL OTHER DEVICES")
                        }
                    }

                    OutlinedButton(
                        onClick = ::load,
                        enabled = !isLoading && !isBusy,
                        shape = RectangleShape,
                    ) {
                        Text("REFRESH")
                    }
                }
            }
        }
    }

    confirmRevoke?.let { device ->
        ConfirmDialog(
            title = "SIGN OUT DEVICE",
            body = "${device.resolvedName} will need to sign in again. Anything playing on it stops.",
            confirmLabel = "SIGN IT OUT",
            onConfirm = {
                confirmRevoke = null
                revoke { repository.revokeDevice(device.id) }
            },
            onDismiss = { confirmRevoke = null },
        )
    }

    if (confirmRevokeOthers) {
        ConfirmDialog(
            title = "SIGN OUT OTHERS",
            body = "Every other signed-in device will need to sign in again. This device stays " +
                "signed in.",
            confirmLabel = "SIGN THEM OUT",
            onConfirm = {
                confirmRevokeOthers = false
                revoke { repository.revokeOtherDevices() }
            },
            onDismiss = { confirmRevokeOthers = false },
        )
    }
}

/**
 * Whether this row is the phone in the user's hand.
 *
 * The server marks it, but only from the token making the request — so a client that fetched the
 * list before its own session existed, or a backend that omits the flag, would show no current
 * device at all. The stored device id from login is the local second opinion.
 */
private fun DeviceSession.isCurrent(session: SessionState): Boolean =
    isCurrent || (session.deviceId != null && session.deviceId == id)

@Composable
private fun DeviceCard(
    device: DeviceSession,
    isCurrent: Boolean,
    onRevoke: (() -> Unit)?,
) {
    AarisCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.resolvedName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrent) AarisTag(text = "This device")
                    device.status?.takeIf { it.isNotBlank() }?.let { AarisTag(text = it) }
                }
            }

            DeviceDetail(label = "Last used", value = formatServerTimestamp(device.lastUsedAt) ?: "Never")
            DeviceDetail(label = "Signed in", value = formatServerTimestamp(device.createdAt) ?: "-")
            DeviceDetail(
                label = "Expires",
                value = listOfNotNull(
                    formatServerTimestamp(device.expiresAt),
                    formatExpiresIn(device.expiresAt, System.currentTimeMillis()),
                ).joinToString(" · ").ifBlank { "-" },
            )
            // Null until the session is actually used, so a fresh sign-in shows a dash, not "null".
            DeviceDetail(label = "Last IP", value = device.lastIp ?: "-")

            onRevoke?.let {
                OutlinedButton(
                    onClick = it,
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Danger),
                ) {
                    Text("SIGN OUT")
                }
            }
        }
    }
}

@Composable
private fun DeviceDetail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MetaText(text = label)
        Spacer(modifier = Modifier.width(12.dp))
        MetaText(text = value, color = AarisColor.Ink)
    }
}

/** Square, orange-accented confirmation for the two irreversible actions on the devices screen. */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AarisColor.BgRaise,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { MetaText(text = body, color = AarisColor.Muted) },
        confirmButton = {
            Button(onClick = onConfirm, shape = RectangleShape) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
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
    skipIntervalMs: Long,
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
        // The mini bar is the only player surface visible on the library and fiction screens, so a
        // stream that died has to be visible from here too — otherwise playback just looks stopped.
        state.error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AarisColor.BgHover)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText(
                    text = message,
                    color = AarisColor.Danger,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = playbackController::retry) { Text("RETRY") }
            }
        }
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
                icon = skipBackIcon(skipIntervalMs),
                contentDescription = "Back ${formatSkipInterval(skipIntervalMs)}",
                enabled = state.hasMedia,
                size = 42.dp,
            ) { playbackController.skipBy(-skipIntervalMs) }
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

/**
 * Shown when the player stopped on an error. The service retries transient failures on its own, so
 * by the time this stays on screen the automatic attempts have already been spent — RETRY is the
 * manual escalation, not the first line of defence.
 */
@Composable
private fun PlaybackErrorBanner(message: String, onRetry: () -> Unit) {
    AarisCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(
                text = message,
                color = AarisColor.Danger,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("RETRY") }
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
 * toggles played state, and unplayable chapters surface their pipeline status as a tag. A long
 * press opens the bulk mark-played actions where [onLongPress] is supplied.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    onPlay: () -> Unit,
    onMarkPlayed: ((Boolean) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onOpenReader: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    download: ChapterDownload? = null,
    onToggleDownload: (() -> Unit)? = null,
) {
    val playable = chapter.audio != null
    val isPlayed = chapter.playback?.isPlayed == true
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isCurrent) AarisColor.BgHover else Color.Transparent)
                .combinedClickable(
                    enabled = playable || onLongPress != null,
                    onClick = { if (playable) onPlay() },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(
                text = chapterNumberLabel(chapter),
                color = if (isCurrent) AarisColor.Accent else AarisColor.Dim,
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
                    downloadMetaLabel(download),
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(
                        text = meta,
                        color = if (download?.state?.isAvailableOffline == true) {
                            AarisColor.Ok
                        } else {
                            AarisColor.Dim
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Offered even for a chapter with no audio yet: the text is worth reading on its own.
            onOpenReader?.let { open ->
                TransportIconButton(
                    icon = Icons.Default.Article,
                    contentDescription = "Read along",
                    enabled = true,
                    size = 36.dp,
                ) { open() }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (!playable) {
                AarisTag(text = chapter.status ?: "pending")
            } else {
                onToggleDownload?.let { toggle ->
                    val state = download?.state ?: ChapterDownloadState.None
                    TransportIconButton(
                        icon = downloadIcon(state),
                        contentDescription = downloadAction(state),
                        enabled = state != ChapterDownloadState.Removing,
                        size = 36.dp,
                        filled = state.isAvailableOffline,
                        onClick = toggle,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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

private fun chapterNumberLabel(chapter: ChapterSummary): String =
    chapter.displayNumber?.let(::chapterNumberLabel) ?: "—"

/** "12" rather than "12.0", but "12.5" kept — chapter numbers are not always whole. */
private fun chapterNumberLabel(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

/** The row's download button. Its icon is the *action*, not the state — except when there is none. */
private fun downloadIcon(state: ChapterDownloadState): ImageVector = when (state) {
    ChapterDownloadState.None -> Icons.Default.Download
    ChapterDownloadState.Downloaded -> Icons.Default.DownloadDone
    ChapterDownloadState.Failed -> Icons.Default.Refresh
    // Queued, Downloading and Removing all resolve to "stop what is happening".
    else -> Icons.Default.Close
}

private fun downloadAction(state: ChapterDownloadState): String = when (state) {
    ChapterDownloadState.None -> "Download for offline"
    ChapterDownloadState.Downloaded -> "Delete download"
    ChapterDownloadState.Failed -> "Retry download"
    ChapterDownloadState.Removing -> "Deleting download"
    else -> "Cancel download"
}

/** Download status folded into the row's meta line, so it costs no extra vertical space. */
private fun downloadMetaLabel(download: ChapterDownload?): String? = when (download?.state) {
    null, ChapterDownloadState.None -> null
    ChapterDownloadState.Downloaded -> "Offline"
    ChapterDownloadState.Queued -> "Queued"
    ChapterDownloadState.Downloading -> "Downloading ${download.percent}%"
    ChapterDownloadState.Failed -> "Download failed"
    ChapterDownloadState.Removing -> "Deleting"
}

@Composable
private fun FictionsScreen(
    padding: PaddingValues,
    onOpenFiction: (FictionSummary) -> Unit,
) {
    val context = LocalContext.current
    val cache = remember { ServiceLocator.libraryCache(context) }
    val state by cache.library.collectAsStateWithLifecycle()
    // Saveable so the browse position and filter survive a trip into a fiction and back.
    var query by rememberSaveable { mutableStateOf("") }
    // Hoisted so the browse position survives the round trip into a fiction, alongside the
    // SaveableStateProvider keyed per back-stack entry.
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { cache.ensureLibrary() }

    val fictions = state.value?.fictions
    when {
        fictions == null && state.isInitialLoad -> LoadingPane(padding)
        fictions == null -> ErrorPane(
            padding = padding,
            message = state.error ?: "Could not load fictions",
            onRetry = cache::refreshLibrary,
        )

        else -> {
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
            RefreshablePane(
                padding = padding,
                isRefreshing = state.isRefreshing,
                error = state.error,
                onRefresh = cache::refreshLibrary,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            state = gridState,
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
    downloadSummary: FictionDownloadSummary = FictionDownloadSummary(),
    onDownloadNext: (() -> Unit)? = null,
    listeningSummary: FictionListeningSummary = FictionListeningSummary(),
    playbackSpeed: Float = 1f,
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

        if (listeningSummary.playable > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (listeningSummary.hasRemaining) {
                    Text(
                        text = "${formatListeningSpan(listeningSummary.remainingSeconds)} remaining",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                MetaText(
                    text = buildString {
                        append("${listeningSummary.played}/${listeningSummary.playable} played")
                        if (listeningSummary.unplayed > 0) {
                            append("  ·  ${listeningSummary.unplayed} left")
                        }
                        // Only worth saying when it changes the answer; at 1x it is the same number
                        // twice, which reads as a bug rather than as extra information.
                        if (listeningSummary.hasRemaining && playbackSpeed != 1f) {
                            val atSpeed = listeningSpanAtSpeed(
                                listeningSummary.remainingSeconds,
                                playbackSpeed,
                            )
                            append("  ·  ${formatListeningSpan(atSpeed)} at ${formatSpeed(playbackSpeed)}")
                        }
                    },
                    color = AarisColor.Dim,
                )
            }
        }

        onDownloadNext?.let { download ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = download,
                    enabled = downloadSummary.remaining > 0,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (downloadSummary.remaining > 0) {
                            "DOWNLOAD NEXT ${minOf(DownloadBatchSize, downloadSummary.remaining)}"
                        } else {
                            "ALL CHAPTERS DOWNLOADED"
                        },
                    )
                }
                MetaText(
                    text = buildString {
                        append("${downloadSummary.downloaded} offline")
                        if (downloadSummary.inFlight > 0) append("  ·  ${downloadSummary.inFlight} in progress")
                        append("  ·  ${downloadSummary.remaining} not downloaded")
                        if (downloadSummary.bytes > 0) append("  ·  ${formatStorageSize(downloadSummary.bytes)}")
                    },
                    color = AarisColor.Dim,
                )
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
        val model = ServerUrls.resolveCoverOrNull(imageUrl, LocalServerUrl.current)
        Text(
            text = fallback.trim().take(1).uppercase().ifBlank { "T" },
            style = MaterialTheme.typography.displaySmall,
            color = AarisColor.Accent,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
        val model = ServerUrls.resolveCoverOrNull(imageUrl, LocalServerUrl.current)
        Text(
            text = fallback.trim().take(1).uppercase().ifBlank { "T" },
            style = MaterialTheme.typography.headlineSmall,
            color = AarisColor.Accent,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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

/**
 * The read-along reader: a chapter's text, following the audio.
 *
 * Reading works with nothing playing at all — that is both a feature in its own right and the only
 * thing a chapter with no timings can offer — so playback state only ever *adds* a highlight.
 */
@Composable
private fun ReaderScreen(
    padding: PaddingValues,
    screen: AppScreen.Reader,
    playerState: PlayerUiState,
    playbackController: PlaybackController,
    repository: TtsRoadRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readerPreferences = remember { ServiceLocator.readerPreferences(context) }
    val prefs by readerPreferences.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
    val sleepTimer = remember { ServiceLocator.sleepTimer() }
    val sleepTimerState by sleepTimer.state.collectAsStateWithLifecycle()
    val palette = remember(prefs.theme) { readerPalette(prefs.theme) }

    var document by remember(screen.chapterId) { mutableStateOf<ReadAlongDocument?>(null) }
    var error by remember(screen.chapterId) { mutableStateOf<String?>(null) }
    var loading by remember(screen.chapterId) { mutableStateOf(true) }
    var reloadToken by remember(screen.chapterId) { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(screen.chapterId, reloadToken) {
        loading = true
        error = null
        runCatching { repository.readAlong(screen.chapterId) }
            .onSuccess { document = it }
            // A 404 is not an error and never lands here: the repository answers it with null.
            .onFailure { error = it.message ?: "Could not load the chapter text" }
        loading = false
    }

    // Only follow when this is the chapter actually playing. Opening the reader for a different
    // chapter is an ordinary thing to do, and highlighting it against someone else's audio would
    // be worse than not highlighting at all.
    val playingChapterId = playerState.queue.getOrNull(playerState.currentIndex)
        ?.let { TtsRoadMediaIds.chapterId(it.mediaId) }
    val isPlayingThisChapter = playingChapterId == screen.chapterId

    var highlight by remember(screen.chapterId) { mutableStateOf(ReadAlongHighlight.None) }

    // Frame-paced, and driven purely by the position the player reports — never by elapsed wall
    // time. Skip-silence is on by default and removes real time from the media timeline, so a
    // highlight advanced by a clock would drift further out of step for the whole chapter. Only the
    // cue actually changing writes to state, so this is a handful of recompositions a second rather
    // than one per frame.
    LaunchedEffect(document, isPlayingThisChapter) {
        val loaded = document
        if (loaded == null || !isPlayingThisChapter || !loaded.hasTimings) {
            highlight = ReadAlongHighlight.None
            return@LaunchedEffect
        }
        while (true) {
            withFrameMillis { it }
            val reported = playbackController.reportedPositionMs() ?: continue
            val next = loaded.highlightAtMillis(reported)
            if (next != highlight) highlight = next
        }
    }

    val listState = rememberLazyListState()
    var followPlayback by remember(screen.chapterId) { mutableStateOf(true) }
    val activeParagraph = remember(highlight, document) {
        val loaded = document
        val word = highlight.word
        if (loaded == null || word == null) -1 else loaded.paragraphIndexAt(word.start)
    }

    // A drag hands control to the user and keeps it. Re-scrolling under a finger is the single most
    // irritating thing an auto-scrolling reader can do, so it offers to catch up instead.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followPlayback = false
        }
    }

    // The chapter header occupies list index 0, so paragraph N is list item N + 1.
    fun paragraphListIndex(paragraph: Int) = paragraph + 1

    suspend fun scrollToActiveParagraph() {
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        listState.animateScrollToItem(
            paragraphListIndex(activeParagraph),
            readerAutoScrollOffsetPx(viewport),
        )
    }

    LaunchedEffect(activeParagraph, followPlayback) {
        if (!followPlayback || activeParagraph < 0) return@LaunchedEffect
        scrollToActiveParagraph()
    }

    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn, sleepTimerState.isFading) {
        view.keepScreenOn = shouldKeepReaderScreenOn(
            preferenceEnabled = prefs.keepScreenOn,
            sleepTimerFading = sleepTimerState.isFading,
        )
        onDispose { view.keepScreenOn = false }
    }

    fun seekToOffset(charOffset: Int) {
        val seconds = document?.seekSecondsForOffset(charOffset) ?: return
        if (!isPlayingThisChapter) return
        playbackController.seekTo((seconds * 1000).roundToLong())
        followPlayback = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(palette.background),
    ) {
        val loaded = document
        when {
            loading && loaded == null -> LoadingPane(PaddingValues(0.dp))

            loaded == null && error != null -> ErrorPane(
                padding = PaddingValues(0.dp),
                message = error ?: "Could not load the chapter text",
                onRetry = { reloadToken++ },
            )

            // Null with no error is the 404: this chapter simply has no read-along.
            loaded == null -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MetaText(text = "// No text for this chapter", color = palette.muted)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This chapter was converted before read-along text was recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                )
            }

            else -> ReaderPage(
                document = loaded,
                highlight = highlight,
                palette = palette,
                prefs = prefs,
                listState = listState,
                isPlayingThisChapter = isPlayingThisChapter,
                onSeekToOffset = ::seekToOffset,
                onOpenSettings = { showSettings = true },
            )
        }

        // Offered rather than forced: the user scrolled away deliberately, so catching up is a tap.
        if (!followPlayback && activeParagraph >= 0) {
            TextButton(
                onClick = {
                    followPlayback = true
                    scope.launch { scrollToActiveParagraph() }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(palette.background)
                    .border(1.dp, palette.accent),
            ) {
                Text("BACK TO CURRENT", color = palette.accent)
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            prefs = prefs,
            palette = palette,
            onDismiss = { showSettings = false },
            onFontScale = { scope.launch { readerPreferences.setFontScale(it) } },
            onTheme = { scope.launch { readerPreferences.setTheme(it) } },
            onHighlight = { scope.launch { readerPreferences.setHighlight(it) } },
        )
    }
}

/** The page itself: paragraphs, the band, and the tap target that seeks. */
@Composable
private fun ReaderPage(
    document: ReadAlongDocument,
    highlight: ReadAlongHighlight,
    palette: ReaderPalette,
    prefs: ReaderPrefs,
    listState: LazyListState,
    isPlayingThisChapter: Boolean,
    onSeekToOffset: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Multiplies the system font scale rather than replacing it: `sp` already carries the user's
    // accessibility setting, and this is the reader-specific adjustment on top of it.
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        color = palette.ink,
        fontSize = 17.sp * prefs.fontScale,
        lineHeight = 28.sp * prefs.fontScale,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "reader-header") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaText(
                        text = if (document.hasTimings) "// Read along" else "// Text only",
                        color = palette.accent,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text("TEXT", color = palette.muted)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = document.title.ifBlank { "Chapter" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.ink,
                )
                if (!document.hasTimings) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MetaText(
                        text = "No timings for this chapter — the text does not follow the audio",
                        color = palette.muted,
                    )
                } else if (!isPlayingThisChapter) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MetaText(text = "Play this chapter to follow along", color = palette.muted)
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = palette.line)
            }
        }

        itemsIndexed(
            document.paragraphs,
            key = { index, span -> "p-$index-${span.start}" },
        ) { _, span ->
            ReaderParagraph(
                document = document,
                span = span,
                highlight = highlight,
                palette = palette,
                granularity = prefs.highlight,
                style = bodyStyle,
                onSeekToOffset = onSeekToOffset,
            )
        }
    }
}

@Composable
private fun ReaderParagraph(
    document: ReadAlongDocument,
    span: TextSpan,
    highlight: ReadAlongHighlight,
    palette: ReaderPalette,
    granularity: HighlightGranularity,
    style: TextStyle,
    onSeekToOffset: (Int) -> Unit,
) {
    // Rebuilt only when something this paragraph actually draws changes, so the other few hundred
    // paragraphs are skipped on every cue change.
    val sentence = highlight.sentence?.takeIf { granularity.showsSentence && it.overlaps(span) }
    val word = highlight.word?.takeIf { granularity.showsWord && it.overlaps(span) }
    val annotated = remember(span, sentence, word, palette) {
        buildAnnotatedString {
            append(document.textIn(span))
            sentence?.let { addStyle(SpanStyle(background = palette.band), span, it) }
            word?.let {
                addStyle(
                    SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold),
                    span,
                    it,
                )
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(span) {
                detectTapGestures { position ->
                    val local = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    onSeekToOffset(span.start + local)
                }
            },
    )
}

/** Apply [style] to the part of [highlight] that falls inside [paragraph], in paragraph coordinates. */
private fun AnnotatedString.Builder.addStyle(
    style: SpanStyle,
    paragraph: TextSpan,
    highlight: TextSpan,
) {
    val start = (highlight.start - paragraph.start).coerceIn(0, paragraph.length)
    val end = (highlight.end - paragraph.start).coerceIn(0, paragraph.length)
    if (end > start) addStyle(style, start, end)
}

private fun TextSpan.overlaps(other: TextSpan): Boolean = start < other.end && other.start < end

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    prefs: ReaderPrefs,
    palette: ReaderPalette,
    onDismiss: () -> Unit,
    onFontScale: (Float) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onHighlight: (HighlightGranularity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AarisColor.BgRaise) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            MetaText(text = "// Text size", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFontScales.forEach { scale ->
                    val selected = kotlin.math.abs(scale - prefs.fontScale) < 0.001f
                    ReaderOptionChip(
                        label = formatReaderFontScale(scale),
                        selected = selected,
                        onClick = { onFontScale(scale) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            MetaText(text = "// Page", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    ReaderOptionChip(
                        label = theme.label,
                        selected = theme == prefs.theme,
                        onClick = { onTheme(theme) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            MetaText(text = "// Follow along", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightGranularity.entries.forEach { granularity ->
                    ReaderOptionChip(
                        label = granularity.label,
                        selected = granularity == prefs.highlight,
                        onClick = { onHighlight(granularity) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Worth saying out loud: the web reader has its own copy of these, and #32 wanted them
            // shared. There is no server endpoint to share them through yet.
            MetaText(text = "// Kept on this phone only", color = palette.muted)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReaderOptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label.uppercase(),
        color = if (selected) AarisColor.Accent else AarisColor.Muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .border(1.dp, if (selected) AarisColor.Accent else AarisColor.Line)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
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

/**
 * Material ships replay/forward glyphs for 5, 10 and 30 seconds only, so 15 / 45 / 60s have no exact
 * icon. Use the nearest one and let the content description carry the real value — a screen reader
 * gets the truth even where the glyph rounds.
 */
private fun skipBackIcon(skipIntervalMs: Long): ImageVector = when {
    skipIntervalMs <= 7_500L -> Icons.Default.Replay5
    skipIntervalMs <= 20_000L -> Icons.Default.Replay10
    else -> Icons.Default.Replay30
}

private fun skipForwardIcon(skipIntervalMs: Long): ImageVector = when {
    skipIntervalMs <= 7_500L -> Icons.Default.Forward5
    skipIntervalMs <= 20_000L -> Icons.Default.Forward10
    else -> Icons.Default.Forward30
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
