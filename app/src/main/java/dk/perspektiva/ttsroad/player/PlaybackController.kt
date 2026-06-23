package dk.perspektiva.ttsroad.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.media.TtsRoadMediaItems
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: String = "Nothing playing",
    val fictionTitle: String? = null,
    val isPlaying: Boolean = false,
    val hasMedia: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercentage: Int = 0,
)

private data class CurrentPlayback(
    val fictionId: Int,
    val chapterId: Int,
    val title: String,
    val fictionTitle: String?,
)

class PlaybackController(
    private val context: Context,
    private val tokenStore: TokenStore,
    private val repository: TtsRoadRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    private var player: ExoPlayer? = null
    private var authHeader: String? = null
    private var tickerJob: Job? = null
    private var progressJob: Job? = null
    private var currentPlayback: CurrentPlayback? = null

    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    suspend fun play(chapter: ChapterSummary, fiction: FictionSummary? = chapter.fiction) {
        val mediaItem = TtsRoadMediaItems.chapter(chapter, fiction) ?: return
        val session = tokenStore.current()
        val player = ensurePlayer(session.authorizationHeader)
        currentPlayback = CurrentPlayback(
            fictionId = chapter.resolvedFictionId,
            chapterId = chapter.resolvedChapterId,
            title = chapter.title,
            fictionTitle = fiction?.title ?: chapter.resolvedFictionTitle,
        )

        val startPositionMs = chapter.playback?.positionSeconds
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000).roundToLong() }
            ?: 0L

        player.setMediaItem(mediaItem, startPositionMs)
        player.prepare()
        player.play()
        startProgressTicker()
        publishState(player)
    }

    fun togglePlayPause() {
        val current = player ?: return
        if (current.isPlaying) {
            pause()
        } else {
            current.play()
            startProgressTicker()
            publishState(current)
        }
    }

    fun pause() {
        val current = player ?: return
        current.pause()
        publishState(current)
        scope.launch { saveProgress(forcePlayed = false) }
    }

    fun seekTo(positionMs: Long) {
        val current = player ?: return
        current.seekTo(positionMs.coerceAtLeast(0L))
        publishState(current)
    }

    fun skipBy(deltaMs: Long) {
        val current = player ?: return
        val duration = current.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        current.seekTo((current.currentPosition + deltaMs).coerceIn(0L, duration))
        publishState(current)
    }

    fun release() {
        tickerJob?.cancel()
        progressJob?.cancel()
        player?.release()
        player = null
        currentPlayback = null
        _state.value = PlayerUiState()
    }

    private fun ensurePlayer(header: String?): ExoPlayer {
        val existing = player
        if (existing != null && authHeader == header) return existing

        existing?.release()
        authHeader = header
        val requestHeaders = header?.let { mapOf("Authorization" to it) }.orEmpty()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { created ->
                created.addListener(
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            publishState(player)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                scope.launch { saveProgress(forcePlayed = true) }
                            }
                            publishState(created)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            publishState(created)
                        }
                    },
                )
                player = created
                startUiTicker()
            }
    }

    private fun startUiTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                player?.let(::publishState)
                delay(1000)
            }
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(15_000)
                saveProgress(forcePlayed = false)
            }
        }
    }

    private suspend fun saveProgress(forcePlayed: Boolean) {
        val current = player ?: return
        val playback = currentPlayback ?: return
        if (!current.isPlaying && !forcePlayed) return
        val duration = current.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = current.currentPosition.coerceAtLeast(0L)
        val nearComplete = duration?.let { total ->
            position >= total - 20_000L || position.toDouble() / total.toDouble() >= 0.96
        } ?: false

        runCatching {
            repository.saveProgress(
                fictionId = playback.fictionId,
                chapterId = playback.chapterId,
                positionSeconds = position / 1000.0,
                isPlayed = forcePlayed || nearComplete,
            )
        }
    }

    private fun publishState(player: Player) {
        val playback = currentPlayback
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        _state.value = PlayerUiState(
            title = playback?.title ?: player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                .ifBlank { "Nothing playing" },
            fictionTitle = playback?.fictionTitle
                ?: player.currentMediaItem?.mediaMetadata?.albumTitle?.toString(),
            isPlaying = player.isPlaying,
            hasMedia = player.currentMediaItem != null,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedPercentage = player.bufferedPercentage,
        )
    }
}

