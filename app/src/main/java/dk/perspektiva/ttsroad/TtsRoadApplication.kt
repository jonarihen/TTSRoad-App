package dk.perspektiva.ttsroad

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dk.perspektiva.ttsroad.core.ServiceLocator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class TtsRoadApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }

    /**
     * TTSRoad-owned cover art may require the bearer token, while Royal Road/CDN covers must never
     * receive it. Use a dedicated client and only authorize requests whose origin exactly matches
     * the server the user signed in to.
     */
    override fun newImageLoader(): ImageLoader {
        val tokenStore = ServiceLocator.tokenStore(this)
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val session = runBlocking { tokenStore.current() }
                val request = chain.request()
                val authorized = request.newBuilder()
                    .removeHeader("Authorization")
                    .apply {
                        if (isSameOrigin(request.url.toString(), session.serverUrl)) {
                            session.authorizationHeader?.let { header("Authorization", it) }
                        }
                    }
                    .build()
                chain.proceed(authorized)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .build()
    }
}

/** True only when both URLs have the same scheme, host and effective port. */
internal fun isSameOrigin(requestUrl: String, serverUrl: String): Boolean {
    val request = requestUrl.toHttpUrlOrNull() ?: return false
    val server = serverUrl.toHttpUrlOrNull() ?: return false
    return request.scheme.equals(server.scheme, ignoreCase = true) &&
        request.host.equals(server.host, ignoreCase = true) &&
        request.port == server.port
}
