package dk.perspektiva.ttsroad.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {

    /**
     * The case the whole feature exists for: a revoked token must not be retried. Retrying would
     * hammer a server that will keep saying no, and would never recover on its own.
     */
    @Test
    fun `a 401 on the audio stream is an auth failure, not something to retry`() {
        assertEquals(
            PlaybackFailure.Unauthorized,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 401),
        )
        assertEquals(
            PlaybackFailure.Unauthorized,
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 403),
        )
    }

    /**
     * A rejected token does not arrive with a tidy error code. ExoPlayer settles on whatever fits
     * the failure it saw, and several of those codes are exactly the ones auto-retry exists for, so
     * the HTTP status has to win — otherwise a revoked token spends the whole backoff being asked
     * again, and the user is left staring at "retrying" instead of the login screen.
     */
    @Test
    fun `a 401 is fatal auth even when the error code says network`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertEquals(
                "error code $code with a 401 must not be retried",
                PlaybackFailure.Unauthorized,
                classifyPlaybackError(code, httpStatus = 401),
            )
        }
    }

    /** The other half of the same rule: a server hiccup stays retryable whatever the code says. */
    @Test
    fun `a 503 stays retryable even under an auth-looking error code`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertTrue(
                "error code $code with a 503 should be retried",
                classifyPlaybackError(code, httpStatus = 503) is PlaybackFailure.Transient,
            )
        }
    }

    @Test
    fun `server-side hiccups are transient`() {
        for (status in listOf(500, 502, 503, 504, 408, 429)) {
            assertTrue(
                "HTTP $status should be retried",
                classifyPlaybackError(
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    httpStatus = status,
                ) is PlaybackFailure.Transient,
            )
        }
    }

    @Test
    fun `a 404 is permanent - the audio is simply not there`() {
        val failure = classifyPlaybackError(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatus = 404,
        )

        assertTrue(failure is PlaybackFailure.Permanent)
        assertTrue(failure.message.contains("404"))
    }

    /** The tunnel / Wi-Fi handover / VPN drop case, which is what auto-retry is for. */
    @Test
    fun `network failures are transient without needing an http status`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )) {
            assertTrue(
                "error code $code should be retried",
                classifyPlaybackError(code) is PlaybackFailure.Transient,
            )
        }
    }

    @Test
    fun `a missing file is permanent`() {
        assertTrue(
            classifyPlaybackError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                is PlaybackFailure.Permanent,
        )
    }

    @Test
    fun `an unknown error code still produces something to show the user`() {
        val failure = classifyPlaybackError(PlaybackException.ERROR_CODE_DECODING_FAILED)

        assertTrue(failure is PlaybackFailure.Permanent)
        assertTrue(failure.message.isNotBlank())
    }

    /**
     * A controller receives the error across the binder with its cause stripped, so it classifies
     * with a null status. It must still get a usable message rather than crashing or going blank.
     */
    @Test
    fun `classifying without an http status never yields a blank message`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertTrue(classifyPlaybackError(code).message.isNotBlank())
        }
    }

    @Test
    fun `backoff grows and then gives up`() {
        assertEquals(2_000L, retryDelayMs(1))
        assertEquals(5_000L, retryDelayMs(2))
        assertEquals(15_000L, retryDelayMs(3))
        assertNull(retryDelayMs(MaxAutomaticRetries + 1))
    }

    @Test
    fun `a non-positive attempt does not wrap around to the last delay`() {
        assertNull(retryDelayMs(0))
        assertNull(retryDelayMs(-1))
    }

    /**
     * #482: a server that times out on every attempt while the phone still reports a network.
     * Nothing will call back, so the retries themselves must not run out while playback is wanted.
     */
    @Test
    fun `a server that keeps timing out is retried slowly for as long as playback is wanted`() {
        val timeout = classifyPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertTrue(timeout is PlaybackFailure.Transient)

        var waitedMs = 0L
        for (attempt in 1..60) {
            val delay = transientRetryDelayMs(attempt, playWhenReady = true)
            assertNotNull("attempt $attempt gave up", delay)
            waitedMs += delay!!
        }
        assertEquals(2_000L, transientRetryDelayMs(1, playWhenReady = true))
        assertEquals(15_000L, transientRetryDelayMs(MaxAutomaticRetries, playWhenReady = true))
        assertEquals(30_000L, transientRetryDelayMs(MaxAutomaticRetries + 1, playWhenReady = true))
        assertEquals(60_000L, transientRetryDelayMs(MaxAutomaticRetries + 2, playWhenReady = true))
        assertEquals(MaxSlowRetryIntervalMs, transientRetryDelayMs(Int.MAX_VALUE, playWhenReady = true))
        assertTrue("well past the old 22 s budget", waitedMs > 30 * 60_000L)
    }

    @Test
    fun `slow retries stop once playback is no longer wanted, the fast backoff does not`() {
        assertNull(transientRetryDelayMs(MaxAutomaticRetries + 1, playWhenReady = false))
        assertEquals(5_000L, transientRetryDelayMs(2, playWhenReady = false))
        assertNull(transientRetryDelayMs(0, playWhenReady = true))
    }
}
