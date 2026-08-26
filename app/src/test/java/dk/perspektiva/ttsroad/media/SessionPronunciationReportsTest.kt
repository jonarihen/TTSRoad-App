package dk.perspektiva.ttsroad.media

import android.os.Bundle
import androidx.media3.session.SessionError
import dk.perspektiva.ttsroad.data.PronunciationReport
import dk.perspektiva.ttsroad.data.ReadAlongCue
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.TextSpan
import dk.perspektiva.ttsroad.player.FakePlayer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/**
 * What a "that word is wrong" press from the car, the notification or the player resolves to (#125).
 *
 * The interesting parts are all here rather than in the service: what the press captures off the
 * live player, whether the word is knowable at all, and what the listener is told when the server
 * says no. The car surface is the one that cannot be checked against a browser afterwards, so the
 * answers it gives have to be pinned somewhere they can be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPronunciationReportsTest {

    private fun chapterExtras(chapterId: Int, fictionId: Int) = Bundle().apply {
        putInt("chapter_id", chapterId)
        putInt("fiction_id", fictionId)
    }

    /** `The Kaelith, she said.` — cue spans carry their punctuation, exactly as the server sends. */
    private fun timedDocument() = ReadAlongDocument(
        chapterId = 10,
        fictionId = 1,
        text = "The Kaelith, she said.",
        paragraphs = listOf(TextSpan(0, 22)),
        cues = listOf(
            ReadAlongCue(TextSpan(0, 3), 0.0),
            ReadAlongCue(TextSpan(4, 12), 1.0),
            ReadAlongCue(TextSpan(13, 16), 2.0),
            ReadAlongCue(TextSpan(17, 22), 3.0),
        ),
    )

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `a press captures the chapter, the book and the second it landed on`() {
        val target = pronunciationReportTargetFor(chapterId = 10, fictionId = 1, positionMs = 283_500L)

        assertEquals(10, target?.chapterId)
        assertEquals(1, target?.fictionId)
        assertEquals(283.5, target?.positionSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `the chapter and position come off the live player`() {
        // The whole feature is "here, now", so where those two numbers come from is the thing worth
        // testing. SimpleBasePlayer derives currentMediaItem and currentPosition from declared
        // state rather than being told what to answer, which a hand-rolled stub would not.
        val player = FakePlayer(
            playlist = listOf(
                FakePlayer.item(
                    mediaId = "chapter:10",
                    title = "Powerful",
                    durationMs = 1_800_000L,
                    extras = chapterExtras(chapterId = 10, fictionId = 1),
                ),
            ),
            positionMs = 283_500L,
        )

        val target = pronunciationReportTargetFor(player)

        assertEquals(10, target?.chapterId)
        assertEquals(1, target?.fictionId)
        assertEquals(283.5, target?.positionSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `an empty player has nothing to report against`() {
        assertNull(pronunciationReportTargetFor(FakePlayer()))
        assertNull(pronunciationReportTargetFor(chapterId = null, fictionId = null, positionMs = 0L))
    }

    @Test
    fun `an item that is not a chapter is nothing to report against`() {
        // `chapter_id` is the only field the server requires, and Bundle.getInt answers 0 for a
        // missing key — so an entry without one arrives here as 0 rather than null.
        val player = FakePlayer(
            playlist = listOf(FakePlayer.item(mediaId = "fiction:1", title = "A Test Serial")),
            positionMs = 12_000L,
        )

        assertNull(pronunciationReportTargetFor(player))
        assertNull(pronunciationReportTargetFor(chapterId = 0, fictionId = 1, positionMs = 12_000L))
    }

    @Test
    fun `a missing fiction id does not stop the capture`() {
        // fiction_id is optional and the server derives the fiction from the chapter anyway, so an
        // entry that carries only a chapter still files — the same reason `word` may be null.
        val target = pronunciationReportTargetFor(chapterId = 10, fictionId = 0, positionMs = 1_000L)

        assertEquals(10, target?.chapterId)
        assertNull(target?.fictionId)
    }

    @Test
    fun `a negative position reports the start of the chapter, not before it`() {
        // Media3 can report a negative position around a seek. The contract stores an unreadable
        // position as 0 rather than refusing, because losing the capture loses everything.
        assertEquals(
            0.0,
            pronunciationReportTargetFor(chapterId = 7, fictionId = 1, positionMs = -250L)
                ?.positionSeconds ?: -1.0,
            0.0001,
        )
    }

    @Test
    fun `the position keeps sub-second precision`() {
        // Integer seconds would land the report up to a second out, which at this app's usual
        // 1.5-2x is most of a sentence — and the sentence is what a human will listen back to.
        assertEquals(
            1.234,
            pronunciationReportTargetFor(chapterId = 1, fictionId = 1, positionMs = 1_234L)
                ?.positionSeconds ?: 0.0,
            0.0001,
        )
    }

    @Test
    fun `a loaded read-along document names the word under the playhead`() {
        assertEquals("Kaelith", pronunciationWordAt(timedDocument(), positionSeconds = 1.4))
    }

    @Test
    fun `the word is stripped of the punctuation the cue carried`() {
        // "Kaelith," is not a spelling anyone would search a chapter for.
        assertEquals("The", pronunciationWordAt(timedDocument(), positionSeconds = 0.0))
        assertEquals("said", pronunciationWordAt(timedDocument(), positionSeconds = 3.5))
    }

    @Test
    fun `no document means no word, and that is the ordinary case`() {
        // A car has no reader open. The contract says word is usually null and the report is still
        // worth filing, so this must answer null rather than refuse anything.
        assertNull(pronunciationWordAt(document = null, positionSeconds = 12.0))
    }

    @Test
    fun `a chapter converted before timings existed has no word to give`() {
        val untimed = ReadAlongDocument(
            chapterId = 10,
            text = "The Kaelith, she said.",
            paragraphs = listOf(TextSpan(0, 22)),
        )

        assertNull(pronunciationWordAt(untimed, positionSeconds = 1.4))
    }

    @Test
    fun `a position before the first cue has no word`() {
        assertNull(pronunciationWordAt(timedDocument(), positionSeconds = -0.5))
    }

    @Test
    fun `a cue that is only punctuation is not a word worth quoting`() {
        val dashes = ReadAlongDocument(
            chapterId = 10,
            text = "— …",
            paragraphs = listOf(TextSpan(0, 3)),
            cues = listOf(ReadAlongCue(TextSpan(0, 3), 0.0)),
        )

        assertNull(pronunciationWordAt(dashes, positionSeconds = 1.0))
    }

    @Test
    fun `a cue long enough to be a phrase is not quoted as a word`() {
        val phrase = "a".repeat(80)
        val document = ReadAlongDocument(
            chapterId = 10,
            text = phrase,
            paragraphs = listOf(TextSpan(0, phrase.length)),
            cues = listOf(ReadAlongCue(TextSpan(0, phrase.length), 0.0)),
        )

        assertNull(pronunciationWordAt(document, positionSeconds = 1.0))
    }

    @Test
    fun `a stored report is filed and says nothing`() {
        val outcome = pronunciationReportOutcomeFor(Result.success(PronunciationReport(id = 7)))

        assertEquals(PronunciationReportOutcome.Filed, outcome)
        // Media3 has no success channel, and the press is its own acknowledgement in a car.
        assertNull(pronunciationReportErrorCode(outcome))
    }

    @Test
    fun `a server that cannot store one says so, rather than reporting a failure`() {
        // The repository answers null for a server without the capability, which is a different
        // sentence from "could not send" and reaches the controller as a different error code.
        val outcome = pronunciationReportOutcomeFor(Result.success(null))

        assertEquals(PronunciationReportOutcome.Unsupported, outcome)
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, pronunciationReportErrorCode(outcome))
    }

    @Test
    fun `the open-report ceiling arrives as the server's own sentence`() {
        // A 409 is the one refusal a listener can act on — an admin working through the list frees
        // the budget — so the server's detail is repeated verbatim rather than paraphrased.
        val outcome = pronunciationReportOutcomeFor(
            Result.failure(
                httpError(409, """{"detail":"You have 500 open pronunciation reports."}"""),
            ),
        )

        assertEquals(
            PronunciationReportOutcome.Refused("You have 500 open pronunciation reports."),
            outcome,
        )
        assertEquals("You have 500 open pronunciation reports.", outcome.message)
    }

    @Test
    fun `a ceiling with no explanation still names the way out of it`() {
        val outcome = pronunciationReportRefusal(code = 409, detail = null)

        assertEquals(PronunciationReportOutcome.Refused(PronunciationReportCeilingMessage), outcome)
        assertTrue(outcome.message.contains("browser"))
    }

    @Test
    fun `an unexplained client error is not dressed up as an explanation`() {
        assertEquals(
            PronunciationReportOutcome.Failed,
            pronunciationReportRefusal(code = 400, detail = null),
        )
        assertEquals(
            PronunciationReportOutcome.Refused("That chapter is gone."),
            pronunciationReportRefusal(code = 404, detail = "That chapter is gone."),
        )
    }

    @Test
    fun `an expired session is left to the sign-in prompt`() {
        // The repository has already expired the session by the time this is reached, and asking
        // for a sign-in beats a toast in a car explaining the 401.
        assertEquals(
            PronunciationReportOutcome.Failed,
            pronunciationReportRefusal(code = 401, detail = "Session expired"),
        )
    }

    @Test
    fun `being offline is a failure, not a refusal`() {
        val outcome = pronunciationReportOutcomeFor(Result.failure(java.io.IOException("offline")))

        assertEquals(PronunciationReportOutcome.Failed, outcome)
        assertEquals(SessionError.ERROR_IO, pronunciationReportErrorCode(outcome))
    }

    @Test
    fun `nothing playing is a state problem and says which`() {
        assertEquals(
            SessionError.ERROR_INVALID_STATE,
            pronunciationReportErrorCode(PronunciationReportOutcome.NothingPlaying),
        )
    }

    @Test
    fun `every outcome that is not success explains itself`() {
        val outcomes = listOf(
            PronunciationReportOutcome.NothingPlaying,
            PronunciationReportOutcome.Unsupported,
            PronunciationReportOutcome.Failed,
            PronunciationReportOutcome.Refused("At the ceiling"),
        )

        for (outcome in outcomes) {
            assertTrue("$outcome should explain itself", outcome.message.isNotBlank())
            assertTrue("$outcome should reach the controller", pronunciationReportErrorCode(outcome) != null)
        }
    }
}
