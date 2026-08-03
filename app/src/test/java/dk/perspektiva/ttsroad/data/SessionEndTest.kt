package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndTest {

    @Test
    fun `an expired token keeps the server's own wording`() {
        val end = parseSessionEnd(
            """{"detail":{"message":"This device session expired. Sign in again.","reason":"token_expired"}}""",
        )

        assertEquals(SessionEndReason.Expired, end.reason)
        assertEquals("This device session expired. Sign in again.", end.message)
    }

    @Test
    fun `a revoked token is told apart from an expired one`() {
        val end = parseSessionEnd(
            """{"detail":{"message":"This device was signed out.","reason":"token_revoked"}}""",
        )

        assertEquals(SessionEndReason.Revoked, end.reason)
        assertEquals("This device was signed out.", end.message)
    }

    @Test
    fun `an invalid token is recognised`() {
        val end = parseSessionEnd("""{"detail":{"message":"Bad token.","reason":"invalid_token"}}""")

        assertEquals(SessionEndReason.Invalid, end.reason)
        assertEquals("Bad token.", end.message)
    }

    /**
     * An older server, a proxy, or a reason this build has never heard of. The session still has to
     * end — the token demonstrably does not work — so the only thing lost is the explanation.
     */
    @Test
    fun `an unknown reason still ends the session with something to show`() {
        for (body in listOf(
            """{"detail":{"reason":"teapot"}}""",
            """{"detail":"Invalid token"}""",
            """{"detail":{}}""",
            "{}",
            "",
            "not json at all",
        )) {
            val end = parseSessionEnd(body)
            assertEquals("body: $body", SessionEndReason.Unknown, end.reason)
            assertTrue("body: $body", end.message.isNotBlank())
        }
    }

    /** FastAPI's plain-string detail is the pre-structured form, and still worth showing. */
    @Test
    fun `a plain string detail is used as the message`() {
        assertEquals("Invalid token", parseSessionEnd("""{"detail":"Invalid token"}""").message)
    }

    @Test
    fun `a null body falls back to the generic wording`() {
        val end = parseSessionEnd(null)

        assertEquals(SessionEndReason.Unknown, end.reason)
        assertTrue(end.message.isNotBlank())
    }

    /** A reason with no message must not surface an empty banner on the login screen. */
    @Test
    fun `each reason has a fallback message when the server sends none`() {
        for (reason in listOf("token_expired", "token_revoked", "invalid_token")) {
            val end = parseSessionEnd("""{"detail":{"reason":"$reason"}}""")
            assertTrue("reason: $reason", end.message.isNotBlank())
        }
    }

    @Test
    fun `a non-401 detail message is still readable for plain error reporting`() {
        assertEquals("Incorrect username or password", detailMessage("""{"detail":"Incorrect username or password"}"""))
        assertEquals("boom", detailMessage("""{"detail":{"message":"boom"}}"""))
        assertNull(detailMessage("""{"detail":{}}"""))
        assertNull(detailMessage("nonsense"))
        assertNull(detailMessage(null))
    }
}
