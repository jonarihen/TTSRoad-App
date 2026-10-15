package dk.perspektiva.ttsroad.player

/**
 * The chapters playback ran through after [from], grouped by fiction (#342).
 *
 * With no sleep timer the queue keeps advancing through the night, and every chapter it finishes
 * crosses [PlayedThreshold] and is marked played — so in the morning the "unplayed" filter hides
 * chapters nobody heard. Jumping back to where the listener fell asleep is the moment that is
 * known, which makes it the moment to offer putting those marks back.
 *
 * Includes [from]'s own chapter: if playback moved on from it, it was finished, and was marked
 * played with it. Empty when playback never left that chapter — then nothing was finished, and
 * there is nothing to offer.
 *
 * Read from the jump-back trail rather than from what the server holds, so it can be offered
 * without a request. It cannot tell a chapter that was already played before the night from one
 * that was marked in it, which is why this is an offer and not something done on its own.
 */
fun chaptersPlayedAfter(history: List<HistorySnapshot>, from: HistorySnapshot): Map<Int, Set<Int>> {
    val after = history
        .filter { it.timestamp >= from.timestamp && it.fictionId > 0 && it.chapterId > 0 }
        .sortedBy { it.timestamp }
    if (after.none { it.chapterId != from.chapterId }) return emptyMap()
    val chapters = buildList {
        if (from.fictionId > 0 && from.chapterId > 0) add(from)
        addAll(after)
    }
    return chapters
        .groupBy({ it.fictionId }, { it.chapterId })
        .mapValues { (_, ids) -> ids.toSet() }
}
