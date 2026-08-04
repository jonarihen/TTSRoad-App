package dk.perspektiva.ttsroad.download

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
}
