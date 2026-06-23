package dk.perspektiva.ttsroad

import android.app.Application
import dk.perspektiva.ttsroad.core.ServiceLocator

class TtsRoadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

