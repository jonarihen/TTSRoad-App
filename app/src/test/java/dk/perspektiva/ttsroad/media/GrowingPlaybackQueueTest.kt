package dk.perspektiva.ttsroad.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrowingPlaybackQueueTest {
    private fun item(id: Int, fiction: Int = 1) = MediaItem.Builder()
        .setMediaId("chapter:$id")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putInt("fiction_id", fiction)
            putInt("chapter_id", id)
        }).build()).build()

    private class QueuePlayer(items: List<MediaItem>) : SimpleBasePlayer(Looper.getMainLooper()) {
        var entries = items.map { data(it) }
        var index = 0
        var position = 321L
        var ready = true
        var state = Player.STATE_READY
        var seeks = 0
        fun publish() = invalidateState()
        fun replace(items: List<MediaItem>) {
            entries = items.map { data(it) }
            index = 0
            publish()
        }
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(entries).setCurrentMediaItemIndex(index)
            .setContentPositionMs(position)
            .setPlayWhenReady(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state).build()
        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            entries = entries.toMutableList().apply { addAll(index, mediaItems.map { data(it) }) }
            if (index <= this.index) this.index += mediaItems.size
            return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            index = mediaItemIndex
            position = positionMs
            state = Player.STATE_READY
            seeks++
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            ready = playWhenReady
            return Futures.immediateVoidFuture()
        }
        override fun handleSetMediaItems(
            mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<*> {
            entries = mediaItems.map { data(it) }
            index = startIndex.coerceAtLeast(0)
            position = startPositionMs
            state = Player.STATE_READY
            return Futures.immediateVoidFuture()
        }
        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
        companion object {
            fun data(item: MediaItem) = MediaItemData.Builder(Any()).setMediaItem(item).build()
        }
    }

    @Test fun `service loop polls while ended and listener checks local queue first`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        var loaded = listOf(item(1))
        var advances = 0
        val queue = GrowingPlaybackQueue(player, backgroundScope, { 1 }, { loaded },
            { advances++; null }, { true })
        queue.start()
        assertEquals(Player.STATE_READY, player.playbackState)
        testScheduler.runCurrent()
        player.state = Player.STATE_ENDED
        player.publish()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        testScheduler.runCurrent()
        assertEquals(1, advances)
        loaded = listOf(item(1), item(2))
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        assertEquals("chapter:2", player.currentMediaItem!!.mediaId)
        assertEquals(1, advances)
        assertEquals(1, player.seeks)
    }

    @Test fun `inserts gaps in server order without changing current position or pause`() = runTest {
        val player = QueuePlayer(listOf(item(2), item(4)))
        player.ready = false
        val queue = GrowingPlaybackQueue(player, this, { 1 },
            { listOf(item(1), item(2), item(3), item(3), item(4), item(5)) }, { null }, { true })
        queue.refresh()
        queue.refresh()
        assertEquals(listOf(1, 2, 3, 4, 5).map { "chapter:$it" }, player.entries.map { it.mediaItem.mediaId })
        assertEquals("chapter:2", player.currentMediaItem!!.mediaId)
        assertEquals(321L, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertEquals(0, player.seeks)
    }

    @Test fun `ended refresh seeks local successor before server advancement`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        player.state = Player.STATE_ENDED
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { listOf(item(1), item(2)) },
            { advances++; null }, { true })
        queue.refresh(true)
        assertEquals("chapter:2", player.currentMediaItem!!.mediaId)
        assertEquals(0L, player.currentPosition)
        assertEquals(1, player.seeks)
        assertTrue(player.playWhenReady)
        assertEquals(0, advances)
    }

    @Test fun `late availability resumes ended queue but polls never advance server`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        player.state = Player.STATE_ENDED
        var loaded = listOf(item(1))
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { loaded }, { advances++; null }, { true })
        queue.refresh(true)
        queue.refresh(true)
        queue.refresh()
        assertEquals(1, advances)
        loaded = listOf(item(1), item(2))
        queue.refresh()
        assertEquals(1, player.seeks)
        assertEquals(1, advances)
    }

    @Test fun `same fiction replacement rejects suspended response`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        val response = CompletableDeferred<List<MediaItem>>()
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { response.await() }, { null }, { true })
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh() }
        player.replace(listOf(item(1)))
        response.complete(listOf(item(1), item(2)))
        refresh.join()
        assertEquals(1, player.mediaItemCount)
    }

    @Test fun `session change rejects suspended response`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        var session = 1
        val response = CompletableDeferred<List<MediaItem>>()
        val queue = GrowingPlaybackQueue(player, this, { session }, { response.await() }, { null }, { true })
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh() }
        session++
        response.complete(listOf(item(1), item(2)))
        refresh.join()
        assertEquals(1, player.mediaItemCount)
    }

    @Test fun `pause during request and end of chapter sleep prevent continuation`() = runTest {
        for (sleep in listOf(false, true)) {
            val player = QueuePlayer(listOf(item(1)))
            player.state = Player.STATE_ENDED
            val response = CompletableDeferred<List<MediaItem>>()
            var advances = 0
            val queue = GrowingPlaybackQueue(player, this, { 1 }, { response.await() },
                { advances++; null }, { !sleep })
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
            if (!sleep) { player.ready = false; player.publish() }
            response.complete(listOf(item(1), item(2)))
            refresh.join()
            assertEquals(2, player.mediaItemCount)
            assertEquals(0, player.seeks)
            assertEquals(0, advances)
        }
    }

    @Test fun `poll and ended requests serialize and only advance once`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        val response = CompletableDeferred<List<MediaItem>>()
        var loads = 0
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 },
            { loads++; response.await() }, { advances++; null }, { true })
        val poll = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh() }
        player.state = Player.STATE_ENDED
        player.publish()
        val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
        assertEquals(1, loads)
        response.complete(listOf(item(1)))
        poll.join()
        ended.join()
        queue.refresh()
        assertEquals(1, advances)
    }

    @Test fun `replacement or logout during server advance rejects returned item`() = runTest {
        for (replace in listOf(false, true)) {
            val player = QueuePlayer(listOf(item(1)))
            player.state = Player.STATE_ENDED
            val response = CompletableDeferred<MediaItem?>()
            var session: Int? = 1
            val queue = GrowingPlaybackQueue(player, this, { session }, { listOf(item(1)) },
                { response.await() }, { true })
            val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
            if (replace) player.replace(listOf(item(1)))
            else session = null
            response.complete(item(9, 2))
            ended.join()
            assertEquals("chapter:1", player.currentMediaItem!!.mediaId)
            assertEquals(0, player.seeks)
        }
    }

    @Test fun `final captured progress flush completes before destructive advance`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        player.state = Player.STATE_ENDED
        val flushed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { listOf(item(1)) },
            { events += "advance"; null }, { true }, { captured, position, _ ->
                assertEquals("chapter:1", captured.mediaId)
                assertEquals(321L, position)
                events += "save"
                flushed.await()
                events += "flushed"
            })
        val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
        val poll = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh() }
        assertEquals(listOf("save"), events)
        flushed.complete(Unit)
        ended.join()
        poll.join()
        assertEquals(listOf("save", "flushed", "advance"), events)
    }

    @Test fun `replacement during final progress flush prevents advance`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        player.state = Player.STATE_ENDED
        val flushed = CompletableDeferred<Unit>()
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { listOf(item(1)) },
            { advances++; null }, { true }, { _, _, _ -> flushed.await() })
        val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
        player.replace(listOf(item(1)))
        flushed.complete(Unit)
        ended.join()
        assertEquals(0, advances)
    }

    @Test fun `pause or sleep during advance installs first handoff paused without another pop`() = runTest {
        for (sleep in listOf(false, true)) {
            val player = QueuePlayer(listOf(item(1)))
            player.state = Player.STATE_ENDED
            val response = CompletableDeferred<MediaItem?>()
            var allowed = true
            var advances = 0
            val queue = GrowingPlaybackQueue(player, this, { 1 },
                { fiction -> listOf(if (fiction == 1) item(1) else item(9, 2)) },
                { advances++; response.await() }, { allowed })
            val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
            assertEquals(1, advances)
            if (sleep) allowed = false else player.pause()
            response.complete(item(9, 2))
            ended.join()
            assertEquals("chapter:9", player.currentMediaItem!!.mediaId)
            assertFalse(player.playWhenReady)
            queue.refresh()
            queue.refresh(true)
            assertEquals(1, advances)
            player.play()
            assertEquals("chapter:9", player.currentMediaItem!!.mediaId)
            assertTrue(player.playWhenReady)
            assertEquals(1, advances)
        }
    }

    @Test fun `backward seek during advance retains handoff until natural completion`() = runTest {
        verifySeekDuringAdvance(1)
    }

    @Test fun `earlier chapter seek during advance retains handoff until natural completion`() = runTest {
        verifySeekDuringAdvance(0)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifySeekDuringAdvance(targetIndex: Int) {
        val chapters = listOf(item(1), item(2))
        val player = QueuePlayer(chapters)
        player.index = 1
        player.state = Player.STATE_ENDED
        val response = CompletableDeferred<MediaItem?>()
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { chapters },
            { advances++; response.await() }, { true })
        val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
        assertEquals(1, advances)
        player.seekTo(targetIndex, 100L)
        response.complete(item(9, 2))
        ended.join()
        assertEquals(chapters[targetIndex].mediaId, player.currentMediaItem!!.mediaId)
        assertEquals(100L, player.currentPosition)
        assertTrue(player.playWhenReady)
        queue.refresh()
        assertEquals(chapters[targetIndex].mediaId, player.currentMediaItem!!.mediaId)
        assertEquals(100L, player.currentPosition)
        assertEquals(1, advances)
        player.seekTo(1, 321L)
        player.state = Player.STATE_ENDED
        player.publish()
        queue.refresh(true)
        assertEquals("chapter:9", player.currentMediaItem!!.mediaId)
        assertEquals(1, advances)
    }

    @Test fun `pending handoff is discarded after playlist replacement or session change`() = runTest {
        for (replace in listOf(false, true)) {
            val player = QueuePlayer(listOf(item(1)))
            player.state = Player.STATE_ENDED
            var session = 1
            val response = CompletableDeferred<MediaItem?>()
            var advances = 0
            val queue = GrowingPlaybackQueue(player, this, { session }, { listOf(item(1)) },
                { advances++; if (advances == 1) response.await() else item(10, 2) }, { true })
            val ended = launch(start = CoroutineStart.UNDISPATCHED) { queue.refresh(true) }
            player.seekTo(0, 100L)
            response.complete(item(9, 2))
            ended.join()
            if (replace) player.replace(listOf(item(1))) else session++
            player.state = Player.STATE_ENDED
            player.publish()
            queue.refresh(true)
            assertEquals("chapter:10", player.currentMediaItem!!.mediaId)
            assertEquals(2, advances)
        }
    }

    @Test fun `expired sleep timer pauses instead of resuming`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        player.state = Player.STATE_ENDED
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { listOf(item(1), item(2)) },
            { fail("Must not advance"); null }, {
                player.ready = false
                player.publish()
                false
            })
        queue.refresh(true)
        assertFalse(player.playWhenReady)
        assertEquals(0, player.seeks)
    }

    @Test fun `mixed fiction queue is not reconciled`() = runTest {
        val player = QueuePlayer(listOf(item(1), item(2, 2)))
        val queue = GrowingPlaybackQueue(player, this, { 1 },
            { fail("Must not load"); emptyList() }, { null }, { true })
        queue.refresh()
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun `network failure leaves queue and playback untouched`() = runTest {
        val player = QueuePlayer(listOf(item(1)))
        var advances = 0
        val queue = GrowingPlaybackQueue(player, this, { 1 }, { throw java.io.IOException() },
            { advances++; null }, { true })
        queue.refresh(true)
        assertEquals(1, player.mediaItemCount)
        assertEquals(321L, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertEquals(0, advances)
    }
}
