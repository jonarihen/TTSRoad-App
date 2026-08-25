package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under a group of settings that says where they are kept.
 *
 * Worth testing rather than eyeballing, because the bug it exists to prevent is not a crash: the
 * reader's footer claimed "Kept on this phone only" for two releases after those settings started
 * following the account (#103), and nothing failed. Only reading the screen would have caught it.
 */
class PreferenceScopeTest {

    @Test
    fun `a capable server never claims reader settings are phone-only`() {
        val note = PreferenceScope.reader(syncsWithAccount = true)

        assertTrue(note, note.contains("account", ignoreCase = true))
        // The exact wording that was wrong before. Guarding the claim, not the phrasing.
        assertTrue(note, !note.contains("this phone only", ignoreCase = true))
        assertTrue(note, !note.contains("cannot", ignoreCase = true))
    }

    @Test
    fun `a capable server still says the phone keeps a copy`() {
        // Both halves are true and the offline one matters: the local store is what the reader
        // renders from, and the sync writes through it rather than around it.
        val note = PreferenceScope.reader(syncsWithAccount = true)

        assertTrue(note, note.contains("offline", ignoreCase = true))
    }

    @Test
    fun `an older server says so honestly rather than promising a sync`() {
        val note = PreferenceScope.reader(syncsWithAccount = false)

        assertTrue(note, note.contains("this phone", ignoreCase = true))
        assertTrue(note, note.contains("cannot", ignoreCase = true))
    }

    @Test
    fun `the two reader answers are actually different`() {
        // A capability-aware helper that returns the same string either way would pass every
        // assertion above while telling the user nothing.
        assertNotEquals(
            PreferenceScope.reader(syncsWithAccount = true),
            PreferenceScope.reader(syncsWithAccount = false),
        )
    }

    @Test
    fun `the device player keys never claim to follow the account`() {
        // These are deliberately local on both native clients. The old copy said only "kept across
        // restarts and reboots", which left the reader of it free to assume either answer.
        assertTrue(PreferenceScope.DevicePlayer, PreferenceScope.DevicePlayer.contains("this phone"))
        assertTrue(
            PreferenceScope.DevicePlayer,
            PreferenceScope.DevicePlayer.contains("not on your account"),
        )
    }

    @Test
    fun `an account-backed setting reports the server's actual ability`() {
        assertTrue(PreferenceScope.account(syncsWithAccount = true).contains("account"))
        assertTrue(PreferenceScope.account(syncsWithAccount = false).contains("this phone"))
        assertNotEquals(
            PreferenceScope.account(syncsWithAccount = true),
            PreferenceScope.account(syncsWithAccount = false),
        )
    }
}
