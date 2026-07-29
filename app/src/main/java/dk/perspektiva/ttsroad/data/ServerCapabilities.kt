package dk.perspektiva.ttsroad.data

/**
 * An optional server feature this build knows how to use.
 *
 * [key] is the JSON key in `/api/mobile/capabilities`. Listing a value here is the only way the
 * app starts recognising a capability — anything else the server advertises is ignored, which is
 * what lets a newer server talk to this build without it guessing at features it cannot render.
 */
enum class Capability(val key: String, val label: String) {
    ReadAlong("readalong", "Read-along"),
    Search("search", "Search"),
    Bookmarks("bookmarks", "Bookmarks"),
    DeltaSync("delta_sync", "Delta sync"),
    BatchProgress("batch_progress", "Batch progress"),
    AudioContentHash("audio_content_hash", "Content hashes"),
    DeviceManagement("device_management", "Device management"),
}

/** What probing a typed server URL turned up, for the line under the login screen's URL field. */
sealed interface ServerProbe {
    val summary: String

    /**
     * The URL answered. [capabilities] is [ServerCapabilities.Baseline] when the answer was a
     * `404`, which is a working server — just one older than capability discovery.
     */
    data class Reached(val capabilities: ServerCapabilities) : ServerProbe {
        override val summary: String
            get() {
                val version = capabilities.serverVersion
                    ?: return "Server reached - baseline features only"
                val name = "${capabilities.serverName} $version"
                val features = capabilities.enabled.joinToString(", ") { it.label }
                return if (features.isEmpty()) "$name - baseline features only" else "$name - $features"
            }
    }

    data object Unreachable : ServerProbe {
        override val summary: String get() = "Could not reach this server"
    }
}

/**
 * What one server can do, as of the last time it was asked.
 *
 * Every optional feature is gated on its own flag. `api_version` deliberately has no say in it:
 * it is a breaking-change signal, and the capabilities here are all additive, so a server can
 * gain any of them without the number moving.
 */
data class ServerCapabilities(
    val serverName: String = "TTSRoad",
    val serverVersion: String? = null,
    val apiVersion: Int = 1,
    val enabled: Set<Capability> = emptySet(),
    val limits: Map<String, Int> = emptyMap(),
    /** True once a server actually answered — false while this is only the assumed baseline. */
    val discovered: Boolean = false,
) {
    operator fun contains(capability: Capability): Boolean = capability in enabled

    /** Server-declared page size for chapter listings, or null if it did not say. */
    val maxChaptersPerPage: Int?
        get() = limits["max_chapters_per_page"]

    companion object {
        /** Never asked, or the question failed. Baseline API only, every optional feature off. */
        val Unknown = ServerCapabilities()

        /**
         * A server that answered `404`: reachable, real, and older than capability discovery.
         * Kept distinct from [Unknown] so the UI can say "old server" rather than "no server".
         */
        val Baseline = ServerCapabilities(discovered = true)

        fun from(response: CapabilitiesResponse): ServerCapabilities = ServerCapabilities(
            serverName = response.server?.name?.takeIf { it.isNotBlank() } ?: "TTSRoad",
            serverVersion = response.server?.version?.takeIf { it.isNotBlank() },
            apiVersion = response.apiVersion,
            // Only a literal `true` enables anything. A missing key, a null, or a value of some
            // other type all mean "not supported" rather than an error, because the client must
            // never fail on a payload shape it did not expect.
            enabled = Capability.entries
                .filterTo(mutableSetOf()) { response.capabilities[it.key] == true },
            limits = response.limits
                .mapNotNull { (key, value) -> (value as? Number)?.let { key to it.toInt() } }
                .toMap(),
            discovered = true,
        )
    }
}
