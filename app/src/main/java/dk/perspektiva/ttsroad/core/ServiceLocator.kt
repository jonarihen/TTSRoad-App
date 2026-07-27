package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.data.LibraryCache
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.update.UpdateManager
import okhttp3.OkHttpClient

object ServiceLocator {
    @Volatile
    private var tokenStore: TokenStore? = null

    @Volatile
    private var repository: TtsRoadRepository? = null

    @Volatile
    private var playbackController: PlaybackController? = null

    @Volatile
    private var playbackHistory: PlaybackHistoryStore? = null

    @Volatile
    private var libraryCache: LibraryCache? = null

    @Volatile
    private var updateManager: UpdateManager? = null

    fun init(context: Context) {
        tokenStore(context)
        repository(context)
        playbackController(context)
        playbackHistory(context)
        libraryCache(context)
    }

    fun tokenStore(context: Context): TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
        }

    fun repository(context: Context): TtsRoadRepository =
        repository ?: synchronized(this) {
            repository ?: TtsRoadRepository(tokenStore(context)).also { repository = it }
        }

    /** The repository's authenticated OkHttp client, shared with Coil for cover art. */
    fun httpClient(context: Context): OkHttpClient = repository(context).httpClient

    fun playbackController(context: Context): PlaybackController =
        playbackController ?: synchronized(this) {
            playbackController ?: PlaybackController(
                context = context.applicationContext,
                tokenStore = tokenStore(context),
            ).also { playbackController = it }
        }

    fun playbackHistory(context: Context): PlaybackHistoryStore =
        playbackHistory ?: synchronized(this) {
            playbackHistory ?: PlaybackHistoryStore(context.applicationContext).also { playbackHistory = it }
        }

    fun libraryCache(context: Context): LibraryCache =
        libraryCache ?: synchronized(this) {
            libraryCache ?: LibraryCache(repository(context)).also { libraryCache = it }
        }

    fun updateManager(): UpdateManager =
        updateManager ?: synchronized(this) {
            updateManager ?: UpdateManager().also { updateManager = it }
        }
}

