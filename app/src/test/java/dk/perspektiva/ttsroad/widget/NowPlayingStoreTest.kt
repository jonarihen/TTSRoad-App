package dk.perspektiva.ttsroad.widget

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one file the widget and the media service share.
 *
 * Its whole contract is that it never throws: it is read by a Glance worker in whatever process the
 * launcher woke, and written fire-and-forget from the media service's progress path. A widget that
 * cannot read its own note should show the empty state — it must not take the service down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.filesDir, "widget_now_playing.json")

    private val snapshot = NowPlayingSnapshot(
        mediaId = "chapter:10",
        fictionId = 1,
        chapterId = 10,
        chapterTitle = "Chapter 4: Powerful",
        fictionTitle = "A Test Serial",
        coverUrl = "https://example.test/cover/1.png",
        positionMs = 90_000L,
        durationMs = 600_000L,
        isPlaying = true,
        speed = 1.5f,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `nothing written reads as nothing played`() {
        file.delete()

        assertNull(NowPlayingStore(context).read())
    }

    @Test
    fun `a written snapshot comes back whole`() {
        val store = NowPlayingStore(context)

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    /** One record, overwritten. A newer note about the player always supersedes an older one. */
    @Test
    fun `the last write wins`() {
        val store = NowPlayingStore(context)
        store.write(snapshot)

        store.write(snapshot.copy(chapterTitle = "Chapter 5", isPlaying = false, updatedAt = 2L))

        val read = store.read()!!
        assertEquals("Chapter 5", read.chapterTitle)
        assertFalse(read.isPlaying)
        assertEquals(2L, read.updatedAt)
    }

    @Test
    fun `signing out removes the file rather than blanking it`() {
        val store = NowPlayingStore(context)
        store.write(snapshot)

        store.clear()

        assertFalse(file.exists())
        assertNull(store.read())
    }

    @Test
    fun `clearing what is already gone is not an error`() {
        file.delete()

        NowPlayingStore(context).clear()

        assertNull(NowPlayingStore(context).read())
    }

    /** A half-written or hand-edited file is "nothing played", never a crash on the home screen. */
    @Test
    fun `unparseable json reads as nothing played`() {
        file.writeText("{ this is not json")

        assertNull(NowPlayingStore(context).read())
    }

    /**
     * A record with no media id cannot be acted on and would draw a nameless row, so it is treated
     * as absent.
     */
    @Test
    fun `a snapshot with no media id is not a snapshot`() {
        NowPlayingStore(context).write(snapshot.copy(mediaId = ""))

        assertNull(NowPlayingStore(context).read())
    }
}
