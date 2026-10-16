package dk.perspektiva.ttsroad.media

/**
 * Read where to resume only once the offline progress backlog has had its chance (#314).
 *
 * On a cold start from the car or a headset, the service's start-up flush and the resume's library
 * read used to run with no order between them. When the read won, playback resumed at the server's
 * older position, and the first 15s tick then recorded that older position for the same chapter —
 * and because the pending store keeps one entry per chapter, it replaced the newer queued one, which
 * was lost for good.
 *
 * [flush] is serialised behind any flush already running, so this simply waits its turn. A flush
 * that cannot send (offline, capabilities unknown) leaves the queue intact and the read goes ahead:
 * there is then nothing newer the server could have been told.
 */
internal suspend fun <T> afterPendingProgress(flush: suspend () -> Unit, read: suspend () -> T): T {
    flush()
    return read()
}
