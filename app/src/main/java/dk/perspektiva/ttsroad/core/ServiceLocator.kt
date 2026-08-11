package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.data.AccountPreferenceSync
import dk.perspektiva.ttsroad.data.ChapterListPreferences
import dk.perspektiva.ttsroad.data.DownloadPreferences
import dk.perspektiva.ttsroad.data.LibraryCache
import dk.perspektiva.ttsroad.data.LocalReaderPreferences
import dk.perspektiva.ttsroad.data.PlaybackPreferences
import dk.perspektiva.ttsroad.data.ReadAlongFileStore
import dk.perspektiva.ttsroad.data.ReaderPreferenceStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.download.OfflineDownloads
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.update.UpdateManager
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
    private var playbackPreferences: PlaybackPreferences? = null

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
    private var offlineDownloads: OfflineDownloads? = null

    @Volatile
    private var accountPreferenceSync: AccountPreferenceSync? = null

    fun init(context: Context) {
        tokenStore(context)
        // Off the main thread and fire-and-forget: it touches the keystore and DataStore, and a
        // token that has not been re-sealed yet still reads fine, so nothing needs to wait on it.
        initScope.launch { tokenStore(context).encryptStoredTokenIfNeeded() }
        repository(context)
        playbackController(context)
        playbackHistory(context)
        sleepTimer()
        playbackPreferences(context)
        readerPreferences(context)
        libraryCache(context)
    }

    fun tokenStore(context: Context): TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
        }

    fun repository(context: Context): TtsRoadRepository =
        repository ?: synchronized(this) {
            repository ?: TtsRoadRepository(
                tokenStore = tokenStore(context),
                // Read-along documents outlive the process so a chapter opened once reads offline.
                readAlongStore = ReadAlongFileStore(
                    File(context.applicationContext.filesDir, "readalong"),
                ),
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

    fun playbackPreferences(context: Context): PlaybackPreferences =
        playbackPreferences ?: synchronized(this) {
            playbackPreferences
                ?: PlaybackPreferences(context.applicationContext).also { playbackPreferences = it }
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

    fun accountPreferenceSync(context: Context): AccountPreferenceSync =
        accountPreferenceSync ?: synchronized(this) {
            accountPreferenceSync ?: AccountPreferenceSync(
                repository = repository(context),
                playbackPreferences = playbackPreferences(context),
                readerPreferences = readerPreferences(context),
                chapterListPreferences = chapterListPreferences(context),
            ).also { accountPreferenceSync = it }
        }

    fun libraryCache(context: Context): LibraryCache =
        libraryCache ?: synchronized(this) {
            libraryCache ?: LibraryCache(repository(context)).also { libraryCache = it }
        }

    fun updateManager(): UpdateManager =
        updateManager ?: synchronized(this) {
            updateManager ?: UpdateManager().also { updateManager = it }
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
            ).also { offlineDownloads = it }
        }
}
