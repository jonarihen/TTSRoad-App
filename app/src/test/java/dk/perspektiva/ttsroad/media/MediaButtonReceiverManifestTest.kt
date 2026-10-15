package dk.perspektiva.ttsroad.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Play press with the service dead has to have somewhere to land (#329).
 *
 * The system delivers a Bluetooth/headset media button to a manifest receiver; Media3's
 * `MediaButtonReceiver` then looks up the session service by its interface action and starts it,
 * which is what reaches `onPlaybackResumption`. Both halves are manifest declarations, so this
 * checks the merged manifest rather than any code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaButtonReceiverManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a media button broadcast resolves to Media3's receiver, exported`() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName),
            PackageManager.GET_RESOLVED_FILTER,
        )
        val receiver = receivers.singleOrNull()?.activityInfo
        assertEquals("androidx.media3.session.MediaButtonReceiver", receiver?.name)
        assertTrue("the system delivers media buttons from another process", receiver!!.exported)
    }

    @Test
    fun `the receiver can find the library service it starts`() {
        val services = context.packageManager.queryIntentServices(
            Intent(MediaLibraryService.SERVICE_INTERFACE).setPackage(context.packageName),
            0,
        )
        assertEquals(TtsRoadMediaService::class.java.name, services.singleOrNull()?.serviceInfo?.name)
    }
}
