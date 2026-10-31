package dk.perspektiva.ttsroad.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepAutoArmedNightTest {
    @Test
    fun `the armed night survives a new preferences instance, as after the service is reaped`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PlaybackPreferences(context).setSleepAutoArmedNight(20_000L)

        assertEquals(20_000L, PlaybackPreferences(context).current().sleepAutoArmedNight)
    }
}
