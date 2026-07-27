package dk.perspektiva.ttsroad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {

    @Test
    fun `no prompt below api 33 where the permission is implicit`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 26, isGranted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, isGranted = false))
    }

    @Test
    fun `prompts from api 33 when the permission is missing`() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, isGranted = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 37, isGranted = false))
    }

    @Test
    fun `no prompt once granted`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 33, isGranted = true))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 37, isGranted = true))
    }
}
