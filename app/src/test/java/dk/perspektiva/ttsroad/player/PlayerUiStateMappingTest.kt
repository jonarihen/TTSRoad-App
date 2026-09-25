package dk.perspektiva.ttsroad.player

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mapping the player screen renders from, once a second for as long as anything is playing.
 *
 * Robolectric only for the Looper [androidx.media3.common.SimpleBasePlayer] requires; nothing here
 * touches a real player, a service, or the network. Pinned to SDK 34 like
 * [dk.perspektiva.ttsroad.MainActivityPlayerIntentTest], because Robolectric 4.16.1 tops out at 36
 * while this app targets 37.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerUiStateMappingTest {

    @Test
    fun `an empty player reads as nothing playing rather than a blank screen`() {
        val state = playerUiStateOf(FakePlayer(), emptyList())

        assertEquals("Nothing playing", state.title)
        assertFalse(state.hasMedia)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.positionMs)
        assertTrue(state.queue.isEmpty())
    }

    @Test
    fun `metadata is carried onto the screen`() {
        val player = FakePlayer(
            playlist = listOf(
                FakePlayer.item(
                    mediaId = "chapter:12",
                    title = "Chapter 12",
                    fictionTitle = "A Fiction",
                    artworkUri = "https://example.test/cover.jpg",
                ),
            ),
            playing = true,
            positionMs = 4_000L,
        )

        val state = playerUiStateOf(player, buildQueue(player))

        assertEquals("Chapter 12", state.title)
        assertEquals("A Fiction", state.fictionTitle)
        assertEquals("https://example.test/cover.jpg", state.coverImageUrl)
        assertTrue(state.hasMedia)
        assertTrue(state.isPlaying)
        assertEquals(4_000L, state.positionMs)
    }

    @Test
    fun `an unknown duration becomes zero, never a negative scrubber`() {
        // Media3 reports an unknown duration as C.TIME_UNSET, which is Long.MIN_VALUE. Letting that
        // through would render a negative-width progress bar.
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1", durationMs = 0L)),
        )

        val state = playerUiStateOf(player, buildQueue(player))

        assertEquals(0L, state.durationMs)
        assertTrue(state.durationMs >= 0L)
    }

    @Test
    fun `next and previous follow the real position in the queue`() {
        // Derived by Media3 from the playlist and index, not asserted against a stub's opinion.
        val playlist = listOf(
            FakePlayer.item("chapter:1", "One"),
            FakePlayer.item("chapter:2", "Two"),
            FakePlayer.item("chapter:3", "Three"),
        )

        val first = playerUiStateOf(FakePlayer(playlist, currentIndex = 0), emptyList())
        val middle = playerUiStateOf(FakePlayer(playlist, currentIndex = 1), emptyList())
        val last = playerUiStateOf(FakePlayer(playlist, currentIndex = 2), emptyList())

        assertTrue(first.hasNext)
        assertFalse(first.hasPrevious)
        assertTrue(middle.hasNext)
        assertTrue(middle.hasPrevious)
        assertFalse(last.hasNext)
        assertTrue(last.hasPrevious)
    }

    @Test
    fun `a chapter with no title falls back to its position, not a blank row`() {
        val player = FakePlayer(
            playlist = listOf(
                FakePlayer.item("chapter:1", title = "Named"),
                FakePlayer.item("chapter:2", title = null),
                FakePlayer.item("chapter:3", title = "   "),
            ),
        )

        val queue = buildQueue(player)

        assertEquals(listOf("Named", "Chapter 2", "Chapter 3"), queue.map { it.title })
        assertEquals(listOf("chapter:1", "chapter:2", "chapter:3"), queue.map { it.mediaId })
    }

    @Test
    fun `replacing a middle chapter refreshes published queue rows`() {
        val first = FakePlayer.item("chapter:1", "One")
        val last = FakePlayer.item("chapter:3", "Three")
        val player = FakePlayer(listOf(first, FakePlayer.item("chapter:2", "Two"), last))
        val snapshot = QueueSnapshot()
        var state = playerUiStateOf(player, snapshot.queueOf(player))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot.onEvents(events)
                state = playerUiStateOf(player, snapshot.queueOf(player))
            }
        })

        player.replacePlaylist(listOf(first, FakePlayer.item("chapter:4", "Four"), last))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("chapter:1", "chapter:4", "chapter:3"), state.queue.map { it.mediaId })
        assertEquals(listOf("One", "Four", "Three"), state.queue.map { it.title })
    }

    @Test
    fun `changing only a middle chapter title refreshes published queue rows`() {
        val first = FakePlayer.item("chapter:1", "One")
        val last = FakePlayer.item("chapter:3", "Three")
        val player = FakePlayer(listOf(first, FakePlayer.item("chapter:2", "Old"), last))
        val snapshot = QueueSnapshot()
        var state = playerUiStateOf(player, snapshot.queueOf(player))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot.onEvents(events)
                state = playerUiStateOf(player, snapshot.queueOf(player))
            }
        })

        player.replacePlaylist(listOf(first, FakePlayer.item("chapter:2", "New"), last))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("One", "New", "Three"), state.queue.map { it.title })
    }

    @Test
    fun `position ticks reuse the same queue instance`() {
        val player = FakePlayer(listOf(FakePlayer.item("chapter:1", "One")))
        val snapshot = QueueSnapshot()
        val initial = playerUiStateOf(player, snapshot.queueOf(player))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshot.onEvents(events)
            }
        })

        player.changePosition(10_000L)
        shadowOf(Looper.getMainLooper()).idle()
        val later = playerUiStateOf(player, snapshot.queueOf(player))

        assertEquals(10_000L, later.positionMs)
        assertSame(initial.queue, later.queue)
        assertSame(later.queue, snapshot.queueOf(player))
    }

    @Test
    fun `speed is reported from the player, not assumed`() {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1")),
            speed = 1.75f,
        )

        assertEquals(1.75f, playerUiStateOf(player, emptyList()).speed, 0.0001f)
    }

    @Test
    fun `a player error becomes a message, and its absence stays null`() {
        val failing = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1")),
            error = PlaybackException(
                /* message= */ "boom",
                /* cause= */ null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
        )
        val healthy = FakePlayer(playlist = listOf(FakePlayer.item("chapter:1")))

        assertNotNull(playerUiStateOf(failing, emptyList()).error)
        assertNull(playerUiStateOf(healthy, emptyList()).error)
    }

    @Test
    fun `buffered percentage stays within a percentage`() {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:1", durationMs = 100_000L)),
            positionMs = 10_000L,
            bufferedMs = 50_000L,
        )

        val percentage = playerUiStateOf(player, emptyList()).bufferedPercentage

        assertTrue("was $percentage", percentage in 0..100)
    }
}
