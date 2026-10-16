package dk.perspektiva.ttsroad.data

import dk.perspektiva.ttsroad.player.PendingProgress
import dk.perspektiva.ttsroad.player.ownedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressesTest {
    @Test
    fun `addresses compare however they were spelled, but a path prefix is a different address`() {
        assertTrue(sameServerAddress("https://Home.Example/", "https://home.example"))
        assertTrue(sameServerAddress("https://home.example:443/", "https://home.example/"))
        assertTrue(sameServerAddress("HTTP://10.0.0.5:8000", "http://10.0.0.5:8000/"))
        assertFalse(sameServerAddress("https://home.example/", "http://home.example/"))
        assertFalse(sameServerAddress("https://home.example/", "https://home.example:8443/"))
        assertFalse(sameServerAddress("https://home.example/ttsroad/", "https://home.example/"))
        assertFalse(sameServerAddress("", "https://home.example/"))
        assertFalse(sameServerAddress(null, null))
    }

    @Test
    fun `a scheme-less address matches nothing, not even itself, in every ownership check`() {
        // The audio check used to guess https:// for it while progress and addresses did not (#457).
        assertFalse(sameServerAddress("home.example", "https://home.example/"))
        assertFalse(sameServerAddress("home.example", "home.example"))
        assertFalse(
            dk.perspektiva.ttsroad.media.sameAudioServer(
                dk.perspektiva.ttsroad.core.AudioAuthSnapshot("home.example"),
                dk.perspektiva.ttsroad.core.AudioAuthSnapshot("https://home.example/"),
            ),
        )
        assertFalse(isAudioUnderServer("https://home.example/audio/a.mp3", "home.example"))
    }

    @Test
    fun `audio belongs to a server only on its origin and under its path prefix`() {
        assertTrue(isAudioUnderServer("https://Home.Example:443/audio/a.mp3", "https://home.example"))
        assertTrue(isAudioUnderServer("https://home.example/tts/audio/a.mp3", "https://home.example/tts/"))
        assertFalse(isAudioUnderServer("https://home.example/audio/a.mp3", "https://home.example/tts/"))
        assertFalse(isAudioUnderServer("http://home.example/audio/a.mp3", "https://home.example/"))
        assertFalse(isAudioUnderServer("https://home.example/covers/a.jpg", "https://home.example/"))
    }

    @Test
    fun `switching moves the address being left to the front and drops the one now in use`() {
        val after = alternatesAfterSwitch(
            current = "https://lan.example/",
            alternates = listOf("https://ts.example/", "https://old.example/"),
            next = "https://TS.example",
        )

        assertEquals(listOf("https://lan.example/", "https://old.example/"), after)
    }

    @Test
    fun `remembering never lists the address in use and does not duplicate a saved one`() {
        assertEquals(
            listOf("https://ts.example/"),
            alternatesAfterRemember("https://lan.example/", listOf("https://ts.example"), "https://ts.example/"),
        )
        assertEquals(
            emptyList<String>(),
            alternatesAfterRemember("https://lan.example/", emptyList(), "https://LAN.example"),
        )
        assertEquals(
            listOf("https://new.example/", "https://ts.example/"),
            alternatesAfterRemember("https://lan.example/", listOf("https://ts.example/"), "https://new.example/"),
        )
    }

    @Test
    fun `the saved list is bounded`() {
        var alternates = emptyList<String>()
        repeat(20) { alternates = alternatesAfterRemember("https://lan.example/", alternates, "https://s$it.example/") }

        assertEquals(MaxAlternateAddresses, alternates.size)
        assertEquals("https://s19.example/", alternates.first())
    }

    @Test
    fun `forgetting removes only that address`() {
        assertEquals(
            listOf("https://b.example/"),
            alternatesAfterForget(listOf("https://a.example/", "https://b.example/"), "https://A.example"),
        )
    }

    @Test
    fun `loopback and unspecified identities name no particular server`() {
        listOf(
            "localhost:8787", "localhost", "app.localhost", "127.0.0.1:8000", "127.1.2.3/ttsroad",
            "0.0.0.0:8787", "[::1]:8787", "[::]", "", "  ", null,
        ).forEach { assertTrue("$it", isPlaceholderIdentity(it)) }
        listOf(
            "tts.example", "tts.example:8443/ttsroad", "192.168.1.10:8000", "[fd7a:115c::1]:443",
            "server.tailnet.ts.net", "localhost.example.com",
        ).forEach { assertFalse("$it", isPlaceholderIdentity(it)) }
    }

    @Test
    fun `the stored form round-trips and ignores blanks`() {
        val list = listOf("https://a.example/", "http://10.0.0.5:8000/")
        assertEquals(list, decodeAlternates(encodeAlternates(list)))
        assertEquals(emptyList<String>(), decodeAlternates(null))
        assertEquals(emptyList<String>(), decodeAlternates(""))
        assertEquals(listOf("https://a.example/"), decodeAlternates("\nhttps://a.example/\n\n"))
    }

    @Test
    fun `known addresses put the one in use first`() {
        val session = SessionState(serverUrl = "https://lan.example/", alternateUrls = listOf("https://ts.example/"))
        assertEquals(listOf("https://lan.example/", "https://ts.example/"), session.knownAddresses)
        assertEquals(emptyList<String>(), SessionState().knownAddresses)
    }

    @Test
    fun `progress heard over a saved address of the same server is still this account's`() {
        val heardAtHome = PendingProgress(
            fictionId = 1,
            chapterId = 2,
            positionSeconds = 30.0,
            isPlayed = false,
            clientUpdatedAt = "2026-10-16T08:00:00.000Z",
            recordedAtMillis = 1L,
            ownerServerUrl = "https://lan.example/",
            ownerAccount = "admin",
        )
        val away = SessionState(
            serverUrl = "https://ts.example/",
            token = "t",
            username = "admin",
            alternateUrls = listOf("https://lan.example/"),
        )

        assertTrue(heardAtHome.ownedBy(away))
        assertFalse("another account", heardAtHome.ownedBy(away.copy(username = "guest")))
        assertFalse("address never confirmed", heardAtHome.ownedBy(away.copy(alternateUrls = emptyList())))
        assertFalse("signed out", heardAtHome.ownedBy(away.copy(token = null)))
    }
}
