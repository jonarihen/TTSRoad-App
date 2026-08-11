package dk.perspektiva.ttsroad.media

import dk.perspektiva.ttsroad.data.AudioInfo
import dk.perspektiva.ttsroad.data.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Queue entries as [android.media.MediaMetadata] items for the car.
 *
 * The MediaItem contract is easy to break silently: an item missing its extras plays but never
 * saves progress, and one missing `requestMetadata.mediaUri` comes back from the binder with no
 * URI at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueMediaItemTest {

    private val entry = QueueItem(
        id = 11,
        position = 0,
        chapterId = 42,
        chapterTitle = "The Gate",
        chapterNumber = 7.0,
        fictionId = 3,
        fictionTitle = "Ashes of Aether",
        audioDuration = 1_800.0,
        positionSeconds = 120.0,
        audio = AudioInfo(url = "https://server.example.com/audio/ashes/42.mp3"),
    )

    @Test
    fun `the media id is a chapter id like any other`() {
        // An item played from the queue must be indistinguishable downstream from one played from
        // a fiction, or progress sync and queue expansion stop recognising it.
        val item = TtsRoadMediaItems.queueItem(entry)

        assertEquals("chapter:42", item?.mediaId)
    }

    @Test
    fun `the extras progress sync reads are all present`() {
        val extras = TtsRoadMediaItems.queueItem(entry)?.mediaMetadata?.extras

        assertNotNull(extras)
        assertEquals(3, extras?.getInt("fiction_id"))
        assertEquals(42, extras?.getInt("chapter_id"))
        assertEquals(7.0, extras?.getDouble("display_number") ?: 0.0, 0.001)
        assertEquals(120.0, extras?.getDouble("position_seconds") ?: 0.0, 0.001)
    }

    @Test
    fun `the playback uri is also set as request metadata`() {
        // Controllers hand items back across the binder with the playback URI stripped; the
        // request metadata is what BrowserCallback.restoreItem puts back.
        val item = TtsRoadMediaItems.queueItem(entry)

        assertNotNull(item?.requestMetadata?.mediaUri)
        assertEquals(
            item?.localConfiguration?.uri.toString(),
            item?.requestMetadata?.mediaUri.toString(),
        )
    }

    @Test
    fun `an entry with no audio is not offered`() {
        // Rather than an item that appears in the car and fails on tap.
        assertNull(TtsRoadMediaItems.queueItem(entry.copy(audio = null)))
    }

    @Test
    fun `the fiction is the subtitle, because the list crosses books`() {
        val metadata = TtsRoadMediaItems.queueItem(entry)?.mediaMetadata

        assertEquals("The Gate", metadata?.title)
        assertEquals("Ashes of Aether", metadata?.subtitle)
        assertEquals("Ashes of Aether", metadata?.albumTitle)
    }

    @Test
    fun `it is playable and not browsable`() {
        val metadata = TtsRoadMediaItems.queueItem(entry)?.mediaMetadata

        assertEquals(true, metadata?.isPlayable)
        assertEquals(false, metadata?.isBrowsable)
    }

    @Test
    fun `a duration is carried in milliseconds`() {
        assertEquals(1_800_000L, TtsRoadMediaItems.queueItem(entry)?.mediaMetadata?.durationMs)
    }

    @Test
    fun `an unstarted chapter carries no resume position`() {
        val extras = TtsRoadMediaItems.queueItem(entry.copy(positionSeconds = 0.0))
            ?.mediaMetadata
            ?.extras

        assertTrue(extras?.containsKey("position_seconds") != true)
    }

    @Test
    fun `the audio host is rewritten to the address actually signed in to`() {
        // The backend builds URLs from its own configured BASE_URL, which is not necessarily the
        // address the phone reached it on.
        val item = TtsRoadMediaItems.queueItem(entry, serverUrl = "http://10.0.2.2:8000")

        assertTrue(
            item?.localConfiguration?.uri.toString(),
            item?.localConfiguration?.uri.toString().startsWith("http://10.0.2.2:8000"),
        )
    }

    @Test
    fun `an untitled entry still has something to show`() {
        val metadata = TtsRoadMediaItems.queueItem(entry.copy(chapterTitle = null))?.mediaMetadata

        assertEquals("Chapter", metadata?.title)
    }
}
