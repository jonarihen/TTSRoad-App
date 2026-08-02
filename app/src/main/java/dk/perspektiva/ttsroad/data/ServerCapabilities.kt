package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

/**
 * Raw `GET /api/mobile/capabilities` body.
 *
 * `capabilities` and `limits` are deliberately loose maps rather than typed fields: the endpoint is
 * additive by contract, so a server newer than this app will send keys it has never heard of, and a
 * strict model would fail to parse the whole payload over one unknown entry.
 */
data class CapabilitiesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val server: CapabilityServerInfo? = null,
    val capabilities: Map<String, Any?> = emptyMap(),
    val limits: Map<String, Any?> = emptyMap(),
)

data class CapabilityServerInfo(
    val name: String = "TTSRoad",
    val version: String? = null,
    @param:Json(name = "base_url") val baseUrl: String? = null,
)

/**
 * The optional server features this client knows how to use.
 *
 * Every flag defaults to false, which is what makes [Baseline] a correct answer for a server that
 * predates discovery entirely. `api_version` is never consulted here: it tracks breaking changes to
 * the baseline API, so using it to infer an additive feature would light up UI the server cannot serve.
 */
data class ServerCapabilities(
    val serverName: String = "TTSRoad",
    val serverVersion: String? = null,
    val apiVersion: Int = 1,
    val readAlong: Boolean = false,
    val search: Boolean = false,
    val bookmarks: Boolean = false,
    val deltaSync: Boolean = false,
    val batchProgress: Boolean = false,
    val audioContentHash: Boolean = false,
    val deviceManagement: Boolean = false,
    val maxChaptersPerPage: Int? = null,
) {
    companion object {
        /** What an older server — or an unreachable one — is assumed to support. */
        val Baseline = ServerCapabilities()

        fun from(response: CapabilitiesResponse): ServerCapabilities {
            val flags = response.capabilities
            return ServerCapabilities(
                serverName = response.server?.name ?: "TTSRoad",
                serverVersion = response.server?.version,
                apiVersion = response.apiVersion,
                readAlong = flags.flag("readalong"),
                search = flags.flag("search"),
                bookmarks = flags.flag("bookmarks"),
                deltaSync = flags.flag("delta_sync"),
                batchProgress = flags.flag("batch_progress"),
                audioContentHash = flags.flag("audio_content_hash"),
                deviceManagement = flags.flag("device_management"),
                maxChaptersPerPage = response.limits.intLimit("max_chapters_per_page"),
            )
        }

        /**
         * Only a literal JSON `true` enables a feature. A string, a number, or null means the server
         * is saying something this client does not understand, and guessing would break playback.
         */
        private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

        /** Moshi parses every JSON number as a Double, so accept any [Number] and drop the rest. */
        private fun Map<String, Any?>.intLimit(key: String): Int? = (this[key] as? Number)?.toInt()
    }
}
