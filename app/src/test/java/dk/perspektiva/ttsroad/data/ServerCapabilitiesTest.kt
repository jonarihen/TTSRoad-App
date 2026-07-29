package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoding a payload has to go through Moshi, because the untyped maps are the risky part. */
private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

private fun parse(json: String): ServerCapabilities =
    ServerCapabilities.from(moshi.adapter(CapabilitiesResponse::class.java).fromJson(json)!!)

class ServerCapabilitiesTest {
    @Test
    fun `a full payload enables exactly the flags the server set`() {
        val capabilities = parse(
            """
            {
              "api_version": 1,
              "server": {"name": "TTSRoad", "version": "1.4.0", "base_url": "https://ttsroad.example.com"},
              "capabilities": {
                "readalong": true,
                "search": false,
                "bookmarks": false,
                "delta_sync": false,
                "batch_progress": false,
                "audio_content_hash": false,
                "device_management": true
              },
              "limits": {"max_chapters_per_page": 200}
            }
            """.trimIndent(),
        )

        assertEquals(setOf(Capability.ReadAlong, Capability.DeviceManagement), capabilities.enabled)
        assertTrue(Capability.ReadAlong in capabilities)
        assertFalse(Capability.Search in capabilities)
        assertEquals("1.4.0", capabilities.serverVersion)
        assertEquals(200, capabilities.maxChaptersPerPage)
        assertTrue(capabilities.discovered)
    }

    @Test
    fun `every flag is gated on its own key, not on api_version`() {
        val capabilities = parse("""{"api_version": 9, "capabilities": {}}""")

        assertEquals(9, capabilities.apiVersion)
        assertEquals(emptySet<Capability>(), capabilities.enabled)
        Capability.entries.forEach { assertFalse("$it must stay off", it in capabilities) }
    }

    @Test
    fun `a missing capability key is false rather than an error`() {
        val capabilities = parse("""{"capabilities": {"search": true}}""")

        assertEquals(setOf(Capability.Search), capabilities.enabled)
        assertFalse(Capability.Bookmarks in capabilities)
        assertNull(capabilities.maxChaptersPerPage)
    }

    @Test
    fun `keys this build does not recognise are ignored`() {
        val capabilities = parse(
            """{"capabilities": {"bookmarks": true, "time_travel": true, "telepathy": true}}""",
        )

        assertEquals(setOf(Capability.Bookmarks), capabilities.enabled)
    }

    @Test
    fun `a capability that is not a boolean counts as off`() {
        val capabilities = parse(
            """{"capabilities": {"search": "yes", "bookmarks": 1, "delta_sync": null}}""",
        )

        assertEquals(emptySet<Capability>(), capabilities.enabled)
    }

    @Test
    fun `a limit that is not a number is dropped instead of failing the payload`() {
        val capabilities = parse(
            """{"capabilities": {"search": true}, "limits": {"max_chapters_per_page": "lots"}}""",
        )

        assertEquals(setOf(Capability.Search), capabilities.enabled)
        assertNull(capabilities.maxChaptersPerPage)
    }

    @Test
    fun `the baseline and unknown answers both leave every feature off`() {
        listOf(ServerCapabilities.Baseline, ServerCapabilities.Unknown).forEach { capabilities ->
            assertEquals(emptySet<Capability>(), capabilities.enabled)
            assertNull(capabilities.serverVersion)
        }
        // Only the 404 answer counts as an answer: the UI says "old server" for one and
        // "not asked yet" for the other.
        assertTrue(ServerCapabilities.Baseline.discovered)
        assertFalse(ServerCapabilities.Unknown.discovered)
    }

    @Test
    fun `the probe summary names the server and the features it offers`() {
        val reached = ServerProbe.Reached(
            ServerCapabilities(
                serverVersion = "1.4.0",
                enabled = setOf(Capability.ReadAlong, Capability.DeviceManagement),
                discovered = true,
            ),
        )

        assertEquals("TTSRoad 1.4.0 - Read-along, Device management", reached.summary)
    }

    @Test
    fun `the probe summary says baseline for a server with nothing optional`() {
        val versioned = ServerProbe.Reached(ServerCapabilities(serverVersion = "1.4.0", discovered = true))
        assertEquals("TTSRoad 1.4.0 - baseline features only", versioned.summary)

        assertEquals(
            "Server reached - baseline features only",
            ServerProbe.Reached(ServerCapabilities.Baseline).summary,
        )
        assertEquals("Could not reach this server", ServerProbe.Unreachable.summary)
    }
}
