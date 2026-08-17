package dk.perspektiva.ttsroad.nav

/**
 * Whether an open reader should move itself onto the chapter that just started playing.
 *
 * The reader is opened with a fixed chapter id, but the player moves on without it — at the end of
 * a chapter, or whenever someone hits next/previous. Left alone the reader keeps showing the
 * finished chapter's text under a highlight that has stopped moving, and the only way out is to
 * leave and re-enter it mid-listen.
 *
 * Following is not a stored mode, because a stored mode has to be reset correctly and this does
 * not: it is re-derived every time the playing chapter changes, from whether the reader and the
 * player *already agreed* immediately beforehand. That single condition covers the cases that
 * matter without any of them being special:
 *
 * - Reading along and the chapter ends. [previousPlayingChapterId] is what the reader is showing,
 *   so the reader moves on with the audio. The point of the fix.
 * - Reader deliberately opened on some *other* chapter. The two never agreed, so nothing here ever
 *   fires and the user is never dragged off the page they chose — including across any number of
 *   later auto-advances, since the reader stays put and the player keeps moving away from it.
 * - Audio catches up to a chapter someone had skipped ahead to read. The two now agree, so from
 *   that point on the reader does follow. It is genuinely reading along by then, which is the
 *   behaviour worth having rather than an exception to argue about.
 * - Playback stops, or the queue is emptied. [playingChapterId] is null and the reader holds its
 *   place; there is nothing to follow and the last chapter read is the useful thing to still show.
 * - Playback *starts* on a chapter while the reader sits on an unrelated one. The two did not
 *   agree beforehand, so starting something elsewhere never yanks the reader across to it.
 *
 * @param readerChapterId the chapter the reader is currently showing.
 * @param previousPlayingChapterId what was playing before this change, or null if nothing was.
 * @param playingChapterId what is playing now, or null if nothing is.
 * @return the chapter id to re-target the reader at, or null to leave it where it is.
 */
fun readerFollowTarget(
    readerChapterId: Int,
    previousPlayingChapterId: Int?,
    playingChapterId: Int?,
): Int? {
    if (playingChapterId == null) return null
    // Already showing it — the ordinary case on every tick, and on the first composition.
    if (playingChapterId == readerChapterId) return null
    // The one condition: the reader was showing what the player was playing.
    if (previousPlayingChapterId != readerChapterId) return null
    return playingChapterId
}
