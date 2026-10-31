package dk.perspektiva.ttsroad.core

import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class UserFacingErrorsTest {
    @Test
    fun `an unknown host says the server cannot be found and keeps the raw text`() {
        val error = UnknownHostException("Unable to resolve host \"https\": No address associated with hostname")
            .toUserFacingError("Login failed")

        assertEquals(CantFindServerMessage, error.message)
        assertTrue(error.details!!.contains("Unable to resolve host"))
    }

    @Test
    fun `a refused connection says the server cannot be reached`() {
        assertEquals(
            CantReachServerMessage,
            ConnectException("Failed to connect to /10.0.0.2:8000").userMessage("x"),
        )
    }

    @Test
    fun `timeouts say the server was slow`() {
        assertEquals(TimeoutMessage, SocketTimeoutException("timeout").userMessage("x"))
        assertEquals(TimeoutMessage, InterruptedIOException("timeout").userMessage("x"))
    }

    @Test
    fun `blocked cleartext explains that http is not allowed`() {
        val error = UnknownServiceException(
            "CLEARTEXT communication to 100.64.1.2 not permitted by network security policy",
        ).toUserFacingError("x")

        assertEquals(CleartextBlockedMessage, error.message)
        assertTrue(error.details!!.contains("CLEARTEXT"))
    }

    @Test
    fun `an untrusted certificate is found even when wrapped in a handshake failure`() {
        val wrapped = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor")
            .apply { initCause(CertPathValidatorException("Trust anchor for certification path not found.")) }

        val error = wrapped.toUserFacingError("x")

        assertEquals(UntrustedCertificateMessage, error.message)
        assertTrue(error.details!!.contains("caused by CertPathValidatorException"))
    }

    @Test
    fun `a hostname mismatch is not reported as an untrusted certificate`() {
        val mismatch = javax.net.ssl.SSLPeerUnverifiedException(
            "Hostname 100.64.0.7 not verified:\n    certificate: sha256/abc\n    DN: CN=nas.example.ts.net",
        )

        val error = mismatch.toUserFacingError("x")

        assertEquals(HostnameMismatchMessage, error.message)
        assertTrue(error.details!!.contains("subject alternative names"))
    }

    @Test
    fun `listener copy names someone to ask and the admin advice stays under details`() {
        val cert = CertPathValidatorException("Trust anchor for certification path not found.").toUserFacingError("x")
        assertTrue(cert.message.contains("Ask whoever set up your server"))
        assertFalse(cert.message.contains("Let's Encrypt"))
        assertTrue(cert.details!!.contains("Let's Encrypt"))

        val http = UnknownServiceException("CLEARTEXT communication not permitted").toUserFacingError("x")
        assertFalse(http.message.contains("tailscale"))
        assertTrue(http.details!!.contains("tailscale cert"))
    }

    @Test
    fun `other TLS failures get the generic secure-connection wording`() {
        assertEquals(SecureConnectionMessage, SSLHandshakeException("Handshake failed").userMessage("x"))
    }

    @Test
    fun `http statuses are worded by class`() {
        assertEquals(SessionExpiredMessage, httpError(401).userMessage("x"))
        assertEquals(ForbiddenMessage, httpError(403).userMessage("x"))
        assertEquals(NotFoundMessage, httpError(404).userMessage("x"))
        assertEquals(ServerProblemMessage, httpError(503).userMessage("x"))
        assertEquals("The server turned that request down - try again, or ask whoever set up your server.", httpError(418).userMessage("x"))
        assertTrue(httpError(503).toUserFacingError("x").details!!.contains("503"))
    }

    @Test
    fun `a dropped connection is worded plainly`() {
        assertEquals(ConnectionLostMessage, IOException("unexpected end of stream on http://h/...").userMessage("x"))
    }

    @Test
    fun `an unreadable answer suggests updating`() {
        assertEquals(UnreadableAnswerMessage, JsonDataException("Expected BEGIN_OBJECT").userMessage("x"))
    }

    @Test
    fun `a message this app or the server wrote is kept as is, with no details`() {
        val error = IllegalStateException("This fiction is already in your library").toUserFacingError("x")

        assertEquals("This fiction is already in your library", error.message)
        assertNull(error.details)
        assertEquals("Could not open that book.", IOException("Could not open that book.").userMessage("x"))
    }

    @Test
    fun `a failure with no message falls back`() {
        val error = IllegalStateException().toUserFacingError("Could not load library")

        assertEquals("Could not load library", error.message)
        assertEquals("IllegalStateException", error.details)
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<Unit>(code, "".toResponseBody(null)))
}
