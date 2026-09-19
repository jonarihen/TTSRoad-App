package dk.perspektiva.ttsroad.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    fun start() {
        player.addListener(object : Player.Listener {
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
                refresh()
            }
        }
    }

    suspend fun refresh(endedEvent: Boolean = false) {
        val requestedQueue = queueIdentity()
        val requestedSession = sessionKey() ?: return
        val requestedItem = player.currentMediaItem
        val endedPosition = player.currentPosition.coerceAtLeast(0L)
        val endedDuration = player.duration.takeIf { it > 0 }
        mutex.withLock {
            if (sessionKey() != requestedSession || queueIdentity() != requestedQueue) return
            pendingHandoff = pendingHandoff?.takeIf {
                it.session == requestedSession && it.playlist == requestedQueue
            }
            if (endedEvent && requestedItem != null) {
                saveEndedProgress(requestedItem, endedPosition, endedDuration)
                if (sessionKey() != requestedSession || queueIdentity() != requestedQueue ||
                    player.currentMediaItem != requestedItem
                ) return
            }
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            val fictionId = player.currentMediaItem?.mediaMetadata?.extras
                ?.getInt("fiction_id")?.takeIf { it > 0 } ?: return
            if (items.any { it.mediaMetadata.extras?.getInt("fiction_id") != fictionId }) return
            val loaded = try {
                load(fictionId).distinctBy { it.mediaId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            if (sessionKey() != requestedSession || queueIdentity() != requestedQueue) return
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
            pendingHandoff = pendingHandoff?.copy(playlist = queueIdentity())
            if (!wasEnded || !player.playWhenReady || !allowContinuation()) return
            val successor = player.currentMediaItemIndex + 1
            if (successor < player.mediaItemCount) {
                player.seekTo(successor, 0L)
                player.prepare()
                return
            }
            if (!endedEvent || player.currentMediaItem != requestedItem) return
            pendingHandoff?.let {
                pendingHandoff = null
                installHandoff(it.item)
                return
            }
            val end = requestedSession to currentUid
            if (advancedEnd == end) return
            advancedEnd = end
            val beforeAdvance = queueIdentity()
            val next = try {
                advance()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return
            if (sessionKey() != requestedSession || queueIdentity() != beforeAdvance) return
            if (player.playbackState != Player.STATE_ENDED ||
                player.currentMediaItem != requestedItem || player.currentPosition != endedPosition
            ) {
                pendingHandoff = PendingHandoff(requestedSession, beforeAdvance, next)
                return
            }
            installHandoff(next)
        }
    }
}
