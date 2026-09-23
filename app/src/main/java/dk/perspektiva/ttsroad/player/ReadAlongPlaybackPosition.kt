package dk.perspektiva.ttsroad.player

internal data class ReadAlongPlaybackSample(
    val mediaId: String,
    val positionMs: Long,
    val isPlaying: Boolean,
    val speed: Float,
    val discontinuityGeneration: Long,
)

internal class ReadAlongPlaybackPosition {
    private var previousSample: ReadAlongPlaybackSample? = null
    private var displayedPositionMs: Long? = null
    private var holdingSinceMs: Long? = null

    fun positionMs(
        sample: ReadAlongPlaybackSample?,
        expectedMediaId: String,
        elapsedRealtimeMs: Long,
    ): Long? {
        if (sample == null || sample.mediaId != expectedMediaId) {
            previousSample = null
            displayedPositionMs = null
            holdingSinceMs = null
            return null
        }

        val previous = previousSample
        val displayed = displayedPositionMs
        previousSample = sample
        val uninterrupted = previous != null &&
            previous.mediaId == sample.mediaId &&
            previous.discontinuityGeneration == sample.discontinuityGeneration &&
            previous.isPlaying && sample.isPlaying &&
            previous.speed == sample.speed

        if (uninterrupted && displayed != null) {
            val correctionMs = displayed - sample.positionMs
            if (correctionMs in 1L..100L) {
                val since = holdingSinceMs ?: elapsedRealtimeMs.also { holdingSinceMs = it }
                if (elapsedRealtimeMs - since in 0L until 250L) {
                    return displayed
                }
            } else if (correctionMs == 0L) {
                return displayed
            }
        }

        holdingSinceMs = null
        displayedPositionMs = sample.positionMs
        return sample.positionMs
    }
}
