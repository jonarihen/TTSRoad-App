package dk.perspektiva.ttsroad.widget

import android.os.Bundle
import androidx.media3.common.C
import dk.perspektiva.ttsroad.player.FakePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What gets written down for the launcher, taken from a real Media3 [androidx.media3.common.Player].
 *
 * [FakePlayer] is a [androidx.media3.common.SimpleBasePlayer] rather than a stub on purpose: an
 * unresolved duration really is [C.TIME_UNSET] and not zero, and a hand-rolled fake would happily
 * report whatever made the test pass. Robolectric only for the Looper `SimpleBasePlayer` needs;
 * pinned to SDK 34 because Robolectric 4.16.1 tops out at 36 while this app targets 37.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingSnapshotTest {

    private fun extras(fictionId: Int, chapterId: Int) = Bundle().apply {
        putInt("fiction_id", fictionId)
        putInt("chapter_id", chapterId)
    }

    @Test
    fun `an empty player has nothing worth writing down`() {
        assertNull(nowPlayingSnapshotOf(FakePlayer(), isPlaying = false, updatedAt = 1L))
    }

    @Test
    fun `the snapshot carries what the launcher draws`() {
        val player = FakePlayer(
            playlist = listOf(
                FakePlayer.item(
                    mediaId = "chapter:10",
                    title = "Chapter 4: Powerful",
                    fictionTitle = "A Test Serial",
                    artworkUri = "https://example.test/cover/1.png",
                    durationMs = 600_000L,
                    extras = extras(fictionId = 1, chapterId = 10),
                ),
            ),
            positionMs = 90_000L,
            speed = 1.5f,
        )

        val snapshot = nowPlayingSnapshotOf(player, isPlaying = true, updatedAt = 42L)!!

        assertEquals("chapter:10", snapshot.mediaId)
        assertEquals(1, snapshot.fictionId)
        assertEquals(10, snapshot.chapterId)
        assertEquals("Chapter 4: Powerful", snapshot.chapterTitle)
        assertEquals("A Test Serial", snapshot.fictionTitle)
        assertEquals("https://example.test/cover/1.png", snapshot.coverUrl)
        assertEquals(90_000L, snapshot.positionMs)
        assertEquals(600_000L, snapshot.durationMs)
        assertTrue(snapshot.isPlaying)
        assertEquals(1.5f, snapshot.speed, 0.001f)
        assertEquals(42L, snapshot.updatedAt)
    }

    /**
     * The first second of a chapter, before the duration resolves. `C.TIME_UNSET` is a large
     * negative sentinel; writing it through would give the widget a nonsense countdown rather than
     * no countdown.
     */
    @Test
    fun `an unresolved duration is stored as zero, not as the sentinel`() {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:10", title = "Chapter", durationMs = 0L)),
        )

        val snapshot = nowPlayingSnapshotOf(player, isPlaying = false, updatedAt = 1L)!!

        assertEquals(C.TIME_UNSET, player.duration)
        assertEquals(0L, snapshot.durationMs)
    }

    @Test
    fun `a chapter with no title still names itself`() {
        val player = FakePlayer(playlist = listOf(FakePlayer.item("chapter:10")))

        assertEquals("Chapter", nowPlayingSnapshotOf(player, isPlaying = false, updatedAt = 1L)!!.chapterTitle)
    }

    /**
     * An item that browsed in without the progress extras — the ids are what a later "resume this"
     * would need, and zero is the honest answer rather than a fabricated id.
     */
    @Test
    fun `missing extras read as no ids rather than throwing`() {
        val player = FakePlayer(playlist = listOf(FakePlayer.item("chapter:10", title = "Chapter")))

        val snapshot = nowPlayingSnapshotOf(player, isPlaying = false, updatedAt = 1L)!!

        assertEquals(0, snapshot.fictionId)
        assertEquals(0, snapshot.chapterId)
        assertNull(snapshot.fictionTitle)
        assertNull(snapshot.coverUrl)
    }

    @Test
    fun `the caller decides whether this counts as playing`() {
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item("chapter:10", title = "Chapter")),
            playing = false,
        )

        assertTrue(nowPlayingSnapshotOf(player, isPlaying = true, updatedAt = 1L)!!.isPlaying)
    }
}
