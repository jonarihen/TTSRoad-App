package dk.perspektiva.ttsroad.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpandedQueueStartPositionTest {
    @Test
    fun `jump back keeps historical position for a one playable chapter fiction`() {
        val queue = expandedQueue()

        val result = queue.withRequestedStartPosition(12_345L)

        assertEquals(12_345L, result.startPositionMs)
        assertEquals(queue.mediaItems, result.mediaItems)
        assertEquals(0, result.startIndex)
    }

    @Test
    fun `explicit zero starts at the beginning instead of server progress`() {
        val result = expandedQueue().withRequestedStartPosition(0L)

        assertEquals(0L, result.startPositionMs)
    }

    @Test
    fun `unset position retains server resume position`() {
        val result = expandedQueue().withRequestedStartPosition(C.TIME_UNSET)

        assertEquals(90_500L, result.startPositionMs)
    }

    @Test
    fun `explicit position preserves expanded queue and selected chapter index`() {
        val queue = expandedQueue(chapterCount = 3, startIndex = 1)

        val result = queue.withRequestedStartPosition(12_345L)

        assertEquals(queue.mediaItems, result.mediaItems)
        assertEquals(1, result.startIndex)
        assertEquals(12_345L, result.startPositionMs)
    }

    private fun expandedQueue(
        chapterCount: Int = 1,
        startIndex: Int = 0,
    ) = MediaSession.MediaItemsWithStartPosition(
        (1..chapterCount).map { MediaItem.Builder().setMediaId("chapter:$it").build() },
        startIndex,
        90_500L,
    )
}
