package dk.perspektiva.ttsroad

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dk.perspektiva.ttsroad.data.ReadAlongCue
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.ReadAlongHighlight
import dk.perspektiva.ttsroad.data.TextSpan
import dk.perspektiva.ttsroad.player.ReadAlongPlaybackSample
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ReaderHighlightTest {
    @get:Rule val compose = createComposeRule()

    private val document = ReadAlongDocument(
        chapterId = 10,
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
    private val currentDocument = mutableStateOf<ReadAlongDocument?>(document)
    private val chapterId = mutableIntStateOf(10)
    private val playback = AtomicReference<ReadAlongPlaybackSample?>(null)
    private val sampleReads = AtomicInteger()
    private val sampleProvider = mutableStateOf<() -> ReadAlongPlaybackSample?>({
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        sampleReads.incrementAndGet()
        playback.get()
    })
    private var highlight = ReadAlongHighlight.None
    private var renderedDocument: ReadAlongDocument? = null
    private val renderedHighlights = mutableListOf<ReadAlongHighlight>()
    private val lifecycleOwnerState = mutableStateOf<ReaderLifecycleOwner?>(null)
    private val lifecycleOwner: ReaderLifecycleOwner get() = checkNotNull(lifecycleOwnerState.value)

    @Before
    fun freezeFrameClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `backward anchor jitter cannot flicker the production highlight`() {
        showReader(sample(3_004L))
        assertHighlightAt(3_004L)

        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)

        playback.set(sample(3_008L))
        frames()
        assertHighlightAt(3_008L)
        assertTrue(renderedHighlights.none { it.cueIndex == 2 })
    }

    @Test
    fun `held jitter expires using elapsed realtime rather than media or frame time`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)

        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(250L))
        }
        frames()
        assertHighlightAt(2_992L)
    }

    @Test
    fun `a tiny explicit seek changes the highlighted word immediately`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)

        playback.set(sample(2_992L, generation = 2L))
        frames()
        assertHighlightAt(2_992L)
    }

    @Test
    fun `atomic speed changes reset continuity without scaling media position`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L, speed = 2f))
        frames()
        assertHighlightAt(2_992L)

        playback.set(sample(1_200L, speed = 3f))
        frames()
        assertHighlightAt(1_200L)
    }

    @Test
    fun `disconnection clears the word and reconnection does not inherit a held position`() {
        showReader(sample(3_004L))
        playback.set(null)
        frames()
        assertNoHighlight()

        playback.set(sample(2_992L))
        poll()
        assertHighlightAt(2_992L)
    }

    @Test
    fun `mismatched media clears the word and polls slowly even while playing`() {
        showReader(sample(3_004L))
        playback.set(sample(3_004L, mediaId = "chapter:11"))
        frames()
        assertNoHighlight()
        val readsBefore = sampleReads.get()
        poll()
        assertTrue(sampleReads.get() - readsBefore in 1..2)
        assertNoHighlight()

        playback.set(sample(2_992L))
        poll()
        assertHighlightAt(2_992L)
    }

    @Test
    fun `paused position only service acknowledgement settles without an external state event`() {
        showReader(sample(3_004L, isPlaying = false))
        assertHighlightAt(3_004L)
        val readsBefore = sampleReads.get()

        playback.set(sample(2_992L, isPlaying = false))
        poll()

        assertHighlightAt(2_992L)
        assertTrue(sampleReads.get() - readsBefore in 1..2)
    }

    @Test
    fun `buffering accepts corrections and keeps polling until playing resumes`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L, isPlaying = false))
        frames()
        assertHighlightAt(2_992L)

        playback.set(sample(1_500L, isPlaying = false))
        poll()
        assertHighlightAt(1_500L)

        playback.set(sample(2_500L))
        poll()
        assertHighlightAt(2_500L)
    }

    @Test
    fun `no media is polled slowly and can become playable without a state event`() {
        showReader(null)
        assertNoHighlight()
        val readsBefore = sampleReads.get()
        poll()
        assertTrue(sampleReads.get() - readsBefore in 1..2)

        playback.set(sample(1_500L))
        poll()
        assertHighlightAt(1_500L)
    }

    @Test
    fun `a null document never samples playback`() {
        currentDocument.value = null
        showReader(sample(3_004L))
        assertSamplingStopped()
        assertEquals(0, sampleReads.get())
    }

    @Test
    fun `an untimed document never samples playback`() {
        currentDocument.value = document.copy(cues = emptyList())
        showReader(sample(3_004L))
        assertSamplingStopped()
        assertEquals(0, sampleReads.get())
    }

    @Test
    fun `a mismatched document never samples playback`() {
        currentDocument.value = document.copy(chapterId = 11)
        showReader(sample(3_004L))
        assertSamplingStopped()
        assertEquals(0, sampleReads.get())
    }

    @Test
    fun `losing timings stops sampling until a timed document is restored`() {
        showReader(sample(3_004L))
        assertHighlightAt(3_004L)
        compose.runOnIdle { currentDocument.value = document.copy(cues = emptyList()) }
        frames()
        assertSamplingStopped()

        playback.set(sample(2_992L))
        val readsBeforeRestore = sampleReads.get()
        compose.runOnIdle {
            Snapshot.withMutableSnapshot { currentDocument.value = document }
        }
        frames()
        assertEquals("Restoration must have recomposed", document, renderedDocument)
        assertTrue(
            "Restoring a timed document must restart sampling",
            sampleReads.get() > readsBeforeRestore,
        )
        assertHighlightAt(2_992L)
    }

    @Test
    fun `a document for another chapter never borrows matching screen media`() {
        showReader(sample(3_004L))
        compose.runOnIdle {
            renderedHighlights.clear()
            currentDocument.value = document.copy(chapterId = 11)
        }
        frames()
        assertNoHighlight()
        assertTrue(renderedHighlights.isNotEmpty())
        assertTrue(renderedHighlights.all { it == ReadAlongHighlight.None })
        assertSamplingStopped()

        playback.set(sample(3_004L, mediaId = "chapter:11"))
        frames()
        assertNoHighlight()
    }

    @Test
    fun `removing a document clears immediately and restoring it resets continuity`() {
        showReader(sample(3_004L))
        compose.runOnIdle {
            renderedHighlights.clear()
            currentDocument.value = null
        }
        frames()
        assertNoHighlight()
        assertTrue(renderedHighlights.isNotEmpty())
        assertTrue(renderedHighlights.all { it == ReadAlongHighlight.None })
        assertSamplingStopped()

        playback.set(sample(2_992L))
        val readsBeforeRestore = sampleReads.get()
        compose.runOnIdle {
            Snapshot.withMutableSnapshot { currentDocument.value = document }
        }
        frames()
        assertEquals("Restoration must have recomposed", document, renderedDocument)
        assertTrue(
            "Restoring a timed document must restart sampling",
            sampleReads.get() > readsBeforeRestore,
        )
        assertHighlightAt(2_992L)
    }

    @Test
    fun `replacement document for the same chapter resets the tracker and uses its own spans`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)
        val replacement = document.copy(
            text = "Red blue green gold.",
            paragraphs = listOf(TextSpan(0, 20)),
            cues = listOf(
                ReadAlongCue(TextSpan(0, 3), 0.0),
                ReadAlongCue(TextSpan(4, 8), 1.0),
                ReadAlongCue(TextSpan(9, 14), 2.0),
                ReadAlongCue(TextSpan(15, 20), 3.0),
            ),
        )

        compose.runOnIdle {
            renderedHighlights.clear()
            currentDocument.value = replacement
        }
        frames()

        assertHighlightAt(2_992L)
        assertTrue(renderedHighlights.isNotEmpty())
        assertTrue(renderedHighlights.all {
            it == ReadAlongHighlight.None || it == replacement.highlightAtMillis(2_992L)
        })
    }

    @Test
    fun `chapter navigation requires screen document and atomic media identity to agree`() {
        showReader(sample(3_004L))
        compose.runOnIdle { chapterId.intValue = 11 }
        frames()
        assertNoHighlight()

        compose.runOnIdle { currentDocument.value = document.copy(chapterId = 11) }
        frames()
        assertNoHighlight()

        playback.set(sample(1_500L, mediaId = "chapter:11"))
        poll()
        assertHighlightAt(1_500L)
    }

    @Test
    fun `a one second UI refresh updates the sampling lambda without restarting the tracker`() {
        showReader(sample(3_004L))
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val replacement = AtomicReference(sample(2_992L))
        val replacementReads = AtomicInteger()
        compose.runOnIdle {
            sampleProvider.value = {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                replacementReads.incrementAndGet()
                replacement.get()
            }
        }
        frames()

        assertTrue(replacementReads.get() > 0)
        assertHighlightAt(3_004L)

        replacement.set(sample(1_500L))
        frames()
        assertHighlightAt(1_500L)
    }

    @Test
    fun `playing samples exactly once per frame on the main thread`() {
        showReader(sample(1_500L))
        val readsBefore = sampleReads.get()
        frames(4)
        assertEquals(4, sampleReads.get() - readsBefore)
        assertHighlightAt(1_500L)
    }

    @Test
    fun `lifecycle below started neither samples initially nor after stopping`() {
        showReader(sample(3_004L), Lifecycle.State.CREATED)
        poll()
        assertEquals(0, sampleReads.get())
        assertNoHighlight()

        moveLifecycleTo(Lifecycle.State.STARTED)
        assertHighlightAt(3_004L)
        assertTrue(sampleReads.get() > 0)

        moveLifecycleTo(Lifecycle.State.CREATED)
        val readsAtStop = sampleReads.get()
        playback.set(sample(2_992L))
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertEquals(readsAtStop, sampleReads.get())
        assertNoHighlight()
    }

    @Test
    fun `reentering started uses a fresh tracker rather than the previous held word`() {
        showReader(sample(3_004L))
        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)

        moveLifecycleTo(Lifecycle.State.CREATED)
        assertNoHighlight()
        moveLifecycleTo(Lifecycle.State.STARTED)
        assertHighlightAt(2_992L)
    }

    @Test
    fun `replacing an active owner resets the tracker and survives destruction of the old owner`() {
        showReader(sample(3_004L))
        val oldOwner = lifecycleOwner
        playback.set(sample(2_992L))
        frames()
        assertHighlightAt(3_004L)

        compose.runOnIdle {
            lifecycleOwnerState.value = ReaderLifecycleOwner().apply {
                lifecycle.currentState = Lifecycle.State.STARTED
            }
        }
        frames()
        assertHighlightAt(2_992L)

        compose.runOnUiThread { oldOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        playback.set(sample(1_500L))
        val readsBefore = sampleReads.get()
        frames(4)
        assertEquals(4, sampleReads.get() - readsBefore)
        assertHighlightAt(1_500L)

        moveLifecycleTo(Lifecycle.State.CREATED)
        assertSamplingStopped()
    }

    @Test
    fun `replacing an active owner with a stopped owner clears and waits for the new owner`() {
        showReader(sample(3_004L))
        val oldOwner = lifecycleOwner
        compose.runOnIdle {
            renderedHighlights.clear()
            lifecycleOwnerState.value = ReaderLifecycleOwner().apply {
                lifecycle.currentState = Lifecycle.State.CREATED
            }
        }
        frames()
        assertTrue(renderedHighlights.isNotEmpty())
        assertTrue(renderedHighlights.all { it == ReadAlongHighlight.None })
        assertSamplingStopped()

        compose.runOnUiThread { oldOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        assertSamplingStopped()
        playback.set(sample(2_992L))
        moveLifecycleTo(Lifecycle.State.STARTED)
        assertHighlightAt(2_992L)
        val readsBefore = sampleReads.get()
        frames(4)
        assertEquals(4, sampleReads.get() - readsBefore)
    }

    private fun showReader(
        initialSample: ReadAlongPlaybackSample?,
        initialLifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
    ) {
        playback.set(initialSample)
        compose.runOnUiThread {
            lifecycleOwnerState.value = ReaderLifecycleOwner().apply {
                lifecycle.currentState = initialLifecycleState
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val currentHighlight = rememberReaderHighlight(
                    document = currentDocument.value,
                    chapterId = chapterId.intValue,
                    playbackSample = sampleProvider.value,
                )
                val composedDocument = currentDocument.value
                SideEffect {
                    renderedDocument = composedDocument
                    highlight = currentHighlight
                    renderedHighlights.add(currentHighlight)
                }
            }
        }
        frames()
    }

    private fun sample(
        positionMs: Long,
        mediaId: String = "chapter:10",
        isPlaying: Boolean = true,
        speed: Float = 1f,
        generation: Long = 1L,
    ) = ReadAlongPlaybackSample(mediaId, positionMs, isPlaying, speed, generation)

    private fun frames(count: Int = 2) {
        repeat(count) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun poll() {
        compose.mainClock.advanceTimeBy(80L)
        compose.waitForIdle()
    }

    private fun assertHighlightAt(positionMs: Long) {
        compose.runOnIdle {
            assertEquals(currentDocument.value!!.highlightAtMillis(positionMs), highlight)
        }
    }

    private fun assertNoHighlight() {
        compose.runOnIdle { assertEquals(ReadAlongHighlight.None, highlight) }
    }

    private fun assertSamplingStopped() {
        val readsBefore = sampleReads.get()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals(readsBefore, sampleReads.get())
        assertNoHighlight()
    }

    private fun moveLifecycleTo(state: Lifecycle.State) {
        compose.runOnUiThread { lifecycleOwner.lifecycle.currentState = state }
        frames()
    }

    private class ReaderLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
