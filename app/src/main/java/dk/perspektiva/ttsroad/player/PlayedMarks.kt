package dk.perspektiva.ttsroad.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a chapter's played flag sticky while it is listened to again (#325).
 *
 * `POST /playback/progress` writes `is_played` exactly as sent, so a progress tick that sends
 * `false` for a chapter already marked played *un-marks* it on the server. Re-listening to the
 * start of a finished chapter used to do exactly that on the first 15s tick. The web player never
 * has: it sends the played state it already knows and only ever auto-marks upwards. This makes the
 * service do the same.
 *
 * What it knows comes from two places, newest first: a mark this process made or saw (the user's
 * explicit played/unplayed in the app, or an automatic mark the service sent), and otherwise the
 * played flag the queue item was built with. The first matters because a queue item's extras are a
 * snapshot — after the user unticks a chapter in the list, the loaded item still says played, and
 * believing it would tick the box straight back.
 *
 * In memory on purpose. After a process death the queue is rebuilt from the server, whose answer
 * is newer than anything this could have persisted.
 */
class PlayedMarks {
    private val known = ConcurrentHashMap<Int, Boolean>()

    /** The user (or the server, in answer to a mark request) set these chapters' flag. */
    fun remember(chapterIds: Collection<Int>, played: Boolean) {
        chapterIds.forEach { known[it] = played }
    }

    /**
     * The `is_played` to send for [chapterId].
     *
     * [loadedAsPlayed] is what the queue item said when it was built; [thresholdReached] is
     * [PlayedThreshold.reached] for this tick. Reaching the threshold marks played and is
     * remembered, so seeking back afterwards does not un-mark what was just marked.
     */
    fun resolve(chapterId: Int, loadedAsPlayed: Boolean, thresholdReached: Boolean): Boolean {
        if (thresholdReached) {
            known[chapterId] = true
            return true
        }
        return known[chapterId] ?: loadedAsPlayed
    }

    /** Sign-out: the next account's chapter ids mean different chapters. */
    fun clear() {
        known.clear()
    }
}
