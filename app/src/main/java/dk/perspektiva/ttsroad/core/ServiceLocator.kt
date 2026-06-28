package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.player.PlaybackController
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.update.UpdateManager

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
    private var updateManager: UpdateManager? = null

    fun init(context: Context) {
        tokenStore(context)
        repository(context)
        playbackController(context)
        playbackHistory(context)
    }

    fun tokenStore(context: Context): TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TokenStore(context.applicationContext).also { tokenStore = it }
        }

    fun repository(context: Context): TtsRoadRepository =
        repository ?: synchronized(this) {
            repository ?: TtsRoadRepository(tokenStore(context)).also { repository = it }
        }

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

    fun updateManager(): UpdateManager =
        updateManager ?: synchronized(this) {
            updateManager ?: UpdateManager().also { updateManager = it }
        }
}

