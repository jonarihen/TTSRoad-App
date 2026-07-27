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

data class QueueItem(
    val mediaId: String,
    val title: String,
)

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    val coverImageUrl: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercentage: Int = 0,
    val speed: Float = 1f,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /**
     * Set while the player is stopped on an error. Clears on its own once playback recovers —
     * including when the service's automatic retry succeeds, which is the common case for a tunnel
     * or a Wi-Fi handover.
     */
    val error: String? = null,
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

    // The queue only changes when a new playlist is set, but publishState runs every second.
    // Cache the mapped list and rebuild only when the timeline's shape actually changes.
    private var cachedQueue: List<QueueItem> = emptyList()
    private var cachedQueueKey: String? = null

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

    /**
     * Play a whole fiction as a playlist, starting at [startChapterId]. Loading the full chapter
     * list (rather than a single item) is what makes next/previous, auto-advance, and the
     * jump-to-chapter list work — and they're shared with the OS controls and Android Auto.
     */
    suspend fun playQueue(
        chapters: List<ChapterSummary>,
        startChapterId: Int,
        fiction: FictionSummary? = null,
        startPositionMsOverride: Long? = null,
    ) {
        val serverUrl = tokenStore.current().serverUrl
        val built = chapters
            .filter { it.audio != null }
            .mapNotNull { chapter ->
                TtsRoadMediaItems.chapter(chapter, fiction ?: chapter.fiction, serverUrl)
                    ?.let { chapter to it }
            }
        if (built.isEmpty()) return

        val startIndex = built.indexOfFirst { it.first.resolvedChapterId == startChapterId }
            .coerceAtLeast(0)
        // A "jump back" passes the exact historical position; otherwise resume where the server says.
        val startPositionMs = startPositionMsOverride
            ?: built[startIndex].first.resolvedPositionSeconds
                .takeIf { it > 0.0 }
                ?.let { (it * 1000).roundToLong() }
            ?: 0L

        val controller = controllerOrNull() ?: return
        controller.setMediaItems(built.map { it.second }, startIndex, startPositionMs)
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
        controller.seekTo(skipTargetMs(controller.currentPosition, controller.duration, deltaMs))
        publishState(controller)
    }

    fun skipToNextChapter() {
        val controller = controller ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            publishState(controller)
        }
    }

    fun skipToPreviousChapter() {
        val controller = controller ?: return
        // Media3's seekToPrevious restarts the current chapter if we're more than a few seconds
        // in, otherwise jumps to the previous one — the usual audiobook "previous" behaviour.
        controller.seekToPrevious()
        publishState(controller)
    }

    fun skipToQueueIndex(index: Int) {
        val controller = controller ?: return
        if (index in 0 until controller.mediaItemCount) {
            controller.seekTo(index, 0L)
            publishState(controller)
        }
    }

    /**
     * Seek to a specific chapter (by media id) and position — used by "jump back" to land on
     * wherever the listener was at a chosen moment. Returns true if the chapter is in the current
     * queue; if it isn't (a different fiction is loaded), playback is left untouched.
     */
    fun seekToMediaId(mediaId: String, positionMs: Long): Boolean {
        val controller = controller ?: return false
        for (index in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(index).mediaId == mediaId) {
                controller.seekTo(index, positionMs.coerceAtLeast(0L))
                controller.play()
                publishState(controller)
                return true
            }
        }
        return false
    }

    /**
     * Re-prepare after a failure the automatic retries gave up on. Does not call play(): prepare()
     * resumes on its own when playWhenReady was set, so a stream that died while paused stays
     * paused rather than starting up unasked.
     */
    fun retry() {
        val controller = controller ?: return
        controller.prepare()
        publishState(controller)
    }

    fun setSpeed(speed: Float) {
        val controller = controller ?: return
        controller.setPlaybackSpeed(speed)
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
        val count = player.mediaItemCount
        val queueKey = if (count > 0) {
            "$count:${player.getMediaItemAt(0).mediaId}:${player.getMediaItemAt(count - 1).mediaId}"
        } else {
            "0"
        }
        val queue = if (queueKey == cachedQueueKey) {
            cachedQueue
        } else {
            val built = if (count > 0) {
                (0 until count).map { i ->
                    val item = player.getMediaItemAt(i)
                    QueueItem(
                        mediaId = item.mediaId,
                        title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                            ?: "Chapter ${i + 1}",
                    )
                }
            } else {
                emptyList()
            }
            cachedQueueKey = queueKey
            cachedQueue = built
            built
        }
        _state.value = PlayerUiState(
            title = metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing",
            fictionTitle = metadata?.albumTitle?.toString(),
            coverImageUrl = metadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            hasMedia = player.currentMediaItem != null,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedPercentage = player.bufferedPercentage,
            speed = player.playbackParameters.speed,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            // The cause does not survive the binder, so the HTTP status is unavailable here; the
            // service reads it from the real exception and handles the 401 case there.
            error = player.playerError?.let { classifyPlaybackError(it.errorCode).message },
        )
    }
}
