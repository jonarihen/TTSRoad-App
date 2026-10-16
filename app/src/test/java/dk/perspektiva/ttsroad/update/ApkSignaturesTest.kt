package dk.perspektiva.ttsroad.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignaturesTest {
    private val pkg = "dk.perspektiva.ttsroad"
    private val installed = SigningIdentity(pkg, setOf("aa"))

    @Test
    fun `the same signer may update`() {
        assertNull(updateSignerProblem(installed, SigningIdentity(pkg, setOf("aa"))))
    }

    @Test
    fun `a different signer is refused in words a person can act on`() {
        val problem = updateSignerProblem(installed, SigningIdentity(pkg, setOf("bb")))
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("different key"))
    }

    @Test
    fun `a rotated key whose history includes the installed one may update`() {
        val rotated = SigningIdentity(pkg, signers = setOf("bb"), history = setOf("aa", "bb"))
        assertNull(updateSignerProblem(installed, rotated))
    }

    @Test
    fun `multiple signers must match exactly`() {
        val both = SigningIdentity(pkg, setOf("aa", "bb"))
        assertNull(updateSignerProblem(both, SigningIdentity(pkg, setOf("bb", "aa"))))
        assertNotNull(updateSignerProblem(both, SigningIdentity(pkg, setOf("aa"))))
        assertNotNull(updateSignerProblem(installed, both))
    }

    @Test
    fun `an unreadable or unsigned download is refused`() {
        assertNotNull(updateSignerProblem(installed, null))
        assertNotNull(updateSignerProblem(installed, SigningIdentity(pkg, emptySet())))
    }

    @Test
    fun `an unreadable installed signature is refused rather than waved through`() {
        assertNotNull(updateSignerProblem(null, SigningIdentity(pkg, setOf("aa"))))
    }

    @Test
    fun `another app signed with the same key is refused`() {
        val problem = updateSignerProblem(installed, SigningIdentity("com.example.other", setOf("aa")))
        assertTrue(problem!!, problem.contains("com.example.other"))
    }

    @Test
    fun `certificate digests are lowercase SHA-256 hex`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            certificateDigest(ByteArray(0)),
        )
    }
}
