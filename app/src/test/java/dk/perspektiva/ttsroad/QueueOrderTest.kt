package dk.perspektiva.ttsroad

import dk.perspektiva.ttsroad.data.QueueItem
import dk.perspektiva.ttsroad.data.QueueWhenEmptyContinue
import dk.perspektiva.ttsroad.data.QueueWhenEmptyStop
import dk.perspektiva.ttsroad.data.sanitizeQueueWhenEmpty
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moving a row in the Up Next queue (#108).
 *
 * The arithmetic is small and the consequence of getting it wrong is not: the endpoint takes the
 * *complete* order, so an off-by-one does not fail — it silently rewrites the whole queue into
 * something nobody asked for, in a list shared with the browser and with Android Auto.
 */
class QueueOrderTest {
    private fun queue(vararg ids: Int) = ids.map { QueueItem(id = it, chapterId = it * 10) }

    @Test
    fun `moving up swaps with the row above`() {
        assertEquals(listOf(11, 12, 13), queue(12, 11, 13).moveItem(index = 1, offset = -1))
    }

    @Test
    fun `moving down swaps with the row below`() {
        assertEquals(listOf(12, 11, 13), queue(11, 12, 13).moveItem(index = 0, offset = 1))
    }

    /**
     * The screen disables the arrow at each end, but the guard lives here too. A move that would
     * fall off the list must return the order unchanged rather than throwing — a crash on the queue
     * screen would be a worse answer to "you pressed a button that does nothing".
     */
    @Test
    fun `a move off either end leaves the order alone`() {
        assertEquals(listOf(11, 12), queue(11, 12).moveItem(index = 0, offset = -1))
        assertEquals(listOf(11, 12), queue(11, 12).moveItem(index = 1, offset = 1))
        assertEquals(listOf(11, 12), queue(11, 12).moveItem(index = 7, offset = -1))
    }

    /** Row ids, never chapter ids: the two are different numbers and only one of them reorders. */
    @Test
    fun `the order is row ids`() {
        assertEquals(listOf(12, 11), queue(11, 12).moveItem(index = 1, offset = -1))
    }

    @Test
    fun `an empty queue has no order to send`() {
        assertEquals(emptyList<Int>(), emptyList<QueueItem>().moveItem(index = 0, offset = 1))
    }
}

/** The account's `queue_when_empty`, and the refusal to guess at a value this build does not know. */
class QueueWhenEmptyTest {
    @Test
    fun `both declared values survive a round trip`() {
        assertEquals(QueueWhenEmptyStop, sanitizeQueueWhenEmpty("stop"))
        assertEquals(QueueWhenEmptyContinue, sanitizeQueueWhenEmpty("continue"))
    }

    @Test
    fun `spelling is forgiven the way the server forgives it`() {
        assertEquals(QueueWhenEmptyContinue, sanitizeQueueWhenEmpty(" Continue "))
    }

    /**
     * Anything else reads as stop — the server's own default, and the conservative half of the
     * pair. A newer server's third option shows as STOP rather than as a blank control; what it
     * must never do is show as CONTINUE, which would describe behaviour the account did not ask for.
     */
    @Test
    fun `an unknown or missing value reads as stop`() {
        assertEquals(QueueWhenEmptyStop, sanitizeQueueWhenEmpty(null))
        assertEquals(QueueWhenEmptyStop, sanitizeQueueWhenEmpty(""))
        assertEquals(QueueWhenEmptyStop, sanitizeQueueWhenEmpty("shuffle"))
    }
}
