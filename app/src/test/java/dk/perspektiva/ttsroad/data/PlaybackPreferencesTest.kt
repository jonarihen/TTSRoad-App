package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DataStore itself needs an Android context, but the part that can actually be wrong is the
 * sanitising: a value written by a different build, or a corrupted file, must not be able to hand
 * the player a speed of 0 or a negative skip.
 */
class PlaybackPreferencesTest {

    @Test
    fun `a stored speed inside the sane range is kept as-is`() {
        for (preset in SpeedPresets) {
            assertEquals(preset, sanitizeSpeed(preset), 0.0001f)
        }
    }

    @Test
    fun `an out-of-range speed is clamped rather than reset`() {
        // Clamped, not snapped to a preset: a value written by a future build with finer steps
        // should survive a downgrade instead of silently reverting to 1.0x.
        assertEquals(0.5f, sanitizeSpeed(0.1f), 0.0001f)
        assertEquals(3.0f, sanitizeSpeed(9.0f), 0.0001f)
        assertEquals(1.35f, sanitizeSpeed(1.35f), 0.0001f)
    }

    @Test
    fun `a speed of zero cannot reach the player`() {
        assertTrue(sanitizeSpeed(0f) > 0f)
        assertTrue(sanitizeSpeed(-2f) > 0f)
    }

    @Test
    fun `NaN falls back to the default instead of poisoning the player`() {
        assertEquals(DefaultSpeed, sanitizeSpeed(Float.NaN), 0.0001f)
    }

    @Test
    fun `every offered skip interval round-trips`() {
        for (option in SkipIntervalOptionsMs) {
            assertEquals(option, sanitizeSkipIntervalMs(option))
        }
    }

    @Test
    fun `an unrecognised skip interval falls back to the default`() {
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(0L))
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(-30_000L))
        assertEquals(DefaultSkipIntervalMs, sanitizeSkipIntervalMs(7_000L))
    }

    @Test
    fun `the default skip interval is one of the offered options`() {
        assertTrue(DefaultSkipIntervalMs in SkipIntervalOptionsMs)
    }

    @Test
    fun `the default speed is one of the offered presets`() {
        assertTrue(SpeedPresets.any { kotlin.math.abs(it - DefaultSpeed) < 0.0001f })
    }

    @Test
    fun `skip labels read as seconds until they are whole minutes`() {
        assertEquals("10s", formatSkipInterval(10_000L))
        assertEquals("15s", formatSkipInterval(15_000L))
        assertEquals("30s", formatSkipInterval(30_000L))
        assertEquals("45s", formatSkipInterval(45_000L))
        assertEquals("1m", formatSkipInterval(60_000L))
    }
}

/**
 * The audio half of the same store. Split into its own class so the speed/skip cases above and the
 * skip-silence/boost cases here stay independently readable.
 */
class AudioPreferencesTest {

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
    fun `skip silence defaults off, so the app sounds like the web player`() {
        assertFalse(DefaultSkipSilence)
        assertFalse(PlaybackPrefs().skipSilence)
    }

    @Test
    fun `the speed presets are the ones the other clients offer`() {
        // Not cosmetic: a listener who settled on a speed in the browser has to be able to pick
        // the same number here, or the two clients disagree by a step.
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f),
            SpeedPresets,
        )
    }

    @Test
    fun `the presets span the full range the server accepts`() {
        // The clamps allowed 0.5-3.0 long before the picker offered it, so the list was the real
        // ceiling. Pin both ends against a future edit that narrows it again.
        assertEquals(0.5f, SpeedPresets.min(), 0.0001f)
        assertEquals(3.0f, SpeedPresets.max(), 0.0001f)
        for (preset in SpeedPresets) {
            assertEquals(preset, sanitizeSpeed(preset), 0.0001f)
        }
    }

    @Test
    fun `presets are offered in ascending order without duplicates`() {
        assertEquals(SpeedPresets.sorted(), SpeedPresets)
        assertEquals(SpeedPresets.distinct().size, SpeedPresets.size)
    }

    @Test
    fun `a speed that is already a preset adds nothing to the picker`() {
        assertEquals(SpeedPresets, speedOptions(1.5f))
        assertEquals(SpeedPresets, speedOptions(3.0f))
    }

    @Test
    fun `a speed set under the old presets stays selectable`() {
        // 0.8x was a preset before the list was widened to match desktop. Dropping it from the
        // picker must not strand someone who is listening at it: the sheet marks the current speed
        // with "Current", and a value absent from the list can never be marked or returned to.
        val options = speedOptions(0.8f)
        assertTrue(options.any { kotlin.math.abs(it - 0.8f) < 0.0001f })
        assertEquals(SpeedPresets.size + 1, options.size)
        assertTrue(options.containsAll(SpeedPresets))
    }

    @Test
    fun `an inserted speed sorts between its neighbours`() {
        val options = speedOptions(0.8f)
        assertEquals(options.sorted(), options)
        assertEquals(0.75f, options[options.indexOfFirst { it > 0.79f && it < 0.81f } - 1], 0.0001f)
    }

    @Test
    fun `a speed arriving from the account is clamped before it is offered`() {
        // Once preferences follow the account the value can come from another client, so the
        // picker must not offer something the player would refuse to play at.
        assertEquals(SpeedPresets, speedOptions(9.0f))
        assertEquals(SpeedPresets, speedOptions(Float.NaN))
        assertTrue(speedOptions(0.6f).all { it in 0.5f..3.0f })
    }

    @Test
    fun `nothing in the defaults alters playback before it is asked for`() {
        // The regression in #43 was a default that changed how the audio sounds. Pin the whole
        // set, so the next one has to be deliberate rather than a one-word edit.
        val defaults = PlaybackPrefs()
        assertEquals(1.0f, defaults.speed, 0.0001f)
        assertFalse(defaults.skipSilence)
        assertEquals(VolumeBoost.Off, defaults.volumeBoost)
    }

    @Test
    fun `boost defaults off, so nothing is altered until asked`() {
        assertEquals(VolumeBoost.Off, PlaybackPrefs().volumeBoost)
    }
}
