package dk.perspektiva.ttsroad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepAutoArmTest {
    private fun at(day: Long, hour: Int) = day * 86_400_000L + hour * 3_600_000L

    @Test fun offNeverArms() = assertNull(sleepAutoArmNight(at(10, 23), SleepAutoArmOff))

    @Test fun outsideTheWindowDoesNotArm() {
        assertNull(sleepAutoArmNight(at(10, 21), 22))
        assertNull(sleepAutoArmNight(at(10, 12), 22))
        assertNull(sleepAutoArmNight(at(10, 5), 22))
    }

    @Test fun eveningAndSmallHoursAreTheSameNight() {
        assertNotNull(sleepAutoArmNight(at(10, 22), 22))
        assertEquals(sleepAutoArmNight(at(10, 23), 22), sleepAutoArmNight(at(11, 1), 22))
        assertEquals(sleepAutoArmNight(at(10, 22), 22), sleepAutoArmNight(at(11, 4), 22))
        assertNotEquals(sleepAutoArmNight(at(10, 23), 22), sleepAutoArmNight(at(11, 23), 22))
    }

    @Test fun midnightStartCoversTheEarlyHours() {
        assertNotNull(sleepAutoArmNight(at(11, 0), 0))
        assertNull(sleepAutoArmNight(at(11, 5), 0))
    }

    private fun assertNotEquals(a: Any?, b: Any?) = org.junit.Assert.assertNotEquals(a, b)
}
