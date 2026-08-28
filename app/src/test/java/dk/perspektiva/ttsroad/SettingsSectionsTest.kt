package dk.perspektiva.ttsroad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kicker convention on the app's longest screen (#162).
 *
 * `SectionHeader` writes the rule down in prose — an ordinal for a band that is always drawn, a
 * mnemonic for one that a capability or an admin flag can take away — and prose is not what breaks.
 * What breaks is the eleventh band: someone adds a section behind a capability, gives it the next
 * number in the sequence because that is what the section above it has, and every ordinal below it
 * now moves depending on what the server publishes. That is the failure the fiction screen already
 * proved, one screen over, and it is invisible in review because the diff is one correct-looking
 * line.
 *
 * These assertions are about [SettingsSection] as a list, which is the only place the whole
 * sequence exists. They cannot check that a band's `alwaysPresent` is true of the `if` that draws
 * it — nothing can, from here — so that claim stays a claim, and this file is what makes the rest
 * of the convention mechanical.
 */
class SettingsSectionsTest {

    @Test
    fun `the bands that are always drawn are numbered, in order, with no gaps`() {
        // 03 is playback on every server or it is a decoration. Declaration order is screen order,
        // so this reads down the page.
        val ordinals = SettingsSection.entries.filter { it.alwaysPresent }.map { it.kicker }

        assertEquals(listOf("01", "02", "03", "04", "05"), ordinals)
    }

    @Test
    fun `a band that can disappear is never numbered`() {
        val numbered = SettingsSection.entries
            .filter { !it.alwaysPresent && it.kicker.all(Char::isDigit) }

        // Naming them rather than counting them: the failure message is the whole point of the test.
        assertEquals(emptyList<SettingsSection>(), numbered)
    }

    @Test
    fun `every kicker is unique on the screen`() {
        // Two bands wearing "SRV" is a landmark that points at two places, which is worse than the
        // accent captions this replaced.
        val kickers = SettingsSection.entries.map { it.kicker }

        assertEquals(kickers.size, kickers.toSet().size)
    }

    @Test
    fun `a kicker stays short enough to read as a code`() {
        // Four characters, from SectionHeader's own rule: past that it stops looking like a code and
        // starts competing with the title beside it.
        val tooLong = SettingsSection.entries.filter { it.kicker.length !in 1..4 }

        assertEquals(emptyList<SettingsSection>(), tooLong)
    }

    @Test
    fun `no title still carries the caption it was promoted from`() {
        // The whole change is that these stopped being "// Something" accent lines. A title that
        // kept the slashes would draw them under the rule, twice.
        val titles = SettingsSection.entries.map { it.title }

        assertTrue(titles.none { it.isBlank() || it.startsWith("/") })
    }
}
