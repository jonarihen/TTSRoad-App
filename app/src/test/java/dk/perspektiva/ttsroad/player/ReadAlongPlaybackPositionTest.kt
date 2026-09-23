package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.ReadAlongCue
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAlongPlaybackPositionTest {
    private val tracker = ReadAlongPlaybackPosition()
    private val mediaId = "chapter:10"
    private val document = ReadAlongDocument(
        text = "One two three four.",
        paragraphs = listOf(TextSpan(0, 19)),
        cues = listOf(
            ReadAlongCue(TextSpan(0, 3), 0.0),
            ReadAlongCue(TextSpan(4, 7), 1.0),
            ReadAlongCue(TextSpan(8, 13), 2.0),
            ReadAlongCue(TextSpan(14, 19), 3.0),
        ),
        audioDurationSeconds = 4.0,
    )

    private fun sample(
        positionMs: Long,
        mediaId: String = this.mediaId,
        isPlaying: Boolean = true,
        speed: Float = 1f,
        generation: Long = 1L,
    ) = ReadAlongPlaybackSample(mediaId, positionMs, isPlaying, speed, generation)

    private fun position(sample: ReadAlongPlaybackSample?, elapsedMs: Long = 0L): Long? =
        tracker.positionMs(sample, mediaId, elapsedMs)

    @Test
    fun `a twelve millisecond anchor correction cannot flicker cue three to two to three`() {
        val raw = listOf(3_004L, 2_992L, 3_008L)
        val displayed = raw.mapIndexed { index, ms -> position(sample(ms), index * 16L)!! }

        assertEquals(listOf(3, 2, 3), raw.map { document.highlightAtMillis(it).cueIndex })
        assertEquals(listOf(3, 3, 3), displayed.map { document.highlightAtMillis(it).cueIndex })
        assertEquals(listOf(3_004L, 3_004L, 3_008L), displayed)
    }

    @Test
    fun `a real tiny backward seek changes generation and immediately changes the cue`() {
        assertEquals(3_004L, position(sample(3_004L)))
        assertEquals(3_004L, position(sample(2_992L), 16L))

        val sought = position(sample(2_992L, generation = 2L), 32L)!!

        assertEquals(2_992L, sought)
        assertEquals(2, document.highlightAtMillis(sought).cueIndex)
        assertEquals(3_002L, position(sample(3_002L, generation = 2L), 48L))
    }

    @Test
    fun `missing media clears the displayed position and all correction history`() {
        assertNull(position(null))
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertNull(position(null, 32L))
        assertEquals(2_980L, position(sample(2_980L), 48L))
    }

    @Test
    fun `a mismatched media id clears stale highlighting even while playback continues`() {
        position(sample(3_004L))
        assertNull(position(sample(3_004L, mediaId = "chapter:11"), 16L))
        assertEquals(2_992L, position(sample(2_992L), 32L))
    }

    @Test
    fun `a changed expected document cannot borrow a previous document position`() {
        position(sample(3_004L))
        assertNull(tracker.positionMs(sample(3_004L), "chapter:11", 16L))
        assertEquals(2_992L, position(sample(2_992L), 32L))
        assertEquals(
            2_980L,
            tracker.positionMs(sample(2_980L, mediaId = "chapter:11"), "chapter:11", 48L),
        )
    }

    @Test
    fun `reconnection resets a held position even when no disconnected sample was observed`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertEquals(2_980L, position(sample(2_980L, generation = 2L), 32L))
    }

    @Test
    fun `pause accepts all anchors and resume starts a new baseline`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertEquals(2_980L, position(sample(2_980L, isPlaying = false), 32L))
        assertEquals(2_968L, position(sample(2_968L, isPlaying = false), 48L))
        assertEquals(2_956L, position(sample(2_956L), 64L))
        assertEquals(2_956L, position(sample(2_944L), 80L))
    }

    @Test
    fun `buffering breaks forward playback continuity despite unchanged generation`() {
        position(sample(3_004L))
        assertEquals(2_992L, position(sample(2_992L, isPlaying = false), 16L))
        assertEquals(2_992L, position(sample(2_992L, isPlaying = false), 5_000L))
        assertEquals(2_980L, position(sample(2_980L), 5_016L))
    }

    @Test
    fun `a changed speed resets smoothing rather than stretching the old baseline`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertEquals(2_980L, position(sample(2_980L, speed = 2f), 32L))
        assertEquals(2_980L, position(sample(2_968L, speed = 2f), 48L))
        assertEquals(2_956L, position(sample(2_956L, speed = 1f), 64L))
    }

    @Test
    fun `exactly one hundred milliseconds is smoothed but one hundred and one is not`() {
        position(sample(3_100L))
        assertEquals(3_100L, position(sample(3_000L), 16L))
        assertEquals(2_999L, position(sample(2_999L), 32L))
        assertEquals(3_010L, position(sample(3_010L), 48L))
    }

    @Test
    fun `correction size is bounded relative to the display not the last raw sample`() {
        position(sample(3_100L))
        assertEquals(3_100L, position(sample(3_040L), 16L))
        assertEquals(2_990L, position(sample(2_990L), 32L))
        assertEquals(3_000L, position(sample(3_000L), 48L))
    }

    @Test
    fun `large backward corrections are immediate and never retain a global maximum`() {
        position(sample(30_000L))
        assertEquals(4_000L, position(sample(4_000L), 16L))
        assertEquals(4_100L, position(sample(4_100L), 32L))
        assertEquals(4_100L, position(sample(4_050L), 48L))
        assertEquals(4_200L, position(sample(4_200L), 64L))
    }

    @Test
    fun `a stalled estimate is held for less than two hundred and fifty milliseconds`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 10L))
        assertEquals(3_004L, position(sample(2_992L), 259L))
        assertEquals(2_992L, position(sample(2_992L), 260L))
        assertEquals(2_992L, position(sample(2_992L), 10_000L))
        assertEquals(2_996L, position(sample(2_996L), 10_016L))
    }

    @Test
    fun `partial recovery and touching the displayed position cannot extend a hold`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 10L))
        assertEquals(3_004L, position(sample(2_998L), 150L))
        assertEquals(3_004L, position(sample(3_004L), 200L))
        assertEquals(2_992L, position(sample(2_992L), 260L))
    }

    @Test
    fun `forward advancement permits a fresh bounded correction window`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 10L))
        assertEquals(3_020L, position(sample(3_020L), 200L))
        assertEquals(3_020L, position(sample(3_008L), 250L))
        assertEquals(3_020L, position(sample(3_008L), 499L))
        assertEquals(3_008L, position(sample(3_008L), 500L))
    }

    @Test
    fun `skip silence moves forward immediately even during a held correction`() {
        position(sample(100L))
        assertEquals(100L, position(sample(88L), 16L))
        val skipped = position(sample(3_500L), 32L)!!

        assertEquals(3_500L, skipped)
        assertEquals(3, document.highlightAtMillis(skipped).cueIndex)
    }

    @Test
    fun `high speed neither multiplies positions nor the wall time hold bound`() {
        assertEquals(1_000L, position(sample(1_000L, speed = 3f)))
        assertEquals(4_000L, position(sample(4_000L, speed = 3f), 1_000L))
        assertEquals(4_000L, position(sample(3_950L, speed = 3f), 1_010L))
        assertEquals(4_000L, position(sample(3_950L, speed = 3f), 1_259L))
        assertEquals(3_950L, position(sample(3_950L, speed = 3f), 1_260L))
    }

    @Test
    fun `sampling less often expires a correction at the next sample`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertEquals(2_992L, position(sample(2_992L), 5_000L))
    }

    @Test
    fun `a new document tracker has no inherited position or hold deadline`() {
        position(sample(3_004L))
        assertEquals(3_004L, position(sample(2_992L), 16L))
        assertEquals(2_980L, ReadAlongPlaybackPosition().positionMs(sample(2_980L), mediaId, 32L))
    }
}
