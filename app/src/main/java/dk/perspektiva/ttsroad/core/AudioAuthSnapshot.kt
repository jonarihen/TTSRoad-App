package dk.perspektiva.ttsroad.core

import androidx.media3.datasource.DataSpec

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class AudioAuthSnapshot(
    val serverUrl: String = "",
    val authorizationHeader: String? = null,
) {
    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = dataSpec.httpRequestHeaders
            .filterKeys { !it.equals("Authorization", ignoreCase = true) }
            .toMutableMap()
        if (authorizationHeader != null && ServerUrls.isSameOrigin(dataSpec.uri.toString(), serverUrl)) {
            headers["Authorization"] = authorizationHeader
        }
        return dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
    }
}
