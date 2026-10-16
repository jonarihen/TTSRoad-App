package dk.perspektiva.ttsroad.core

import android.content.Context
import androidx.glance.appwidget.updateAll
import dk.perspektiva.ttsroad.data.AccountPreferenceSync
import dk.perspektiva.ttsroad.data.BrowsePreferences
import dk.perspektiva.ttsroad.data.ChapterListPreferences
import dk.perspektiva.ttsroad.data.DownloadPreferences
import dk.perspektiva.ttsroad.data.FictionSpeedPreferences
import dk.perspektiva.ttsroad.data.LibraryCache
import dk.perspektiva.ttsroad.data.LibrarySnapshotStore
import dk.perspektiva.ttsroad.data.LocalReaderPreferences
import dk.perspektiva.ttsroad.data.PlaybackPreferences
import dk.perspektiva.ttsroad.data.ReadAlongFileStore
import dk.perspektiva.ttsroad.data.ReaderPreferenceStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.download.AudioHashRecord
import dk.perspektiva.ttsroad.download.OfflineDownloads
import dk.perspektiva.ttsroad.download.StaleDownloadScanner
import dk.perspektiva.ttsroad.player.PendingProgressStore
import dk.perspektiva.ttsroad.player.PlayedMarks
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.player.historyAccountKey
import dk.perspektiva.ttsroad.player.ProgressSync
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerStopStore
import dk.perspektiva.ttsroad.update.UpdateManager
import dk.perspektiva.ttsroad.widget.NowPlayingStore
import dk.perspektiva.ttsroad.widget.NowPlayingWidget
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ServiceLocator {
    /** For one-shot startup work that must not block [init]; see the token re-seal there. */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tokenStore: TokenStore? = null

    @Volatile
    private var repository: TtsRoadRepository? = null

    @Volatile
    private var playbackController: PlaybackController? = null

    @Volatile
    private var playbackHistory: PlaybackHistoryStore? = null

    @Volatile
    private var sleepTimer: SleepTimerController? = null

    @Volatile
    private var sleepTimerStops: SleepTimerStopStore? = null

    @Volatile
    private var playbackPreferences: PlaybackPreferences? = null

    @Volatile
    private var fictionSpeedPreferences: FictionSpeedPreferences? = null

    @Volatile
    private var libraryCache: LibraryCache? = null

    @Volatile
    private var readerPreferences: ReaderPreferenceStore? = null

    @Volatile
    private var updateManager: UpdateManager? = null

    @Volatile
    private var downloadPreferences: DownloadPreferences? = null

    @Volatile
    private var chapterListPreferences: ChapterListPreferences? = null

    @Volatile
    private var browsePreferences: BrowsePreferences? = null

    @Volatile
    private var offlineDownloads: OfflineDownloads? = null

    @Volatile
    private var initializeDownloadManager: Boolean = true

    @Volatile
    private var staleDownloads: StaleDownloadScanner? = null

    @Volatile
    private var accountPreferenceSync: AccountPreferenceSync? = null

    @Volatile
    private var pendingProgress: PendingProgressStore? = null

    @Volatile
    private var progressSync: ProgressSync? = null

    /** What this process knows about chapters' played flags; read by the service, fed by marks. */
    val playedMarks: PlayedMarks = PlayedMarks()

    fun init(context: Context) {
        tokenStore(context)
        // Off the main thread and fire-and-forget: it touches the keystore and DataStore, and a
        // token that has not been re-sealed yet still reads fine, so nothing needs to wait on it.
        initScope.launch { tokenStore(context).encryptStoredTokenIfNeeded() }
        bindHistoryToAccount(context.applicationContext)
        repository(context)
        playbackController(context)
        playbackHistory(context)
        sleepTimer()
        playbackPreferences(context)
        readerPreferences(context)
        libraryCache(context)
    }

    /**
     * Keep the jump-back history and the widget's note on the account that recorded them (#368).
     *
     * Here rather than in the media service, because the history is also read by the app with the
     * service dead, and the widget is drawn by the launcher with this whole process dead — so the
     * scoping cannot depend on whichever of them happens to be running at sign-out.
     */
    private fun bindHistoryToAccount(context: Context) {
        initScope.launch {
            var previous: String? = null
            tokenStore(context).session
                .map(::historyAccountKey)
                .distinctUntilChanged()
                .collect { key ->
                    playbackHistory(context).bindAccount(key)
                    // Signed out from a session this process saw. The media service clears the note
                    // too when it is running; this covers sign-out with the service already gone.
                    if (previous != null && key == null) {
                        NowPlayingStore(context).clear()
                        // The offline copy of the library is this account's too (#327).
                        LibrarySnapshotStore(context.applicationContext).clear()
                        runCatching { NowPlayingWidget().updateAll(context) }
                    }
                    previous = key
                }
        }
    }

    fun tokenStore(context: Context): TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
        }

    fun repository(context: Context): TtsRoadRepository =
        repository ?: synchronized(this) {
            val appContext = context.applicationContext
            repository ?: TtsRoadRepository(
                tokenStore = tokenStore(appContext),
                // Read-along documents outlive the process so a chapter opened once reads offline.
                readAlongStore = ReadAlongFileStore(
                    File(appContext.filesDir, "readalong"),
                ),
                onSessionCleared = {
                    pendingProgress(appContext).clear()
                    playedMarks.clear()
                },
                // A 401 keeps the offline backlog: it is owner-stamped, so it only ever flushes
                // once the same account is signed in again (#311).
                onSessionExpired = { playedMarks.clear() },
                onSignedIn = { session -> pendingProgress(appContext).retainOwnedBy(session) },
            ).also { repository = it }
        }

    fun readerPreferences(context: Context): ReaderPreferenceStore =
        readerPreferences ?: synchronized(this) {
            readerPreferences
                ?: LocalReaderPreferences(context.applicationContext).also { readerPreferences = it }
        }

    fun playbackController(context: Context): PlaybackController =
        playbackController ?: synchronized(this) {
            playbackController ?: PlaybackController(
                context = context.applicationContext,
                tokenStore = tokenStore(context),
                preferences = playbackPreferences(context),
                fictionSpeeds = fictionSpeedPreferences(context),
            ).also { playbackController = it }
        }

    fun playbackHistory(context: Context): PlaybackHistoryStore =
        playbackHistory ?: synchronized(this) {
            playbackHistory ?: PlaybackHistoryStore(context.applicationContext).also { playbackHistory = it }
        }

    fun sleepTimer(): SleepTimerController =
        sleepTimer ?: synchronized(this) {
            sleepTimer ?: SleepTimerController().also { sleepTimer = it }
        }

    fun sleepTimerStops(context: Context): SleepTimerStopStore =
        sleepTimerStops ?: synchronized(this) {
            sleepTimerStops ?: SleepTimerStopStore(context.applicationContext).also { sleepTimerStops = it }
        }

    fun playbackPreferences(context: Context): PlaybackPreferences =
        playbackPreferences ?: synchronized(this) {
            playbackPreferences
                ?: PlaybackPreferences(context.applicationContext).also { playbackPreferences = it }
        }

    /** Per-fiction speed overrides. Device-local — see [FictionSpeedPreferences]. */
    fun fictionSpeedPreferences(context: Context): FictionSpeedPreferences =
        fictionSpeedPreferences ?: synchronized(this) {
            fictionSpeedPreferences
                ?: FictionSpeedPreferences(context.applicationContext)
                    .also { fictionSpeedPreferences = it }
        }

    fun downloadPreferences(context: Context): DownloadPreferences =
        downloadPreferences ?: synchronized(this) {
            downloadPreferences
                ?: DownloadPreferences(context.applicationContext).also { downloadPreferences = it }
        }

    fun chapterListPreferences(context: Context): ChapterListPreferences =
        chapterListPreferences ?: synchronized(this) {
            chapterListPreferences
                ?: ChapterListPreferences(context.applicationContext).also {
                    chapterListPreferences = it
                }
        }

    /** How the browse grid was last ordered and filtered. Device-local — see [BrowsePreferences]. */
    fun browsePreferences(context: Context): BrowsePreferences =
        browsePreferences ?: synchronized(this) {
            browsePreferences
                ?: BrowsePreferences(context.applicationContext).also { browsePreferences = it }
        }

    fun accountPreferenceSync(context: Context): AccountPreferenceSync =
        accountPreferenceSync ?: synchronized(this) {
            accountPreferenceSync ?: AccountPreferenceSync(
                repository = repository(context),
                playbackPreferences = playbackPreferences(context),
                readerPreferences = readerPreferences(context),
                chapterListPreferences = chapterListPreferences(context),
            ).also { accountPreferenceSync = it }
        }

    /**
     * Positions recorded on this device that the server has not accepted yet.
     *
     * One per process, like everything else here: the media service records into it and flushes it,
     * and two instances would each hold half the backlog and overwrite the other's file.
     */
    fun pendingProgress(context: Context): PendingProgressStore =
        pendingProgress ?: synchronized(this) {
            pendingProgress
                ?: PendingProgressStore(context.applicationContext).also { pendingProgress = it }
        }

    fun progressSync(context: Context): ProgressSync =
        progressSync ?: synchronized(this) {
            progressSync ?: ProgressSync(
                repository = repository(context),
                store = pendingProgress(context),
            ).also { progressSync = it }
        }

    fun libraryCache(context: Context): LibraryCache =
        libraryCache ?: synchronized(this) {
            libraryCache ?: LibraryCache(
                repository = repository(context),
                snapshots = LibrarySnapshotStore(context.applicationContext),
                accountKey = { historyAccountKey(tokenStore(context).current()) },
            ).also { libraryCache = it }
        }

    fun updateManager(): UpdateManager =
        updateManager ?: synchronized(this) {
            updateManager ?: UpdateManager().also { updateManager = it }
        }

    /**
     * Whether each downloaded chapter still holds the audio the server has (#109).
     *
     * Wired to the repository here rather than inside the scanner so the comparison itself stays a
     * pure function of three sets, testable without a network.
     */
    fun staleDownloads(context: Context): StaleDownloadScanner =
        staleDownloads ?: synchronized(this) {
            staleDownloads ?: StaleDownloadScanner(
                record = AudioHashRecord(context.applicationContext),
                fetchHashes = { fictionId -> repository(context).audioHashes(fictionId) },
            ).also { staleDownloads = it }
        }

    /**
     * Deliberately not created in [init]: opening the media cache scans its directory, and 0.7.0
     * taught this app what putting extra work into application startup costs. The playback service,
     * the download service and the UI all reach it through here, so there is still exactly one
     * cache and one download index per process — [OfflineDownloads] depends on that.
     */
    fun offlineDownloads(context: Context): OfflineDownloads =
        offlineDownloads ?: synchronized(this) {
            offlineDownloads ?: OfflineDownloads(
                context = context.applicationContext,
                tokenStore = tokenStore(context),
                // The server's own identity, which is what keeps one instance's downloads out of
                // another's cache entries.
                capabilities = repository(context).currentCapabilities,
                downloadPrefs = downloadPreferences(context).prefs,
                // A downloaded chapter keeps its text and cues too, so the reader works on a plane
                // rather than only on chapters that happened to be opened beforehand.
                pinReadAlong = { chapterId -> repository(context).pinReadAlong(chapterId) },
                unpinReadAlong = { chapterId -> repository(context).unpinReadAlong(chapterId) },
                // A download that is gone cannot be stale, and its recorded hash describes bytes
                // that are no longer on disk.
                forgetAudioHash = { scope, chapterId -> staleDownloads(context).forget(chapterId, scope) },
                forgetAllAudioHashes = { staleDownloads(context).clear() },
                initializeManager = initializeDownloadManager,
                replacementHash = { chapterId -> staleDownloads(context).replacementHash(chapterId) },
                replacementCompleted = { scope, chapterId, hash ->
                    staleDownloads(context).replacementCompleted(chapterId, hash, scope)
                },
                audioHashScope = { scope, legacyOwned -> staleDownloads(context).useScope(scope, legacyOwned) },
            ).also { offlineDownloads = it }
        }

    /**
     * Prevent a settings-layout test from opening Media3's real download index.
     *
     * Must be called before [offlineDownloads]. Production never calls it and keeps the default
     * true. The tests using it assert labels and navigation only; constructing a receiver-owning
     * DownloadManager there tests nothing and lets its IO startup outlive Robolectric's sandbox
     * (#248).
     */
    fun disableDownloadManagerForTest() {
        check(offlineDownloads == null || !initializeDownloadManager) { "OfflineDownloads was already created" }
        initializeDownloadManager = false
    }

    fun restoreDownloadManagerAfterTest() {
        val existing = synchronized(this) {
            check(!initializeDownloadManager) { "The real download manager was not disabled" }
            offlineDownloads.also {
                offlineDownloads = null
                initializeDownloadManager = true
            }
        }
        existing?.closeWithoutManagerForTest()
    }
}
