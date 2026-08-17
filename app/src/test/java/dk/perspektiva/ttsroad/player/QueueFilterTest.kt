package dk.perspektiva.ttsroad.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player queue sheet's find-a-chapter field.
 *
 * The thing actually worth testing is that filtering never renumbers the queue: the index a row
 * carries is both its label and what [PlaybackController.skipToQueueIndex] is handed, so losing it
 * would play the wrong chapter from a list that looked correct.
 */
class QueueFilterTest {

    private val queue = listOf(
        QueueItem(mediaId = "chapter:1", title = "The Gate Opens"),
        QueueItem(mediaId = "chapter:2", title = "Ashes"),
        QueueItem(mediaId = "chapter:3", title = "Interlude: The Lighthouse"),
        QueueItem(mediaId = "chapter:4", title = "A Long Walk"),
        QueueItem(mediaId = "chapter:5", title = "The Gate Closes"),
    )

    @Test
    fun `an empty query lists the whole queue in order`() {
        val rows = queue.queueRows("")

        assertEquals(queue, rows.map { it.item })
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.index })
    }

    @Test
    fun `a filtered row keeps its position in the unfiltered queue`() {
        // "The Gate Closes" is the fifth entry. After filtering it is the second row on screen, but
        // it must still report index 4 — that is what gets played and what the row is numbered with.
        val rows = queue.queueRows("gate")

        assertEquals(listOf(0, 4), rows.map { it.index })
        assertEquals(listOf("The Gate Opens", "The Gate Closes"), rows.map { it.item.title })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf(2), queue.queueRows("LIGHTHOUSE").map { it.index })
    }

    @Test
    fun `the position is matched as it is shown, one-based`() {
        // The sheet labels the first row "01", so typing 1 finds it — index 0 is an implementation
        // detail the user never sees.
        assertEquals(listOf(0), queue.queueRows("1").map { it.index })
        assertEquals(listOf(4), queue.queueRows("5").map { it.index })
    }

    @Test
    fun `a query matching nothing gives no rows`() {
        assertEquals(emptyList<QueueRow>(), queue.queueRows("nothing here"))
    }

    @Test
    fun `an empty queue stays empty whatever is typed`() {
        assertEquals(emptyList<QueueRow>(), emptyList<QueueItem>().queueRows("gate"))
        assertEquals(emptyList<QueueRow>(), emptyList<QueueItem>().queueRows(""))
    }

    @Test
    fun `a long queue matches a position by prefix`() {
        // 200 entries, titled so nothing matches by text and the position is the only thing being
        // tested: typing "17" narrows to 17 and the 170s rather than every position containing a
        // 17. Same reasoning as the fiction screen's number match.
        val long = (1..200).map { QueueItem(mediaId = "chapter:$it", title = "A chapter") }

        val positions = long.queueRows("17").map { it.index + 1 }

        assertEquals(listOf(17) + (170..179).toList(), positions)
    }

    @Test
    fun `a number in the title still matches as text`() {
        // The number match is a prefix, but the title match is a substring — so a queue whose rows
        // are literally named "Chapter 117" still turns up when someone types 17.
        val numbered = (1..200).map { QueueItem(mediaId = "chapter:$it", title = "Chapter $it") }

        val positions = numbered.queueRows("117").map { it.index + 1 }

        assertEquals(listOf(117), positions)
    }
}
