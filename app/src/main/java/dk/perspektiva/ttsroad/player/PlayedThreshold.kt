package dk.perspektiva.ttsroad.player

/**
 * When finishing a chapter counts as having played it.
 *
 * Extracted from `TtsRoadMediaService.saveCurrentProgress` so the rule can be tested. It had lived
 * inline as one expression since the service was written, which meant the two thresholds below —
 * and, once `auto_mark_played` was honoured (#119), the interaction between them and the account
 * preference — were only ever exercised by running the app.
 *
 * The two thresholds are an either/or on purpose. The percentage covers ordinary chapters; the
 * absolute tail covers long ones, where 4% of ninety minutes is nearly four unlistened minutes and
 * a listener who has reached the last twenty seconds is plainly finished.
 */
object PlayedThreshold {
    /** Past this fraction of the chapter, it counts as played. */
    const val Fraction: Double = 0.96

    /** Within this much of the end, it counts as played however long the chapter is. */
    const val TailMs: Long = 20_000L

    /**
     * Whether the automatic mark should fire.
     *
     * [autoMarkEnabled] is the account's `auto_mark_played`. An explicit mark from the user does
     * not come through here — the service applies that unconditionally, which is how the web reads
     * the preference too: it governs the automatic path only.
     *
     * A null [durationMs] means the player has not resolved a duration yet, and nothing can be
     * concluded from a position without one.
     */
    fun reached(
        positionMs: Long,
        durationMs: Long?,
        autoMarkEnabled: Boolean,
    ): Boolean {
        if (!autoMarkEnabled) return false
        val total = durationMs?.takeIf { it > 0 } ?: return false
        return positionMs >= total - TailMs ||
            positionMs.toDouble() / total.toDouble() >= Fraction
    }
}
