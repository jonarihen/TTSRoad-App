package dk.perspektiva.ttsroad.download

import dk.perspektiva.ttsroad.data.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCacheKeysTest {
    @Test
    fun `the same chapter keeps one cache key across a change of server address`() {
        // The whole point: signing in again against the LAN IP, the domain, or a VPN address must
        // not orphan gigabytes of already-downloaded audio.
        val lan = DownloadCacheKeys.forUrl("http://192.168.1.20:8000/audio/my-fiction/0001.mp3")
        val domain = DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3")
        val emulator = DownloadCacheKeys.forUrl("http://10.0.2.2:8000/audio/my-fiction/0001.mp3")

        assertEquals(lan, domain)
        assertEquals(lan, emulator)
    }

    @Test
    fun `the key is the server-relative path`() {
        assertEquals(
            "/audio/my-fiction/0001.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `different chapters get different keys`() {
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0002.mp3"),
        )
    }

    @Test
    fun `two fictions sharing a filename do not collide`() {
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/one/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/two/0001.mp3"),
        )
    }

    @Test
    fun `a relative url is already server-relative and keys the same as the absolute one`() {
        assertEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my-fiction/0001.mp3"),
            DownloadCacheKeys.forUrl("/audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `a path without a leading slash is normalised`() {
        assertEquals(
            "/audio/my-fiction/0001.mp3",
            DownloadCacheKeys.forUrl("audio/my-fiction/0001.mp3"),
        )
    }

    @Test
    fun `a re-rendered chapter with a new query is a new key`() {
        // Query is kept deliberately: if the backend ever versions a re-synthesised chapter through
        // the URL, reusing the old key would play stale audio forever.
        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3?v=1"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3?v=2"),
        )
    }

    @Test
    fun `a fragment is not part of the key`() {
        assertEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3"),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3#t=30"),
        )
    }

    @Test
    fun `unencoded spaces in a filename survive`() {
        // java.net.URI would reject these; the backend really does emit them for EPUB imports.
        assertEquals(
            "/audio/my fiction/chapter 1.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/my fiction/chapter 1.mp3"),
        )
    }

    @Test
    fun `a blank url keys to itself rather than throwing`() {
        assertEquals("", DownloadCacheKeys.forUrl(""))
    }

    @Test
    fun `two servers holding the same slug do not share a cache entry`() {
        // The collision this scoping exists for: a path is only unique per server, so the same
        // fiction slug on two instances would otherwise be one file.
        val mine = DownloadCacheKeys.serverIdentity("https://ttsroad.example.com")
        val theirs = DownloadCacheKeys.serverIdentity("https://ttsroad.other.example")

        assertNotEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", mine),
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", theirs),
        )
    }

    @Test
    fun `scoping still survives a change of server address`() {
        // The 0.8.0 property has to hold inside the new keyspace too: the identity comes from what
        // the server says about itself, not from how the phone reached it.
        val identity = DownloadCacheKeys.serverIdentity("https://ttsroad.example.com")

        assertEquals(
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", identity),
            DownloadCacheKeys.forUrl("http://192.168.1.20:8000/audio/f/0001.mp3", identity),
        )
    }

    @Test
    fun `an unknown identity keeps the key that shipped in 0_8_0`() {
        // Older servers advertise no base_url, and capabilities have not been fetched at all on
        // first launch. Both must key exactly as before rather than inventing an identity.
        assertEquals(
            "/audio/f/0001.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", null),
        )
        assertEquals(
            "/audio/f/0001.mp3",
            DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", ""),
        )
    }

    @Test
    fun `putting the server behind TLS does not orphan its downloads`() {
        // Same instance, same downloads — only the scheme in its configured BASE_URL changed.
        assertEquals(
            DownloadCacheKeys.serverIdentity("http://ttsroad.example.com"),
            DownloadCacheKeys.serverIdentity("https://ttsroad.example.com"),
        )
    }

    @Test
    fun `an identity ignores trailing slashes and letter case`() {
        val expected = DownloadCacheKeys.serverIdentity("https://ttsroad.example.com")

        assertEquals(expected, DownloadCacheKeys.serverIdentity("https://TTSRoad.Example.com/"))
        assertEquals(expected, DownloadCacheKeys.serverIdentity("  https://ttsroad.example.com  "))
    }

    @Test
    fun `a port and a path prefix are part of the identity`() {
        // Two instances behind one reverse proxy differ only by port or mount point.
        assertNotEquals(
            DownloadCacheKeys.serverIdentity("https://host.example:8000"),
            DownloadCacheKeys.serverIdentity("https://host.example:8001"),
        )
        assertNotEquals(
            DownloadCacheKeys.serverIdentity("https://host.example/books"),
            DownloadCacheKeys.serverIdentity("https://host.example/audiobooks"),
        )
    }

    @Test
    fun `a base url with nothing usable in it yields no identity`() {
        assertNull(DownloadCacheKeys.serverIdentity(null))
        assertNull(DownloadCacheKeys.serverIdentity(""))
        assertNull(DownloadCacheKeys.serverIdentity("   "))
        assertNull(DownloadCacheKeys.serverIdentity("not-a-url"))
        assertNull(DownloadCacheKeys.serverIdentity("https://"))
    }

    @Test
    fun `a scoped key is told apart from a 0_8_0 one`() {
        // What the re-key migration reads: an entry already naming a server belongs to that server
        // and must not be moved into another's keyspace.
        val identity = DownloadCacheKeys.serverIdentity("https://ttsroad.example.com")

        assertTrue(
            DownloadCacheKeys.isScoped(
                DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", identity),
            ),
        )
        assertFalse(
            DownloadCacheKeys.isScoped(
                DownloadCacheKeys.forUrl("https://ttsroad.example.com/audio/f/0001.mp3", null),
            ),
        )
        assertFalse(DownloadCacheKeys.isScoped(""))
    }

    @Test
    fun `a new address never inherits the previous discovery identity`() {
        val identities = SessionCacheIdentity()
        val discovered = ServerCapabilities(
            discoveryBaseUrl = "https://server-a.example/",
            serverBaseUrl = "https://canonical-a.example",
        )

        assertEquals("canonical-a.example", identities.forServer("https://server-a.example/", discovered))
        assertEquals("address:https://server-b.example/", identities.forServer("https://server-b.example/", discovered))
        assertEquals("address:https://server-b.example/", identities.forServer("https://server-b.example/", ServerCapabilities.Baseline))
        assertNull(identities.advertisedForServer("https://server-b.example/", discovered))
    }

    @Test
    fun `missing and failed discovery keep a learned same-address identity without migration`() {
        val remembered = mutableMapOf<String, String>()
        val identities = SessionCacheIdentity(remember = { address, identity -> remembered[address] = identity })
        val address = "https://server-a.example/"
        val discovered = ServerCapabilities(discoveryBaseUrl = address, serverBaseUrl = "https://canonical.example")
        identities.forServer(address, discovered)

        val unavailable = ServerCapabilities(discoveryBaseUrl = address)
        assertEquals("canonical.example", identities.forServer(address, unavailable))
        assertNull(identities.advertisedForServer(address, unavailable))
        assertEquals("canonical.example", identities.forServer(address, ServerCapabilities.Baseline))
        assertEquals("canonical.example", SessionCacheIdentity(remembered).forServer(address, unavailable))
    }

    @Test
    fun `two addresses advertising the same canonical identity retain one scope`() {
        val identities = SessionCacheIdentity()
        val canonical = "https://canonical.example/"
        val first = identities.forServer(
            "https://public.example/",
            ServerCapabilities(discoveryBaseUrl = "https://public.example/", serverBaseUrl = canonical),
        )
        val second = identities.forServer(
            "http://192.168.1.20:8000/",
            ServerCapabilities(discoveryBaseUrl = "http://192.168.1.20:8000/", serverBaseUrl = canonical),
        )

        assertEquals(first, second)
        assertEquals(first, identities.forServer("https://public.example/", ServerCapabilities.Baseline))
    }

    @Test
    fun `address fallback cannot collide with another servers advertised identity`() {
        val identities = SessionCacheIdentity()
        val advertised = identities.forServer(
            "https://server-a.example/",
            ServerCapabilities(discoveryBaseUrl = "https://server-a.example/", serverBaseUrl = "https://server-b.example/"),
        )
        val fallback = identities.forServer("https://server-b.example/", ServerCapabilities.Baseline)

        assertNotEquals(advertised, fallback)
        assertNotEquals(
            fallback,
            identities.forServer("http://server-b.example/", ServerCapabilities.Baseline),
        )
    }

    @Test
    fun `equivalent origins normalize case default ports and trailing slashes`() {
        val identities = SessionCacheIdentity()
        val equivalent = listOf(
            "https://TTSRoad.Example/Books/",
            "HTTPS://ttsroad.example:443/Books",
            " https://ttsroad.example/Books/// ",
        )
        assertEquals(1, equivalent.map { identities.fallbackForServer(it) }.toSet().size)
        assertEquals("address:https://ttsroad.example/Books/", identities.fallbackForServer(equivalent.first()))
        assertEquals(identities.fallbackForServer("http://host/"), identities.fallbackForServer("http://HOST:80"))
    }

    @Test
    fun `address normalization does not conflate schemes nondefault ports or path namespaces`() {
        val identities = SessionCacheIdentity()
        val addresses = listOf(
            "https://host/Books/", "http://host/Books/", "https://host:444/Books/",
            "https://host/books/", "https://host/Books2/", "https://other/Books/",
        )
        assertEquals(addresses.size, addresses.map { identities.fallbackForServer(it) }.toSet().size)
    }

    @Test
    fun `persisted canonical identities and discovery ownership normalize equivalent addresses`() {
        val identities = SessionCacheIdentity(mapOf("https://HOST:443/Books" to "canonical.example"))
        assertEquals("canonical.example", identities.forServer("https://host/Books/", ServerCapabilities.Baseline))
        assertEquals(
            "canonical.example",
            identities.advertisedForServer(
                "https://HOST:443/Books/",
                ServerCapabilities(discoveryBaseUrl = "https://host/Books", serverBaseUrl = "https://canonical.example/"),
            ),
        )
        assertNull(
            identities.advertisedForServer(
                "https://host/books/",
                ServerCapabilities(discoveryBaseUrl = "https://host/Books/", serverBaseUrl = "https://canonical.example/"),
            ),
        )
    }

    @Test
    fun `legacy ownership requires matching origin and exact audio namespace`() {
        val identities = SessionCacheIdentity()
        assertTrue(identities.ownsLegacyUrl("https://HOST:443/Books/", "https://host/Books/audio/chapter.mp3", ServerCapabilities.Baseline))
        listOf(
            "https://host/books/", "https://host/", "https://host/Books2/", "https://other/Books/",
            "https://host:444/Books/", "http://host/Books/",
        ).forEach { address ->
            assertFalse(identities.ownsLegacyUrl(address, "https://host/Books/audio/chapter.mp3", ServerCapabilities.Baseline))
        }
    }

    @Test
    fun `new canonical mappings are persisted under a normalized address`() {
        val remembered = mutableMapOf<String, String>()
        val identities = SessionCacheIdentity(remember = { address, identity -> remembered[address] = identity })
        identities.forServer(
            "https://HOST:443/Books",
            ServerCapabilities(discoveryBaseUrl = "https://host/Books/", serverBaseUrl = "https://canonical.example/"),
        )
        assertEquals(mapOf("https://host/Books/" to "canonical.example"), remembered)
        assertEquals("canonical.example", SessionCacheIdentity(remembered).forServer("https://HOST/Books", ServerCapabilities.Baseline))
    }

    @Test
    fun `unidentified servers get separate scoped keys including port and mount point`() {
        val identities = SessionCacheIdentity()
        val addresses = listOf("https://host.example:8000/", "https://host.example:8001/", "https://host.example/books/")
        val keys = addresses.map { address ->
            DownloadCacheKeys.forUrl("/audio/chapter.mp3", identities.forServer(address, ServerCapabilities.Baseline))
        }

        assertEquals(addresses.size, keys.toSet().size)
        assertTrue(keys.all(DownloadCacheKeys::isScoped))
    }
}
