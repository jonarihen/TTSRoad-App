package dk.perspektiva.ttsroad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two properties, both of which fail silently and permanently if they regress: reporting must stay
 * off unless someone deliberately turned it on, and a self-hosted server's address must not ride
 * along inside the reports when they do.
 */
class CrashReportingTest {

    @Test
    fun `no dsn means no reporting, which is the default build`() {
        // Nothing configured is the normal case — every build made without opting in, including
        // anything CI produces. It has to be a working configuration, not a broken one.
        assertNull(crashReportingDsn(null))
        assertNull(crashReportingDsn(""))
        assertNull(crashReportingDsn("   "))
        assertFalse(crashReportingEnabled(null))
        assertFalse(crashReportingEnabled(""))
    }

    @Test
    fun `a configured dsn turns reporting on`() {
        val dsn = "https://public@sentry.example.com/1"

        assertEquals(dsn, crashReportingDsn(dsn))
        assertTrue(crashReportingEnabled(dsn))
    }

    @Test
    fun `a dsn is trimmed, since it comes from a properties file`() {
        assertEquals(
            "https://public@sentry.example.com/1",
            crashReportingDsn("  https://public@sentry.example.com/1  "),
        )
    }

    @Test
    fun `the server address is redacted from a message`() {
        val redacted = redactServerUrl(
            "Failed to reach https://ttsroad.example.com/api/mobile/fictions",
            "https://ttsroad.example.com",
        )

        assertEquals("Failed to reach $RedactedServer/api/mobile/fictions", redacted)
    }

    @Test
    fun `redaction matches the origin, not the exact configured string`() {
        // The same server is signed in to as a bare origin but appears in errors with paths, ports
        // and query strings attached. Matching only the configured string would miss nearly all of
        // them, which is the failure mode worth testing for.
        val serverUrl = "https://ttsroad.example.com/"

        assertEquals(
            "GET $RedactedServer/api/mobile/chapters?playable_only=true failed",
            redactServerUrl(
                "GET https://ttsroad.example.com/api/mobile/chapters?playable_only=true failed",
                serverUrl,
            ),
        )
    }

    @Test
    fun `a LAN address with a port is redacted whole`() {
        // The common self-hosted shape, and the one that leaks a home network layout.
        assertEquals(
            "connect to $RedactedServer timed out",
            redactServerUrl("connect to http://192.168.1.20:8000 timed out", "http://192.168.1.20:8000"),
        )
    }

    @Test
    fun `a different host is left alone`() {
        // Royal Road and CDN URLs are not the user's server and are genuinely useful in a report.
        val text = "Failed to load https://royalroadcdn.example/cover.jpg"

        assertEquals(text, redactServerUrl(text, "https://ttsroad.example.com"))
    }

    @Test
    fun `nothing signed in means nothing to redact`() {
        val text = "Something failed"

        assertEquals(text, redactServerUrl(text, null))
        assertEquals(text, redactServerUrl(text, ""))
        assertEquals(text, redactServerUrl(text, "not-a-url"))
    }

    @Test
    fun `null and empty text survive redaction`() {
        assertNull(redactServerUrl(null, "https://ttsroad.example.com"))
        assertEquals("", redactServerUrl("", "https://ttsroad.example.com"))
    }

    @Test
    fun `redaction is case-insensitive on the host`() {
        assertEquals(
            "at $RedactedServer/x",
            redactServerUrl("at https://TTSRoad.Example.COM/x", "https://ttsroad.example.com"),
        )
    }

    @Test
    fun `every occurrence is redacted, not just the first`() {
        assertEquals(
            "$RedactedServer/a then $RedactedServer/b",
            redactServerUrl(
                "https://ttsroad.example.com/a then https://ttsroad.example.com/b",
                "https://ttsroad.example.com",
            ),
        )
    }
}
