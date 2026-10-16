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

    @Test
    fun `the bare host in an unresolved host error is redacted`() {
        assertEquals(
            "java.net.UnknownHostException: Unable to resolve host \"$RedactedServer\": No address associated with hostname",
            redactServerUrl(
                "java.net.UnknownHostException: Unable to resolve host \"home.example.com\": No address associated with hostname",
                "https://home.example.com",
            ),
        )
    }

    @Test
    fun `the host and the address it resolved to are redacted from a connect failure`() {
        assertEquals(
            "Failed to connect to $RedactedServer",
            redactServerUrl(
                "Failed to connect to home.example.com/203.0.113.4:8000",
                "http://home.example.com:8000",
            ),
        )
    }

    @Test
    fun `a bare IP server is redacted with and without its port`() {
        val serverUrl = "http://100.64.0.5:8000"

        assertEquals("failed to connect to /$RedactedServer", redactServerUrl("failed to connect to /100.64.0.5", serverUrl))
        assertEquals("timeout at $RedactedServer.", redactServerUrl("timeout at 100.64.0.5:8000.", serverUrl))
    }

    @Test
    fun `a certificate error naming the host is redacted case-insensitively`() {
        assertEquals(
            "Hostname $RedactedServer not verified",
            redactServerUrl("Hostname Home.Example.com not verified", "https://home.example.com/"),
        )
    }

    @Test
    fun `a longer name that only contains the host is left alone`() {
        val text = "Failed to load https://cdn.home.example.com.mirror.net/cover.jpg and myhome.example.com"

        assertEquals(text, redactServerUrl(text, "https://home.example.com"))
    }

    @Test
    fun `a path after a redacted origin is not mistaken for a resolved address`() {
        assertEquals(
            "GET $RedactedServer/api/mobile/fictions",
            redactServerUrl("GET https://home.example.com/api/mobile/fictions", "https://home.example.com"),
        )
    }

    @Test
    fun `the status says when no dsn is built in`() {
        assertEquals("Off (not configured in this build)", crashReportingStatus(null, enabledOnDevice = true))
        assertEquals("Off (not configured in this build)", crashReportingStatus("  ", enabledOnDevice = true))
    }

    @Test
    fun `the status names where reports go, without the public key`() {
        val dsn = "https://abc123@sentry.example.com/4"

        assertEquals("sentry.example.com", crashReportingHost(dsn))
        assertEquals("sentry.example.com:9000", crashReportingHost("https://abc123@sentry.example.com:9000/4"))
        assertEquals("On → sentry.example.com", crashReportingStatus(dsn, enabledOnDevice = true))
        assertEquals(
            "Off on this phone (build reports to sentry.example.com)",
            crashReportingStatus(dsn, enabledOnDevice = false),
        )
    }

    @Test
    fun `remembered servers are origins, newest first, deduplicated and capped`() {
        var known = emptyList<String>()
        known = rememberServer(known, "https://a.example/")
        known = rememberServer(known, "http://10.0.0.2:8000/api")
        known = rememberServer(known, "https://A.example")
        known = rememberServer(known, "")
        known = rememberServer(known, null)

        assertEquals(listOf("https://A.example", "http://10.0.0.2:8000"), known)

        repeat(20) { known = rememberServer(known, "https://s$it.example") }
        assertEquals(RememberedServerLimit, known.size)
        assertEquals("https://s19.example", known.first())
    }

    @Test
    fun `every address of the signed-in server is remembered, the one in use first`() {
        val known = rememberServers(
            listOf("https://old.example"),
            listOf("https://ts.example/", "http://192.168.1.10:8000/"),
        )

        assertEquals(listOf("https://ts.example", "http://192.168.1.10:8000", "https://old.example"), known)
        assertEquals(
            "$RedactedServer/a $RedactedServer/b",
            redactServerUrls("https://ts.example/a http://192.168.1.10:8000/b", known),
        )
    }

    @Test
    fun `string values in a map are redacted and others kept`() {
        val redacted = redactStringValues(mapOf("url" to "https://a.example/x", "n" to 2, "none" to null)) {
            redactServerUrl(it, "https://a.example")
        }

        assertEquals(mapOf("url" to "$RedactedServer/x", "n" to 2, "none" to null), redacted)
    }
}
