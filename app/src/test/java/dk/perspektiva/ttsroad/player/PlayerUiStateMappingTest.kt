package dk.perspektiva.ttsroad.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
    fun `the queue key changes when the queue does, and not when only position does`() {
        // This is what stops a 246-chapter list being rebuilt every second for a whole night.
        val playlist = listOf(FakePlayer.item("chapter:1"), FakePlayer.item("chapter:2"))
        val atStart = queueKeyOf(FakePlayer(playlist, currentIndex = 0, positionMs = 0L))
        val laterSameQueue = queueKeyOf(FakePlayer(playlist, currentIndex = 1, positionMs = 90_000L))
        val differentQueue = queueKeyOf(
            FakePlayer(listOf(FakePlayer.item("chapter:9"), FakePlayer.item("chapter:10"))),
        )
        val shorterQueue = queueKeyOf(FakePlayer(listOf(FakePlayer.item("chapter:1"))))

        assertEquals(atStart, laterSameQueue)
        assertTrue(atStart != differentQueue)
        assertTrue(atStart != shorterQueue)
        assertEquals("0", queueKeyOf(FakePlayer()))
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
