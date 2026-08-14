package dk.perspektiva.ttsroad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockTimeTest {
    @Test
    fun `four digit time works with the numeric keyboard`() {
        assertEquals(23 to 49, parseClockTime("2349"))
        assertEquals(9 to 45, parseClockTime("0945"))
        assertEquals(0 to 0, parseClockTime("0000"))
    }

    @Test
    fun `colon time remains valid for pasted and hardware keyboard input`() {
        assertEquals(23 to 49, parseClockTime("23:49"))
        assertEquals(9 to 45, parseClockTime("9:45"))
        assertEquals(9 to 45, parseClockTime(" 09:45 "))
    }

    @Test
    fun `invalid clock values are rejected`() {
        assertNull(parseClockTime("2400"))
        assertNull(parseClockTime("1260"))
        assertNull(parseClockTime("945"))
        assertNull(parseClockTime("not a time"))
    }
}
