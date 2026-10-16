package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one "is this still the same session?" comparison every owner check goes through (#318). */
class SessionStateSameSessionTest {
    private val signedIn = SessionState(serverUrl = "https://ttsroad.example/", token = "token-a", username = "ann")

    @Test
    fun `the same server and token is the same session`() {
        assertTrue(signedIn.isSameSession(signedIn.copy()))
        // Details that do not identify the session do not split it.
        assertTrue(signedIn.isSameSession(signedIn.copy(serverName = "Renamed", isAdmin = true)))
    }

    @Test
    fun `a different token is a different session`() {
        assertFalse(signedIn.isSameSession(signedIn.copy(token = "token-b")))
    }

    @Test
    fun `a different server is a different session`() {
        assertFalse(signedIn.isSameSession(signedIn.copy(serverUrl = "https://other.example/")))
    }

    @Test
    fun `signing out ends the session`() {
        val signedOut = signedIn.copy(token = null)
        assertFalse(signedIn.isSameSession(signedOut))
        assertFalse(signedOut.isSameSession(signedIn))
        assertFalse(signedIn.isSameSession(null))
    }

    @Test
    fun `it says nothing about being signed in`() {
        val signedOut = SessionState(serverUrl = "https://ttsroad.example/")
        assertTrue(signedOut.isSameSession(signedOut.copy()))
        assertFalse(signedOut.isLoggedIn)
    }
}
