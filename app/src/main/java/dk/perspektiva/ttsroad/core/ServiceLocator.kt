package dk.perspektiva.ttsroad.core

import android.content.Context
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.player.PlaybackController

object ServiceLocator {
    @Volatile
    private var tokenStore: TokenStore? = null

    @Volatile
    private var repository: TtsRoadRepository? = null

    @Volatile
    private var playbackController: PlaybackController? = null

    fun init(context: Context) {
        tokenStore(context)
        repository(context)
        playbackController(context)
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
                repository = repository(context),
            ).also { playbackController = it }
        }
}

