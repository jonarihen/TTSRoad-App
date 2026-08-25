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
    /**
     * The server's *own* configured `BASE_URL`, which is the only stable identity it has: the
     * address the phone reached it on changes with the network, but this does not. The download
     * cache keys on it so two servers cannot share a cache entry — see `DownloadCacheKeys`.
     */
    val serverBaseUrl: String? = null,
    val apiVersion: Int = 1,
    val readAlong: Boolean = false,
    val search: Boolean = false,
    val bookmarks: Boolean = false,
    val deltaSync: Boolean = false,
    val batchProgress: Boolean = false,
    val audioContentHash: Boolean = false,
    val deviceManagement: Boolean = false,
    /**
     * A server-side cross-library queue the app can read and mutate.
     *
     * Its own flag rather than folded into another: a client that builds its own queue keeps
     * working, and the Android Auto "Up Next" node is only offered when the server can back it.
     */
    val queue: Boolean = false,

    /**
     * Per-user libraries. When false, `/api/mobile/library` is still the whole shared list and the
     * app must not offer a follow control it cannot honour.
     */
    val follows: Boolean = false,

    /**
     * The shared player/reader preference vocabulary on `/api/me/preferences`.
     *
     * The server gates this on its *schema* route rather than on the preferences endpoint, because
     * that endpoint predates the vocabulary: an older server answers PATCH happily and drops the
     * keys it has never heard of. So this flag, and not a 404 probe, is what says the account can
     * actually hold these settings.
     */
    val playerPreferences: Boolean = false,

    /**
     * Adding, editing and deleting fictions over `/api/mobile/fictions`.
     *
     * The same handlers the web console uses, behind the mobile door so the add-fields-don't-rename
     * guarantee and the mobile contract test cover them. The routes are admin-gated server-side; the
     * flag says the server *has* them, not that this account may use them — see [SessionState.isAdmin]
     * for that half.
     */
    val fictionManagement: Boolean = false,

    /**
     * Whole-fiction M4B exports can be listed and downloaded (#113).
     *
     * Says the API surface exists, not that the server can currently produce one — that depends on
     * ffmpeg, which `/api/mobile/exports` reports per request.
     */
    val audiobookExport: Boolean = false,

    /** Multipart EPUB import over the mobile surface (#114). Separate from [fictionManagement]. */
    val epubUpload: Boolean = false,

    /** The server can plan a download batch: which chapters, in what order, how many bytes. */
    val offlineDownloads: Boolean = false,

    /** Short-lived signed audio URLs, for players that cannot attach a header. Android can. */
    val signedAudioUrls: Boolean = false,

    /** A server-push event stream. Browser-only by design; listed so the panel can say so. */
    val liveEvents: Boolean = false,

    /** Per-fiction voice selection and preview exist server-side. No mobile client yet (#111). */
    val voicePreview: Boolean = false,

    /**
     * Every flag the server advertised, exactly as sent, including ones this build has never heard
     * of.
     *
     * Kept alongside the typed fields rather than instead of them: the fields are what gates UI,
     * and this is what the Settings panel lists. A newer server's flag has no field to land in, and
     * dropping it would make the panel quietly wrong about what the server can do (#120).
     */
    val advertised: Map<String, Boolean> = emptyMap(),
    val maxChaptersPerPage: Int? = null,
    /**
     * How many items `/playback/sync` accepts in one batch. Null on a server that does not say, in
     * which case the client uses its own conservative default rather than guessing high and
     * having a whole flush rejected with a 400.
     */
    val maxPlaybackSyncItems: Int? = null,
    /** The largest EPUB the server will accept, for checking a file before uploading it (#114). */
    val maxEpubBytes: Long? = null,
) {
    companion object {
        /** What an older server — or an unreachable one — is assumed to support. */
        val Baseline = ServerCapabilities()

        fun from(response: CapabilitiesResponse): ServerCapabilities {
            val flags = response.capabilities
            return ServerCapabilities(
                serverName = response.server?.name ?: "TTSRoad",
                serverVersion = response.server?.version,
                serverBaseUrl = response.server?.baseUrl?.takeIf { it.isNotBlank() },
                apiVersion = response.apiVersion,
                readAlong = flags.flag("readalong"),
                search = flags.flag("search"),
                bookmarks = flags.flag("bookmarks"),
                deltaSync = flags.flag("delta_sync"),
                batchProgress = flags.flag("batch_progress"),
                audioContentHash = flags.flag("audio_content_hash"),
                deviceManagement = flags.flag("device_management"),
                queue = flags.flag("queue"),
                follows = flags.flag("follows"),
                playerPreferences = flags.flag("player_preferences"),
                fictionManagement = flags.flag("fiction_management"),
                audiobookExport = flags.flag("audiobook_export"),
                epubUpload = flags.flag("epub_upload"),
                offlineDownloads = flags.flag("offline_downloads"),
                signedAudioUrls = flags.flag("signed_audio_urls"),
                liveEvents = flags.flag("live_events"),
                voicePreview = flags.flag("voice_preview"),
                // Only entries that are actually booleans. A server sending something else for a
                // key is saying something this build cannot read, and listing it as "off" would be
                // a guess presented as fact.
                advertised = flags.mapNotNull { (key, value) ->
                    (value as? Boolean)?.let { key to it }
                }.toMap(),
                maxChaptersPerPage = response.limits.intLimit("max_chapters_per_page"),
                maxPlaybackSyncItems = response.limits.intLimit("max_playback_sync_items"),
                maxEpubBytes = response.limits.longLimit("max_epub_bytes"),
            )
        }

        /**
         * Only a literal JSON `true` enables a feature. A string, a number, or null means the server
         * is saying something this client does not understand, and guessing would break playback.
         */
        private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true

        /** Moshi parses every JSON number as a Double, so accept any [Number] and drop the rest. */
        private fun Map<String, Any?>.intLimit(key: String): Int? = (this[key] as? Number)?.toInt()

        /** As [intLimit], for a limit that can exceed what an Int holds — a byte count. */
        private fun Map<String, Any?>.longLimit(key: String): Long? = (this[key] as? Number)?.toLong()
    }
}
