package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DataStore itself needs an Android context, but the parts that can actually be wrong are the
 * gain mapping and the tolerance for a stored value written by a different build.
 */
class PlaybackPreferencesTest {

    @Test
    fun `off means no gain at all, not a small one`() {
        assertEquals(0, VolumeBoost.Off.gainMillibels)
    }

    @Test
    fun `boost levels increase strictly`() {
        val gains = VolumeBoost.entries.map { it.gainMillibels }

        assertEquals(gains.sorted(), gains)
        assertEquals(gains.distinct().size, gains.size)
    }

    /**
     * LoudnessEnhancer applies raw gain with no limiter, so a chapter already near full scale will
     * clip. 10 dB is the point past which that becomes audible on ordinary TTS output, and clipping
     * is a worse problem than the uneven loudness being fixed.
     */
    @Test
    fun `no boost level exceeds ten decibels`() {
        for (boost in VolumeBoost.entries) {
            assertTrue("${boost.name} is ${boost.gainMillibels}mB", boost.gainMillibels <= 1_000)
        }
    }

    @Test
    fun `every boost level has a label to show`() {
        for (boost in VolumeBoost.entries) {
            assertTrue(boost.label.isNotBlank())
        }
    }

    @Test
    fun `a stored boost name round-trips`() {
        for (boost in VolumeBoost.entries) {
            assertEquals(boost, volumeBoostOf(boost.name))
        }
    }

    @Test
    fun `an unknown or missing stored boost falls back to off`() {
        // A value written by a build with a different set of levels must not throw while reading
        // preferences - that would take the whole settings screen down with it.
        assertEquals(VolumeBoost.Off, volumeBoostOf(null))
        assertEquals(VolumeBoost.Off, volumeBoostOf(""))
        assertEquals(VolumeBoost.Off, volumeBoostOf("Extreme"))
        assertEquals(VolumeBoost.Off, volumeBoostOf("low"))
    }

    @Test
    fun `skip silence defaults on, since that is the point for synthesised speech`() {
        assertTrue(DefaultSkipSilence)
        assertTrue(PlaybackPrefs().skipSilence)
    }

    @Test
    fun `boost defaults off, so nothing is altered until asked`() {
        assertEquals(VolumeBoost.Off, PlaybackPrefs().volumeBoost)
    }
}
