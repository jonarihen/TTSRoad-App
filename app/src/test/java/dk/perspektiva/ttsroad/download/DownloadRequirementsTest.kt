package dk.perspektiva.ttsroad.download

import androidx.media3.exoplayer.scheduler.Requirements
import dk.perspektiva.ttsroad.data.DefaultWifiOnly
import dk.perspektiva.ttsroad.data.DownloadPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requirement is what makes a queued chapter *wait* rather than fail, so the property that
 * matters is that both settings still demand a network — the Wi-Fi switch narrows which network
 * counts, it does not turn the requirement off.
 */
class DownloadRequirementsTest {

    @Test
    fun `wifi-only demands an unmetered network`() {
        val requirements = downloadRequirements(wifiOnly = true)

        assertTrue(requirements.isUnmeteredNetworkRequired)
        assertTrue(requirements.isNetworkRequired)
    }

    @Test
    fun `off still demands a network, just not an unmetered one`() {
        // Without this a download queued in a tunnel fails instead of waiting, and the chapter is
        // silently not there when the car needs it.
        val requirements = downloadRequirements(wifiOnly = false)

        assertFalse(requirements.isUnmeteredNetworkRequired)
        assertTrue(requirements.isNetworkRequired)
    }

    @Test
    fun `neither setting waits for a charger`() {
        // Downloads here are started by hand, minutes before someone drives off. Refusing to run
        // because the phone is unplugged would make the feature useless exactly when it is wanted.
        for (wifiOnly in listOf(true, false)) {
            val requirements = downloadRequirements(wifiOnly)
            assertFalse(requirements.isChargingRequired)
            assertFalse(requirements.isIdleRequired)
        }
    }

    @Test
    fun `the two settings are actually different requirements`() {
        // media3's constructor folds NETWORK into NETWORK_UNMETERED, so the wifi-only value is the
        // two flags together rather than the one that was passed. Pinned because it is the reason
        // the flags cannot be compared to the constant that produced them.
        assertEquals(
            Requirements.NETWORK or Requirements.NETWORK_UNMETERED,
            downloadRequirements(wifiOnly = true).requirements,
        )
        assertEquals(Requirements.NETWORK, downloadRequirements(wifiOnly = false).requirements)
        assertNotEquals(
            downloadRequirements(wifiOnly = true).requirements,
            downloadRequirements(wifiOnly = false).requirements,
        )
    }

    @Test
    fun `wifi-only is the default, so a data plan is never spent without asking`() {
        assertTrue(DefaultWifiOnly)
        assertTrue(DownloadPrefs().wifiOnly)
    }
}
