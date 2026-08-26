package dk.perspektiva.ttsroad.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's judgement, which is all of the interesting part (#150).
 *
 * None of this needs a launcher, a Glance runtime or a device: everything the composable draws is
 * decided here, from a stored note and a clock. That split is the point — the part that could only
 * be checked by hand is thin, and the part that decides whether a pause button appears for audio
 * that stopped at 2am is checked here.
 */
class WidgetPresentationTest {

    private val base = NowPlayingSnapshot(
        mediaId = "chapter:10",
        fictionId = 1,
        chapterId = 10,
        chapterTitle = "Chapter 4: Powerful",
        fictionTitle = "A Test Serial",
        coverUrl = "https://example.test/cover/1.png",
        positionMs = 60_000L,
        durationMs = 600_000L,
        isPlaying = true,
        speed = 1f,
        updatedAt = 1_000_000L,
    )

    @Test
    fun `signed out never shows the previous account's book`() {
        val view = widgetView(base, signedIn = false, now = base.updatedAt)

        assertEquals(WidgetView.SignedOut, view)
    }

    @Test
    fun `signed out wins even with no snapshot`() {
        assertEquals(WidgetView.SignedOut, widgetView(null, signedIn = false, now = 0L))
    }

    @Test
    fun `no snapshot is nothing played, not an error`() {
        assertEquals(WidgetView.NothingPlayed, widgetView(null, signedIn = true, now = 0L))
    }

    @Test
    fun `a snapshot with no chapter title is nothing played`() {
        val view = widgetView(base.copy(chapterTitle = "  "), signedIn = true, now = base.updatedAt)

        assertEquals(WidgetView.NothingPlayed, view)
    }

    @Test
    fun `a fresh playing snapshot is playing`() {
        val view = widgetView(base, signedIn = true, now = base.updatedAt + 5_000L)

        val playback = view as WidgetView.Playback
        assertTrue(playback.isPlaying)
        assertFalse(playback.wentQuiet)
        assertEquals("A Test Serial", playback.fictionTitle)
        assertEquals("Chapter 4: Powerful", playback.chapterTitle)
        assertEquals("https://example.test/cover/1.png", playback.coverUrl)
    }

    /**
     * The bug this whole staleness rule exists to prevent: a widget showing a moving progress bar
     * and a pause button for audio that stopped when the process was reaped. A tap on that does the
     * opposite of what it looks like.
     */
    @Test
    fun `a playing snapshot older than the threshold went quiet`() {
        val view = widgetView(base, signedIn = true, now = base.updatedAt + StalePlayingThresholdMs + 1)

        val playback = view as WidgetView.Playback
        assertFalse(playback.isPlaying)
        assertTrue(playback.wentQuiet)
    }

    @Test
    fun `exactly at the threshold is still believed`() {
        val view = widgetView(base, signedIn = true, now = base.updatedAt + StalePlayingThresholdMs)

        assertTrue((view as WidgetView.Playback).isPlaying)
    }

    /** A paused note never goes stale — a paused player has not moved, however long ago it stopped. */
    @Test
    fun `an old paused snapshot is paused, not quiet`() {
        val snapshot = base.copy(isPlaying = false)

        val view = widgetView(snapshot, signedIn = true, now = snapshot.updatedAt + 86_400_000L)

        val playback = view as WidgetView.Playback
        assertFalse(playback.isPlaying)
        assertFalse(playback.wentQuiet)
        assertEquals(10, playback.progressPercent)
    }

    @Test
    fun `no duration means no progress and no countdown`() {
        val view = widgetView(base.copy(durationMs = 0L), signedIn = true, now = base.updatedAt)

        val playback = view as WidgetView.Playback
        assertNull(playback.progressPercent)
        assertNull(playback.remainingLabel)
    }

    @Test
    fun `a blank fiction title is absent rather than empty`() {
        val view = widgetView(base.copy(fictionTitle = "   "), signedIn = true, now = base.updatedAt)

        assertNull((view as WidgetView.Playback).fictionTitle)
    }

    @Test
    fun `a blank cover url is absent rather than fetched`() {
        val view = widgetView(base.copy(coverUrl = ""), signedIn = true, now = base.updatedAt)

        assertNull((view as WidgetView.Playback).coverUrl)
    }

    @Test
    fun `progress and remaining follow the extrapolated position`() {
        // 60s in, 30s of wall clock at 1x, of a 600s chapter.
        val view = widgetView(base, signedIn = true, now = base.updatedAt + 30_000L)

        val playback = view as WidgetView.Playback
        assertEquals(15, playback.progressPercent)
        assertEquals("8m left", playback.remainingLabel)
    }

    // --- estimatedPositionMs ---

    @Test
    fun `a paused snapshot does not advance`() {
        val snapshot = base.copy(isPlaying = false)

        assertEquals(60_000L, estimatedPositionMs(snapshot, snapshot.updatedAt + 60_000L))
    }

    /**
     * Someone listening at 1.75x covers twenty-six seconds of a chapter in fifteen of wall clock.
     * A bar that assumed 1.0x would fall behind by nearly half.
     */
    @Test
    fun `extrapolation is scaled by playback speed`() {
        val snapshot = base.copy(speed = 2f)

        assertEquals(80_000L, estimatedPositionMs(snapshot, snapshot.updatedAt + 10_000L))
    }

    @Test
    fun `a nonsense speed falls back to real time`() {
        val snapshot = base.copy(speed = 0f)

        assertEquals(70_000L, estimatedPositionMs(snapshot, snapshot.updatedAt + 10_000L))
    }

    @Test
    fun `extrapolation stops at the end of the chapter`() {
        // 60s in, a 100s chapter, and a full freshness window of wall clock to overrun it with.
        val snapshot = base.copy(durationMs = 100_000L)

        val position = estimatedPositionMs(snapshot, snapshot.updatedAt + StalePlayingThresholdMs)

        assertEquals(100_000L, position)
    }

    /** A stale note stopped at an unknown moment, so its recorded position is the last known truth. */
    @Test
    fun `a stale snapshot does not keep advancing`() {
        val now = base.updatedAt + StalePlayingThresholdMs + 60_000L

        assertEquals(60_000L, estimatedPositionMs(base, now))
    }

    /** A clock that went backwards must not rewind the bar or produce a negative position. */
    @Test
    fun `a now before the snapshot does not go backwards`() {
        assertEquals(60_000L, estimatedPositionMs(base, base.updatedAt - 500_000L))
    }

    @Test
    fun `no duration still extrapolates, with no ceiling to clamp to`() {
        val snapshot = base.copy(durationMs = 0L)

        assertEquals(70_000L, estimatedPositionMs(snapshot, snapshot.updatedAt + 10_000L))
    }

    // --- remainingLabel ---

    @Test
    fun `remaining is coarse and human`() {
        assertEquals("finished", remainingLabel(0L))
        assertEquals("finished", remainingLabel(-5_000L))
        assertEquals("under a minute left", remainingLabel(30_000L))
        assertEquals("1m left", remainingLabel(90_000L))
        assertEquals("59m left", remainingLabel(59 * 60_000L))
        assertEquals("1h left", remainingLabel(60 * 60_000L))
        assertEquals("1h 12m left", remainingLabel(72 * 60_000L))
        assertEquals("3h 20m left", remainingLabel(200 * 60_000L))
    }
}
