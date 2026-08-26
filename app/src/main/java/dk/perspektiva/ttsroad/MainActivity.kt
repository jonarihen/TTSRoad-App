package dk.perspektiva.ttsroad

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dk.perspektiva.ttsroad.data.AudiobookExportRow
import dk.perspektiva.ttsroad.data.AudiobookExportsResponse
import dk.perspektiva.ttsroad.data.ChapterFilter
import dk.perspektiva.ttsroad.data.Bookmark
import dk.perspektiva.ttsroad.data.CapabilityCatalog
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.DefaultMaxEpubBytes
import dk.perspektiva.ttsroad.data.DeviceSession
import dk.perspektiva.ttsroad.data.EpubPickerMimeTypes
import dk.perspektiva.ttsroad.data.FictionAddResult
import dk.perspektiva.ttsroad.data.FictionEditResult
import dk.perspektiva.ttsroad.data.FictionMetadataDraft
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.FictionUpdateRequest
import dk.perspektiva.ttsroad.data.HighlightGranularity
import dk.perspektiva.ttsroad.data.MetadataFieldAuthor
import dk.perspektiva.ttsroad.data.MetadataFieldCoverImageUrl
import dk.perspektiva.ttsroad.data.MetadataFieldDescription
import dk.perspektiva.ttsroad.data.MetadataFieldTags
import dk.perspektiva.ttsroad.data.MetadataFieldTitle
import dk.perspektiva.ttsroad.data.PickedCover
import dk.perspektiva.ttsroad.data.PickedEpub
import dk.perspektiva.ttsroad.data.QueueAdvanceResponse
import dk.perspektiva.ttsroad.data.QueueItem
import dk.perspektiva.ttsroad.data.QueueResponse
import dk.perspektiva.ttsroad.data.QueueWhenEmptyContinue
import dk.perspektiva.ttsroad.data.QueueWhenEmptyStop
import dk.perspektiva.ttsroad.data.sanitizeQueueWhenEmpty
import dk.perspektiva.ttsroad.data.fictionMetadataPatch
import dk.perspektiva.ttsroad.data.formatFictionTags
import dk.perspektiva.ttsroad.data.readPickedCover
import dk.perspektiva.ttsroad.data.readPickedEpub
import dk.perspektiva.ttsroad.data.megabyteLabel
import dk.perspektiva.ttsroad.data.formatExpiresIn
import dk.perspektiva.ttsroad.data.formatServerTimestamp
import dk.perspektiva.ttsroad.data.FeedsResponse
import dk.perspektiva.ttsroad.data.LibraryFeed
import dk.perspektiva.ttsroad.data.LibraryScopeAll
import dk.perspektiva.ttsroad.data.listeningStateFileName
import dk.perspektiva.ttsroad.data.listeningStateImportSummary
import dk.perspektiva.ttsroad.data.listeningStateJson
import dk.perspektiva.ttsroad.data.parseListeningStateJson
import dk.perspektiva.ttsroad.data.LoginResult
import dk.perspektiva.ttsroad.data.MaintenanceResponse
import dk.perspektiva.ttsroad.data.PronunciationReport
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.ReadAlongHighlight
import dk.perspektiva.ttsroad.data.ReaderFontScales
import dk.perspektiva.ttsroad.data.ReaderLineHeights
import dk.perspektiva.ttsroad.data.ReaderPrefs
import dk.perspektiva.ttsroad.data.ReaderTheme
import dk.perspektiva.ttsroad.data.SearchGroup
import dk.perspektiva.ttsroad.data.SearchHit
import dk.perspektiva.ttsroad.data.SearchResponse
import dk.perspektiva.ttsroad.data.ServerCapabilities
import dk.perspektiva.ttsroad.data.DefaultSkipIntervalMs
import dk.perspektiva.ttsroad.data.DefaultSkipSilence
import dk.perspektiva.ttsroad.data.DownloadPrefs
import dk.perspektiva.ttsroad.data.StreamingCacheChoices
import dk.perspektiva.ttsroad.data.StreamingCacheUnlimited
import dk.perspektiva.ttsroad.data.KeepAheadChoices
import dk.perspektiva.ttsroad.data.PlaybackPrefs
import dk.perspektiva.ttsroad.data.PreferenceScope
import dk.perspektiva.ttsroad.data.SessionState
import dk.perspektiva.ttsroad.data.SkipIntervalOptionsMs
import dk.perspektiva.ttsroad.data.SleepTimerDefaultOptions
import dk.perspektiva.ttsroad.data.speedOptions
import dk.perspektiva.ttsroad.data.TextSpan
import dk.perspektiva.ttsroad.data.VolumeBoost
import dk.perspektiva.ttsroad.data.formatReaderFontScale
import dk.perspektiva.ttsroad.data.formatReaderLineHeight
import dk.perspektiva.ttsroad.data.formatSkipInterval
import dk.perspektiva.ttsroad.data.readAlongAvailability
import dk.perspektiva.ttsroad.data.readerAutoScrollOffsetPx
import dk.perspektiva.ttsroad.data.shouldKeepReaderScreenOn
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.allChapterIds
import dk.perspektiva.ttsroad.data.audiobookExportEncoderNote
import dk.perspektiva.ttsroad.data.audiobookExportRows
import dk.perspektiva.ttsroad.data.canReadServerLogs
import dk.perspektiva.ttsroad.data.chapterIdsBefore
import dk.perspektiva.ttsroad.data.chapterNumberText
import dk.perspektiva.ttsroad.data.chapterView
import dk.perspektiva.ttsroad.download.ChapterDownload
import dk.perspektiva.ttsroad.download.ChapterDownloadState
import dk.perspektiva.ttsroad.download.DownloadBatchSize
import dk.perspektiva.ttsroad.download.FictionDownloadSummary
import dk.perspektiva.ttsroad.download.fictionDownloadSummary
import dk.perspektiva.ttsroad.download.formatStorageSize
import dk.perspektiva.ttsroad.download.handledChapterIds
import dk.perspektiva.ttsroad.download.nextChaptersToDownload
import dk.perspektiva.ttsroad.download.streamingCacheChoiceLabel
import dk.perspektiva.ttsroad.media.PronunciationReportOutcome
import dk.perspektiva.ttsroad.media.pronunciationReportOutcomeFor
import dk.perspektiva.ttsroad.media.pronunciationWordAt
import dk.perspektiva.ttsroad.media.TtsRoadMediaIds
import dk.perspektiva.ttsroad.nav.AppScreen
import dk.perspektiva.ttsroad.nav.navigateTo
import dk.perspektiva.ttsroad.nav.popScreen
import dk.perspektiva.ttsroad.nav.readerFollowTarget
import dk.perspektiva.ttsroad.nav.replaceTop
import dk.perspektiva.ttsroad.nav.rootBackStack
import dk.perspektiva.ttsroad.nav.saveKey
import dk.perspektiva.ttsroad.nav.withFiction
import dk.perspektiva.ttsroad.player.FictionListeningSummary
import dk.perspektiva.ttsroad.player.fictionListeningSummary
import dk.perspektiva.ttsroad.player.jumpBackOptions
import dk.perspektiva.ttsroad.player.formatListeningSpan
import dk.perspektiva.ttsroad.player.HistorySnapshot
import dk.perspektiva.ttsroad.player.breadcrumbSnapshot
import dk.perspektiva.ttsroad.player.mergeBreadcrumbs
import dk.perspektiva.ttsroad.player.lastHeardSnapshot
import dk.perspektiva.ttsroad.player.listeningSpanAtSpeed
import dk.perspektiva.ttsroad.player.remainingMs
import dk.perspektiva.ttsroad.player.remainingMsAtSpeed
import dk.perspektiva.ttsroad.player.BookmarkMarker
import dk.perspektiva.ttsroad.player.bookmarkMarkers
import dk.perspektiva.ttsroad.player.markerAt
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlayerUiState
import dk.perspektiva.ttsroad.player.queueRows
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerMode
import dk.perspektiva.ttsroad.player.SleepTimerState
import dk.perspektiva.ttsroad.ui.AarisCard
import dk.perspektiva.ttsroad.ui.AarisChoiceRow
import dk.perspektiva.ttsroad.ui.AarisColor
import dk.perspektiva.ttsroad.ui.AarisTag
import dk.perspektiva.ttsroad.ui.MetaText
import dk.perspektiva.ttsroad.ui.MinTouchTargetSize
import dk.perspektiva.ttsroad.ui.ReaderPalette
import dk.perspektiva.ttsroad.ui.readerPalette
import kotlin.math.roundToLong
import dk.perspektiva.ttsroad.ui.ThinProgress
import dk.perspektiva.ttsroad.ui.TtsRoadTheme
import dk.perspektiva.ttsroad.update.ReleaseInfo
import dk.perspektiva.ttsroad.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val accountPreferenceSync = remember { ServiceLocator.accountPreferenceSync(context) }
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
            // Strictly after discovery: the preferences client is gated on the capability, so
            // pulling first would always see it false and skip. Adopts only what the account holds
            // and this phone does not, and answers null rather than throwing on an older server.
            accountPreferenceSync.pull()
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
                onReplaceScreen = { backStack = backStack.replaceTop(it) },
                // A fiction rides *in* the stack, so an edit has to be written back into every entry
                // holding it — otherwise the screen under the editor, and the top bar that reads its
                // title, keep showing what the source used to say.
                onFictionUpdated = { backStack = backStack.withFiction(it) },
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
    onReplaceScreen: (AppScreen) -> Unit,
    /** Rewrite the stack around an edited fiction, without navigating anywhere. */
    onFictionUpdated: (FictionSummary) -> Unit,
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
    // Same bookkeeping as popping: the entry being replaced is gone for good, so its saved state
    // goes with it. An overnight listen re-targets the reader once a chapter, and without this
    // every chapter it passed through would keep a scroll offset nothing can ever restore.
    val replaceScreen = { next: AppScreen ->
        stateHolder.removeState(screen.saveKey)
        onReplaceScreen(next)
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
        is AppScreen.FictionEdit -> "Edit details"
        AppScreen.Library -> session.serverName
        AppScreen.Fictions -> "All fictions"
        AppScreen.Player -> "Now playing"
        is AppScreen.Reader -> screen.title
        AppScreen.Settings -> "Settings"
        AppScreen.Devices -> "Device sessions"
        AppScreen.Bookmarks -> "Bookmarks"
        AppScreen.PronunciationReports -> "Pronunciation"
        AppScreen.Queue -> "Up next"
        AppScreen.Stats -> "Listening stats"
        AppScreen.Logs -> "Server log"
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
                    repository = repository,
                    // Presentation only. The server enforces admin on every one of these routes;
                    // hiding the control just stops offering a button that would 403.
                    isAdmin = session.isAdmin,
                    onOpenFiction = { onScreenChange(AppScreen.Fiction(it)) },
                    onOpenReader = { onScreenChange(it) },
                )

                is AppScreen.Fiction -> FictionScreen(
                    padding = padding,
                    fiction = screen.fiction,
                    repository = repository,
                    playbackController = playbackController,
                    isAdmin = session.isAdmin,
                    onOpenPlayer = { onScreenChange(AppScreen.Player) },
                    onOpenReader = { onScreenChange(it) },
                    onEditDetails = { onScreenChange(AppScreen.FictionEdit(screen.fiction)) },
                    // The screen is about a fiction that no longer exists, so it cannot stay open.
                    onDeleted = popBackStack,
                )

                is AppScreen.FictionEdit -> FictionEditScreen(
                    padding = padding,
                    fiction = screen.fiction,
                    repository = repository,
                    // Every accepted write lands here, cover uploads included, so the fiction
                    // screen behind the editor is already correct when the editor closes.
                    onFictionChanged = onFictionUpdated,
                    onDone = popBackStack,
                )

                AppScreen.Player -> PlayerScreen(
                    padding = padding,
                    playerState = playerState,
                    playbackController = playbackController,
                    skipIntervalMs = skipIntervalMs,
                    onOpenReader = { onScreenChange(it) },
                    onOpenQueue = { onScreenChange(AppScreen.Queue) },
                )

                is AppScreen.Reader -> ReaderScreen(
                    padding = padding,
                    screen = screen,
                    playerState = playerState,
                    playbackController = playbackController,
                    repository = repository,
                    // Replaces rather than pushes: the reader moving on with the audio is the same
                    // destination showing a later chapter, not somewhere the user navigated to.
                    onFollowChapter = replaceScreen,
                )

                AppScreen.Settings -> SettingsScreen(
                    padding = padding,
                    session = session,
                    repository = repository,
                    onOpenDevices = { onScreenChange(AppScreen.Devices) },
                    onOpenBookmarks = { onScreenChange(AppScreen.Bookmarks) },
                    onOpenPronunciationReports = {
                        onScreenChange(AppScreen.PronunciationReports)
                    },
                    onOpenQueue = { onScreenChange(AppScreen.Queue) },
                    onOpenStats = { onScreenChange(AppScreen.Stats) },
                    onOpenLogs = { onScreenChange(AppScreen.Logs) },
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

                AppScreen.PronunciationReports -> PronunciationReportsScreen(
                    padding = padding,
                    repository = repository,
                    onOpenReader = { onScreenChange(it) },
                )

                AppScreen.Queue -> QueueScreen(
                    padding = padding,
                    repository = repository,
                    playbackController = playbackController,
                    onOpenPlayer = { onScreenChange(AppScreen.Player) },
                )

                AppScreen.Stats -> ListeningStatsScreen(
                    padding = padding,
                    repository = repository,
                )

                AppScreen.Logs -> ServerLogsScreen(
                    padding = padding,
                    session = session,
                    repository = repository,
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
    isAdmin: Boolean = false,
    onOpenPlayer: () -> Unit,
    onOpenReader: (AppScreen.Reader) -> Unit,
    onEditDetails: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cache = remember { ServiceLocator.libraryCache(context) }
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    val chapterState by remember(fiction.id) { cache.chapters(fiction.id) }
        .collectAsStateWithLifecycle()
    val downloads = remember { ServiceLocator.offlineDownloads(context) }
    val downloadState by downloads.downloads.collectAsStateWithLifecycle()
    // #109: chapter audio is not immutable, and the download index keys on a URL that does not
    // change when it is rewritten. Which chapters on disk are no longer what the server has.
    val staleDownloads = remember { ServiceLocator.staleDownloads(context) }
    val staleChapters by staleDownloads.staleChapters.collectAsStateWithLifecycle()
    val serverUrl = LocalServerUrl.current
    var error by remember { mutableStateOf<String?>(null) }
    // Not keyed on fiction.id, unlike everything around it: the filter is a library-wide setting
    // that outlives this screen. It used to reset to All on every open, so anyone working through
    // a series in order re-picked "Unplayed" on each book, every time.
    val chapterListPrefs = remember { ServiceLocator.chapterListPreferences(context) }
    // Written through the account sync rather than straight to the store: this is the web's
    // "Hide played" under another name, and the two used to disagree with each other.
    val accountPreferenceSync = remember { ServiceLocator.accountPreferenceSync(context) }
    val filter by chapterListPrefs.filter
        .collectAsStateWithLifecycle(initialValue = ChapterFilter.All)
    var ascending by remember(fiction.id) { mutableStateOf(true) }
    // Keyed on the fiction, unlike the filter above: "which chapter was called X" is a question
    // about the book in front of you, and carrying the text to the next one would only hide it.
    var chapterQuery by remember(fiction.id) { mutableStateOf("") }
    var bulkTarget by remember(fiction.id) { mutableStateOf<ChapterSummary?>(null) }
    var confirmDelete by remember(fiction.id) { mutableStateOf(false) }
    var isDeleting by remember(fiction.id) { mutableStateOf(false) }
    var isFollowBusy by remember(fiction.id) { mutableStateOf(false) }
    // The shelf is the server's answer, so the toggle reads from the loaded library rather than
    // from the FictionSummary handed over by navigation, which may be a browse-screen row that has
    // been followed since.
    val libraryState by cache.library.collectAsStateWithLifecycle()
    val browseState by cache.browseAll.collectAsStateWithLifecycle()
    val isFollowing = remember(libraryState, browseState, fiction.id) {
        browseState.value?.fictions?.firstOrNull { it.id == fiction.id }?.following
            ?: libraryState.value?.followingIds?.contains(fiction.id)
            ?: fiction.following
    }
    var didAutoScroll by remember(fiction.id) { mutableStateOf(false) }
    // Held here so an in-place row update cannot scroll a 500-row list back to the top.
    val listState = rememberLazyListState()
    val playerState by playbackController.state.collectAsStateWithLifecycle()

    LaunchedEffect(fiction.id) { cache.ensureChapters(fiction.id) }

    // The marks in *this* book (#121). `bookmarks()` has taken a fiction id since bookmarks
    // shipped and had never been passed one, so the only view of a mark was a flat account-wide
    // list in Settings — while "the marks in this book" is the question you actually have once you
    // have marks across several. Scoped server-side rather than filtered here.
    var fictionBookmarks by remember(fiction.id) { mutableStateOf<List<Bookmark>>(emptyList()) }
    // Maintenance (#107, #112). One busy flag for the lot: they all act on the same fiction and
    // running two at once is never what anyone meant.
    var isMaintaining by remember(fiction.id) { mutableStateOf(false) }
    var showMaintenance by remember(fiction.id) { mutableStateOf(false) }
    var maintenanceNote by remember(fiction.id) { mutableStateOf<String?>(null) }
    var confirmReconvert by remember(fiction.id) { mutableStateOf(false) }
    var confirmDeleteChapter by remember(fiction.id) { mutableStateOf<ChapterSummary?>(null) }
    // This book's podcast URL (#115). One small request, only on a server that can answer it, and
    // only worth making because the alternative is mailing the link to yourself from a laptop.
    var feedUrl by remember(fiction.id) { mutableStateOf<String?>(null) }
    var confirmRotateFeed by remember(fiction.id) { mutableStateOf(false) }
    LaunchedEffect(fiction.id, capabilities.feedUrls) {
        feedUrl = if (!capabilities.feedUrls) {
            null
        } else {
            // Asked for the whole server rather than the shelf: a fiction screen can be opened
            // from browse-all for a book this account does not follow, and the followed-only
            // default would answer nothing for exactly that case.
            runCatching {
                repository.feeds(scope = LibraryScopeAll)
                    ?.fictions
                    ?.firstOrNull { it.fictionId == fiction.id }
                    ?.feedUrl
            }.getOrNull()
        }
    }
    LaunchedEffect(fiction.id, capabilities.bookmarks) {
        fictionBookmarks = if (!capabilities.bookmarks) {
            emptyList()
        } else {
            // A failure leaves the section absent, which is what it always was. Nothing to report:
            // this is not the list the user opened the screen for.
            runCatching { repository.bookmarks(fictionId = fiction.id).orEmpty() }
                .getOrDefault(emptyList())
        }
    }

    // Only the completed downloads: a chapter still transferring has no bytes to be wrong about,
    // and a failed one has none at all.
    val downloadedChapterIds = remember(downloadState) {
        downloadState
            .filterValues { it.state.isAvailableOffline }
            .keys
            .mapNotNullTo(mutableSetOf(), TtsRoadMediaIds::chapterId)
    }
    // Re-runs when a download finishes or is deleted, which is when the answer can have changed.
    // The scan is one small request and is skipped entirely without the capability or a download,
    // so opening a fiction you have nothing saved from costs nothing.
    LaunchedEffect(fiction.id, downloadedChapterIds, capabilities.audioContentHash) {
        staleDownloads.scan(fiction.id, downloadedChapterIds)
    }

    /**
     * Run one maintenance action and report what it did (#107, #112).
     *
     * The counts are the whole point of reporting anything: "re-narrate every chapter" and "rewrite
     * the tags" both answer `status: ok`, and only a number distinguishes a no-op from four hundred
     * conversions. The chapter list is reloaded afterwards because every one of these changes it —
     * a requeued chapter goes back to processing, an excluded one leaves the list.
     */
    fun maintain(describe: (MaintenanceResponse) -> String, action: suspend () -> MaintenanceResponse?) {
        scope.launch {
            isMaintaining = true
            error = null
            maintenanceNote = null
            runCatching { action() }
                .onSuccess { response ->
                    maintenanceNote = response?.let(describe)
                        ?: "This server cannot do that yet."
                }
                .onFailure {
                    // Surfaced rather than swallowed: unlike the background freshness check, the
                    // user pressed a button for this and silence would read as success.
                    error = it.message ?: "That did not work"
                }
            isMaintaining = false
            // The server works in the background, so the reload shows the *accepted* state — a
            // chapter back in processing — rather than a finished conversion.
            cache.refreshChapters(fiction.id)
        }
    }

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
            val visible = remember(chapters, filter, ascending, chapterQuery) {
                chapters.chapterView(filter, ascending, chapterQuery)
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

            // Results start at the top. A chapter list is often scrolled hundreds of rows down, and
            // LazyColumn clamps rather than resets when the list shrinks under it — so without this
            // typing into the field lands at the *end* of the matches instead of the first one.
            LaunchedEffect(chapterQuery) {
                if (chapterQuery.isNotBlank()) listState.scrollToItem(0)
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
                            isFollowing = isFollowing,
                            isFollowBusy = isFollowBusy,
                            // Hidden entirely on a server whose library is still the whole shared
                            // list: there is no shelf for a follow to mean anything against.
                            onSetFollowing = if (capabilities.follows) {
                                { wanted ->
                                    scope.launch {
                                        isFollowBusy = true
                                        error = null
                                        runCatching { repository.setFollowing(fiction.id, wanted) }
                                            .onSuccess { result ->
                                                // Trust the server's answer, not the request: a
                                                // fiction deleted since the screen loaded comes
                                                // back as not followed rather than as an error.
                                                result?.let { cache.applyFollowing(fiction.id, it) }
                                            }
                                            .onFailure {
                                                error = it.message ?: "Could not update your library"
                                            }
                                        isFollowBusy = false
                                    }
                                }
                            } else {
                                null
                            },
                            downloadSummary = remember(chapters, downloadState) {
                                fictionDownloadSummary(chapters, downloadState)
                            },
                            listeningSummary = remember(chapters) {
                                fictionListeningSummary(chapters)
                            },
                            playbackSpeed = playerState.speed,
                            // Two gates, both needed: the server has to have the routes, and this
                            // account has to be the admin they are restricted to. The server does
                            // the enforcing either way — hiding it just avoids offering a 403.
                            onEdit = onEditDetails.takeIf { capabilities.fictionManagement && isAdmin },
                            onDelete = if (capabilities.fictionManagement && isAdmin) {
                                { confirmDelete = true }
                            } else {
                                null
                            },
                            isDeleting = isDeleting,
                            isMaintaining = isMaintaining,
                            // Poll is not admin-gated, and that is the server's decision rather
                            // than a looser one taken here: it is rate-limited server-side and a
                            // fresh chapter benefits every reader.
                            onPoll = if (capabilities.fictionMaintenance) {
                                {
                                    maintain(
                                        describe = { response ->
                                            when {
                                                response.fullIngest -> "Re-reading the whole chapter list."
                                                (response.partialSync ?: 0) > 0 ->
                                                    "Checking the last ${response.partialSync} chapters."

                                                else -> "Checking the source now."
                                            }
                                        },
                                    ) { repository.pollFiction(fiction.id) }
                                }
                            } else {
                                null
                            },
                            // Admin-only, because the server's route is. The count above it is not
                            // gated — a non-admin still sees that something failed, they just
                            // cannot be the one to requeue it.
                            onRetryFailed = if (
                                capabilities.fictionMaintenance && isAdmin && fiction.errorChapters > 0
                            ) {
                                {
                                    maintain(
                                        describe = { "Requeued ${it.resetCount ?: 0} chapters." },
                                    ) { repository.retryFailedChapters(fiction.id) }
                                }
                            } else {
                                null
                            },
                            onMaintain = if (capabilities.fictionMaintenance && isAdmin) {
                                { showMaintenance = true }
                            } else {
                                null
                            },
                            feedUrl = feedUrl,
                            onShareFeed = { url ->
                                shareText(context, url, "${fiction.title} podcast feed")
                            },
                            onRotateFeed = if (capabilities.fictionMaintenance && isAdmin) {
                                { confirmRotateFeed = true }
                            } else {
                                null
                            },
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
                        // What the server accepted, in its own numbers. Every one of these actions
                        // runs in the background, so "accepted" is genuinely all there is to say —
                        // claiming it had finished would be a lie about a 400-chapter re-convert.
                        maintenanceNote?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            MetaText(text = it, color = AarisColor.Ok)
                        }
                        val staleHere = remember(chapters, staleChapters) {
                            chapters.count { it.resolvedChapterId in staleChapters }
                        }
                        if (staleHere > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            StaleDownloadsNotice(
                                count = staleHere,
                                onUpdateAll = {
                                    val outdated = chapters.filter {
                                        it.resolvedChapterId in staleChapters
                                    }
                                    staleDownloads.markUpdating(outdated.map { it.resolvedChapterId })
                                    downloads.download(outdated, serverUrl)
                                },
                            )
                        }
                        if (fictionBookmarks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            FictionBookmarksSection(
                                bookmarks = fictionBookmarks,
                                // Read-along is where a bookmark leads: the point of marking a line
                                // is going back to read it. Same gate the Settings list uses.
                                onOpen = if (capabilities.readAlong) {
                                    { bookmark ->
                                        onOpenReader(
                                            AppScreen.Reader(
                                                chapterId = bookmark.chapterId,
                                                title = bookmark.chapterTitle
                                                    ?: bookmark.resolvedLabel,
                                            ),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(kicker = "CH", title = "Chapters")
                        ChapterListControls(
                            filter = filter,
                            ascending = ascending,
                            showJumpToCurrent = currentOffScreen,
                            query = chapterQuery,
                            onQuery = { chapterQuery = it },
                            onFilter = { scope.launch { accountPreferenceSync.setChapterFilter(it) } },
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
                            isStale = chapter.resolvedChapterId in staleChapters,
                            onToggleDownload = {
                                val current = downloadState[
                                    TtsRoadMediaIds.chapter(chapter.resolvedChapterId),
                                ]?.state ?: ChapterDownloadState.None
                                val isStale = chapter.resolvedChapterId in staleChapters
                                when {
                                    // A stale copy is replaced rather than deleted: what is wanted
                                    // here is the current narration, not the space back.
                                    isStale && current.isAvailableOffline -> {
                                        staleDownloads.markUpdating(listOf(chapter.resolvedChapterId))
                                        downloads.download(chapter, serverUrl)
                                    }
                                    // Anything already on disk or in flight is removed; anything
                                    // else (including a previous failure) is started.
                                    current.isAvailableOffline || current.isBusy ->
                                        downloads.remove(chapter.resolvedChapterId)

                                    else -> downloads.download(chapter, serverUrl)
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
                    // A list that filtered down to nothing needs to say so. Silence here reads as a
                    // fiction whose chapters failed to load, which is a different problem entirely.
                    if (visible.isEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            EmptyCard(
                                message = if (chapterQuery.isBlank()) {
                                    "// No chapters match this filter"
                                } else {
                                    "// No chapter matches \"$chapterQuery\""
                                },
                            )
                        }
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
                    onQueue = if (capabilities.queue) {
                        { playNext ->
                            bulkTarget = null
                            scope.launch {
                                error = null
                                runCatching {
                                    repository.addToQueue(
                                        listOf(target.resolvedChapterId),
                                        playNext = playNext,
                                    )
                                }.onFailure {
                                    error = it.message ?: "Could not update the queue"
                                }
                            }
                        }
                    } else {
                        null
                    },
                    isBusy = isMaintaining,
                    // Open to any account, exactly as the server has it: repairing one chapter
                    // harms nobody, and the person staring at a failed row is usually the one who
                    // wants it fixed.
                    onRetry = if (capabilities.chapterMaintenance) {
                        {
                            bulkTarget = null
                            maintain(describe = { "Queued for conversion." }) {
                                repository.retryChapter(target.resolvedChapterId)
                            }
                        }
                    } else {
                        null
                    },
                    // Admin-gated, because a chapter is a shared object: excluding one changes
                    // what every account's podcast feed contains.
                    onSetExcluded = if (capabilities.chapterMaintenance && isAdmin) {
                        { excluded ->
                            bulkTarget = null
                            maintain(
                                describe = {
                                    if (excluded) "Excluded from every feed." else "Back on the feeds."
                                },
                            ) { repository.setChapterExcluded(target.resolvedChapterId, excluded) }
                        }
                    } else {
                        null
                    },
                    onDelete = if (capabilities.chapterMaintenance && isAdmin) {
                        {
                            bulkTarget = null
                            confirmDeleteChapter = target
                        }
                    } else {
                        null
                    },
                )
            }

            confirmDeleteChapter?.let { chapter ->
                ConfirmDialog(
                    title = "DELETE THIS CHAPTER?",
                    // Specific rather than generic, for the same reason DELETE FICTION is: this is
                    // not scoped to one account, and there is no undo.
                    body = "\"${chapter.resolvedTitle}\" and its audio are deleted from the " +
                        "server, for everyone. It cannot be undone. The next poll may fetch it " +
                        "again from the source unless the chapter filter excludes it.",
                    confirmLabel = "DELETE",
                    onDismiss = { confirmDeleteChapter = null },
                    onConfirm = {
                        confirmDeleteChapter = null
                        maintain(describe = { "Chapter deleted." }) {
                            repository.deleteChapter(chapter.resolvedChapterId)
                        }
                    },
                )
            }

            if (showMaintenance) {
                FictionMaintenanceSheet(
                    fiction = fiction,
                    isBusy = isMaintaining,
                    onDismiss = { showMaintenance = false },
                    onPollFull = {
                        showMaintenance = false
                        maintain(describe = { "Re-reading the whole chapter list." }) {
                            repository.pollFiction(fiction.id, full = true)
                        }
                    },
                    onApplyFilter = {
                        showMaintenance = false
                        maintain(
                            describe = { response ->
                                response.detail?.takeIf { it.isNotBlank() }
                                    ?: "Excluded ${response.excludedCount ?: 0} chapters."
                            },
                        ) { repository.applyChapterFilter(fiction.id) }
                    },
                    onRetag = {
                        showMaintenance = false
                        maintain(describe = { "Rewriting tags on ${it.fileCount ?: 0} files." }) {
                            repository.retagFiction(fiction.id)
                        }
                    },
                    // The one action here that spends real time and outbound requests, so it is
                    // the one that asks first. Everything else in this sheet is cheap or reversible.
                    onReconvertAll = {
                        showMaintenance = false
                        confirmReconvert = true
                    },
                    onRetryFailed = if (fiction.errorChapters > 0) {
                        {
                            showMaintenance = false
                            maintain(describe = { "Requeued ${it.resetCount ?: 0} chapters." }) {
                                repository.retryFailedChapters(fiction.id)
                            }
                        }
                    } else {
                        null
                    },
                )
            }

            if (confirmRotateFeed) {
                ConfirmDialog(
                    title = "REGENERATE THIS FEED LINK?",
                    // Deliberately not the same warning as the account links: this token is shared
                    // by everyone subscribed to the fiction, so the blast radius is not just you.
                    body = "This book's feed URL is replaced. Every podcast app subscribed to it — " +
                        "on any account — stops receiving new chapters until it is given the new " +
                        "link. No audio or progress is lost.",
                    confirmLabel = "REGENERATE",
                    onDismiss = { confirmRotateFeed = false },
                    onConfirm = {
                        confirmRotateFeed = false
                        maintain(
                            describe = { response ->
                                // Adopt the new URL from the answer rather than re-fetching the
                                // whole feed list to read back one string.
                                response.feedUrl?.takeIf { it.isNotBlank() }?.let { feedUrl = it }
                                "Feed link regenerated."
                            },
                        ) { repository.rotateFictionFeedToken(fiction.id) }
                    },
                )
            }

            if (confirmReconvert) {
                ConfirmDialog(
                    title = "RE-NARRATE EVERY CHAPTER?",
                    body = "All ${fiction.totalChapters} chapters are converted again from " +
                        "scratch. That is ${fiction.totalChapters} conversions of server time and " +
                        "outbound requests, and every chapter already downloaded on a phone " +
                        "becomes an out-of-date copy. Saved positions and bookmarks are not touched.",
                    confirmLabel = "RE-NARRATE",
                    onDismiss = { confirmReconvert = false },
                    onConfirm = {
                        confirmReconvert = false
                        maintain(describe = { "Requeued ${it.resetCount ?: 0} chapters." }) {
                            repository.reconvertAllChapters(fiction.id)
                        }
                    },
                )
            }

            if (confirmDelete) {
                ConfirmDialog(
                    title = "DELETE ${fiction.title.uppercase()}?",
                    // Deliberately specific rather than a generic "are you sure". Deleting a fiction
                    // is not scoped to this account: it destroys the audio and every listener's
                    // saved position, and there is no undo.
                    body = "This deletes the fiction, all ${fiction.totalChapters} chapters and " +
                        "their audio from the server, for everyone — including saved positions and " +
                        "bookmarks on other accounts. It cannot be undone.",
                    confirmLabel = "DELETE",
                    onDismiss = { confirmDelete = false },
                    onConfirm = {
                        confirmDelete = false
                        scope.launch {
                            isDeleting = true
                            error = null
                            runCatching { repository.deleteFiction(fiction.id) }
                                .onSuccess { deleted ->
                                    if (deleted == true) {
                                        // Both lists held it, and neither can be patched in place
                                        // to represent something that no longer exists.
                                        cache.refreshLibrary()
                                        cache.refreshBrowseAll()
                                        onDeleted()
                                    } else {
                                        error = "This server cannot delete fictions."
                                    }
                                }
                                .onFailure {
                                    error = it.message ?: "Could not delete this fiction"
                                }
                            isDeleting = false
                        }
                    },
                )
            }
        }
    }
}

/**
 * Find-a-chapter field, filter chips, sort direction and the "jump to current" affordance above the
 * chapter list.
 *
 * The field filters rows already on screen, which is a different job from the server search on the
 * library screen: this one works offline, has no lag, and answers "which one was chapter 173"
 * rather than "which chapter mentioned the lighthouse".
 */
@Composable
private fun ChapterListControls(
    filter: ChapterFilter,
    ascending: Boolean,
    showJumpToCurrent: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onFilter: (ChapterFilter) -> Unit,
    onToggleSort: () -> Unit,
    onJumpToCurrent: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text("FIND A CHAPTER") },
        placeholder = { Text("Title or number") },
        singleLine = true,
        shape = RectangleShape,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear the chapter filter")
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
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
    /** Null on a server with no cross-library queue. `true` means "play it next". */
    onQueue: ((playNext: Boolean) -> Unit)? = null,
    /**
     * Convert this chapter again (#107).
     *
     * Not admin-gated, because the server's route is not: it repairs one chapter, harms nobody, and
     * the account looking at a failed row is usually the one that wants it fixed.
     */
    onRetry: (() -> Unit)? = null,
    /**
     * Take this chapter off every feed and player, or put it back. Admin only.
     *
     * A chapter is a shared object — excluding one changes what every account's podcast feed
     * contains — which is why this is gated and [onRetry] is not.
     */
    onSetExcluded: ((Boolean) -> Unit)? = null,
    /** Delete this chapter and its audio, for everyone. Admin only. */
    onDelete: (() -> Unit)? = null,
    isBusy: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AarisColor.BgRaise) {
        MetaText(
            text = "// ${chapter.resolvedTitle}",
            color = AarisColor.Accent,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        // The full reason, uncapped: the row shows two lines of it, and a stack trace tail or a
        // long URL is exactly the case where the rest is the part worth reading.
        chapter.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            MetaText(
                text = message,
                color = AarisColor.Danger,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
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
        onQueue?.let { queue ->
            // Only a chapter with audio can be queued — the server rejects the rest anyway, and
            // offering it would be offering something that silently does nothing.
            val playable = chapter.audio != null
            BulkAction(
                title = "Play next",
                subtitle = if (playable) {
                    "After the chapter playing now"
                } else {
                    "No audio for this chapter yet"
                },
                enabled = playable,
                onClick = { queue(true) },
            )
            BulkAction(
                title = "Add to queue",
                subtitle = if (playable) "At the end of Up Next" else "No audio for this chapter yet",
                enabled = playable,
                onClick = { queue(false) },
            )
        }
        // Repair, below the everyday actions. #107 was filed because "N failed" was stated on the
        // fiction screen and nothing in the app could act on it — this is the per-chapter half.
        onRetry?.let { retry ->
            BulkAction(
                title = if (chapter.hasError) "Convert again" else "Convert this chapter again",
                subtitle = if (chapter.hasError) {
                    "Queue it for another attempt"
                } else {
                    "Re-narrates it with the fiction's current voice and text rules"
                },
                enabled = !isBusy,
                onClick = retry,
            )
        }
        onSetExcluded?.let { setExcluded ->
            val excluded = chapter.excluded
            BulkAction(
                title = if (excluded) "Include this chapter" else "Exclude this chapter",
                subtitle = if (excluded) {
                    "Put it back on every feed and player"
                } else {
                    "Takes it off every feed and player, for every account"
                },
                enabled = !isBusy,
                onClick = { setExcluded(!excluded) },
            )
        }
        onDelete?.let { delete ->
            BulkAction(
                title = "Delete this chapter",
                subtitle = "Deletes it and its audio from the server, for everyone",
                enabled = !isBusy,
                onClick = delete,
            )
        }
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
                // Disabled, not de-emphasised: Dim is readable body text now, and reusing it here
                // would leave the row looking available.
                color = if (enabled) AarisColor.Ink else AarisColor.Disabled,
            )
            Spacer(modifier = Modifier.height(2.dp))
            MetaText(text = subtitle, color = AarisColor.Dim)
        }
        HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
    }
}

/**
 * Below this much window height the player scrolls and the artwork shrinks, rather than pushing its
 * own controls out of the window (#101).
 *
 * 520 dp sits between the two cases deliberately: a landscape phone is around 360 dp tall and a
 * portrait one starts at 640, so the split falls in the gap rather than near either. Split screen
 * and freeform windows land wherever they land and are handled by the same rule.
 */
private val CompactPlayerHeight = 520.dp

/** Thumbnail height for the short-height player. Enough to recognise a cover, not enough to cost. */
private val CompactCoverHeight = 96.dp

/**
 * Chapter title and fiction title, shared by the two player layouts.
 *
 * One block rather than two copies because the short-height layout differs from the tall one in
 * exactly one thing — the text is beside the cover, so it is start-aligned rather than centred —
 * and two copies of it would drift the moment either grew a line.
 */
@Composable
private fun PlayerTitleBlock(
    title: String,
    fictionTitle: String?,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (textAlign == TextAlign.Center) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
        fictionTitle?.let {
            Spacer(modifier = Modifier.height(10.dp))
            MetaText(text = it)
        }
    }
}

/**
 * Where in this chapter you have marked something, drawn under the scrub bar (#121).
 *
 * The 0.12.0 car action opened a loop this closes: you press BOOKMARK at the wheel precisely so you
 * can come back to that spot, and coming back meant Settings → Bookmarks → tap → the reader. Now the
 * spot is on the bar in front of you, and tapping it seeks there.
 *
 * Three deliberate choices:
 *
 * - **A lane of its own, not an overlay on the track.** Drawing on the slider means competing with
 *   its drag gesture for the same pixels, and the thing that must keep working there is scrubbing.
 * - **A tap that misses every mark does nothing.** The lane is not a second scrubber; a mistimed
 *   tap must not jump the playhead. [markerAt] returns null outside the tolerance and this honours
 *   that rather than falling back to "nearest".
 * - **No marks means no lane.** The strip costs no height when it is empty, so a player with
 *   nothing marked looks exactly as it did.
 */
@Composable
private fun BookmarkMarkerLane(markers: List<BookmarkMarker>, onSeek: (Long) -> Unit) {
    if (markers.isEmpty()) return
    // The tap has to be resolved against the bar's own width, which is only known once measured.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // Below Android's 48 dp floor on purpose: this is not a control in its own right but an
            // annotation on the slider above it, which is the thing with the touch target. Every
            // mark it draws is reachable by the ordinary means — the list in Settings, and the
            // reader — so a missed tap here costs nothing.
            .height(20.dp),
    ) {
        val width = maxWidth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(markers, width) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        markerAt(markers, fraction)?.let { onSeek(it.positionMs) }
                    }
                }
                // One description for the lane rather than one per mark: the marks overlap at a
                // finger's width, so per-mark nodes would be a set of targets a screen reader user
                // could not separate. The list in Settings is the accessible route to a given mark.
                .semantics {
                    contentDescription = if (markers.size == 1) {
                        "1 bookmark in this chapter"
                    } else {
                        "${markers.size} bookmarks in this chapter"
                    }
                },
        ) {
            markers.forEach { marker ->
                Box(
                    modifier = Modifier
                        // Half the tick's width back, so the line sits *on* the position rather
                        // than starting at it — otherwise every mark reads a little late.
                        .offset(x = width * marker.fraction - MarkerWidth / 2)
                        .width(MarkerWidth)
                        .fillMaxHeight()
                        .background(AarisColor.Accent),
                )
            }
        }
    }
}

/** Wide enough to see against the track, narrow enough that two close marks stay two marks. */
private val MarkerWidth = 2.dp

/**
 * One of the player's tertiary actions — speed, sleep, read, bookmark, jump back, chapters.
 *
 * A `TextButton` stops at Material's 40 dp minimum height, which is a density decision rather than
 * an accessibility one, so these carry the 48 dp floor explicitly (#104). Six near-identical copies
 * of the same three arguments became one; `softWrap = false` is the reason they are identical, and
 * it matters: a squeezed label here once wrapped "CHAPTERS 53/246" into a column one character wide.
 */
@Composable
private fun PlayerActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = MinTouchTargetSize),
    ) {
        Text(text = label, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * Everything the player *shows*, with none of what it is wired to.
 *
 * Split out of [PlayerScreen] so the layout can be rendered in a test at a stated viewport. The
 * screen itself pulls a repository, a history store, a sleep timer and a preference store out of
 * [ServiceLocator] and holds a live [PlaybackController]; none of that can be stood up on the JVM,
 * and all of it is beside the point when the question is whether pause is on screen at 360 dp.
 *
 * So the parameters are deliberately data and lambdas rather than the objects they came from —
 * [canRead] rather than the capability set, [onBookmark] rather than the repository.
 */
@Composable
internal fun PlayerScreenBody(
    playerState: PlayerUiState,
    skipIntervalMs: Long,
    sleepTimerState: SleepTimerState,
    /**
     * Transient confirmation of a write just made — a bookmark, a pronunciation report. Null most
     * of the time.
     *
     * One slot rather than one per action: these are four-second acknowledgements of things done
     * *while listening*, they are never both true, and a second identical line would only make the
     * player taller for no reader.
     */
    actionFeedback: String?,
    canRead: Boolean,
    canBookmark: Boolean,
    /** The server can store a captured mispronunciation, and there is a chapter to hang it on. */
    canReportPronunciation: Boolean,
    canJumpBack: Boolean,
    /**
     * Marks in the chapter that is playing, for the strip under the scrub bar (#121).
     *
     * Empty is the ordinary case and draws nothing at all — the lane costs no height when there is
     * nothing in it, so a player with no marks looks exactly as it did.
     */
    bookmarkMarkers: List<BookmarkMarker> = emptyList(),
    /** The server has a cross-library queue, so there is an Up Next worth opening (#108). */
    canOpenQueue: Boolean,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onRead: () -> Unit,
    onBookmark: () -> Unit,
    onReportPronunciation: () -> Unit,
    onOpenJumpBack: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track the drag locally and only seek on release, so scrubbing does not spam the player.
    var dragMs by remember { mutableStateOf<Float?>(null) }

    // #101: the cover used to be the only weighted child, so once it had given up all its height
    // there was no strategy left and the scrubber, the transport and every tertiary action were
    // laid out below the window with nothing that could scroll to them. On a player, "pause is
    // off-screen" is the bug; the artwork being small is not.
    //
    // The font scale belongs in the breakpoint rather than beside it. A short viewport is only one
    // of the two ways to run out of room; the other is an ordinary 640 dp portrait phone at a large
    // display size, where the window is normal and the text in it is half again as tall. Both
    // arrive at the same place, so both take the same exit.
    val isShortHeight = LocalConfiguration.current.screenHeightDp.dp <
        CompactPlayerHeight * LocalDensity.current.fontScale
    Column(
        modifier = modifier
            .fillMaxSize()
            // Weight needs a bounded height and scrolling gives an unbounded one, so these two are
            // genuinely exclusive: the tall layout hands leftover height to the cover, and the
            // short one has no leftover height to hand out.
            .then(
                if (isShortHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier,
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetaText(text = "// Now Playing", color = AarisColor.Accent)
        playerState.error?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            PlaybackErrorBanner(message = message, onRetry = onRetry)
        }
        actionFeedback?.let { message ->
            MetaText(
                text = "// $message",
                color = AarisColor.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (isShortHeight) {
            // Landscape and split screen are short, not narrow, so the cover moves to where the
            // room actually is: beside the title rather than above it, at a fixed thumbnail height.
            // Artwork is the one thing on this screen that is not a control, so it is what gets
            // rationed first.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverFill(
                    imageUrl = playerState.coverImageUrl,
                    fallback = playerState.fictionTitle ?: playerState.title,
                    modifier = Modifier
                        .height(CompactCoverHeight)
                        .aspectRatio(0.7f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                PlayerTitleBlock(
                    title = playerState.title,
                    fictionTitle = playerState.fictionTitle,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
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
            PlayerTitleBlock(
                title = playerState.title,
                fictionTitle = playerState.fictionTitle,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Slider(
            value = dragMs ?: playerState.positionMs.coerceAtMost(playerState.durationMs).toFloat(),
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { onSeek(it.toLong()) }
                dragMs = null
            },
            valueRange = 0f..playerState.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = playerState.durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        // Its own strip rather than an overlay on the slider. Drawing on the track would mean
        // competing with the slider's drag for the same pixels, and the thing that must keep
        // working there is scrubbing.
        BookmarkMarkerLane(markers = bookmarkMarkers, onSeek = onSeek)
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
            ) { onPreviousChapter() }
            TransportIconButton(
                icon = skipBackIcon(skipIntervalMs),
                contentDescription = "Back ${formatSkipInterval(skipIntervalMs)}",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { onSkipBack() }
            TransportIconButton(
                icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                enabled = playerState.hasMedia,
                size = 68.dp,
                filled = true,
            ) { onTogglePlayPause() }
            TransportIconButton(
                icon = skipForwardIcon(skipIntervalMs),
                contentDescription = "Forward ${formatSkipInterval(skipIntervalMs)}",
                enabled = playerState.hasMedia,
                size = 46.dp,
            ) { onSkipForward() }
            TransportIconButton(
                icon = Icons.Default.SkipNext,
                contentDescription = "Next chapter",
                enabled = playerState.hasNext,
                size = 46.dp,
            ) { onNextChapter() }
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
                PlayerActionButton(label = "SPEED ${formatSpeed(playerState.speed)}", onClick = onOpenSpeed)
                PlayerActionButton(
                    label = if (sleepTimerState.isArmed) {
                        "SLEEP ${formatDuration(sleepTimerState.remainingMs)}"
                    } else {
                        "SLEEP"
                    },
                    onClick = onOpenSleepTimer,
                    enabled = playerState.hasMedia || sleepTimerState.isArmed,
                    color = if (sleepTimerState.isArmed) AarisColor.Accent else Color.Unspecified,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Hidden entirely on a server without read-along, rather than shown and then 404ing.
                if (canRead) {
                    PlayerActionButton(label = "READ", onClick = onRead)
                }
                // Same gating as READ: hidden outright on a server without bookmarks, rather than
                // offered and then failing.
                if (canBookmark) {
                    PlayerActionButton(label = "BOOKMARK", onClick = onBookmark)
                }
                // Next to BOOKMARK because it is the same gesture — mark this moment, keep
                // listening — and hidden on the same terms, since the server gates the write route
                // as well as the read one (#125).
                if (canReportPronunciation) {
                    PlayerActionButton(label = "SAID WRONG", onClick = onReportPronunciation)
                }
                if (canJumpBack) {
                    PlayerActionButton(label = "JUMP BACK", onClick = onOpenJumpBack)
                }
                if (playerState.queue.size > 1) {
                    PlayerActionButton(
                        label = "CHAPTERS ${playerState.currentIndex + 1}/${playerState.queue.size}",
                        onClick = onOpenChapters,
                    )
                }
                // Not the same list as CHAPTERS, and the labels have to earn the difference:
                // CHAPTERS is this book, UP NEXT is what was lined up across books.
                if (canOpenQueue) {
                    PlayerActionButton(label = "UP NEXT", onClick = onOpenQueue)
                }
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
    skipIntervalMs: Long,
    onOpenReader: (AppScreen.Reader) -> Unit,
    onOpenQueue: () -> Unit,
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
    val playbackPrefs by preferences.prefs.collectAsStateWithLifecycle(
        initialValue = PlaybackPrefs(),
    )
    var showChapters by remember { mutableStateOf(false) }
    // Cleared when the sheet closes rather than remembered: reopening it to find where you are
    // should show where you are, not the last thing you went looking for.
    var queueQuery by remember(showChapters) { mutableStateOf("") }
    var showJumpBack by remember { mutableStateOf(false) }
    LaunchedEffect(showJumpBack) {
        if (!showJumpBack) return@LaunchedEffect
        remoteBreadcrumbs = runCatching {
            repository.breadcrumbs().orEmpty().mapNotNull { breadcrumbSnapshot(it) }
        }.getOrDefault(emptyList())
    }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    // Which books have a pace of their own. Read here rather than in the sheet so the switch shows
    // the stored answer the moment the sheet opens, with no flash of the wrong state.
    val fictionSpeeds by remember { ServiceLocator.fictionSpeedPreferences(context).overrides }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Confirmation for the writes made from this screen — a bookmark, a pronunciation report.
    // Either one made while listening gives no other sign that anything happened, and the
    // alternative — opening the list — is the thing they exist to avoid.
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    // The marks in *this* chapter, for the strip under the scrub bar (#121). Scoped server-side:
    // `bookmarks()` takes a chapter id and had never been passed one, so the app was fetching the
    // whole account to render one chapter's worth.
    var chapterBookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    // Counts bookmark writes, and is the refetch key below. The message used to be that key, which
    // made every mark cost two fetches — one when it appeared, one when it cleared — and would now
    // also refetch marks after a pronunciation report, which cannot change them.
    var bookmarkWrites by remember { mutableStateOf(0) }
    // Re-fetched when the chapter changes and when a mark is made, which is exactly when the answer
    // can differ. Not on a timer: a bookmark list nobody has touched does not change under you.
    LaunchedEffect(playingChapterId, capabilities.bookmarks, bookmarkWrites) {
        val chapterId = playingChapterId?.takeIf { capabilities.bookmarks }
        chapterBookmarks = if (chapterId == null) {
            emptyList()
        } else {
            runCatching { repository.bookmarks(chapterId = chapterId).orEmpty() }
                // A failure here leaves the bar unmarked, which is what it always was. There is
                // nothing to tell the user: they did not ask for this list.
                .getOrDefault(emptyList())
        }
    }
    val markers = remember(chapterBookmarks, playingChapterId, playerState.durationMs) {
        bookmarkMarkers(
            bookmarks = chapterBookmarks,
            chapterId = playingChapterId,
            durationMs = playerState.durationMs,
        )
    }
    LaunchedEffect(actionFeedback) {
        // Clears itself: it is a confirmation, not a state the screen should settle into.
        if (actionFeedback != null) {
            delay(4_000)
            actionFeedback = null
        }
    }
    PlayerScreenBody(
        playerState = playerState,
        skipIntervalMs = skipIntervalMs,
        sleepTimerState = sleepTimerState,
        actionFeedback = actionFeedback,
        canRead = capabilities.readAlong && playingChapterId != null,
        canBookmark = capabilities.bookmarks && playingChapterId != null,
        canReportPronunciation = capabilities.pronunciationReports && playingChapterId != null,
        canJumpBack = jumpBackOptions.isNotEmpty(),
        canOpenQueue = capabilities.queue,
        bookmarkMarkers = markers,
        onRetry = playbackController::retry,
        onSeek = playbackController::seekTo,
        onPreviousChapter = playbackController::skipToPreviousChapter,
        onSkipBack = { playbackController.skipBy(-skipIntervalMs) },
        onTogglePlayPause = playbackController::togglePlayPause,
        onSkipForward = { playbackController.skipBy(skipIntervalMs) },
        onNextChapter = playbackController::skipToNextChapter,
        onOpenSpeed = { showSpeed = true },
        onOpenSleepTimer = { showSleepTimer = true },
        onRead = {
            playingChapterId?.let {
                onOpenReader(AppScreen.Reader(chapterId = it, title = playerState.title))
            }
        },
        onBookmark = {
            val chapterId = playingChapterId ?: return@PlayerScreenBody
            scope.launch {
                // Deliberately does not touch playback: marking a line worth keeping is
                // something you do *while* listening.
                actionFeedback = runCatching {
                    repository.createBookmark(
                        chapterId = chapterId,
                        positionSeconds = playerState.positionMs / 1000.0,
                        label = playerState.title.takeIf { it.isNotBlank() },
                    )
                }.fold(
                    onSuccess = {
                        bookmarkWrites++
                        "Bookmarked at ${formatDuration(playerState.positionMs)}"
                    },
                    onFailure = { "Could not save the bookmark" },
                )
            }
        },
        onReportPronunciation = {
            val chapterId = playingChapterId ?: return@PlayerScreenBody
            // Captured here, off the state the screen is already drawing, so the report lands where
            // the tap did rather than wherever the round trip finished. The word comes from the
            // read-along document only if one is already in memory — usually it is not, and the
            // contract is explicit that a report without one is still worth filing.
            val positionMs = playerState.positionMs
            val positionSeconds = positionMs / 1000.0
            val word = pronunciationWordAt(
                document = repository.loadedReadAlong(chapterId),
                positionSeconds = positionSeconds,
            )
            scope.launch {
                // Playback is untouched, exactly as for a bookmark: you flag a mispronunciation
                // because you are still listening to the sentence after it.
                val outcome = pronunciationReportOutcomeFor(
                    runCatching {
                        repository.createPronunciationReport(
                            chapterId = chapterId,
                            positionSeconds = positionSeconds,
                            fictionId = playerState.fictionId,
                            word = word,
                        )
                    },
                )
                actionFeedback = when (outcome) {
                    // Success says what was captured, because the whole question a second later is
                    // "did it get the word, or just the spot?".
                    PronunciationReportOutcome.Filed -> if (word == null) {
                        "Reported at ${formatDuration(positionMs)}"
                    } else {
                        "Reported \"$word\" at ${formatDuration(positionMs)}"
                    }

                    // Everything else already carries a sentence, and for the ceiling's 409 it is
                    // the server's own.
                    else -> outcome.message
                }
            }
        },
        onOpenJumpBack = { showJumpBack = true },
        onOpenChapters = { showChapters = true },
        onOpenQueue = onOpenQueue,
        modifier = Modifier.padding(padding),
    )

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
            // Null when nothing is playing, or when the item carries no fiction id — either way
            // there is no book to pin a speed to, so the switch is not offered at all.
            val speedFictionId = playerState.fictionId
            val isPinnedToBook = speedFictionId != null && fictionSpeeds.containsKey(speedFictionId)
            if (speedFictionId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Only for this book")
                        Spacer(modifier = Modifier.height(2.dp))
                        MetaText(
                            text = if (isPinnedToBook) {
                                "This book plays at ${formatSpeed(playerState.speed)} whatever the " +
                                    "speed is elsewhere. Turning this off hands it back."
                            } else {
                                "Different narrators want different paces. On, the speed you pick " +
                                    "applies here and nowhere else."
                            },
                            color = AarisColor.Dim,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isPinnedToBook,
                        onCheckedChange = { pin ->
                            if (pin) {
                                // Pinned at whatever is playing now, so turning the switch on is
                                // never itself a change in pace.
                                playbackController.setSpeedForFiction(
                                    speedFictionId,
                                    playerState.speed,
                                )
                            } else {
                                playbackController.clearSpeedForFiction(speedFictionId)
                            }
                        },
                    )
                }
                MetaText(
                    text = PreferenceScope.DevicePlayer,
                    color = AarisColor.Dim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
            }
            speedOptions(playerState.speed).forEach { preset ->
                val selected = kotlin.math.abs(preset - playerState.speed) < 0.01f
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Which store the tap lands in is the switch's whole job: pinned,
                                // it edits this book's override; otherwise the global speed.
                                if (isPinnedToBook && speedFictionId != null) {
                                    playbackController.setSpeedForFiction(speedFictionId, preset)
                                } else {
                                    playbackController.setSpeed(preset)
                                }
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
            // Same job as the fiction screen's field: a several-hundred-entry queue is unpleasant to
            // scroll, and this is the surface where scrolling it happens mid-listen.
            OutlinedTextField(
                value = queueQuery,
                onValueChange = { queueQuery = it },
                label = { Text("FIND A CHAPTER") },
                placeholder = { Text("Title or number") },
                singleLine = true,
                shape = RectangleShape,
                trailingIcon = {
                    if (queueQuery.isNotEmpty()) {
                        IconButton(onClick = { queueQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear the chapter filter")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            val queueRows = remember(playerState.queue, queueQuery) {
                playerState.queue.queueRows(queueQuery)
            }
            val chapterListState = rememberLazyListState()
            LaunchedEffect(playerState.currentIndex, queueRows, queueQuery) {
                if (queueQuery.isBlank()) {
                    // The sheet is composed fresh each time it opens, so this lands on the playing
                    // chapter instead of the top of a several-hundred-entry queue. Its row is looked
                    // up rather than assumed: clearing the field restores the full list, and the
                    // queue index is only the row index while nothing is filtered.
                    val row = queueRows.indexOfFirst { it.index == playerState.currentIndex }
                    if (row >= 0) chapterListState.scrollToItem(row)
                } else {
                    // Results start at the top. The list was sitting wherever the playing chapter
                    // is, and LazyColumn clamps rather than resets when the list shrinks under it —
                    // so without this the first match can be scrolled off the top of the sheet.
                    chapterListState.scrollToItem(0)
                }
            }
            if (queueRows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    MetaText(text = "// No chapter matches \"$queueQuery\"", color = AarisColor.Muted)
                }
            }
            LazyColumn(state = chapterListState, modifier = Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(
                    queueRows,
                    key = { _, row -> "${row.item.mediaId}-${row.index}" },
                ) { _, row ->
                    // The queue position, not the row's position in the filtered list: it is both
                    // what the row is labelled with and what skipToQueueIndex is given.
                    val index = row.index
                    val item = row.item
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
                    text = when {
                        // The ceiling is what will fire, so saying "at the end of this chapter"
                        // would be telling the user the opposite of what is about to happen.
                        sleepTimerState.mode == SleepTimerMode.EndOfChapter &&
                            sleepTimerState.willStopAtCap ->
                            "// Stopping in ${formatDuration(sleepTimerState.remainingMs)}, " +
                                "before this chapter ends"

                        sleepTimerState.mode == SleepTimerMode.EndOfChapter ->
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
                val chapterRemainingMs =
                    (playerState.durationMs - playerState.positionMs).coerceAtLeast(0L)
                SleepTimerOption(label = "End of current chapter") {
                    sleepTimer.armEndOfChapter(chapterRemainingMs)
                    showSleepTimer = false
                }
                // Only when the chapter is actually longer than the ceiling. Below it the two rows
                // would do exactly the same thing, and offering the same stop twice under different
                // names is worse than not offering it: the row appearing is itself the signal that
                // this chapter is long enough for the question to matter.
                if (chapterRemainingMs > SleepTimerController.ChapterEndCapMs) {
                    SleepTimerOption(
                        label = "End of chapter, or 30 minutes",
                        supporting = "This chapter has ${formatDuration(chapterRemainingMs)} left, " +
                            "so it will stop after 30m.",
                    ) {
                        sleepTimer.armEndOfChapter(
                            chapterRemainingMs,
                            capMs = SleepTimerController.ChapterEndCapMs,
                        )
                        showSleepTimer = false
                    }
                }
            }
            SleepTimerController.DurationOptionsMinutes.forEach { minutes ->
                SleepTimerOption(
                    label = "$minutes minutes",
                    isDefault = minutes == playbackPrefs.sleepTimerDefaultMinutes,
                ) {
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
                        placeholder = { Text("2349") },
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
                                parsed == null -> sleepTimeError = "Use 24h time, e.g. 2349 or 23:49"
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
private fun SleepTimerOption(
    label: String,
    isDefault: Boolean = false,
    /** A second line under the label, for a row whose behaviour the label cannot fully carry. */
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (isDefault) AarisColor.Accent else AarisColor.Ink,
            )
            supporting?.let {
                Spacer(modifier = Modifier.height(2.dp))
                MetaText(text = it, color = AarisColor.Dim)
            }
        }
        if (isDefault) {
            MetaText(text = "Default", color = AarisColor.Accent)
        }
    }
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
    onOpenPronunciationReports: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { ServiceLocator.updateManager() }
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    val preferences = remember { ServiceLocator.playbackPreferences(context) }
    val accountPreferenceSync = remember { ServiceLocator.accountPreferenceSync(context) }
    val prefs by preferences.prefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val downloads = remember { ServiceLocator.offlineDownloads(context) }
    val downloadPreferences = remember { ServiceLocator.downloadPreferences(context) }
    val downloadPrefs by downloadPreferences.prefs
        .collectAsStateWithLifecycle(initialValue = DownloadPrefs())
    val cacheBytes by downloads.cacheBytes.collectAsStateWithLifecycle()
    val downloadCacheBytes by downloads.downloadCacheBytes.collectAsStateWithLifecycle()
    val streamedBytes by downloads.streamingCacheBytes.collectAsStateWithLifecycle()
    var confirmDeleteDownloads by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    // Podcast URLs (#115) and the listening-state backup (#116). Both are Settings-side and both
    // load on demand rather than on entry: neither is looked at often, and a Settings screen that
    // spends two requests every time it is opened to read the version number is a worse trade.
    var feeds by remember { mutableStateOf<FeedsResponse?>(null) }
    var feedsError by remember { mutableStateOf<String?>(null) }
    var confirmRotateFeed by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var isBackupBusy by remember { mutableStateOf(false) }

    LaunchedEffect(capabilities.feedUrls) {
        feeds = if (!capabilities.feedUrls) {
            null
        } else {
            runCatching { repository.feeds() }
                .onFailure { feedsError = it.message ?: "Could not load your feed links" }
                .getOrNull()
        }
    }

    // Finished M4B exports (#113). Two gates, like every other admin surface: the capability says
    // the server has the route, `is_admin` says this account may reach it. Asking without the
    // second is a 403 the user cannot act on.
    var exports by remember { mutableStateOf<AudiobookExportsResponse?>(null) }
    var exportsError by remember { mutableStateOf<String?>(null) }
    val canListExports = capabilities.audiobookExport && session.isAdmin

    LaunchedEffect(canListExports) {
        exportsError = null
        exports = if (!canListExports) {
            null
        } else {
            runCatching { repository.audiobookExports() }
                .onFailure { exportsError = it.message ?: "Could not load the export list" }
                .getOrNull()
        }
    }

    /**
     * Write the account's listening state to a file the user chose (#116).
     *
     * A document the *user* picks rather than a share sheet, and rather than the app's own storage:
     * the point of a backup is surviving this install, so it has to land somewhere the app cannot
     * take with it when it is uninstalled.
     */
    val saveListeningState = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBackupBusy = true
            backupError = null
            backupNote = null
            runCatching {
                val document = repository.exportListeningState()
                    ?: error("This server cannot export listening state.")
                // Serialised with the same Moshi vocabulary it was parsed with, so a document from
                // a newer server round-trips whole rather than being trimmed to known fields.
                val json = listeningStateJson(document)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("Could not write to that file.")
                }
            }
                .onSuccess { backupNote = "Saved." }
                .onFailure { backupError = it.message ?: "Could not save your listening state" }
            isBackupBusy = false
        }
    }

    /** Read a saved document back and post it. Merged server-side, never destructive. */
    val restoreListeningState = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBackupBusy = true
            backupError = null
            backupNote = null
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not open that file.")
                }
                val document = parseListeningStateJson(text)
                    ?: error("That file is not a listening-state backup.")
                repository.importListeningState(document)
                    ?: error("This server cannot restore listening state.")
            }
                .onSuccess { backupNote = listeningStateImportSummary(it) }
                .onFailure { backupError = it.message ?: "Could not restore that backup" }
            isBackupBusy = false
        }
    }

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
                // The same flag that decides whether the player and the car offer the action at
                // all, so the capture and the place it lands appear and disappear together (#125).
                if (capabilities.pronunciationReports) {
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    MetaText(
                        text = "Words you flagged as said wrong, and where you heard them. " +
                            "Fixing one is a rule, and rules are made in the browser.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(
                        onClick = onOpenPronunciationReports,
                        shape = RectangleShape,
                    ) {
                        Text("PRONUNCIATION REPORTS")
                    }
                }
                // Same gate the add-to-queue actions already use, so the screen and the actions
                // that feed it appear and disappear together (#108).
                if (capabilities.queue) {
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    MetaText(
                        text = "Chapters lined up across books, and what to do when they run out. " +
                            "The same queue the browser and Android Auto see.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(onClick = onOpenQueue, shape = RectangleShape) {
                        Text("UP NEXT")
                    }
                }
                // Not gated on the capability: the local half of that screen is computed on the
                // phone and works against any server, so the button always leads somewhere (#117).
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                MetaText(
                    text = "How long you have been listening — today on this phone, and all " +
                        "time on the account.",
                    color = AarisColor.Dim,
                )
                OutlinedButton(onClick = onOpenStats, shape = RectangleShape) {
                    Text("LISTENING STATS")
                }
            }
        }

        if (capabilities.accountSecurity) {
            AccountSecuritySettings(repository = repository)
        }

        MetaText(text = "// Server", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsItem(
                    label = "Version",
                    value = capabilities.serverVersion ?: "Not reported",
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                MetaText(
                    text = "What this server can do. The app hides anything it cannot back, so a " +
                        "control you expected and cannot find is usually explained here.",
                    color = AarisColor.Dim,
                )
                val rows = remember(capabilities.advertised) {
                    CapabilityCatalog.rows(capabilities.advertised)
                }
                if (rows.isEmpty()) {
                    // A server too old to answer /capabilities at all, or one not reached yet.
                    // Listing every feature as unsupported would be a guess presented as fact.
                    MetaText(
                        text = "This server did not say. Anything added since it was built is " +
                            "assumed unavailable.",
                        color = AarisColor.Muted,
                    )
                } else {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MetaText(
                                text = row.label,
                                color = if (row.supported) AarisColor.Ink else AarisColor.Dim,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            MetaText(
                                text = when {
                                    row.note != null -> row.note
                                    row.supported -> "Yes"
                                    else -> "No"
                                },
                                color = when {
                                    row.note != null -> AarisColor.Muted
                                    row.supported -> AarisColor.Ok
                                    else -> AarisColor.Dim
                                },
                            )
                        }
                    }
                }
                // The pipeline's own log (#124). Two gates, like every other admin surface: the
                // capability says the server publishes it, `is_admin` says this account may read
                // it. "Why did that chapter fail" is a question you have with the app open, and
                // the answer used to be a laptop away.
                if (canReadServerLogs(capabilities, session.isAdmin)) {
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    MetaText(
                        text = "Everything the conversion pipeline has reported — failures, " +
                            "polls, imports. Read-only, filtered by level or by book.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(onClick = onOpenLogs, shape = RectangleShape) {
                        Text("SERVER LOG")
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
                AarisChoiceRow(
                    options = SkipIntervalOptionsMs,
                    selected = prefs.skipIntervalMs,
                    label = ::formatSkipInterval,
                    onSelect = { scope.launch { preferences.setSkipIntervalMs(it) } },
                )
                MetaText(
                    text = "Used by the player, the mini player, and the lockscreen buttons. " +
                        PreferenceScope.DevicePlayer,
                    color = AarisColor.Dim,
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                SettingsItem(label = "Playback speed", value = formatSpeed(prefs.speed))
                MetaText(
                    text = "Change it from the player; it is kept across restarts and reboots. " +
                        PreferenceScope.DevicePlayer,
                    color = AarisColor.Dim,
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                MetaText(text = "Sleep timer default")
                AarisChoiceRow(
                    options = SleepTimerDefaultOptions,
                    selected = prefs.sleepTimerDefaultMinutes,
                    label = ::formatSleepTimerDefault,
                    onSelect = {
                        scope.launch { accountPreferenceSync.setSleepTimerDefaultMinutes(it) }
                    },
                )
                MetaText(
                    text = "Marked in the player's sleep sheet. " +
                        PreferenceScope.account(capabilities.playerPreferences),
                    color = AarisColor.Dim,
                )
                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetaText(text = "Mark chapters played automatically")
                        Spacer(modifier = Modifier.height(2.dp))
                        MetaText(
                            text = "Finishing a chapter marks it played without being asked. " +
                                "Turn it off to keep that deliberate; marking a chapter yourself " +
                                "still works either way. " +
                                PreferenceScope.account(capabilities.playerPreferences),
                            color = AarisColor.Dim,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = prefs.autoMarkPlayed,
                        onCheckedChange = {
                            scope.launch { accountPreferenceSync.setAutoMarkPlayed(it) }
                        },
                    )
                }
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
                                "chapter finish sooner here. " + PreferenceScope.DevicePlayer,
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
                        "mean reaching for the volume. " + PreferenceScope.DevicePlayer,
                    color = AarisColor.Dim,
                )
                AarisChoiceRow(
                    options = VolumeBoost.entries,
                    selected = prefs.volumeBoost,
                    label = { it.label },
                    onSelect = { scope.launch { preferences.setVolumeBoost(it) } },
                )
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

                MetaText(text = "Keep chapters ahead")
                AarisChoiceRow(
                    options = KeepAheadChoices,
                    selected = downloadPrefs.keepAheadChapters,
                    label = { if (it == 0) "OFF" else it.toString() },
                    onSelect = { scope.launch { downloadPreferences.setKeepAheadChapters(it) } },
                )
                MetaText(
                    text = if (downloadPrefs.keepAheadChapters <= 0) {
                        "Off. Chapters are downloaded only when you ask for them, so losing " +
                            "signal mid-book stops playback."
                    } else {
                        "${downloadPrefs.keepAheadChapters} chapters are kept on the phone as you " +
                            "listen, starting with the one playing, so a tunnel or a dead zone " +
                            "does not stop playback. They are deleted again once you are past " +
                            "them; chapters you downloaded yourself are never touched."
                    },
                    color = AarisColor.Dim,
                )

                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

                MetaText(text = "Keep streamed audio")
                AarisChoiceRow(
                    options = StreamingCacheChoices,
                    selected = downloadPrefs.streamingCacheBytes,
                    label = ::streamingCacheChoiceLabel,
                    onSelect = { scope.launch { downloadPreferences.setStreamingCacheBytes(it) } },
                )
                MetaText(
                    text = if (downloadPrefs.streamingCacheBytes == StreamingCacheUnlimited) {
                        "Everything you play is kept, so replaying it never touches the server. " +
                            "Nothing is ever dropped, so this grows for as long as you use the app."
                    } else {
                        "Chapters you play are kept so replaying them is free. Past " +
                            "${formatStorageSize(downloadPrefs.streamingCacheBytes)} the ones you " +
                            "have not touched in longest are dropped, and play again from the " +
                            "server if you want them. Downloads are in a separate store and are " +
                            "never touched by this."
                    },
                    color = AarisColor.Dim,
                )

                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)

                SettingsItem(label = "Storage used", value = formatStorageSize(cacheBytes))
                SettingsItem(label = "— Downloaded", value = formatStorageSize(downloadCacheBytes))
                SettingsItem(label = "— Streamed", value = formatStorageSize(streamedBytes))
                MetaText(
                    text = "Downloaded chapters are never deleted automatically. Streamed audio is, " +
                        "once it is over the size above.",
                    color = AarisColor.Dim,
                )
                OutlinedButton(
                    onClick = { downloads.clearStreamingCache() },
                    enabled = streamedBytes > 0,
                    shape = RectangleShape,
                ) {
                    Text("CLEAR STREAMED AUDIO")
                }
                OutlinedButton(
                    onClick = { confirmDeleteDownloads = true },
                    enabled = cacheBytes > 0,
                    shape = RectangleShape,
                ) {
                    Text("DELETE ALL DOWNLOADS")
                }
            }
        }

        // The other storage figure, and until now the one nobody could see from here: how much disk
        // the *server* is using, per fiction (#124). It sits next to the phone's own cache card
        // because "storage" used to mean only the download cache, while the volume actually at risk
        // of filling up is the one the MP3s are written to. Read-only — every reclamation stays on
        // the web console, deliberately.
        ServerStorageSettings(
            capabilities = capabilities,
            isAdmin = session.isAdmin,
            repository = repository,
        )

        // Serving a private podcast feed is what TTSRoad is for, and the phone is where a podcast
        // app lives — so getting a tokenised URL onto the phone used to mean mailing it to yourself
        // from a laptop (#115). Share rather than copy, because handing the URL straight to a
        // podcast app is the actual goal.
        if (capabilities.feedUrls) {
            MetaText(text = "// Podcast feeds", color = AarisColor.Accent)
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetaText(
                        text = "These carry a private token for your account. Treat them like a " +
                            "password — anyone holding one can read your library.",
                        color = AarisColor.Dim,
                    )
                    feedsError?.let { MetaText(text = it, color = AarisColor.Danger) }
                    val library = feeds?.library
                    ShareUrlRow(
                        label = "Every fiction, newest first",
                        url = library?.feedUrl,
                        onShare = { shareText(context, it, "Podcast feed") },
                    )
                    ShareUrlRow(
                        label = "OPML — subscribe to every per-fiction feed at once",
                        url = library?.opmlUrl,
                        onShare = { shareText(context, it, "OPML") },
                    )
                    HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                    MetaText(
                        text = "Regenerating revokes both links. Every podcast app you gave the " +
                            "old ones to stops working until you hand it the new one.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(
                        onClick = { confirmRotateFeed = true },
                        enabled = library?.feedUrl != null && !isBusy,
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Warning),
                    ) {
                        Text("REGENERATE LINKS")
                    }
                }
            }
        }

        // "Audio can always be made again. Where you are in a four-hundred-chapter serial cannot."
        // The phone writes most of that state and could not save a copy of it (#116).
        if (capabilities.listeningStateBackup) {
            MetaText(text = "// Listening state", color = AarisColor.Accent)
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetaText(
                        text = "Every position and bookmark on your account, in one file. Audio " +
                            "can always be made again; where you are in a four-hundred-chapter " +
                            "serial cannot.",
                        color = AarisColor.Dim,
                    )
                    backupError?.let { MetaText(text = it, color = AarisColor.Danger) }
                    backupNote?.let { MetaText(text = it, color = AarisColor.Ok) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { saveListeningState.launch(listeningStateFileName()) },
                            enabled = !isBackupBusy,
                            shape = RectangleShape,
                        ) {
                            Text("SAVE A COPY")
                        }
                        OutlinedButton(
                            // Not restricted to application/json: a file manager that stored the
                            // backup as octet-stream would otherwise be unable to hand it back, and
                            // the parse below is what actually decides whether a file is one.
                            onClick = { restoreListeningState.launch(arrayOf("*/*")) },
                            enabled = !isBackupBusy,
                            shape = RectangleShape,
                        ) {
                            Text("RESTORE")
                        }
                    }
                    MetaText(
                        text = "Restoring merges: a position only ever moves forward and bookmarks " +
                            "are added, so an old backup cannot undo newer listening.",
                        color = AarisColor.Dim,
                    )
                }
            }
        }

        // Whole-book M4B files the server has already made (#113). Read-only and admin-only,
        // exactly as the server has it: starting an export and deleting one stay on the web
        // console. The app deliberately does not *play* these — it streams a fiction chapter by
        // chapter with a position per chapter, and one multi-gigabyte file carrying a single
        // position is a downgrade, not a feature. What they are for is another audiobook player.
        if (canListExports) {
            MetaText(text = "// Audiobook exports", color = AarisColor.Accent)
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetaText(
                        text = "Finished M4B files on this server, for handing to another " +
                            "audiobook player. Making one and deleting one are still jobs for " +
                            "the web console.",
                        color = AarisColor.Dim,
                    )
                    exportsError?.let { MetaText(text = it, color = AarisColor.Danger) }
                    // ffmpeg is reported per request, not as a capability: the route existing and
                    // the machine behind it being able to encode are two different questions, and
                    // an empty list would answer the wrong one.
                    audiobookExportEncoderNote(exports)?.let {
                        MetaText(text = it, color = AarisColor.Warning)
                    }
                    val rows = remember(exports, session.serverUrl) {
                        audiobookExportRows(exports) {
                            ServerUrls.rewriteHost(it, session.serverUrl)
                        }
                    }
                    if (rows.isEmpty()) {
                        // The error above already says why an empty list is empty, when it is.
                        if (exportsError == null) {
                            MetaText(
                                text = if (exports == null) {
                                    "Loading…"
                                } else {
                                    "Nothing has been exported on this server yet."
                                },
                                color = AarisColor.Muted,
                            )
                        }
                    } else {
                        MetaText(
                            text = "A download carries your account's bearer token, so these links " +
                                "do nothing in a plain browser. Share one to something that can " +
                                "send the header.",
                            color = AarisColor.Dim,
                        )
                        rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(thickness = 1.dp, color = AarisColor.Line)
                            }
                            AudiobookExportListItem(
                                row = row,
                                onShare = { shareText(context, it, row.title) },
                            )
                        }
                    }
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

    if (confirmRotateFeed) {
        ConfirmDialog(
            title = "REGENERATE FEED LINKS?",
            body = "Your combined feed and OPML links are replaced with new ones. Every podcast " +
                "app you have already given the old links to stops receiving new chapters until " +
                "you hand it the new URL. Nothing is deleted and no progress is lost.",
            confirmLabel = "REGENERATE",
            onDismiss = { confirmRotateFeed = false },
            onConfirm = {
                confirmRotateFeed = false
                scope.launch {
                    isBusy = true
                    feedsError = null
                    runCatching { repository.rotateLibraryFeed() }
                        .onSuccess { rotated ->
                            // The rotate answer carries the new pair, so nothing has to re-fetch
                            // the whole feed list to show a URL that just changed.
                            rotated?.let {
                                feeds = feeds?.copy(
                                    library = LibraryFeed(
                                        feedTokenVersion = it.feedTokenVersion,
                                        feedUrl = it.feedUrl,
                                        opmlUrl = it.opmlUrl,
                                    ),
                                )
                            } ?: run { feedsError = "This server cannot regenerate those links." }
                        }
                        .onFailure { feedsError = it.message ?: "Could not regenerate your links" }
                    isBusy = false
                }
            },
        )
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
 * The mispronunciations captured from the player and the car, newest first (#125).
 *
 * The point of the capture action is that it can be used without looking — at the wheel, on a walk,
 * with the phone locked — and the price of that is a press with no visible result. This screen is
 * the other half: what did I actually file, and undo the one I fumbled. Nothing else. Resolving a
 * report, reading a whole fiction's, and turning any of them into a pronunciation rule are
 * admin-side and stay on the web's Text Tools page, where the dry run and the impact list live.
 *
 * Open reports only by default, because that is the list that describes outstanding work. The
 * server's `include_resolved` is what the ALL filter asks for, and a resolved row is worth seeing
 * mostly to explain why something you reported has stopped happening.
 */
@Composable
private fun PronunciationReportsScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    onOpenReader: (AppScreen.Reader) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    var reports by remember { mutableStateOf<List<PronunciationReport>?>(null) }
    var includeResolved by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PronunciationReport?>(null) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.pronunciationReports(includeResolved = includeResolved) }
                // Null means the server cannot take reports at all, which the capability gate on
                // the Settings button should already have caught; empty means none are filed.
                .onSuccess { reports = it.orEmpty() }
                .onFailure { error = it.message ?: "Could not load your pronunciation reports" }
            isLoading = false
        }
    }

    // Re-runs when the filter changes: `include_resolved` is a server-side query, not something to
    // filter a stale list by, and the resolved rows were never fetched to begin with.
    LaunchedEffect(includeResolved) { load() }

    val loaded = reports
    when {
        isLoading && loaded == null && error == null -> LoadingPane(padding)
        loaded == null -> ErrorPane(
            padding = padding,
            message = error ?: "Could not load your pronunciation reports",
            onRetry = ::load,
        )

        else -> PronunciationReportsBody(
            padding = padding,
            reports = loaded,
            isLoading = isLoading,
            error = error,
            includeResolved = includeResolved,
            isBusy = isBusy,
            onSetIncludeResolved = { includeResolved = it },
            // Read-along is where a report leads, exactly as a bookmark does: seeing the sentence
            // is how you work out the spelling you could not catch by ear.
            onOpenReader = if (capabilities.readAlong) {
                { report ->
                    onOpenReader(
                        AppScreen.Reader(
                            chapterId = report.chapterId,
                            title = report.chapterTitle ?: "Chapter",
                        ),
                    )
                }
            } else {
                null
            },
            onDelete = { confirmDelete = it },
            onRefresh = ::load,
        )
    }

    confirmDelete?.let { report ->
        ConfirmDialog(
            title = "DELETE REPORT",
            body = "This report will be removed. It is the undo for a mistaken tap, so it only " +
                "ever removes your own.",
            confirmLabel = "DELETE IT",
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    isBusy = true
                    error = null
                    runCatching { repository.deletePronunciationReport(report.id) }
                        .onFailure { error = it.message ?: "Could not delete the report" }
                    isBusy = false
                    // Reload rather than removing locally: the server owns the list, and an admin
                    // working through it in the browser is changing the same rows.
                    load()
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/**
 * Everything the reports screen *shows*, with none of what it is wired to.
 *
 * Split out for the same reason [PlayerScreenBody] is: the interesting question — does a report
 * with no word still render as something useful, does the ALL filter say which list you are looking
 * at — is answerable at a stated viewport on the JVM, and none of the repository behind it is.
 */
@Composable
internal fun PronunciationReportsBody(
    padding: PaddingValues,
    reports: List<PronunciationReport>,
    isLoading: Boolean,
    error: String?,
    includeResolved: Boolean,
    isBusy: Boolean,
    onSetIncludeResolved: (Boolean) -> Unit,
    onOpenReader: ((PronunciationReport) -> Unit)?,
    onDelete: (PronunciationReport) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
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

        AarisChoiceRow(
            options = listOf(false, true),
            selected = includeResolved,
            label = { showAll -> if (showAll) "ALL" else "OPEN" },
            onSelect = onSetIncludeResolved,
        )

        if (reports.isEmpty()) {
            MetaText(text = "// Nothing reported", color = AarisColor.Accent)
            EmptyCard(
                if (includeResolved) {
                    "Nothing has been reported from this account yet. Tap SAID WRONG in the " +
                        "player, or the flag in the car, the moment you hear a name mangled."
                } else {
                    "No open reports. Anything you filed has been dealt with in the browser."
                },
            )
        } else {
            MetaText(
                text = if (reports.size == 1) "// 1 report" else "// ${reports.size} reports",
                color = AarisColor.Accent,
            )
            reports.forEach { report ->
                PronunciationReportCard(
                    report = report,
                    onOpen = onOpenReader?.takeIf { report.chapterId > 0 }?.let { open ->
                        { open(report) }
                    },
                    onDelete = { onDelete(report) }.takeIf { !isBusy },
                )
            }
        }

        OutlinedButton(
            onClick = onRefresh,
            enabled = !isLoading && !isBusy,
            shape = RectangleShape,
        ) {
            Text("REFRESH")
        }
    }
}

/**
 * One captured moment.
 *
 * The word is the headline when there is one and the position is the headline when there is not,
 * which is the honest ordering: a report filed from a locked phone knows the second it happened
 * and nothing else, and that second is still the whole value of it.
 */
@Composable
private fun PronunciationReportCard(
    report: PronunciationReport,
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
                text = report.word ?: "Word not captured",
                style = MaterialTheme.typography.titleMedium,
                color = if (report.word == null) AarisColor.Muted else AarisColor.Ink,
            )
            // The payload carries the fiction and chapter titles precisely so a list like this
            // needs no extra request per row.
            report.fictionTitle?.let { MetaText(text = it, color = AarisColor.Muted) }
            report.chapterTitle?.let { MetaText(text = it, color = AarisColor.Dim) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AarisTag(text = formatDuration((report.positionSeconds * 1000).toLong()))
                report.chapterNumber?.let { AarisTag(text = "CH ${chapterNumberLabel(it)}") }
                // Only ever an admin's doing, from the web. Worth saying, because "it stopped
                // being wrong" and "nobody has looked at this" are different answers.
                if (report.resolved) {
                    AarisTag(text = "RESOLVED", color = AarisColor.Accent)
                }
            }
            report.note?.let { MetaText(text = it, color = AarisColor.Dim) }
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("DELETE", color = AarisColor.Danger)
                }
            }
        }
    }
}

/**
 * The cross-library Up Next queue — the first place in the app it can actually be looked at.
 *
 * The queue has been writable since 0.11.0 and readable nowhere: **Play next** and **Add to queue**
 * on the chapter sheet put a chapter on a list that could not be seen, corrected or emptied without
 * plugging the phone into a car or opening a browser (#108).
 *
 * Every mutation goes to the server and the answer is what redraws the list. That is not caution
 * for its own sake — the queue is shared with the browser and with Android Auto, so a locally
 * patched copy is a second opinion about state this client does not own. The server's reply to a
 * mutation already carries the whole queue, which is what makes "reload after every write" one
 * request rather than two.
 */
@Composable
private fun QueueScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    playbackController: PlaybackController,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    var queue by remember { mutableStateOf<QueueResponse?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            isLoading = true
            error = null
            runCatching { repository.queue() }
                // Null is not an empty queue: this server has no cross-library queue at all, and
                // the answer to that is a sentence rather than an empty list.
                .onSuccess { loaded ->
                    unsupported = loaded == null
                    queue = loaded
                }
                .onFailure { error = it.message ?: "Could not load the queue" }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    /**
     * Run one mutation and adopt the queue it answers with.
     *
     * The mutation endpoints return the new state, so this is a write and a read in one round trip.
     * A failure leaves the list exactly as it was and says so — nothing here is applied optimistically,
     * because a queue that shows a move the server refused is worse than one that shows nothing yet.
     */
    fun mutate(action: suspend () -> QueueAdvanceResponse?) {
        scope.launch {
            isBusy = true
            error = null
            runCatching { action() }
                .onSuccess { response ->
                    if (response == null) {
                        unsupported = true
                    } else {
                        queue = queue?.copy(items = response.items, total = response.total)
                            ?: QueueResponse(items = response.items, total = response.total)
                    }
                }
                .onFailure { error = it.message ?: "Could not change the queue" }
            isBusy = false
        }
    }

    /**
     * Play [item] as part of its own fiction, not as a lone chapter.
     *
     * Tapping a queued chapter should behave like tapping it anywhere else in the app — with
     * next/previous, auto-advance and a jump-to-chapter list — so this loads the fiction the way
     * the fiction screen does. The row stays on the queue: taking it off is what `advance` is for,
     * and that is the car's job, not a decision to make on someone's behalf because they looked.
     */
    fun play(item: QueueItem) {
        scope.launch {
            isBusy = true
            error = null
            runCatching {
                val response = repository.chapters(item.fictionId, playableOnly = true)
                playbackController.playQueue(
                    chapters = response.chapters,
                    startChapterId = item.chapterId,
                    fiction = response.fiction,
                )
            }
                .onSuccess { onOpenPlayer() }
                .onFailure { error = it.message ?: "Could not play that chapter" }
            isBusy = false
        }
    }

    val loaded = queue
    when {
        isLoading && loaded == null && error == null && !unsupported -> LoadingPane(padding)
        loaded == null && !unsupported -> ErrorPane(
            padding = padding,
            message = error ?: "Could not load the queue",
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
            if (unsupported) {
                MetaText(text = "// Not available", color = AarisColor.Accent)
                EmptyCard(
                    "This server has no cross-library queue. Update the backend to line chapters " +
                        "up across books.",
                )
                return@Column
            }

            if (isLoading || isBusy) {
                ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
            }
            error?.let { MetaText(text = it, color = AarisColor.Danger) }

            val items = loaded?.items.orEmpty()
            if (items.isEmpty()) {
                MetaText(text = "// Nothing queued", color = AarisColor.Accent)
                EmptyCard(
                    "Long-press a chapter and choose PLAY NEXT or ADD TO QUEUE. The queue is " +
                        "shared with the browser and with Android Auto's Up Next.",
                )
            } else {
                MetaText(
                    text = if (items.size == 1) "// 1 queued" else "// ${items.size} queued",
                    color = AarisColor.Accent,
                )
                items.forEachIndexed { index, item ->
                    QueueRow(
                        item = item,
                        position = index + 1,
                        // The ends of the list have nowhere to go, and a disabled arrow says that
                        // more honestly than one that silently does nothing.
                        onMoveUp = { mutate { repository.reorderQueue(items.moveItem(index, -1)) } }
                            .takeIf { index > 0 && !isBusy },
                        onMoveDown = {
                            mutate { repository.reorderQueue(items.moveItem(index, 1)) }
                        }.takeIf { index < items.lastIndex && !isBusy },
                        onRemove = { mutate { repository.removeFromQueue(listOf(item.id)) } }
                            .takeIf { !isBusy },
                        onPlay = { play(item) }.takeIf { !isBusy },
                    )
                }
            }

            // What happens at the end of the list, next to the list it is about — the same place
            // the web drawer puts it. It is an account preference and the server acts on it inside
            // `advance`, so this writes and re-reads rather than keeping a local copy.
            QueueWhenEmptyCard(
                value = sanitizeQueueWhenEmpty(loaded?.whenEmpty),
                enabled = !isBusy && capabilities.queue,
                onSelect = { choice ->
                    // Optimistic here and nowhere else on this screen, deliberately: the value is
                    // this account's own setting rather than shared list state, and a chip that
                    // does not move until a round trip finishes reads as broken.
                    queue = loaded?.copy(whenEmpty = choice) ?: QueueResponse(whenEmpty = choice)
                    scope.launch {
                        if (!repository.setQueueWhenEmpty(choice)) {
                            error = "Could not save that setting"
                            load()
                        }
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = ::load,
                    enabled = !isLoading && !isBusy,
                    shape = RectangleShape,
                ) {
                    Text("REFRESH")
                }
                if (items.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = !isBusy,
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AarisColor.Danger,
                        ),
                    ) {
                        Text("CLEAR QUEUE")
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "CLEAR QUEUE",
            body = "Every queued chapter is removed, here and in the browser. Nothing is deleted " +
                "and no progress is lost — only the order you lined them up in.",
            confirmLabel = "CLEAR IT",
            onConfirm = {
                confirmClear = false
                mutate { repository.clearQueue() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/**
 * The whole order with the item at [index] shifted by [offset], as the row ids the server takes.
 *
 * A complete order rather than a move instruction because that is the endpoint's shape, and it is
 * the right one: the queue is shared, and a "move item 4 up" applied to an order the browser has
 * since changed lands somewhere nobody asked for. An offset that would fall off either end returns
 * the list unchanged, so a caller that gets its guard wrong is a no-op rather than a corruption.
 */
internal fun List<QueueItem>.moveItem(index: Int, offset: Int): List<Int> {
    val target = index + offset
    if (index !in indices || target !in indices) return map { it.id }
    val ids = map { it.id }.toMutableList()
    ids.add(target, ids.removeAt(index))
    return ids
}

@Composable
private fun QueueRow(
    item: QueueItem,
    position: Int,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onPlay: (() -> Unit)?,
) {
    AarisCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(
                text = position.toString().padStart(2, '0'),
                color = AarisColor.Dim,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.resolvedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AarisColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.fictionTitle?.takeIf { it.isNotBlank() },
                    item.audioDuration?.let { formatDuration((it * 1000).toLong()) },
                    // Worth saying: a played chapter in the queue is usually a deliberate re-listen,
                    // but it is also what an accidental add looks like.
                    "Played".takeIf { item.isPlayed },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(text = meta, color = AarisColor.Dim, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            TransportIconButton(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Move up",
                enabled = onMoveUp != null,
                size = 36.dp,
            ) { onMoveUp?.invoke() }
            TransportIconButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Move down",
                enabled = onMoveDown != null,
                size = 36.dp,
            ) { onMoveDown?.invoke() }
            TransportIconButton(
                icon = Icons.Default.Close,
                contentDescription = "Remove from queue",
                enabled = onRemove != null,
                size = 36.dp,
            ) { onRemove?.invoke() }
        }
    }
}

/**
 * The account's `queue_when_empty`, which the app has read and acted on since 0.11.0 and never
 * offered a way to change.
 *
 * So the behaviour at the end of a book — stop, or keep going with the oldest unplayed thing in the
 * library — was set in a browser and only ever observed on the phone. The media service reads the
 * server's decision through `advance`, which is exactly why this is a single account-wide control
 * rather than a device setting.
 */
@Composable
private fun QueueWhenEmptyCard(
    value: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaText(text = "When the queue runs out")
            MetaText(
                text = "Stop is the safe answer: waking to an unrelated book is a surprise. " +
                    "Keep going picks the oldest chapter you have not played. " +
                    PreferenceScope.account(true),
                color = AarisColor.Dim,
            )
            AarisChoiceRow(
                options = listOf(QueueWhenEmptyStop, QueueWhenEmptyContinue),
                selected = value,
                label = { if (it == QueueWhenEmptyContinue) "KEEP GOING" else "STOP" },
                onSelect = { if (enabled) onSelect(it) },
            )
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
internal fun TransportIconButton(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    /** The size of the *drawn* button. The area that responds to a finger is at least 48 dp. */
    size: androidx.compose.ui.unit.Dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    // Two boxes, and the split is the whole point (#104). The outer one takes the tap and never
    // measures under 48 dp; the inner one is the square that gets drawn. Previously they were the
    // same box, so a 36 dp chapter-row action had a 36 dp target — and its neighbours are Play and
    // Mark played, where a near miss does something rather than nothing.
    Box(
        modifier = Modifier
            .sizeIn(minWidth = MinTouchTargetSize, minHeight = MinTouchTargetSize)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            // On the node that takes the tap, not on the glyph inside it: TalkBack should land on
            // the thing that is actually pressable and announce it as a button.
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
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
                .let { if (filled) it else it.border(1.dp, AarisColor.Line) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    filled -> AarisColor.Bg
                    enabled -> AarisColor.Muted
                    else -> AarisColor.Dim
                },
                modifier = Modifier.size(size / 2),
            )
        }
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
internal fun ChapterRow(
    chapter: ChapterSummary,
    fiction: FictionSummary?,
    onPlay: () -> Unit,
    onMarkPlayed: ((Boolean) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onOpenReader: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    download: ChapterDownload? = null,
    onToggleDownload: (() -> Unit)? = null,
    /**
     * The audio on disk is not the audio the server has any more (#109) — the chapter was
     * re-converted, retagged or re-narrated after it was downloaded.
     */
    isStale: Boolean = false,
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
                    downloadMetaLabel(download, isStale),
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(
                        text = meta,
                        // "Offline" earns the green; "Outdated copy" must not wear it. A stale
                        // download is still available offline, so the same branch would otherwise
                        // colour a warning as a success (#109).
                        color = when {
                            download?.state?.isAvailableOffline != true -> AarisColor.Dim
                            isStale -> AarisColor.Warning
                            else -> AarisColor.Ok
                        },
                    )
                }
                // Why it failed, on the row rather than only behind a long press. A list of rows
                // reading "FAILED" and nothing else is what #106 was filed about; one line of the
                // server's own message is usually enough to tell a locked chapter from a broken one.
                chapter.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(modifier = Modifier.height(2.dp))
                    MetaText(text = message, color = AarisColor.Danger, maxLines = 2)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // No spacers between the actions any more. Each is a 48 dp target around a 36 dp glyph
            // (#104), so there is already 12 dp of clear space between two drawn buttons; keeping
            // the old 8 dp gaps as well would take 24 dp off the title for no visible benefit, and
            // this row is on a 320 dp phone.
            // Offered even for a chapter with no audio yet: the text is worth reading on its own.
            onOpenReader?.let { open ->
                TransportIconButton(
                    icon = Icons.Default.Article,
                    contentDescription = "Read along",
                    enabled = true,
                    size = 36.dp,
                ) { open() }
            }
            if (!playable) {
                AarisTag(
                    text = chapter.statusLabel,
                    color = if (chapter.hasError) AarisColor.Danger else AarisColor.Muted,
                )
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

/**
 * "12" rather than "12.0", but "12.5" kept — chapter numbers are not always whole.
 *
 * Delegates rather than reimplementing: the find-a-chapter field matches against the same text, and
 * typing what a row shows has to find that row.
 */
private fun chapterNumberLabel(number: Double): String = chapterNumberText(number).orEmpty()

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

/**
 * The marks made in this book, on the screen for this book (#121).
 *
 * Settings → Bookmarks answers "every mark on the account, newest first". Once you have marks
 * across several books that is the wrong question — "the marks in *this* one" is the one you have,
 * and the API has always been able to answer it.
 *
 * Collapsed to a count with the newest few showing, rather than the whole list: this sits above the
 * chapter list on a screen whose job is the chapter list, and a heavily marked book would otherwise
 * push the chapters off the screen entirely.
 */
@Composable
private fun FictionBookmarksSection(
    bookmarks: List<Bookmark>,
    onOpen: ((Bookmark) -> Unit)?,
) {
    var expanded by rememberSaveable(bookmarks.size) { mutableStateOf(false) }
    val shown = if (expanded) bookmarks else bookmarks.take(FictionBookmarkPreviewCount)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            kicker = "BM",
            title = if (bookmarks.size == 1) "1 bookmark" else "${bookmarks.size} bookmarks",
        )
        shown.forEach { bookmark ->
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onOpen != null) {
                                Modifier.clickable { onOpen(bookmark) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = bookmark.resolvedLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AarisColor.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        bookmark.chapterTitle?.takeIf {
                            // Already the headline when there is no label of its own; saying it
                            // twice in two type sizes is noise.
                            it.isNotBlank() && it != bookmark.resolvedLabel
                        },
                        bookmark.positionLabel?.takeIf { it.isNotBlank() }
                            ?: formatDuration((bookmark.positionSeconds * 1000).toLong()),
                    ).joinToString("  ·  ")
                    MetaText(text = meta, color = AarisColor.Dim, maxLines = 1)
                }
            }
        }
        if (bookmarks.size > FictionBookmarkPreviewCount) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "SHOW FEWER" else "SHOW ALL ${bookmarks.size}")
            }
        }
    }
}

/** How many marks show before the section asks to be expanded. */
private const val FictionBookmarkPreviewCount = 3

/**
 * "The copies you have are not the ones the server has any more" (#109).
 *
 * Worth a fiction-level notice and not only a per-row mark: a re-convert is a whole-fiction action,
 * so this is routinely twenty rows at once, and scrolling a serial to find them is not a task
 * anyone should be given. It offers and does not act — re-fetching a book's worth of audio is the
 * user's decision and possibly their mobile data.
 */
@Composable
private fun StaleDownloadsNotice(count: Int, onUpdateAll: () -> Unit) {
    AarisCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaText(
                text = if (count == 1) {
                    "// 1 downloaded chapter is out of date"
                } else {
                    "// $count downloaded chapters are out of date"
                },
                color = AarisColor.Warning,
            )
            MetaText(
                text = "The audio was re-made on the server after you downloaded it — a new voice, " +
                    "new tags, or a text rule that changed how it reads. What is on the phone still " +
                    "plays; it is just no longer what the server would play.",
                color = AarisColor.Dim,
            )
            OutlinedButton(onClick = onUpdateAll, shape = RectangleShape) {
                Text(if (count == 1) "DOWNLOAD IT AGAIN" else "DOWNLOAD THEM AGAIN")
            }
        }
    }
}

/** Download status folded into the row's meta line, so it costs no extra vertical space. */
private fun downloadMetaLabel(
    download: ChapterDownload?,
    isStale: Boolean = false,
): String? = when (download?.state) {
    null, ChapterDownloadState.None -> null
    // "Offline" is a promise that this plays without the server. That stays true for a stale
    // download, and stops being the useful thing to say — what plays is the old narration (#109).
    ChapterDownloadState.Downloaded -> if (isStale) "Outdated copy" else "Offline"
    ChapterDownloadState.Queued -> "Queued"
    ChapterDownloadState.Downloading -> "Downloading ${download.percent}%"
    ChapterDownloadState.Failed -> "Download failed"
    ChapterDownloadState.Removing -> "Deleting"
}

@Composable
private fun FictionsScreen(
    padding: PaddingValues,
    repository: TtsRoadRepository,
    isAdmin: Boolean = false,
    onOpenFiction: (FictionSummary) -> Unit,
    onOpenReader: (AppScreen.Reader) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember { ServiceLocator.libraryCache(context) }
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
    // Browse means *everything on the server* once the server has per-user libraries — that is what
    // makes this the screen a fiction gets followed from. Without them there is only one list, and
    // browsing the shelf is browsing the server.
    val browseAll = capabilities.follows
    val state by (if (browseAll) cache.browseAll else cache.library)
        .collectAsStateWithLifecycle()
    // Server search is a *second* path, never a replacement. The local filter below is instant and
    // works offline; this one can match narration text, which the local filter structurally cannot.
    var serverResults by remember { mutableStateOf<SearchResponse?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // Saveable so the browse position and filter survive a trip into a fiction and back.
    var query by rememberSaveable { mutableStateOf("") }
    // Hoisted so the browse position survives the round trip into a fiction, alongside the
    // SaveableStateProvider keyed per back-stack entry.
    val gridState = rememberLazyGridState()

    LaunchedEffect(browseAll) {
        if (browseAll) cache.ensureBrowseAll() else cache.ensureLibrary()
    }
    val refresh: () -> Unit = if (browseAll) cache::refreshBrowseAll else cache::refreshLibrary

    val fictions = state.value?.fictions
    when {
        fictions == null && state.isInitialLoad -> LoadingPane(padding)
        fictions == null -> ErrorPane(
            padding = padding,
            message = state.error ?: "Could not load fictions",
            onRetry = refresh,
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
                onRefresh = refresh,
            ) {
                // One scrolling owner for the whole screen (#100). Everything above the grid used
                // to be eager content in a Column that could not scroll, so a search answering in
                // three groups pushed its own later hits — and the entire catalogue — past the
                // bottom of the window with no way to reach them.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Adding lives on the browse screen rather than the shelf because this is the
                    // screen that already answers "what is on this server", and a fiction has to
                    // exist here before it can be followed onto a shelf.
                    // Two separate server capabilities, deliberately: a deployment may take JSON
                    // fiction CRUD without accepting file uploads, or the reverse. Either one is
                    // enough to draw the section — it just holds fewer controls (#114).
                    if (isAdmin && (capabilities.fictionManagement || capabilities.epubUpload)) {
                        fullWidthItem(key = "add-fiction") {
                            AddFictionSection(
                                canAddByUrl = capabilities.fictionManagement,
                                canUploadEpub = capabilities.epubUpload,
                                maxEpubBytes = capabilities.effectiveMaxEpubBytes,
                                onAdd = { url -> repository.addFiction(url) },
                                onUploadEpub = { book -> repository.uploadEpub(book) },
                                onAdded = refresh,
                            )
                        }
                    }
                    fullWidthItem(key = "filter") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                // Typing starts a new question; last answer's results are stale.
                                serverResults = null
                                searchError = null
                            },
                            label = { Text("SEARCH TITLE, AUTHOR OR TAG") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (capabilities.search) {
                        serverSearchSection(
                            query = query,
                            results = serverResults,
                            isSearching = isSearching,
                            error = searchError,
                            onSearch = {
                                scope.launch {
                                    isSearching = true
                                    searchError = null
                                    runCatching { repository.search(query) }
                                        .onSuccess { serverResults = it }
                                        .onFailure {
                                            searchError = it.message ?: "Search failed"
                                        }
                                    isSearching = false
                                }
                            },
                            onOpenFiction = { fictionId ->
                                fictions.firstOrNull { it.id == fictionId }?.let(onOpenFiction)
                            },
                            // A text hit's whole point is the passage, so it opens the reader —
                            // where read-along already lands.
                            onOpenChapter = if (capabilities.readAlong) {
                                { hit ->
                                    hit.chapterId?.let { chapterId ->
                                        onOpenReader(
                                            AppScreen.Reader(
                                                chapterId = chapterId,
                                                title = hit.resolvedTitle,
                                            ),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    if (filtered.isEmpty()) {
                        fullWidthItem(key = "empty") {
                            EmptyCard(
                                if (query.isBlank()) "No fictions found" else "No matches for \"$query\"",
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { fiction ->
                            FictionGridCard(fiction = fiction, onClick = { onOpenFiction(fiction) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The two ways a fiction gets into the library from a phone: paste a URL, or hand over a book that
 * is already on the device. Admin-only, and each half is hidden unless the server advertises it.
 *
 * A single field is the whole interaction for the URL path on purpose. The URL is usually already in
 * the clipboard from browsing on the phone, and everything else the create endpoint accepts — voice,
 * rate, the sync window — has a server-side default and is a poor thing to be choosing on a phone.
 * The upload takes the same view: no voice picker, just the file.
 *
 * The server's refusal is shown verbatim rather than replaced with a generic failure: it is the half
 * that knows which sites have adapters, and "Fiction already tracked" is a different instruction to
 * the user than "that is not a URL I can read". The same goes for an EPUB the server recognises by
 * content hash — "already uploaded" is an answer, not an error to retry.
 *
 * Internal rather than private so the capability gating can be tested: which controls a server can
 * back is the whole behaviour here, and it is invisible from the outside when it is wrong.
 */
@Composable
internal fun AddFictionSection(
    canAddByUrl: Boolean = true,
    canUploadEpub: Boolean = false,
    /** This server's advertised ceiling, so an oversized book is refused before it is uploaded. */
    maxEpubBytes: Long = DefaultMaxEpubBytes,
    onAdd: suspend (String) -> FictionAddResult = { FictionAddResult.Unsupported },
    onUploadEpub: suspend (PickedEpub.Ready) -> FictionAddResult = { FictionAddResult.Unsupported },
    onAdded: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    /** What to say about a result, and whether the list behind this needs to be refetched. */
    fun adopt(result: FictionAddResult, clearsUrl: Boolean) {
        when (result) {
            is FictionAddResult.Added -> {
                isError = false
                message = result.fiction?.title?.let { "Tracking \"$it\"." } ?: "Fiction added."
                // Cleared only on success, so a rejected URL stays in the field to be corrected
                // rather than having to be pasted again.
                if (clearsUrl) url = ""
                // The new fiction is not in the loaded list, and conversion has only just been
                // queued, so the list has to come from the server again.
                onAdded()
            }

            is FictionAddResult.Refused -> {
                isError = true
                message = result.message
            }

            FictionAddResult.Unsupported -> {
                isError = true
                message = "This server cannot add fictions."
            }
        }
    }

    /**
     * Pick a book off the device and send it.
     *
     * `OpenDocument` rather than the visual-media picker the cover upload uses: an EPUB is a
     * document, and it is as likely to be in Downloads or a cloud drive as anywhere the gallery
     * knows about. The URI it hands back is readable for as long as this screen lives, which is
     * longer than the upload takes.
     */
    val pickEpub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A cancelled picker is not an event: no message, no state change.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            message = null
            // Off the main thread: the metadata query talks to a content provider that may be
            // backed by anything, including a network.
            val picked = withContext(Dispatchers.IO) {
                readPickedEpub(context.contentResolver, uri, maxEpubBytes)
            }
            when (picked) {
                is PickedEpub.Rejected -> {
                    isError = true
                    message = picked.message
                }

                is PickedEpub.Ready -> {
                    val result = runCatching { onUploadEpub(picked) }.getOrElse { failure ->
                        FictionAddResult.Refused(failure.message ?: "Could not upload that book.")
                    }
                    adopt(result, clearsUrl = false)
                }
            }
            isUploading = false
        }
    }

    Column(
        // No horizontal gutter of its own: this is a full-width row of the browse grid now, and
        // the grid's contentPadding already holds it off the screen edge (#100).
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val isBusy = isAdding || isUploading
        if (canAddByUrl) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    message = null
                },
                label = { Text("ADD A FICTION BY URL OR ID") },
                placeholder = { Text("Royal Road URL or ID") },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        isAdding = true
                        message = null
                        val result = runCatching { onAdd(url) }.getOrElse { failure ->
                            FictionAddResult.Refused(
                                failure.message ?: "Could not add this fiction.",
                            )
                        }
                        adopt(result, clearsUrl = true)
                        isAdding = false
                    }
                },
                enabled = !isBusy && url.isNotBlank(),
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isAdding) "ADDING" else "ADD FICTION")
            }
        }
        if (canUploadEpub) {
            OutlinedButton(
                onClick = { pickEpub.launch(EpubPickerMimeTypes) },
                enabled = !isBusy,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isUploading) "UPLOADING" else "UPLOAD AN EPUB")
            }
            MetaText(
                text = "A book already on this phone. Chapters are detected automatically, and " +
                    "narration starts on the server. Up to ${megabyteLabel(maxEpubBytes)}.",
                color = AarisColor.Dim,
            )
        }
        message?.let {
            MetaText(text = it, color = if (isError) AarisColor.Danger else AarisColor.Muted)
        }
    }
}

/**
 * A row that spans every column of a [LazyVerticalGrid], for content that is not a grid cell.
 *
 * The point of putting headers, search results and empty states through here rather than above the
 * grid is that a lazy list is the only container on this screen that scrolls (#100). Anything
 * placed outside it is placed *outside the viewport* once it is taller than the window, with no
 * gesture that can reach it.
 */
internal fun LazyGridScope.fullWidthItem(
    key: Any,
    content: @Composable () -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * Server-side search, offered alongside the local filter rather than instead of it.
 *
 * The local filter above is instant, works offline, and matches what is already loaded. This one
 * costs a round trip but can match chapter titles and the narration text itself — "which chapter
 * was the bit about the lighthouse in" is a question with an answer on the server and none in the
 * app. Making it an explicit action keeps the offline case from silently becoming useless.
 *
 * Emits into the screen's grid rather than composing a `Column` of its own. A response can carry
 * three groups of up to twenty hits each, every one of them a card with a snippet, which is far
 * more than a phone screen holds — so these have to be items of the one scrolling container, and
 * each hit has to be its own item so the list can recycle them.
 */
internal fun LazyGridScope.serverSearchSection(
    query: String,
    results: SearchResponse?,
    isSearching: Boolean,
    error: String?,
    onSearch: () -> Unit,
    onOpenFiction: (Int) -> Unit,
    /** Null when the server has no read-along, which is where a text hit would otherwise land. */
    onOpenChapter: ((SearchHit) -> Unit)?,
) {
    fullWidthItem(key = "server-search-action") {
        OutlinedButton(
            onClick = onSearch,
            enabled = query.isNotBlank() && !isSearching,
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isSearching) "SEARCHING" else "SEARCH CHAPTERS AND TEXT ON THE SERVER")
        }
    }
    error?.let { message ->
        fullWidthItem(key = "server-search-error") {
            MetaText(text = message, color = AarisColor.Danger)
        }
    }

    val found = results ?: return
    if (found.total == 0) {
        fullWidthItem(key = "server-search-empty") {
            MetaText(
                text = "// Nothing on the server for \"${found.query}\"",
                color = AarisColor.Muted,
            )
        }
        return
    }

    // Group order is rank order: fictions, then chapter titles, then narration text.
    searchHitGroup(
        id = "fictions",
        title = "Fictions",
        group = found.fictions,
        onOpen = { hit -> hit.fictionId?.let(onOpenFiction) },
    )
    searchHitGroup(
        id = "chapters",
        title = "Chapter titles",
        group = found.chapters,
        onOpen = onOpenChapter,
    )
    searchHitGroup(
        id = "text",
        title = "In the text",
        group = found.text,
        onOpen = onOpenChapter,
    )
    if (!found.indexed) {
        // Worth saying rather than quietly returning less than the server could.
        fullWidthItem(key = "server-search-unindexed") {
            MetaText(
                text = "// The full-text index is unavailable, so text matches may be incomplete",
                color = AarisColor.Dim,
            )
        }
    }
}

/**
 * [id] rather than [title] as the key prefix: the same chapter can be a hit in two groups, and a
 * lazy list needs keys unique across the whole list, not within a section.
 */
internal fun LazyGridScope.searchHitGroup(
    id: String,
    title: String,
    group: SearchGroup,
    onOpen: ((SearchHit) -> Unit)?,
) {
    if (group.items.isEmpty()) return
    fullWidthItem(key = "search-heading-$id") {
        MetaText(
            // `total` stops at the server's cap, so say "20+" rather than an exact-looking 20.
            text = "// $title (${group.total}${if (group.capped) "+" else ""})",
            color = AarisColor.Accent,
        )
    }
    itemsIndexed(
        items = group.items,
        span = { _, _ -> GridItemSpan(maxLineSpan) },
        key = { index, _ -> "search-hit-$id-$index" },
    ) { _, hit ->
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpen != null) Modifier.clickable { onOpen(hit) } else Modifier,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = hit.resolvedTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = AarisColor.Ink,
                )
                hit.fictionTitle
                    ?.takeIf { it != hit.resolvedTitle }
                    ?.let { MetaText(text = it, color = AarisColor.Muted) }
                // The matching passage is the answer for a text hit, so it is the row's substance
                // rather than a decoration.
                hit.snippet?.takeIf { it.isNotBlank() }?.let {
                    MetaText(text = it, color = AarisColor.Dim)
                }
            }
        }
    }
    if (group.hasMore) {
        fullWidthItem(key = "search-more-$id") {
            MetaText(
                text = "// More matches on the server — narrow the search",
                color = AarisColor.Dim,
            )
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
    /** Null on a server without per-user libraries, where there is no shelf to be on. */
    onSetFollowing: ((Boolean) -> Unit)? = null,
    isFollowing: Boolean = true,
    isFollowBusy: Boolean = false,
    listeningSummary: FictionListeningSummary = FictionListeningSummary(),
    playbackSpeed: Float = 1f,
    /** Null unless this account is an admin on a server that can edit fictions. */
    onEdit: (() -> Unit)? = null,
    /** Null unless this account is an admin on a server that can delete fictions. */
    onDelete: (() -> Unit)? = null,
    isDeleting: Boolean = false,
    /** Ask the source for new chapters now (#112). Null on a server without the routes. */
    onPoll: (() -> Unit)? = null,
    /** Requeue this fiction's failed chapters (#107). Null when there is nothing to requeue. */
    onRetryFailed: (() -> Unit)? = null,
    /** Open the rest of the maintenance actions. Admin-only, so null for everyone else. */
    onMaintain: (() -> Unit)? = null,
    /** A maintenance request is in flight; every one of these controls waits for it. */
    isMaintaining: Boolean = false,
    /** This fiction's podcast feed URL, or null on a server that cannot report one (#115). */
    feedUrl: String? = null,
    onShareFeed: ((String) -> Unit)? = null,
    /** Admin only: this token is shared, so rotating it re-subscribes everyone. */
    onRotateFeed: (() -> Unit)? = null,
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

        onSetFollowing?.let { setFollowing ->
            OutlinedButton(
                onClick = { setFollowing(!isFollowing) },
                enabled = !isFollowBusy,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isFollowing) AarisColor.Accent else AarisColor.Muted,
                ),
            ) {
                Text(
                    when {
                        isFollowBusy -> "WORKING"
                        isFollowing -> "FOLLOWING"
                        else -> "FOLLOW"
                    },
                )
            }
            MetaText(
                text = if (isFollowing) {
                    "On your library. Unfollowing leaves it on the server."
                } else {
                    "Not on your library. Follow it to see it on the home screen."
                },
                color = AarisColor.Dim,
            )
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
            // #107: the count used to be the entire treatment of a failed chapter on the phone.
            // A number you cannot act on and cannot explain is worse than no number — it says
            // something is wrong and then refuses to offer a fix.
            if (fiction.errorChapters > 0 && onRetryFailed != null) {
                OutlinedButton(
                    onClick = onRetryFailed,
                    enabled = !isMaintaining,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Warning),
                ) {
                    Text("RETRY ${fiction.errorChapters} FAILED")
                }
            }
        }

        // How this book is produced, as opposed to what it is about. All of it has been in the
        // library payload since before the app existed; the client simply never decoded it, so
        // "which voice is this" and "why has nothing new arrived" had no answer on a phone (#111).
        ProductionMeta(fiction)

        // In the header rather than the sheet, and deliberately: "the author posted an hour ago,
        // where is it" is the single most likely reason to want the phone to *do* something to a
        // fiction rather than play it, and the server leaves this route open to any account.
        onPoll?.let { poll ->
            OutlinedButton(
                onClick = poll,
                enabled = !isMaintaining,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isMaintaining) "WORKING" else "CHECK FOR NEW CHAPTERS")
            }
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

        // The podcast URL for this book (#115). Above the admin block rather than in it: handing
        // a feed to a podcast app is something any reader does, not housekeeping.
        feedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                onShareFeed?.let { share ->
                    OutlinedButton(
                        onClick = { share(url) },
                        shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("SHARE PODCAST FEED")
                    }
                }
                MetaText(
                    text = "Subscribe a podcast app to this book. The link carries a token — " +
                        "anyone holding it can listen without signing in.",
                    color = AarisColor.Dim,
                )
                onRotateFeed?.let { rotate ->
                    OutlinedButton(
                        onClick = rotate,
                        enabled = !isMaintaining,
                        shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Warning),
                    ) {
                        Text("REGENERATE FEED LINK")
                    }
                    MetaText(
                        text = "This link is shared by everyone subscribed to this book, so " +
                            "regenerating it makes all of them re-subscribe.",
                        color = AarisColor.Dim,
                    )
                }
            }
        }

        // Admin housekeeping from here down, below everything anyone reaches for mid-listen.
        // Correcting a title or a synopsis is a rare, deliberate act, and it should not sit next to
        // RESUME.
        onEdit?.let { edit ->
            OutlinedButton(
                onClick = edit,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("EDIT DETAILS")
            }
        }

        // The other half of EDIT DETAILS (#112). Renaming a book from a phone was only half
        // applied while the files carrying the old title could not be rewritten from the same
        // place — and a count of failed chapters was stated and could not be acted on (#107).
        onMaintain?.let { maintain ->
            OutlinedButton(
                onClick = maintain,
                enabled = !isMaintaining,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isMaintaining) "WORKING" else "MAINTENANCE")
            }
        }

        // Last in the header, and the only destructive control on the screen. Deleting is admin
        // housekeeping, not something anyone does mid-listen, so it sits below everything that is.
        onDelete?.let { delete ->
            OutlinedButton(
                onClick = delete,
                enabled = !isDeleting,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AarisColor.Danger),
            ) {
                Text(if (isDeleting) "DELETING" else "DELETE FICTION")
            }
        }
    }
}

/**
 * Correct what the source got wrong: title, author, synopsis, tags and cover art.
 *
 * Admin-only. The entry point is hidden for anyone else and the server refuses them anyway; this
 * screen simply is not offered rather than being offered and 403ing.
 *
 * Three things shape it. Saving sends **only the fields that changed**, because the server records
 * every field a PATCH sets as hand-edited and stops refreshing it from the source — so a form that
 * was opened, read and saved would quietly freeze a whole fiction against its own updates. The
 * cover is uploaded the moment it is picked, since it is its own route and its own request, and
 * there is nothing left to save afterwards. And what the server answers with is what is adopted:
 * the trimmed title, the de-duplicated tags and the rehosted cover URL are all its decisions, not
 * this screen's.
 */
/**
 * One private URL and the share sheet that hands it somewhere useful (#115).
 *
 * Share rather than copy-to-clipboard, and that is the point of putting these on a phone at all:
 * the goal is getting a tokenised feed URL *into a podcast app*, and on a phone share-sheet-to-app
 * is a far better path than a clipboard. On a desktop the clipboard is shared with the browser
 * that already has the URL, which is why the web page can settle for Copy.
 *
 * The URL itself is shown, truncated, rather than hidden behind the button: a link you cannot see
 * is one you cannot tell apart from the one you regenerated it away from.
 */
@Composable
private fun ShareUrlRow(label: String, url: String?, onShare: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetaText(text = label)
        if (url.isNullOrBlank()) {
            MetaText(text = "Not available", color = AarisColor.Dim)
            return@Column
        }
        MetaText(text = url, color = AarisColor.Dim, maxLines = 2)
        OutlinedButton(onClick = { onShare(url) }, shape = RectangleShape) {
            Text("SHARE")
        }
    }
}

/**
 * One finished M4B export: what it is, how big it is, when it finished, and where it lives (#113).
 *
 * There is no play button, and that is the design rather than an omission — the app streams a
 * fiction chapter by chapter with a position per chapter, which beats one multi-gigabyte file with
 * a single position everywhere except inside another app. Nor is there a download button: the URL
 * needs the account's `Authorization` header, so the phone cannot hand it to the system browser or
 * to DownloadManager and expect anything but a 401. Sharing is the honest action for a link that
 * only means something to something holding a token.
 */
@Composable
private fun AudiobookExportListItem(row: AudiobookExportRow, onShare: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AarisColor.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        row.detail.takeIf { it.isNotBlank() }?.let { MetaText(text = it, color = AarisColor.Muted) }
        row.finished?.let { MetaText(text = "Finished $it", color = AarisColor.Dim) }
        val url = row.downloadUrl
        if (url == null) {
            // The URL is built from the server's own BASE_URL and can come back empty. Saying so
            // beats a SHARE button that shares nothing.
            MetaText(text = "This server did not give a download link.", color = AarisColor.Dim)
        } else {
            MetaText(text = url, color = AarisColor.Dim, maxLines = 2)
            OutlinedButton(onClick = { onShare(url) }, shape = RectangleShape) {
                Text("SHARE LINK")
            }
        }
    }
}

/** Hand [text] to the system share sheet. [title] is what the chooser calls it. */
private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    // Always a chooser, never a remembered default: these are private URLs, and silently reopening
    // whatever app handled the last share is not a decision to make on someone's behalf.
    context.startActivity(Intent.createChooser(intent, title))
}

/**
 * The rest of the fiction maintenance actions, behind one button (#112).
 *
 * A sheet rather than four more buttons in the header. The header is what someone opens to press
 * RESUME, and every action here is a rare, deliberate act with a cost — the two at the bottom spend
 * real server time, and one of them is four hundred conversions on a long serial. Poll is the
 * exception and lives in the header, because "has the author posted" is a question people have
 * while listening.
 *
 * Each action says what it will do and what it costs, because none of them can be undone and two
 * of them are indistinguishable from the outside until they finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FictionMaintenanceSheet(
    fiction: FictionSummary,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onPollFull: () -> Unit,
    onApplyFilter: () -> Unit,
    onRetag: () -> Unit,
    onReconvertAll: () -> Unit,
    onRetryFailed: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AarisColor.BgRaise) {
        MetaText(
            text = "// Maintenance",
            color = AarisColor.Accent,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        onRetryFailed?.let { retry ->
            BulkAction(
                title = "Retry failed chapters",
                subtitle = "${fiction.errorChapters} failed",
                enabled = !isBusy,
                onClick = retry,
            )
        }
        BulkAction(
            title = "Fetch all chapters",
            subtitle = "Re-reads the whole chapter list, not just the recent tail",
            enabled = !isBusy,
            onClick = onPollFull,
        )
        BulkAction(
            title = "Re-apply chapter filter",
            subtitle = "Excludes chapters the filter matches. Never un-excludes: one taken out " +
                "by hand had a reason.",
            enabled = !isBusy,
            onClick = onApplyFilter,
        )
        BulkAction(
            title = "Refresh MP3 tags",
            subtitle = "Rewrites the tags on files that already exist. No audio is re-made.",
            enabled = !isBusy,
            onClick = onRetag,
        )
        BulkAction(
            title = "Re-narrate every chapter",
            subtitle = "${fiction.totalChapters} chapters, converted again from scratch. This is " +
                "the expensive one.",
            enabled = !isBusy,
            onClick = onReconvertAll,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Voice, rate, source and poll state for one fiction.
 *
 * Renders nothing at all when the server said none of it — an older server, or a payload that
 * predates these keys. An empty panel captioned "Production" would be worse than no panel: it
 * reads as a feature that is broken rather than one the server cannot answer.
 */
@Composable
private fun ProductionMeta(fiction: FictionSummary) {
    val facts = listOfNotNull(
        fiction.voice?.takeIf { it.isNotBlank() },
        fiction.rate?.takeIf { it.isNotBlank() },
        fiction.sourceTypeLabel,
        fiction.lastPolledLabel(),
    )
    if (facts.isEmpty() && !fiction.isPaused) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fiction.isPaused) {
            // The one line here that is a problem rather than a fact, so it gets the warning
            // colour and its own row: a paused book looks identical to an up-to-date one until
            // you notice nothing has arrived for a fortnight.
            MetaText(
                text = "Paused — the server is not polling this or converting anything new",
                color = AarisColor.Warning,
            )
        }
        if (facts.isNotEmpty()) {
            MetaText(text = facts.joinToString("  ·  "), color = AarisColor.Dim)
        }
    }
}

@Composable
private fun FictionEditScreen(
    padding: PaddingValues,
    fiction: FictionSummary,
    repository: TtsRoadRepository,
    /** Called with the server's copy after every accepted write — an edit or a cover alike. */
    onFictionChanged: (FictionSummary) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cache = remember { ServiceLocator.libraryCache(context) }
    // Keyed on the fiction rather than on the whole row: an accepted cover upload hands a new
    // FictionSummary in while the form is still open, and that must not throw away typing.
    var title by rememberSaveable(fiction.id) { mutableStateOf(fiction.title) }
    var author by rememberSaveable(fiction.id) { mutableStateOf(fiction.author.orEmpty()) }
    var description by rememberSaveable(fiction.id) { mutableStateOf(fiction.description.orEmpty()) }
    var tagText by rememberSaveable(fiction.id) { mutableStateOf(formatFictionTags(fiction.tags)) }
    var isSaving by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }

    val draft = FictionMetadataDraft(
        title = title,
        author = author,
        description = description,
        tags = tagText,
    )
    val patch = remember(fiction, draft) { fictionMetadataPatch(fiction, draft) }
    val overridden = fiction.overriddenFields
    val isBusy = isSaving || isUploading
    // Whether this server understands hand-edited metadata at all. An older one accepts a
    // description, drops it and answers "ok", so offering the field would be offering a lie; the
    // title and author it has always been able to store are still editable.
    val editsEverything = fiction.supportsMetadataEditing

    /**
     * Adopt whatever the server answered with, and say so.
     *
     * The echoed fiction is the only trustworthy account of what a write did, and it has to reach
     * three places: the library lists, the back stack, and the form's own baseline for "what has
     * changed". Answers true when the write landed.
     */
    fun adopt(result: FictionEditResult, done: String, unsupported: String): Boolean = when (result) {
        is FictionEditResult.Saved -> {
            result.fiction?.let { saved ->
                cache.applyFiction(saved)
                onFictionChanged(saved)
            }
            isError = false
            message = done
            true
        }

        is FictionEditResult.Refused -> {
            isError = true
            message = result.message
            false
        }

        FictionEditResult.Unsupported -> {
            isError = true
            message = unsupported
            false
        }
    }

    fun save() {
        val changes = patch ?: return
        scope.launch {
            isSaving = true
            message = null
            val result = runCatching { repository.updateFiction(fiction.id, changes) }
                .getOrElse { FictionEditResult.Refused(it.message ?: "Could not save those details.") }
            val saved = adopt(result, "Saved.", "This server cannot edit fictions.")
            isSaving = false
            // Only on success: a refusal has to stay on screen with the text that caused it, so it
            // can be corrected rather than retyped.
            if (saved) onDone()
        }
    }

    /**
     * Hand the edited fields back to the source.
     *
     * This drops the protection, not the text. Nothing on screen changes until the fiction is next
     * polled, which is exactly what the confirmation says.
     */
    fun revert() {
        scope.launch {
            isSaving = true
            message = null
            val result = runCatching {
                repository.updateFiction(fiction.id, FictionUpdateRequest(clearOverrides = overridden))
            }.getOrElse { FictionEditResult.Refused(it.message ?: "Could not hand those fields back.") }
            adopt(
                result,
                "Handed back. The next poll may replace them.",
                "This server cannot edit fictions.",
            )
            isSaving = false
        }
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // A cancelled picker is not an event: no message, no state change.
        if (uri != null) {
            scope.launch {
                isUploading = true
                message = null
                // Off the main thread: the bytes may be coming from a cloud provider, not a file.
                val picked = withContext(Dispatchers.IO) {
                    readPickedCover(context.contentResolver, uri)
                }
                when (picked) {
                    is PickedCover.Rejected -> {
                        isError = true
                        message = picked.message
                    }

                    is PickedCover.Ready -> {
                        val result = runCatching {
                            repository.uploadFictionCover(fiction.id, picked.bytes, picked.mimeType)
                        }.getOrElse {
                            FictionEditResult.Refused(it.message ?: "Could not upload that image.")
                        }
                        adopt(
                            result,
                            "Cover updated.",
                            "This server is older than cover uploads. Update the backend to change " +
                                "cover art from here.",
                        )
                    }
                }
                isUploading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isBusy) {
            ThinProgress(fraction = 1f, modifier = Modifier.fillMaxWidth(), height = 2.dp)
        }

        MetaText(text = "// Cover", color = AarisColor.Accent)
        AarisCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CoverThumb(imageUrl = fiction.coverImageUrl, fallback = fiction.title, size = 96)
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (fiction.isMetadataOverridden(MetadataFieldCoverImageUrl)) {
                        AarisTag(text = "Hand-picked")
                    }
                    MetaText(
                        text = "JPEG, PNG, WEBP or GIF, up to 10 MB. It replaces the cover " +
                            "everywhere, for everyone.",
                        color = AarisColor.Dim,
                    )
                    OutlinedButton(
                        onClick = {
                            pickCover.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !isBusy && editsEverything,
                        shape = RectangleShape,
                    ) {
                        Text(if (isUploading) "UPLOADING" else "CHOOSE IMAGE")
                    }
                }
            }
        }

        MetaText(text = "// Details", color = AarisColor.Accent)
        AarisCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!editsEverything) {
                    MetaText(
                        text = "This server can only edit the title and author. Update the backend " +
                            "for the synopsis, tags and cover art.",
                        color = AarisColor.Warning,
                    )
                }
                MetadataField(
                    label = "Title",
                    value = title,
                    onValueChange = { title = it },
                    enabled = !isBusy,
                    isOverridden = fiction.isMetadataOverridden(MetadataFieldTitle),
                )
                if (!draft.hasUsableTitle) {
                    MetaText(text = "A fiction has to be called something.", color = AarisColor.Danger)
                }
                MetadataField(
                    label = "Author",
                    value = author,
                    onValueChange = { author = it },
                    enabled = !isBusy,
                    isOverridden = fiction.isMetadataOverridden(MetadataFieldAuthor),
                    supporting = "Leave it empty to clear it.",
                )
                MetadataField(
                    label = "Synopsis",
                    value = description,
                    onValueChange = { description = it },
                    enabled = !isBusy && editsEverything,
                    isOverridden = fiction.isMetadataOverridden(MetadataFieldDescription),
                    singleLine = false,
                    minLines = 4,
                    supporting = "Leave it empty to clear it.",
                )
                MetadataField(
                    label = "Tags",
                    value = tagText,
                    onValueChange = { tagText = it },
                    enabled = !isBusy && editsEverything,
                    isOverridden = fiction.isMetadataOverridden(MetadataFieldTags),
                    supporting = "Separated by commas. Up to 50, and duplicates are dropped.",
                )
                // The chips are what will actually be stored — same trimming, same de-duplication
                // the server does — so the field is a preview rather than a promise.
                if (draft.parsedTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draft.parsedTags.forEach { tag -> AarisTag(text = tag) }
                    }
                }
            }
        }

        Button(
            onClick = ::save,
            // Nothing changed means nothing to send: a PATCH carrying an untouched field would
            // freeze it against the source for no reason at all.
            enabled = !isBusy && patch != null && draft.hasUsableTitle,
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isSaving) "SAVING" else "SAVE CHANGES")
        }

        message?.let {
            MetaText(text = it, color = if (isError) AarisColor.Danger else AarisColor.Muted)
        }

        if (editsEverything) {
            MetaText(text = "// Hand-edited", color = AarisColor.Accent)
            AarisCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (overridden.isEmpty()) {
                        MetaText(
                            text = "Nothing has been edited here. Every field still follows the " +
                                "source, and changes there arrive with the next poll.",
                            color = AarisColor.Dim,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            overridden.forEach { field -> AarisTag(text = metadataFieldLabel(field)) }
                        }
                        MetaText(
                            text = "The source no longer overwrites these. Everything else is still " +
                                "refreshed when the fiction is polled.",
                            color = AarisColor.Dim,
                        )
                        OutlinedButton(
                            onClick = { confirmRevert = true },
                            enabled = !isBusy,
                            shape = RectangleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("USE SOURCE VALUES")
                        }
                    }
                }
            }
        }
    }

    if (confirmRevert) {
        ConfirmDialog(
            title = "USE SOURCE VALUES?",
            // Specific about what it does *not* do. "Revert" reads as an undo, and this is not one:
            // the text on screen stays until the fiction is polled and the source replaces it.
            body = "The source is allowed to overwrite " +
                overridden.joinToString(", ") { metadataFieldLabel(it).lowercase() } +
                " again from the next poll. It does not bring the old values back — what is here " +
                "now stays until the source replaces it.",
            confirmLabel = "HAND THEM BACK",
            onConfirm = {
                confirmRevert = false
                revert()
            },
            onDismiss = { confirmRevert = false },
        )
    }
}

/** What a `metadata_overrides` name is called on screen. */
private fun metadataFieldLabel(field: String): String = when (field) {
    MetadataFieldTitle -> "Title"
    MetadataFieldAuthor -> "Author"
    MetadataFieldDescription -> "Synopsis"
    MetadataFieldCoverImageUrl -> "Cover"
    MetadataFieldTags -> "Tags"
    // A field name from a server newer than this build. Showing it plainly is more use than
    // dropping it: whatever it is, the user is being told it is no longer following the source.
    else -> field.replace('_', ' ')
}

/**
 * One labelled field in the metadata editor, marked when the source no longer owns it.
 *
 * The marker is not decoration. "Why is this fiction's description still the old one" and "why did
 * my correction survive the poll" are the same question from opposite sides, and this tag is the
 * answer to both.
 */
@Composable
private fun MetadataField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isOverridden: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaText(text = label, color = AarisColor.Accent)
            if (isOverridden) AarisTag(text = "Hand-edited")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
        supporting?.let { MetaText(text = it, color = AarisColor.Dim) }
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
internal fun EmptyCard(message: String) {
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
 *
 * When the reader *is* showing what is playing it also follows the audio across a chapter boundary,
 * via [onFollowChapter] — see [readerFollowTarget] for which changes count. Every piece of
 * per-chapter state below hangs off `screen.chapterId`, so re-targeting the entry reloads the text,
 * re-arms the highlight and resets the scroll without any of them needing to know it happened.
 */
@Composable
private fun ReaderScreen(
    padding: PaddingValues,
    screen: AppScreen.Reader,
    playerState: PlayerUiState,
    playbackController: PlaybackController,
    repository: TtsRoadRepository,
    onFollowChapter: (AppScreen.Reader) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readerPreferences = remember { ServiceLocator.readerPreferences(context) }
    // Reader appearance follows the account, so the page looks the same in the browser. The store
    // is still what the reader renders from — the sync writes through it, not around it.
    val accountPreferenceSync = remember { ServiceLocator.accountPreferenceSync(context) }
    val prefs by readerPreferences.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
    // Only for the settings sheet's footer, which has to say whether these follow the account.
    val capabilities by repository.currentCapabilities.collectAsStateWithLifecycle()
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

    // Only highlight when this is the chapter actually playing. Opening the reader for a different
    // chapter is an ordinary thing to do, and highlighting it against someone else's audio would
    // be worse than not highlighting at all.
    val playingItem = playerState.queue.getOrNull(playerState.currentIndex)
    val playingChapterId = playingItem?.let { TtsRoadMediaIds.chapterId(it.mediaId) }
    val isPlayingThisChapter = playingChapterId == screen.chapterId

    // Seeded with whatever was playing when this entry was composed, which is what makes a reader
    // opened on an unrelated chapter stay there: it never matches, so it never follows. A re-target
    // recomposes this whole subtree under a new save key, and the seed matches again on the way in.
    var previousPlayingChapterId by remember { mutableStateOf(playingChapterId) }
    LaunchedEffect(playingChapterId) {
        val target = readerFollowTarget(
            readerChapterId = screen.chapterId,
            previousPlayingChapterId = previousPlayingChapterId,
            playingChapterId = playingChapterId,
        )
        previousPlayingChapterId = playingChapterId
        if (target != null) {
            // The queue row is where the id came from, so its title describes the same chapter.
            // It is only what the top bar shows until the document lands and names it properly.
            onFollowChapter(
                AppScreen.Reader(chapterId = target, title = playingItem?.title ?: screen.title),
            )
        }
    }

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
            syncsWithAccount = capabilities.playerPreferences,
            onDismiss = { showSettings = false },
            onFontScale = { scope.launch { accountPreferenceSync.setReaderFontScale(it) } },
            onLineHeight = { scope.launch { accountPreferenceSync.setReaderLineHeight(it) } },
            onTheme = { scope.launch { accountPreferenceSync.setReaderTheme(it) } },
            onHighlight = { scope.launch { accountPreferenceSync.setReaderHighlight(it) } },
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
    val bodyFontSize = 17.sp * prefs.fontScale
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        color = palette.ink,
        fontSize = bodyFontSize,
        // Spacing is a multiple of the font size, which is how the server and the web reader both
        // express it. This used to be a fixed 28sp against a 17sp body — a ratio of 1.65 — so the
        // default reading is very slightly airier than before, at the 1.75 the server declares.
        lineHeight = bodyFontSize * prefs.lineHeight,
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
    /** Whether this server can hold reader settings on the account — see [PreferenceScope]. */
    syncsWithAccount: Boolean,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onHighlight: (HighlightGranularity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AarisColor.BgRaise) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            MetaText(text = "// Text size", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReaderFontScales.forEach { scale ->
                    val selected = kotlin.math.abs(scale - prefs.fontScale) < 0.001f
                    ReaderOptionChip(
                        label = formatReaderFontScale(scale),
                        selected = selected,
                        onClick = { onFontScale(scale) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            MetaText(text = "// Line spacing", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReaderLineHeights.forEach { height ->
                    ReaderOptionChip(
                        label = formatReaderLineHeight(height),
                        selected = kotlin.math.abs(height - prefs.lineHeight) < 0.001f,
                        onClick = { onLineHeight(height) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            MetaText(text = "// Page", color = AarisColor.Accent)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HighlightGranularity.entries.forEach { granularity ->
                    ReaderOptionChip(
                        label = granularity.label,
                        selected = granularity == prefs.highlight,
                        onClick = { onHighlight(granularity) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            // These have followed the account since #62; the footer went on claiming otherwise for
            // two releases (#103). It is capability-aware because both answers are real: an older
            // server genuinely cannot hold them, and saying so is not the same as denying the sync.
            MetaText(
                text = PreferenceScope.reader(syncsWithAccount),
                color = palette.muted,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun ReaderOptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // The chip stays the size it looks; the box around it is what the finger has to find (#104).
    // Width matters as much as height here and is the half that is easy to miss: these chips are as
    // wide as their label, and the shortest are "OFF" and "80%".
    //
    // `selectable` rather than `clickable` because these are a one-of-N group: the selected chip is
    // marked by colour alone, so without the role and the selected flag a screen reader reads four
    // labels and never says which page theme is in force.
    Box(
        modifier = Modifier
            .sizeIn(minWidth = MinTouchTargetSize, minHeight = MinTouchTargetSize)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = if (selected) AarisColor.Accent else AarisColor.Muted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .border(1.dp, if (selected) AarisColor.Accent else AarisColor.Line)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
internal fun SettingsItem(label: String, value: String) {
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

/** "30m", or "Ask" for the server's 0, which means "no default — pick one each time". */
private fun formatSleepTimerDefault(minutes: Int): String =
    if (minutes <= 0) "Ask" else "${minutes}m"

private fun formatSpeed(speed: Float): String {
    val text = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "${text}×"
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

/**
 * Parses a 24-hour clock time typed from a health app's sleep log.
 *
 * The field deliberately keeps the numeric keyboard: every Android number pad can enter `2349`,
 * while many provide no colon at all. The colon form remains valid for pasted values and hardware
 * keyboards so this change does not reject input the sheet accepted before.
 */
internal fun parseClockTime(input: String): Pair<Int, Int>? {
    val value = input.trim()
    val compact = Regex("""^(\d{2})(\d{2})$""").matchEntire(value)
    val separated = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(value)
    val hour = (compact?.groupValues?.get(1) ?: separated?.groupValues?.get(1))
        ?.toIntOrNull() ?: return null
    val minute = (compact?.groupValues?.get(2) ?: separated?.groupValues?.get(2))
        ?.toIntOrNull() ?: return null
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
