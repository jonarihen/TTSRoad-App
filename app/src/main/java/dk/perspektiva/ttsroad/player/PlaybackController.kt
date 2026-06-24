package dk.perspektiva.ttsroad.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.media.TtsRoadMediaItems
import dk.perspektiva.ttsroad.media.TtsRoadMediaService
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    val coverImageUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercentage: Int = 0,
)

/**
 * Thin wrapper around a Media3 [MediaController] that connects to [TtsRoadMediaService].
 *
 * The actual player lives in the service, so OS media controls, the foreground notification,
 * audio focus, and Android Auto all share a single playback session. The UI only ever drives
 * the player through this controller — it never owns a player of its own.
 */
class PlaybackController(
    private val context: Context,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting: Deferred<MediaController?>? = null
    private var tickerJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishState(player)
        }
    }

    /** Eagerly establish the session connection (e.g. right after login). */
    fun connect() {
        scope.launch { controllerOrNull() }
    }

    private suspend fun controllerOrNull(): MediaController? {
        controller?.let { return it }
        val pending = connecting ?: scope.async {
            val token = SessionToken(
                context,
                ComponentName(context, TtsRoadMediaService::class.java),
            )
            val created = runCatching {
                MediaController.Builder(context, token).buildAsync().await()
            }.getOrNull()
            if (created != null) {
                controller = created
                created.addListener(listener)
                startTicker()
                publishState(created)
            }
            created
        }.also { connecting = it }
        return pending.await()
    }

    suspend fun play(chapter: ChapterSummary, fiction: FictionSummary? = chapter.fiction) {
        val serverUrl = tokenStore.current().serverUrl
        val item = TtsRoadMediaItems.chapter(chapter, fiction, serverUrl) ?: return
        val controller = controllerOrNull() ?: return
        val startPositionMs = chapter.resolvedPositionSeconds
            .takeIf { it > 0.0 }
            ?.let { (it * 1000).roundToLong() }
            ?: 0L

        controller.setMediaItem(item, startPositionMs)
        controller.prepare()
        controller.play()
        publishState(controller)
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
        publishState(controller)
    }

    fun pause() {
        val controller = controller ?: return
        controller.pause()
        publishState(controller)
    }

    fun seekTo(positionMs: Long) {
        val controller = controller ?: return
        controller.seekTo(positionMs.coerceAtLeast(0L))
        publishState(controller)
    }

    fun skipBy(deltaMs: Long) {
        val controller = controller ?: return
        val duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        controller.seekTo((controller.currentPosition + deltaMs).coerceIn(0L, duration))
        publishState(controller)
    }

    /** Stop and clear playback, e.g. on logout. */
    fun stop() {
        val controller = controller ?: return
        controller.pause()
        controller.clearMediaItems()
        _state.value = PlayerUiState()
    }

    fun release() {
        tickerJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        connecting = null
        _state.value = PlayerUiState()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                controller?.let(::publishState)
                delay(1000)
            }
        }
    }

    private fun publishState(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        _state.value = PlayerUiState(
            title = metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing",
            fictionTitle = metadata?.albumTitle?.toString(),
            coverImageUrl = metadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            hasMedia = player.currentMediaItem != null,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedPercentage = player.bufferedPercentage,
        )
    }
}
