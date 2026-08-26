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
    fun `the server's own base url is carried through, and its absence is null not blank`() {
        // The download cache keys on this to tell one instance from another, and treats null as
        // "no identity" — an empty string arriving instead would look like a real one.
        val reported = ServerCapabilities.from(
            CapabilitiesResponse(
                server = CapabilityServerInfo(baseUrl = "https://ttsroad.example.com"),
            ),
        )
        val blank = ServerCapabilities.from(CapabilitiesResponse(server = CapabilityServerInfo(baseUrl = "  ")))
        val absent = ServerCapabilities.from(CapabilitiesResponse(server = CapabilityServerInfo()))

        assertEquals("https://ttsroad.example.com", reported.serverBaseUrl)
        assertNull(blank.serverBaseUrl)
        assertNull(absent.serverBaseUrl)
        assertNull(ServerCapabilities.Baseline.serverBaseUrl)
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

    @Test
    fun `the epub ceiling survives being a double, which is the only kind of number Moshi makes`() {
        // 100 MB is 104857600, which is well past what a Float holds exactly and is parsed into a
        // Double before anything here sees it. Reading it as an Int would also silently work for
        // this value and stop working the day a server raises the limit past 2 GB.
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(
                capabilities = mapOf("epub_upload" to true),
                limits = mapOf("max_epub_bytes" to 104857600.0),
            ),
        )

        assertTrue(capabilities.epubUpload)
        assertEquals(104857600L, capabilities.maxEpubBytes)
        assertEquals(104857600L, capabilities.effectiveMaxEpubBytes)
    }

    @Test
    fun `a server that offers epub upload without publishing a ceiling gets the documented one`() {
        // The flag and the limit shipped together, but a client cannot assume that: the fallback is
        // the value the server itself compiles in, so it refuses exactly what the server would.
        val capabilities = ServerCapabilities.from(
            CapabilitiesResponse(capabilities = mapOf("epub_upload" to true)),
        )

        assertTrue(capabilities.epubUpload)
        assertNull(capabilities.maxEpubBytes)
        assertEquals(DefaultMaxEpubBytes, capabilities.effectiveMaxEpubBytes)
        assertEquals(DefaultMaxEpubBytes, ServerCapabilities.Baseline.effectiveMaxEpubBytes)
    }
}
