package dk.perspektiva.ttsroad.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@androidx.annotation.OptIn(UnstableApi::class)
internal class GrowingPlaybackQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val sessionKey: () -> Any?,
    private val load: suspend (Int) -> List<MediaItem>,
    private val advance: suspend () -> MediaItem?,
    private val allowContinuation: () -> Boolean,
    private val saveEndedProgress: suspend (MediaItem, Long, Long?) -> Unit = { _, _, _ -> },
) {
    private val mutex = Mutex()
    private var advancedEnd: Pair<Any?, Any>? = null
    private data class PendingHandoff(val session: Any, val playlist: List<Any>, val item: MediaItem)
    private var pendingHandoff: PendingHandoff? = null
    private var navigationGeneration = 0L
    private var pendingNext = 0
    private val nextMutex = Mutex()
    private data class NextIntent(val session: Any, var playlist: List<Any>, var generation: Long)
    private var nextIntent: NextIntent? = null
    private data class ReconciledQueue(val playlist: List<Any>)

    val sessionPlayer: Player = object : ForwardingPlayer(player) {
        override fun seekToNextMediaItem() = requestNext { player.seekToNextMediaItem() }
        override fun seekToNext() = requestNext { player.seekToNext() }
    }

    private fun requestNext(transition: () -> Unit) {
        val requestedSession = sessionKey() ?: return
        val requestedQueue = queueIdentity()
        val intent = nextIntent?.takeIf {
            it.session == requestedSession && it.playlist == requestedQueue &&
                it.generation == navigationGeneration
        } ?: NextIntent(requestedSession, requestedQueue, navigationGeneration).also { nextIntent = it }
        pendingNext++
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                nextMutex.withLock {
                    if (sessionKey() != intent.session || navigationGeneration != intent.generation ||
                        queueIdentity() != intent.playlist || player.mediaItemCount == 0
                    ) return@withLock
                    val currentUid = player.currentTimeline.getWindow(
                        player.currentMediaItemIndex, Timeline.Window(),
                    ).uid
                    val result = withTimeoutOrNull(3_000) { reconcile(continueEnded = false) }
                    if (sessionKey() != intent.session || navigationGeneration != intent.generation ||
                        queueIdentity() != (result?.playlist ?: intent.playlist) ||
                        player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).uid != currentUid
                    ) return@withLock
                    transition()
                    intent.playlist = queueIdentity()
                    intent.generation = navigationGeneration
                }
            } finally {
                pendingNext--
                if (pendingNext == 0) nextIntent = null
            }
        }
    }

    private fun installHandoff(item: MediaItem) {
        if (!allowContinuation()) player.pause()
        val position = item.mediaMetadata.extras?.getDouble("position_seconds") ?: 0.0
        player.setMediaItem(item, (position * 1000).toLong().coerceAtLeast(0L))
        player.prepare()
    }

    private fun queueIdentity(): List<Any> {
        val timeline = player.currentTimeline
        return (0 until timeline.windowCount).map {
            timeline.getWindow(it, Timeline.Window()).uid
        }
    }

    private fun shouldPoll(): Boolean {
        if (!player.playWhenReady || player.mediaItemCount == 0) return false
        if (player.playbackState == Player.STATE_ENDED) return true
        return player.isPlaying && player.duration > 0 &&
            player.duration - player.currentPosition <= 60_000
    }

    fun start() {
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                navigationGeneration++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        refresh(endedEvent = true)
                    }
                }
            }
        })
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (shouldPoll()) refresh()
            }
        }
    }

    suspend fun refresh(endedEvent: Boolean = false) {
        reconcile(endedEvent)
    }

    private suspend fun reconcile(
        endedEvent: Boolean = false,
        continueEnded: Boolean = true,
    ): ReconciledQueue? {
        val requestedQueue = queueIdentity()
        val requestedSession = sessionKey() ?: return null
        val requestedItem = player.currentMediaItem
        val endedPosition = player.currentPosition.coerceAtLeast(0L)
        val endedDuration = player.duration.takeIf { it > 0 }
        return mutex.withLock {
            if (sessionKey() != requestedSession || queueIdentity() != requestedQueue) return null
            pendingHandoff = pendingHandoff?.takeIf {
                it.session == requestedSession && it.playlist == requestedQueue
            }
            if (endedEvent && requestedItem != null) {
                saveEndedProgress(requestedItem, endedPosition, endedDuration)
                if (sessionKey() != requestedSession || queueIdentity() != requestedQueue ||
                    player.currentMediaItem != requestedItem
                ) return null
            }
            val unchanged = ReconciledQueue(requestedQueue)
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            val fictionId = player.currentMediaItem?.mediaMetadata?.extras
                ?.getInt("fiction_id")?.takeIf { it > 0 } ?: return unchanged
            if (items.any { it.mediaMetadata.extras?.getInt("fiction_id") != fictionId }) return unchanged
            val loaded = try {
                load(fictionId).distinctBy { it.mediaId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return unchanged
            }
            if (sessionKey() != requestedSession || queueIdentity() != requestedQueue) return null
            val wasEnded = player.playbackState == Player.STATE_ENDED
            val currentUid = player.currentTimeline.getWindow(
                player.currentMediaItemIndex, Timeline.Window(),
            ).uid
            val present = items.mapTo(mutableSetOf()) { it.mediaId }
            for ((index, item) in loaded.withIndex()) {
                if (item.mediaMetadata.extras?.getInt("fiction_id") != fictionId ||
                    !present.add(item.mediaId)
                ) continue
                val following = loaded.drop(index + 1).map { it.mediaId }.toSet()
                val insertion = (0 until player.mediaItemCount)
                    .firstOrNull { player.getMediaItemAt(it).mediaId in following }
                    ?: player.mediaItemCount
                player.addMediaItem(insertion, item)
            }
            val reconciled = ReconciledQueue(queueIdentity())
            pendingHandoff = pendingHandoff?.copy(playlist = reconciled.playlist)
            if (!continueEnded || pendingNext > 0 || !wasEnded || !player.playWhenReady ||
                !allowContinuation()
            ) return reconciled
            val successor = player.currentMediaItemIndex + 1
            if (successor < player.mediaItemCount) {
                player.seekTo(successor, 0L)
                player.prepare()
                return reconciled
            }
            if (!endedEvent || player.currentMediaItem != requestedItem) return reconciled
            pendingHandoff?.let {
                pendingHandoff = null
                installHandoff(it.item)
                return reconciled
            }
            val end = requestedSession to currentUid
            if (advancedEnd == end) return reconciled
            advancedEnd = end
            val beforeAdvance = queueIdentity()
            val next = try {
                advance()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return reconciled
            if (sessionKey() != requestedSession || queueIdentity() != beforeAdvance) return null
            if (player.playbackState != Player.STATE_ENDED ||
                player.currentMediaItem != requestedItem || player.currentPosition != endedPosition
            ) {
                pendingHandoff = PendingHandoff(requestedSession, beforeAdvance, next)
                return reconciled
            }
            installHandoff(next)
            reconciled
        }
    }
}
