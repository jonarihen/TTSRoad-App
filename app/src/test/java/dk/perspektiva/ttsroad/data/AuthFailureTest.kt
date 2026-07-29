package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 401 bodies the backend actually sends, plus the shapes it might send from an older build or
 * a proxy in front of it. Every one of them has to produce a notice — this parser runs on the
 * sign-out path, where the worst outcome is throwing and leaving a dead token in place.
 */
class AuthFailureTest {
    @Test
    fun `an expired session keeps the server's reason and message`() {
        val notice = parseSessionEndedNotice(
            """{"detail":{"message":"This device session expired. Sign in again.",""" +
                """"reason":"token_expired"}}""",
        )

        assertEquals(AuthFailureReason.Expired, notice.reason)
        assertEquals("This device session expired. Sign in again.", notice.message)
    }

    @Test
    fun `a revoked session is told apart from an expired one`() {
        val notice = parseSessionEndedNotice(
            """{"detail":{"message":"This device session was revoked. Sign in again.",""" +
                """"reason":"token_revoked"}}""",
        )

        assertEquals(AuthFailureReason.Revoked, notice.reason)
    }

    @Test
    fun `an invalid token is recognised`() {
        val notice = parseSessionEndedNotice(
            """{"detail":{"message":"The bearer token is invalid.","reason":"invalid_token"}}""",
        )

        assertEquals(AuthFailureReason.Invalid, notice.reason)
        assertEquals("The bearer token is invalid.", notice.message)
    }

    @Test
    fun `a plain string detail is still shown to the user`() {
        val notice = parseSessionEndedNotice("""{"detail":"Not authenticated"}""")

        assertEquals(AuthFailureReason.Unknown, notice.reason)
        assertEquals("Not authenticated", notice.message)
    }

    @Test
    fun `an unrecognised reason falls back but keeps the message`() {
        val notice = parseSessionEndedNotice(
            """{"detail":{"message":"Something else","reason":"who_knows"}}""",
        )

        assertEquals(AuthFailureReason.Unknown, notice.reason)
        assertEquals("Something else", notice.message)
    }

    @Test
    fun `a reason with no message still gets usable wording`() {
        val notice = parseSessionEndedNotice("""{"detail":{"reason":"token_expired"}}""")

        assertEquals(AuthFailureReason.Expired, notice.reason)
        assertEquals(SessionEndedNotice.Fallback.message, notice.message)
    }

    @Test
    fun `an empty, malformed or missing body falls back instead of throwing`() {
        listOf(null, "", "   ", "not json at all", "<html>502 Bad Gateway</html>", "{}").forEach {
            assertEquals("body: $it", SessionEndedNotice.Fallback, parseSessionEndedNotice(it))
        }
    }

    @Test
    fun `a blank message is not shown as an empty notice`() {
        val notice = parseSessionEndedNotice("""{"detail":{"message":"  ","reason":"token_revoked"}}""")

        assertEquals(AuthFailureReason.Revoked, notice.reason)
        assertEquals(SessionEndedNotice.Fallback.message, notice.message)
    }

    @Test
    fun `login failures keep reading a plain detail message`() {
        assertEquals(
            "Incorrect username or password",
            parseDetailMessage("""{"detail":"Incorrect username or password"}"""),
        )
        assertEquals(
            "Invalid authentication code",
            parseDetailMessage(
                """{"detail":{"message":"Invalid authentication code","totp_required":true}}""",
            ),
        )
        assertEquals(null, parseDetailMessage("nonsense"))
    }
}
