package dk.perspektiva.ttsroad.data

/** Whether the sync index says the derived home payload needs a sparse pull. */
fun DeltaSyncResponse.libraryMoved(): Boolean =
    changed.library || changed.fictions.isNotEmpty() || deleted.fictions.isNotEmpty()

/** Fiction ids whose already-loaded chapter lists need sparse refreshes. */
fun DeltaSyncResponse.fictionsWithChapterChanges(): List<Int> =
    changed.fictions.filter(DeltaFictionChange::chaptersMoved).map(DeltaFictionChange::fictionId)

/**
 * Merge a sparse `/library?updated_since=` answer into the complete response already on screen.
 *
 * The two chapter rails and `following_ids` are complete on every delta and replace their old
 * values. Only `fictions` is sparse. An unfollow has no fiction tombstone, so the complete
 * membership list is also what removes rows from the followed shelf.
 */
fun mergeLibraryDelta(current: LibraryResponse, update: LibraryResponse): LibraryResponse {
    if (!update.delta) return update
    val removed = update.deleted.toSet()
    val followed = update.followingIds.toSet()
    val isShelf = update.scope == LibraryScopeFollowed
    val changed = update.fictions.associateBy(FictionSummary::id)

    val merged = buildList {
        current.fictions.forEach { fiction ->
            if (fiction.id in removed || (isShelf && fiction.id !in followed)) return@forEach
            add(changed[fiction.id] ?: fiction)
        }
        val existingIds = current.fictions.mapTo(mutableSetOf(), FictionSummary::id)
        update.fictions.forEach { fiction ->
            if (fiction.id !in existingIds && fiction.id !in removed &&
                (!isShelf || fiction.id in followed)
            ) {
                add(fiction)
            }
        }
    }.sortedBy { it.title.lowercase() }

    return current.copy(
        scope = update.scope,
        followingIds = update.followingIds,
        fictions = merged,
        continueListening = update.continueListening,
        recentChapters = update.recentChapters,
        serverTime = update.serverTime,
        updatedSince = update.updatedSince,
        delta = false,
        deleted = emptyList(),
    )
}

/** Merge changed and deleted chapter rows without rebuilding untouched objects. */
fun mergeChapterDelta(
    current: List<ChapterSummary>,
    update: ChaptersResponse,
): List<ChapterSummary> {
    if (!update.delta) return update.chapters
    val removed = update.deleted.toSet()
    val changed = update.chapters.associateBy(ChapterSummary::resolvedChapterId)
    val existingIds = current.mapTo(mutableSetOf(), ChapterSummary::resolvedChapterId)

    return buildList {
        current.forEach { chapter ->
            val id = chapter.resolvedChapterId
            if (id !in removed) add(changed[id] ?: chapter)
        }
        update.chapters.forEach { chapter ->
            if (chapter.resolvedChapterId !in existingIds && chapter.resolvedChapterId !in removed) {
                add(chapter)
            }
        }
    }.sortedWith(ServerChapterOrder)
}

/**
 * The order the server sends chapters in, reproduced so a merge does not reshuffle the list.
 *
 * A full response arrives already sorted and is used as-is; a sparse one has to be placed into a
 * list the client already holds, and the only correct place is where the server would have put it.
 * That key is `(chapter_number, id)` with unnumbered chapters last — mirroring `_chapter_sort_key`
 * in `app/routers/fictions.py`. Sorting by `player_index` instead would look right and be wrong:
 * the server only assigns one to *playable* chapters, so every pending and failed chapter would
 * collect at the bottom of a list it had been interleaved through a moment earlier.
 */
private val ServerChapterOrder =
    compareBy<ChapterSummary> { it.chapterNumber ?: UnnumberedChapterSortKey }
        .thenBy(ChapterSummary::resolvedChapterId)

/** The server's own sentinel for "no chapter number", so both sides break the tie identically. */
private const val UnnumberedChapterSortKey = 1_000_000_000.0
