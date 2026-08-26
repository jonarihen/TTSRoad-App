package dk.perspektiva.ttsroad.media

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionError
import dk.perspektiva.ttsroad.data.PronunciationReport
import dk.perspektiva.ttsroad.data.ReadAlongDocument
import dk.perspektiva.ttsroad.data.detailMessage
import retrofit2.HttpException

/**
 * The moment a "that word is wrong" press should file (#125).
 *
 * @param chapterId the chapter the report hangs on. The only field the server requires.
 * @param fictionId the book, when the queue entry names one. Sent because it is already known; the
 *   server derives the fiction from the chapter regardless rather than trusting the pair.
 * @param positionSeconds how far into that chapter the press landed — the ten seconds a human will
 *   later listen to, and the entire value of a report with no word attached.
 */
data class PronunciationReportTarget(
    val chapterId: Int,
    val fictionId: Int?,
    val positionSeconds: Double,
)

/**
 * What a press resolved to, and what to say about it.
 *
 * A sealed hierarchy rather than an enum for one case: the open-report ceiling's `409` carries the
 * server's own `detail`, and "you have 500 open reports" is the one refusal here a listener can
 * actually do something about. Repeating the server's sentence is worth more than a constant.
 */
sealed interface PronunciationReportOutcome {
    val message: String

    /** Stored. Silent, for the reason [pronunciationReportErrorCode] gives. */
    data object Filed : PronunciationReportOutcome {
        override val message: String = "Pronunciation problem reported"
    }

    data object NothingPlaying : PronunciationReportOutcome {
        override val message: String = "Nothing playing to report"
    }

    data object Unsupported : PronunciationReportOutcome {
        override val message: String = "This server does not take pronunciation reports"
    }

    /** The server refused and explained itself. [message] is the server's sentence, not ours. */
    data class Refused(override val message: String) : PronunciationReportOutcome

    data object Failed : PronunciationReportOutcome {
        override val message: String = "Could not send the report"
    }
}

/**
 * Said only when the server hit its ceiling without explaining why, which it does not normally do.
 *
 * Names the fix rather than the limit: the count is of *open* reports, so an administrator working
 * through the list on the web is what frees the budget.
 */
const val PronunciationReportCeilingMessage: String =
    "Too many pronunciation reports are still open. Clear some in the browser first."

/** Longer than this is a cue covering a phrase or a stray span, not a word worth quoting. */
private const val MaxReportedWordLength = 64

/**
 * Resolve the moment to report from what the player is holding, or null if there is nothing to
 * report against.
 *
 * Deliberately takes ids and a position rather than the player, so the interesting decisions stay
 * testable without one — the [Player] overload is the thin wrapper the session command calls.
 *
 * A missing or non-positive chapter id is "nothing to report" rather than an error: it covers an
 * empty player and a queue entry that is not a chapter alike, and `chapter_id` is the only field
 * the server requires. A fiction id of zero becomes null for the opposite reason — it is optional,
 * and `Bundle.getInt` answers 0 for a key that was never set.
 */
fun pronunciationReportTargetFor(
    chapterId: Int?,
    fictionId: Int?,
    positionMs: Long,
): PronunciationReportTarget? {
    if (chapterId == null || chapterId <= 0) return null
    return PronunciationReportTarget(
        chapterId = chapterId,
        fictionId = fictionId?.takeIf { it > 0 },
        // Media3 can report a negative position around a seek or a discontinuity, and the server
        // takes seconds. The contract stores an unreadable position as 0 rather than refusing the
        // capture, and clamping here keeps a press in that window inside its own chapter.
        positionSeconds = positionMs.coerceAtLeast(0L) / 1000.0,
    )
}

/**
 * The same moment, read straight off the live player.
 *
 * Read synchronously, before anything suspends, for the reason the whole feature exists: the press
 * says *here*, and resolving the position after a network round trip would file the wrong second.
 */
fun pronunciationReportTargetFor(player: Player): PronunciationReportTarget? {
    val extras = player.currentMediaItem?.mediaMetadata?.extras
    return pronunciationReportTargetFor(
        chapterId = extras?.getInt("chapter_id"),
        fictionId = extras?.getInt("fiction_id"),
        positionMs = player.currentPosition,
    )
}

/**
 * The word being spoken at [positionSeconds], when a timed read-along document happens to be loaded.
 *
 * Null is the ordinary answer and the contract says so: the phone knows the word only when the
 * reader has already pulled this chapter's cues in, which is not the case in a car with the screen
 * off. A report with no word still points a human at ten seconds to listen to, so this must never
 * gate the capture — every path out of here is null rather than an exception or a fetch.
 *
 * Cues carry whatever punctuation sits against the word, and `Kaelith,` is not the spelling anyone
 * wants to search a chapter for, so the edges are trimmed back to something word-shaped. A span
 * that trims away to nothing, or one long enough to be a phrase rather than a word, is not worth
 * quoting and answers null.
 */
fun pronunciationWordAt(document: ReadAlongDocument?, positionSeconds: Double): String? {
    val timed = document?.takeIf { it.hasTimings } ?: return null
    val span = timed.highlightAt(positionSeconds).word ?: return null
    val word = timed.textIn(span).trim().trim { !it.isLetterOrDigit() }
    return word.takeIf { it.isNotEmpty() && it.length <= MaxReportedWordLength }
}

/**
 * What to say about a server refusal.
 *
 * The `409` is the ceiling and always gets a sentence, the server's own where it sent one. Any
 * other client error is repeated only when the server explained itself — a bare status code
 * dressed up as an explanation would be worse than the generic failure.
 *
 * A `401` deliberately falls through to [PronunciationReportOutcome.Failed]: the repository's
 * authorization wrapper has already expired the session by the time this is reached, and the
 * sign-in prompt that follows is a better answer than a toast in a car.
 */
fun pronunciationReportRefusal(code: Int, detail: String?): PronunciationReportOutcome {
    val explained = detail?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        code == 409 ->
            PronunciationReportOutcome.Refused(explained ?: PronunciationReportCeilingMessage)

        code == 401 -> PronunciationReportOutcome.Failed
        code in 400..499 && explained != null -> PronunciationReportOutcome.Refused(explained)
        else -> PronunciationReportOutcome.Failed
    }
}

/**
 * Turn whatever came back from a capture attempt into something to say.
 *
 * Shared by the media-session command and the player screen so the two never drift on what a `409`
 * means — the car surface is the one that cannot be checked against a browser, and it is the one
 * that most needs the server's own words.
 *
 * A null report is not a failure to report as one: it is this server having no capture store,
 * which is a different sentence from "could not send".
 */
fun pronunciationReportOutcomeFor(result: Result<PronunciationReport?>): PronunciationReportOutcome =
    result.fold(
        onSuccess = { report ->
            if (report == null) {
                PronunciationReportOutcome.Unsupported
            } else {
                PronunciationReportOutcome.Filed
            }
        },
        onFailure = { error ->
            when (error) {
                is HttpException -> pronunciationReportRefusal(
                    code = error.code(),
                    // Reading the error body is itself allowed to fail: a truncated response must
                    // not turn a refusal we could describe into a crash on the media thread.
                    detail = runCatching {
                        detailMessage(error.response()?.errorBody()?.string())
                    }.getOrNull(),
                )

                else -> PronunciationReportOutcome.Failed
            }
        },
    )

/**
 * The [SessionError] code for an outcome, or null when there is nothing to send.
 *
 * Success is null on purpose. Media3 surfaces an error to the controller — a toast in the car, a
 * message on the notification — and has no success equivalent, and that asymmetry is the right way
 * round for a control whose whole point is being pressed without looking at the screen: the press
 * is the acknowledgement, and the only interruption worth making is one that says it did not land.
 */
@OptIn(UnstableApi::class)
fun pronunciationReportErrorCode(outcome: PronunciationReportOutcome): Int? = when (outcome) {
    PronunciationReportOutcome.Filed -> null
    PronunciationReportOutcome.NothingPlaying -> SessionError.ERROR_INVALID_STATE
    PronunciationReportOutcome.Unsupported -> SessionError.ERROR_NOT_SUPPORTED
    is PronunciationReportOutcome.Refused -> SessionError.ERROR_BAD_VALUE
    // Everything else is the network: offline, a dead server, an expired token.
    PronunciationReportOutcome.Failed -> SessionError.ERROR_IO
}
