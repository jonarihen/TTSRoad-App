package dk.perspektiva.ttsroad.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.player.FileHistoryPersistence
import dk.perspektiva.ttsroad.player.PlaybackHistoryStore
import dk.perspektiva.ttsroad.player.PlayedThreshold
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitionFinishedItemTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `auto-advance leaves the previous chapter behind`() {
        assertEquals(item("chapter:1"), transitionFinishedItem(item("chapter:1"), "chapter:2"))
    }

    @Test
    fun `nothing playing means nothing to save`() {
        assertNull(transitionFinishedItem(null, "chapter:2"))
    }

    @Test
    fun `same item re-set saves nothing`() {
        assertNull(transitionFinishedItem(item("chapter:1"), "chapter:1"))
    }

    @Test
    fun `queue cleared saves the last item`() {
        assertEquals(item("chapter:1"), transitionFinishedItem(item("chapter:1"), null))
    }

    @Test
    fun `auto advance at twice speed saves duration and marks finished chapter played`() {
        val player = TransitionPlayer()
        val saves = mutableListOf<SavedProgress>()
        player.addListener(DepartingChapterProgressListener(player) { item, position, duration, completed ->
            saves += SavedProgress(item, position, duration, completed)
        })
        val lastTickPosition = player.currentPosition
        assertEquals(2f, player.playbackParameters.speed)
        assertFalse(PlayedThreshold.reached(lastTickPosition, player.duration, true))
        player.elapse(14_500L)

        player.advanceAutomatically()

        val saved = saves.single()
        assertEquals("chapter:1", saved.item.mediaId)
        assertEquals(120_000L, saved.position)
        assertEquals(120_000L, saved.duration)
        assertTrue(saved.completed)
        assertTrue(PlayedThreshold.reached(saved.position, saved.duration, true, saved.completed))
        assertEquals("chapter:2", player.currentMediaItem?.mediaId)
        assertEquals(360_000L, player.duration)
    }

    @Test
    fun `auto advance respects disabled automatic played marking`() {
        val player = TransitionPlayer()
        val saves = mutableListOf<SavedProgress>()
        player.addListener(DepartingChapterProgressListener(player) { item, position, duration, completed ->
            saves += SavedProgress(item, position, duration, completed)
        })

        player.advanceAutomatically()

        val saved = saves.single()
        assertEquals(120_000L, saved.position)
        assertFalse(PlayedThreshold.reached(saved.position, saved.duration, false, saved.completed))
    }

    @Test
    fun `manual chapter seek and next save actual outgoing position without completing`() {
        for (next in listOf(false, true)) {
            val player = TransitionPlayer()
            val saves = mutableListOf<SavedProgress>()
            player.addListener(DepartingChapterProgressListener(player) { item, position, duration, completed ->
                saves += SavedProgress(item, position, duration, completed)
            })
            player.seekTo(0, 23_456L)
            assertTrue(saves.isEmpty())
            player.elapse(2_000L)

            if (next) player.seekToNextMediaItem() else player.seekTo(1, 9_000L)

            val saved = saves.single()
            assertEquals("chapter:1", saved.item.mediaId)
            assertEquals(27_456L, saved.position)
            assertEquals(120_000L, saved.duration)
            assertFalse(saved.completed)
            assertFalse(PlayedThreshold.reached(saved.position, saved.duration, true, saved.completed))
        }
    }

    @Test
    fun `rapid next before another tick saves chapter two even while first save is suspended`() = runTest {
        val player = TransitionPlayer()
        val saves = mutableListOf<SavedProgress>()
        val finishSave = CompletableDeferred<Unit>()
        val listener = DepartingChapterProgressListener(player) { item, position, duration, completed ->
            launch {
                saves += SavedProgress(item, position, duration, completed)
                finishSave.await()
            }
        }
        player.addListener(listener)
        player.seekTo(0, 23_456L)
        player.seekToNextMediaItem()
        assertEquals("chapter:2", listener.lastProgressItem?.mediaId)
        testScheduler.runCurrent()
        assertEquals("chapter:2", listener.lastProgressItem?.mediaId)
        player.elapse(2_000L)
        player.seekToNextMediaItem()
        testScheduler.runCurrent()

        assertEquals(listOf("chapter:1", "chapter:2"), saves.map { it.item.mediaId })
        assertEquals(listOf(23_456L, 4_000L), saves.map { it.position })
        assertEquals(listOf(120_000L, 360_000L), saves.map { it.duration })
        assertEquals("chapter:3", listener.lastProgressItem?.mediaId)
        finishSave.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("chapter:3", listener.lastProgressItem?.mediaId)
    }

    @Test
    fun `clearing queue saves actual departing position with metadata duration`() {
        val player = TransitionPlayer()
        val saves = mutableListOf<SavedProgress>()
        val listener = DepartingChapterProgressListener(player) { item, position, duration, completed ->
            saves += SavedProgress(item, position, duration, completed)
        }
        player.addListener(listener)
        player.seekTo(0, 23_456L)

        player.clearMediaItems()

        val saved = saves.single()
        assertEquals("chapter:1", saved.item.mediaId)
        assertEquals(23_456L, saved.position)
        assertEquals(125_000L, saved.duration)
        assertFalse(saved.completed)
        assertNull(listener.lastProgressItem)
    }

    @Test
    fun `manual departure stays batched and queue clear flushes exact positions for restart`() = runTest {
        val player = TransitionPlayer()
        val file = File(temporaryFolder.root, "playback_history.json")
        val history = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        val listener = departingChapterProgressListener(player, this) { item, position, _, _, flushHistory ->
            recordHistory(history, item, position, testScheduler.currentTime)
            if (flushHistory) history.flush()
        }
        player.addListener(listener)
        recordHistory(history, player.currentMediaItem!!, player.currentPosition, 0L)
        player.seekTo(0, 23_456L)
        player.elapse(2_000L)

        player.seekToNextMediaItem()
        testScheduler.runCurrent()

        assertEquals(27_456L, history.snapshots.value.single().positionMs)
        assertFalse(file.exists())
        player.elapse(3_000L)
        player.clearMediaItems()
        testScheduler.runCurrent()

        val restarted = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        assertEquals(history.snapshots.value, restarted.snapshots.value)
        assertEquals(listOf("chapter:1", "chapter:2"), restarted.snapshots.value.map { it.mediaId })
        assertEquals(listOf(27_456L, 6_000L), restarted.snapshots.value.map { it.positionMs })
        assertNull(listener.lastProgressItem)
    }

    @Test
    fun `automatic completion flushes departing duration and retains played preference gating`() = runTest {
        val player = TransitionPlayer()
        val file = File(temporaryFolder.root, "playback_history.json")
        val history = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        val saves = mutableListOf<SavedProgress>()
        player.addListener(departingChapterProgressListener(player, this) { item, position, duration, completed, flushHistory ->
            saves += SavedProgress(item, position, duration, completed)
            recordHistory(history, item, position, testScheduler.currentTime)
            assertTrue(flushHistory)
            if (flushHistory) history.flush()
        })
        recordHistory(history, player.currentMediaItem!!, player.currentPosition, 0L)

        player.advanceAutomatically()
        testScheduler.runCurrent()

        val restarted = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        assertEquals("chapter:1", restarted.snapshots.value.single().mediaId)
        assertEquals(120_000L, restarted.snapshots.value.single().positionMs)
        val saved = saves.single()
        assertEquals(120_000L, saved.duration)
        assertTrue(PlayedThreshold.reached(saved.position, saved.duration, true, saved.completed))
        assertFalse(PlayedThreshold.reached(saved.position, saved.duration, false, saved.completed))
    }

    @Test
    fun `rapid departing saves cannot replace current tracking while exact history is flushed on stop`() = runTest {
        val player = TransitionPlayer()
        val file = File(temporaryFolder.root, "playback_history.json")
        val history = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        val finishSave = CompletableDeferred<Unit>()
        val listener = departingChapterProgressListener(player, this) { item, position, _, _, flushHistory ->
            recordHistory(history, item, position, testScheduler.currentTime)
            if (flushHistory) history.flush()
            finishSave.await()
        }
        player.addListener(listener)
        player.seekTo(0, 23_456L)
        player.seekToNextMediaItem()
        testScheduler.runCurrent()
        player.elapse(2_000L)
        player.seekToNextMediaItem()
        testScheduler.runCurrent()
        assertEquals("chapter:3", listener.lastProgressItem?.mediaId)
        assertFalse(file.exists())

        player.elapse(3_000L)
        player.clearMediaItems()
        testScheduler.runCurrent()
        finishSave.complete(Unit)
        testScheduler.runCurrent()

        val restarted = PlaybackHistoryStore(FileHistoryPersistence(file), this)
        assertEquals(history.snapshots.value, restarted.snapshots.value)
        assertEquals(listOf("chapter:1", "chapter:2", "chapter:3"), restarted.snapshots.value.map { it.mediaId })
        assertEquals(listOf(23_456L, 4_000L, 6_000L), restarted.snapshots.value.map { it.positionMs })
        assertNull(listener.lastProgressItem)
    }

    private fun recordHistory(history: PlaybackHistoryStore, item: MediaItem, position: Long, timestamp: Long) {
        history.record(timestamp, item.mediaId, 10, item.mediaId.substringAfter(":").toInt(), "C", "F", position)
    }

    private data class SavedProgress(
        val item: MediaItem,
        val position: Long,
        val duration: Long?,
        val completed: Boolean,
    )

    private class TransitionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var entries = listOf(120_000L, 360_000L, 240_000L).mapIndexed { index, duration ->
            val item = MediaItem.Builder().setMediaId("chapter:${index + 1}")
                .setMediaMetadata(MediaMetadata.Builder().setDurationMs(duration + 5_000L).build())
                .build()
            MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(duration * 1000L).build()
        }
        private var index = 0
        private var position = 91_000L
        private var discontinuity: Int? = null

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(entries)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(PositionSupplier.getConstant(position))
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (entries.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlaybackParameters(PlaybackParameters(2f))
            .apply { discontinuity?.let { setPositionDiscontinuity(it, position) } }
            .build()

        fun elapse(wallClockMs: Long) {
            position += (wallClockMs * playbackParameters.speed).toLong()
            invalidateState()
        }

        fun advanceAutomatically() {
            index++
            position = 0L
            discontinuity = Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            invalidateState()
            discontinuity = null
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            index = mediaItemIndex
            position = if (positionMs == C.TIME_UNSET) 0L else positionMs
            return Futures.immediateVoidFuture()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            entries = entries.toMutableList().apply { subList(fromIndex, toIndex).clear() }
            index = 0
            position = 0L
            return Futures.immediateVoidFuture()
        }
    }
}
