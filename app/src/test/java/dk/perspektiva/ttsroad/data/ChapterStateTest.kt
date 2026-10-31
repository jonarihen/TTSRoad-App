package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter fields that say why a chapter is not playable.
 *
 * The server has always sent `sub_status`, `tts_progress`, `error_message` and `excluded`;
 * [ChapterSummary] did not declare them, so Moshi dropped all four and every unplayable chapter
 * rendered as the bare word its `status` happened to hold. The decode assertions here are the
 * point: a field that is not declared fails silently rather than loudly, so "it parses" is the
 * thing worth pinning down.
 */
class ChapterStateTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ChapterSummary::class.java)

    private fun decode(json: String): ChapterSummary =
        requireNotNull(adapter.fromJson(json)) { "adapter returned null for $json" }

    @Test
    fun `decodes the four fields the client used to drop`() {
        val chapter = decode(
            """
            {
              "id": 41,
              "title": "The Lighthouse",
              "status": "error",
              "sub_status": null,
              "tts_progress": 0,
              "error_message": "Chapter is locked behind a pledge tier",
              "excluded": false,
              "playable": false
            }
            """.trimIndent(),
        )

        assertEquals("error", chapter.status)
        assertEquals("Chapter is locked behind a pledge tier", chapter.errorMessage)
        assertEquals(0, chapter.ttsProgress)
        assertNull(chapter.subStatus)
        assertFalse(chapter.excluded)
    }

    @Test
    fun `a server that sends none of them still decodes`() {
        // The whole point of the defaults: an older server omits these keys entirely, and a
        // required field would fail the whole payload rather than one chapter.
        val chapter = decode("""{"id": 7, "title": "Chapter 7", "status": "done", "playable": true}""")

        assertNull(chapter.subStatus)
        assertNull(chapter.ttsProgress)
        assertNull(chapter.errorMessage)
        assertFalse(chapter.excluded)
        assertFalse(chapter.hasError)
    }

    @Test
    fun `a converting chapter reports how far along it is`() {
        val chapter = ChapterSummary(
            id = 1,
            status = ChapterStatus.Processing,
            subStatus = ChapterSubStatus.Converting,
            ttsProgress = 62,
        )

        assertEquals("getting ready 62%", chapter.statusLabel)
        assertEquals("Status: converting 62%\nStage: converting", chapter.statusDetail)
        assertFalse(chapter.hasError)
    }

    @Test
    fun `converting without a percentage does not invent one`() {
        val chapter = ChapterSummary(
            id = 1,
            status = ChapterStatus.Processing,
            subStatus = ChapterSubStatus.Converting,
        )

        assertEquals("getting ready…", chapter.statusLabel)
        assertEquals("Status: converting\nStage: converting", chapter.statusDetail)
    }

    @Test
    fun `the pipeline stages read as getting ready, with their own names behind details`() {
        // "PENDING" reads like a payment problem to a listener who has never seen the backend (#488).
        val fetching = ChapterSummary(
            id = 1,
            status = ChapterStatus.Processing,
            subStatus = ChapterSubStatus.FetchingHtml,
        )
        val cleaning = ChapterSummary(
            id = 1,
            status = ChapterStatus.Processing,
            subStatus = ChapterSubStatus.Preprocessing,
        )

        assertEquals("getting ready…", fetching.statusLabel)
        assertEquals("getting ready…", cleaning.statusLabel)
        assertTrue(fetching.statusDetail.startsWith("Status: fetching"))
        assertTrue(cleaning.statusDetail.startsWith("Status: cleaning"))
        assertEquals("Not ready to listen yet - it is still being prepared.", fetching.statusSentence)
    }

    @Test
    fun `a failure is worded for the listener and keeps the server's reason behind details`() {
        val chapter = ChapterSummary(id = 1, status = ChapterStatus.Error, errorMessage = "boom")

        assertEquals("couldn't prepare", chapter.statusLabel)
        assertEquals("Couldn't prepare this chapter.", chapter.statusSentence)
        assertEquals("Status: failed\nServer said: boom", chapter.statusDetail)
        assertTrue(chapter.hasError)
    }

    @Test
    fun `an excluded chapter says so rather than reporting its underlying status`() {
        // Excluded wins over everything: a chapter can be excluded *and* done, and "done" would be
        // the misleading half — it is not in the feed, the counts or the player either way.
        val chapter = ChapterSummary(id = 1, status = ChapterStatus.Done, excluded = true)

        assertEquals("skipped", chapter.statusLabel)
        assertEquals("Status: excluded", chapter.statusDetail)
    }

    @Test
    fun `a queued chapter reads as getting ready and its raw status stays pending`() {
        assertEquals("getting ready…", ChapterSummary(id = 1, status = ChapterStatus.Pending).statusLabel)
        assertEquals("getting ready…", ChapterSummary(id = 1).statusLabel)
        assertEquals("Status: pending", ChapterSummary(id = 1).statusDetail)
    }

    @Test
    fun `a playable chapter has nothing to explain`() {
        val chapter = ChapterSummary(id = 1, status = ChapterStatus.Done, audio = AudioInfo(url = "https://x/a.mp3"))

        assertEquals(null, chapter.statusSentence)
    }

    @Test
    fun `an unrecognised sub status falls back to the coarse status`() {
        // Additive by contract: a newer server may add a stage this build has never heard of. The
        // listener sees "getting ready"; the details name what the server actually sent.
        val chapter = ChapterSummary(
            id = 1,
            status = ChapterStatus.Processing,
            subStatus = "transcoding_to_opus",
        )

        assertEquals("getting ready…", chapter.statusLabel)
        assertEquals("Status: processing\nStage: transcoding_to_opus", chapter.statusDetail)
    }
}
