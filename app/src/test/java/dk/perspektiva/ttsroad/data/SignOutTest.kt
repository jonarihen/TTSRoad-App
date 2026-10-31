package dk.perspektiva.ttsroad.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutTest {
    @Test
    fun `normal sign out flushes before revoking`() = runTest {
        val calls = mutableListOf<String>()
        val result = signOut(false, flush = { calls += "flush"; 0 }, logout = { calls += "logout"; true })

        assertEquals(listOf("flush", "logout"), calls)
        assertEquals(SignOutStep.Done(true), result)
    }

    @Test
    fun `an unsent queue prevents logout`() = runTest {
        val result = signOut(false, flush = { 3 }, logout = { error("Must keep the session") })

        assertEquals(SignOutStep.Unsynced(3), result)
    }

    @Test
    fun `sign out anyway skips flushing and returns the revoke result`() = runTest {
        val result = signOut(true, flush = { error("Already warned") }, logout = { false })

        assertEquals(SignOutStep.Done(false), result)
    }

    @Test
    fun `flush failure never discards the session`() = runTest {
        val result = runCatching {
            signOut(false, flush = { error("Flush failed") }, logout = { error("Must not revoke") })
        }

        assertTrue(result.isFailure)
        assertEquals("Flush failed", result.exceptionOrNull()?.message)
    }
}
