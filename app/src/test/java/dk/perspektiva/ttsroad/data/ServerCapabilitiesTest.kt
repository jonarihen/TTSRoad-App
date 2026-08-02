package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing/gating rules for [ServerCapabilities]. The contract is deliberately defensive:
 * an unknown or absent key is `false`, never an error, so a client can talk to a server that is
 * older *or* newer than it is.
 */
class ServerCapabilitiesTest {
    @Test
    fun `a full payload reports every advertised capability`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(
                apiVersion = 1,
                server = CapabilityServerInfo(name = "TTSRoad", version = "1.4.0"),
                capabilities = mapOf(
                    "readalong" to true,
                    "search" to false,
                    "bookmarks" to false,
                    "delta_sync" to false,
                    "batch_progress" to false,
                    "audio_content_hash" to false,
                    "device_management" to true,
                ),
                limits = mapOf("max_chapters_per_page" to 200.0),
            ),
        )

        assertTrue(capabilities.readAlong)
        assertTrue(capabilities.deviceManagement)
        assertFalse(capabilities.search)
        assertFalse(capabilities.bookmarks)
        assertFalse(capabilities.deltaSync)
        assertFalse(capabilities.batchProgress)
        assertFalse(capabilities.audioContentHash)
        assertEquals("1.4.0", capabilities.serverVersion)
        assertEquals(200, capabilities.maxChaptersPerPage)
    }

    @Test
    fun `a capability the server does not mention is false`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("readalong" to true)),
        )

        assertTrue(capabilities.readAlong)
        assertFalse(capabilities.search)
        assertFalse(capabilities.deviceManagement)
    }

    @Test
    fun `a capability key the client does not recognise is ignored`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("readalong" to true, "time_travel" to true),
            ),
        )

        assertTrue(capabilities.readAlong)
        assertFalse(capabilities.search)
    }

    @Test
    fun `a non-boolean capability value is treated as false rather than crashing`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("readalong" to "partial", "search" to 1.0, "bookmarks" to null),
            ),
        )

        assertFalse(capabilities.readAlong)
        assertFalse(capabilities.search)
        assertFalse(capabilities.bookmarks)
    }

    @Test
    fun `api_version is never a proxy for an additive feature`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(apiVersion = 9, capabilities = emptyMap()),
        )

        assertFalse(capabilities.readAlong)
        assertFalse(capabilities.search)
        assertFalse(capabilities.bookmarks)
        assertFalse(capabilities.deltaSync)
        assertFalse(capabilities.batchProgress)
        assertFalse(capabilities.audioContentHash)
        assertFalse(capabilities.deviceManagement)
    }

    @Test
    fun `the baseline stands in for a server with no discovery endpoint`() {
        val baseline = ServerCapabilities.Baseline

        assertFalse(baseline.readAlong)
        assertFalse(baseline.search)
        assertFalse(baseline.bookmarks)
        assertFalse(baseline.deltaSync)
        assertFalse(baseline.batchProgress)
        assertFalse(baseline.audioContentHash)
        assertFalse(baseline.deviceManagement)
        assertNull(baseline.serverVersion)
        assertNull(baseline.maxChaptersPerPage)
    }

    @Test
    fun `a missing limits block leaves the limit unset rather than zero`() {
        val capabilities = ServerCapabilities.from(CapabilitiesResponse())

        assertNull(capabilities.maxChaptersPerPage)
    }

    @Test
    fun `a non-numeric limit is ignored`() {
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(limits = mapOf("max_chapters_per_page" to "lots")),
        )

        assertNull(capabilities.maxChaptersPerPage)
    }
}
