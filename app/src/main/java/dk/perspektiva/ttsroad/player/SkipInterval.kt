package dk.perspektiva.ttsroad.player

/**
 * Resolve a relative skip to an absolute position inside the current chapter.
 *
 * Seeking past the end of a chapter would roll over into the next one, and these controls are
 * meant to move *within* a chapter, so the target is pinned to `[0, duration]`. A duration that
 * isn't known yet — `C.TIME_UNSET`, or 0 before the stream has been read — clamps at zero only,
 * leaving the player to handle the upper end.
 */
fun skipTargetMs(currentPositionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val end = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val target = currentPositionMs + deltaMs
    // A negative delta on an early position underflows to below zero, never past Long.MIN_VALUE,
    // so coerceIn is safe without an explicit overflow guard.
    return target.coerceIn(0L, end)
}
