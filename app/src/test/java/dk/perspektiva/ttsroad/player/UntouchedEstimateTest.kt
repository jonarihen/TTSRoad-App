package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.ReadAlongCue
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UntouchedEstimateTest {
    private val now = 8 * 60 * 60_000L

    /** One 15s tick per snapshot between the two ages, at [speed]x, starting at [startPos]. */
    private fun run(startAgoMin: Long, endAgoMin: Long, startPos: Long, speed: Double = 1.0): List<HistorySnapshot> {
        val t0 = now - startAgoMin * 60_000L
        return generateSequence(t0) { it + 15_000L }
            .takeWhile { it <= now - endAgoMin * 60_000L }
            .map { t ->
                HistorySnapshot(
                    timestamp = t, mediaId = "chapter:7", fictionId = 3, chapterId = 7,
                    title = "Chapter 7", fictionTitle = "Ashes",
                    positionMs = startPos + ((t - t0) * speed).toLong(),
                )
            }.toList()
    }

    @Test
    fun `a long steady stretch after a seek is dated to the seek`() {
        val awake = run(startAgoMin = 300, endAgoMin = 290, startPos = 0L)
        // The listener skips forward 5 minutes (a seek), then drifts off with the book running.
        val dozing = run(startAgoMin = 289, endAgoMin = 200, startPos = awake.last().positionMs + 300_000L)

        val result = estimateLastHeard(awake + dozing, now, sleepStop = null)!!

        assertEquals(LastHeardKind.LastTouched, result.kind)
        assertEquals(dozing.first(), result.snapshot)
        assertEquals(dozing.last().timestamp, result.stoppedAtMs)
    }

    @Test
    fun `a short session stays audio stopped`() {
        val history = run(startAgoMin = 100, endAgoMin = 90, startPos = 0L)

        assertEquals(LastHeardKind.AudioStopped, estimateLastHeard(history, now, null)!!.kind)
    }

    @Test
    fun `a pause gap breaks the stretch`() {
        val before = run(startAgoMin = 400, endAgoMin = 300, startPos = 0L)
        val after = run(startAgoMin = 250, endAgoMin = 200, startPos = before.last().positionMs)

        assertEquals(after.first(), estimateLastHeard(before + after, now, null)!!.snapshot)
    }

    @Test
    fun `speed does not matter`() {
        val history = run(startAgoMin = 200, endAgoMin = 100, startPos = 0L, speed = 1.5)

        assertEquals(history.first(), estimateLastHeard(history, now, null)!!.snapshot)
    }
}

class ExcerptAtTest {
    private val doc = ReadAlongDocument(
        text = "Hello there.  She   walked on. The end",
        paragraphs = listOf(TextSpan(0, 38)),
        cues = listOf(ReadAlongCue(TextSpan(0, 5), 0.0), ReadAlongCue(TextSpan(14, 17), 10.0)),
    )

    @Test
    fun `returns the sentence at the position with whitespace collapsed`() {
        assertEquals("She walked on.", excerptAt(doc, 11_000L))
        assertEquals("Hello there.", excerptAt(doc, 1_000L))
    }

    @Test
    fun `no timings means no excerpt`() {
        assertNull(excerptAt(doc.copy(cues = emptyList()), 1_000L))
    }

    @Test
    fun `long sentences are cut`() {
        assertEquals(10, excerptAt(doc, 11_000L, maxChars = 10)!!.length)
    }
}
