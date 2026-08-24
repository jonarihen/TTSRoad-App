package dk.perspektiva.ttsroad.player

/**
 * Which recorded moments the player's jump-back sheet offers.
 *
 * [PlaybackHistoryStore] keeps 2000 snapshots, which at the service's 15s tick is about eight and a
 * half hours — deliberately, because the case the feature exists for is falling asleep and waking up
 * to a book that kept playing all night. The sheet has room for two dozen rows, and until 0.13.0 it
 * spent all of them at a flat five-minute spacing: two hours of reach out of eight, with the wrong
 * two hours. Someone who fell asleep at midnight and reached for this at six was offered nothing
 * older than four in the morning.
 *
 * So the spacing widens with age instead. Near the present, where a rewind is a correction — a
 * missed sentence, a phone call — five minutes is the right grain. Six hours back, nobody is
 * choosing between 02:15 and 02:20; they are looking for roughly where they stopped taking it in,
 * and half-hour steps get there in the same two dozen rows.
 *
 * Pulled out of `MainActivity` because it is arithmetic over a list, and the reach it produces is
 * the whole value of a 2000-entry history that the sheet was previously throwing away.
 */

/**
 * How far apart two offers may be, given how long ago the older one was.
 *
 * The thresholds are where the *kind* of question changes, not round numbers for their own sake:
 * inside half an hour a jump back is a correction, out past two hours it is "where was I when I
 * stopped listening".
 */
internal fun jumpBackSpacingMs(ageMs: Long): Long = when {
    ageMs < 30 * 60_000L -> 5 * 60_000L
    ageMs < 2 * 60 * 60_000L -> 15 * 60_000L
    else -> 30 * 60_000L
}

/** Rows the sheet can show without becoming a scroll of its own. */
internal const val MaxJumpBackOptions: Int = 24

/**
 * Thin [history] into jump-back targets, newest first.
 *
 * The last minute is skipped: an offer to jump back to where playback already is is not an offer.
 *
 * Spacing is measured against the last row *kept* rather than against the previous snapshot, so a
 * gap in the history — the app closed, a night with playback paused — does not shift everything
 * after it out of step.
 */
fun jumpBackOptions(
    history: List<HistorySnapshot>,
    now: Long,
    limit: Int = MaxJumpBackOptions,
): List<HistorySnapshot> {
    val out = mutableListOf<HistorySnapshot>()
    var lastTs = Long.MAX_VALUE
    for (snap in history.asReversed()) {
        val ageMs = now - snap.timestamp
        if (ageMs < 60_000L) continue
        // The spacing required of *this* row is set by its own age, so the sheet gets fine steps
        // near the present and coarse ones at the far end within one pass.
        if (lastTs - snap.timestamp < jumpBackSpacingMs(ageMs)) continue
        out.add(snap)
        lastTs = snap.timestamp
        if (out.size >= limit) break
    }
    return out
}
