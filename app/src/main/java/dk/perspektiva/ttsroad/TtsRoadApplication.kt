package dk.perspektiva.ttsroad

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dk.perspektiva.ttsroad.core.ServiceLocator
import kotlinx.coroutines.runBlocking

class TtsRoadApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }

    /**
     * Cover art sits behind the same bearer auth as the audio, so Coil has to use the repository's
     * client rather than its own. The repository only sets its auth header as a side effect of an
     * API call, so stamp the header from the stored session here instead — otherwise the first
     * covers after launch race the first API call and come back 401.
     */
    override fun newImageLoader(): ImageLoader {
        val tokenStore = ServiceLocator.tokenStore(this)
        val client = ServiceLocator.httpClient(this).newBuilder()
            .addInterceptor { chain ->
                val header = runBlocking { tokenStore.current().authorizationHeader }
                val request = chain.request()
                chain.proceed(
                    if (header == null) request
                    else request.newBuilder().header("Authorization", header).build(),
                )
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .build()
    }
}
