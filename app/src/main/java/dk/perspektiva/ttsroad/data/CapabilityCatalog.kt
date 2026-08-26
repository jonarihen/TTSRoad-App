package dk.perspektiva.ttsroad.data

/**
 * What each server capability means, in words, for the Settings panel that lists them.
 *
 * The app hides a dozen controls when the server cannot back them, which is the right behaviour and
 * an invisible one: from the user's side "this server is older than my app", "I am not an admin"
 * and "this app is broken" all look identical (#120). Naming the flags is what separates them.
 *
 * Keyed by the server's own flag names (`app/routers/platform.py`) so a key this build has never
 * heard of can still be listed — with its raw name, which beats omitting it.
 */
object CapabilityCatalog {

    /** Known flags in the order the panel shows them: what a listener notices first, first. */
    val Order: List<String> = listOf(
        "readalong",
        "bookmarks",
        "queue",
        "search",
        "follows",
        "player_preferences",
        "offline_downloads",
        "audio_content_hash",
        "delta_sync",
        "batch_progress",
        "device_management",
        "fiction_management",
        "chapter_maintenance",
        "fiction_maintenance",
        "epub_upload",
        "feed_urls",
        "listening_state_backup",
        "account_security",
        "audiobook_export",
        "voice_preview",
        "signed_audio_urls",
        "live_events",
    )

    private val labels: Map<String, String> = mapOf(
        "readalong" to "Read along",
        "bookmarks" to "Bookmarks",
        "queue" to "Up Next queue",
        "search" to "Search chapter text",
        "follows" to "Follow fictions",
        "player_preferences" to "Settings follow your account",
        "offline_downloads" to "Server-planned downloads",
        "audio_content_hash" to "Detect re-converted audio",
        "delta_sync" to "Fetch only what changed",
        "batch_progress" to "Ordered progress sync",
        "device_management" to "Device sessions",
        "fiction_management" to "Add and edit fictions",
        "chapter_maintenance" to "Repair a chapter",
        "fiction_maintenance" to "Maintain a fiction",
        "epub_upload" to "Upload an EPUB",
        "feed_urls" to "Podcast feed links",
        "listening_state_backup" to "Back up your progress",
        "account_security" to "Password and two-factor",
        "audiobook_export" to "Audiobook exports",
        "voice_preview" to "Voice previews",
        "signed_audio_urls" to "Signed audio links",
        "live_events" to "Live updates",
    )

    /**
     * A few flags are true on the server and still unused here, which would otherwise read as the
     * app being broken. Saying "not used by this app" is more honest than a bare tick.
     */
    private val unusedByThisApp: Map<String, String> = mapOf(
        "offline_downloads" to "this app plans its own",
        "delta_sync" to "not used yet",
        "voice_preview" to "not used yet",
        "signed_audio_urls" to "not needed — this app sends a header",
        "live_events" to "browser only",
    )

    /** The human label for [key], or the raw key when this build has never heard of it. */
    fun label(key: String): String = labels[key] ?: key

    /** A parenthetical qualifying a supported flag, or null when the tick speaks for itself. */
    fun note(key: String, supported: Boolean): String? =
        if (supported) unusedByThisApp[key] else null

    /**
     * Every flag to show: the known ones in [Order] first, then anything else the server sent.
     *
     * A server newer than this app is the normal case, not the edge case, so an unrecognised flag
     * is appended rather than dropped — the panel's whole job is to report what the server said.
     */
    fun rows(advertised: Map<String, Boolean>): List<CapabilityRow> {
        val known = Order.filter { it in advertised }
        val extra = advertised.keys.filterNot { it in Order }.sorted()
        return (known + extra).map { key ->
            val supported = advertised[key] == true
            CapabilityRow(
                key = key,
                label = label(key),
                supported = supported,
                note = note(key, supported),
            )
        }
    }
}

/** One line of the Settings capability list. */
data class CapabilityRow(
    val key: String,
    val label: String,
    val supported: Boolean,
    val note: String?,
)
