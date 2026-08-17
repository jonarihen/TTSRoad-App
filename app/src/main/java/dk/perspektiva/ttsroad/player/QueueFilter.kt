package dk.perspektiva.ttsroad.player

import dk.perspektiva.ttsroad.data.matchesChapterQuery

/**
 * A queue row that survived the sheet's find-a-chapter field, carrying the position it holds in the
 * *unfiltered* queue.
 *
 * That index is the whole reason this is not a plain `filter`. It is both what the row is labelled
 * with and what [PlaybackController.skipToQueueIndex] is given, so filtering without keeping it
 * would renumber the queue and — far worse — play the wrong chapter, silently, from a list that
 * looked right.
 */
data class QueueRow(
    val index: Int,
    val item: QueueItem,
)

/**
 * The queue rows to draw for [query].
 *
 * Matches on the title and on the row's 1-based position, which is what the sheet labels each row
 * with. A queue entry carries no chapter number of its own — it is a media id and a title — so the
 * position is the only number there is to type, and in the ordinary case of a whole fiction queued
 * in order it is the chapter number anyway.
 *
 * Blank matches everything, so a sheet with nothing typed lists the queue exactly as it always did.
 */
fun List<QueueItem>.queueRows(query: String): List<QueueRow> =
    mapIndexed { index, item -> QueueRow(index, item) }
        .filter { row ->
            matchesChapterQuery(
                title = row.item.title,
                numberLabel = (row.index + 1).toString(),
                query = query,
            )
        }
