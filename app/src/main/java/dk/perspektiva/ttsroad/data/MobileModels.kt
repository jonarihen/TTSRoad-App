package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json
import java.time.Duration
import java.time.Instant

data class LoginRequest(
    val username: String,
    val password: String,
    @param:Json(name = "device_name") val deviceName: String,
    @param:Json(name = "totp_code") val totpCode: String? = null,
)

data class LoginResponse(
    val token: String,
    @param:Json(name = "token_type") val tokenType: String = "bearer",
    // Which of the account's mobile sessions this token is, so the devices screen can mark the
    // row the user is holding. Absent on servers that predate the devices endpoints.
    @param:Json(name = "device_id") val deviceId: Int? = null,
    // When the token lapses if it goes unused. Stored, not watched: every authenticated request
    // renews it server-side, so a countdown here would say nothing true.
    @param:Json(name = "expires_at") val expiresAt: String? = null,
    val user: MobileUser,
    val server: ServerInfo? = null,
)

data class LogoutResponse(
    val status: String = "",
    val revoked: Boolean = false,
)

data class CurrentUserResponse(
    val user: MobileUser,
)

data class DevicesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val devices: List<DeviceSession> = emptyList(),
)

/**
 * One mobile sign-in on this account.
 *
 * Everything but the id is optional: `last_ip` is null until the session is used, and older or
 * partially-populated rows should list rather than fail the whole response.
 */
data class DeviceSession(
    val id: Int = 0,
    @param:Json(name = "device_name") val deviceName: String? = null,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "last_used_at") val lastUsedAt: String? = null,
    @param:Json(name = "expires_at") val expiresAt: String? = null,
    @param:Json(name = "last_ip") val lastIp: String? = null,
    val status: String? = null,
    @param:Json(name = "is_current") val isCurrent: Boolean = false,
) {
    /** Never blank, so a nameless session still has something to tap on. */
    val resolvedName: String
        get() = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Unnamed device"
}

data class MobileUser(
    val id: Int,
    val username: String,
    @param:Json(name = "is_admin") val isAdmin: Boolean = false,
)

data class ServerInfo(
    val name: String = "TTSRoad",
    @param:Json(name = "base_url") val baseUrl: String? = null,
    @param:Json(name = "api_version") val apiVersion: Int = 1,
)

data class LibraryResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    /**
     * `followed` or `all`, echoed back by the server. Read rather than assumed: a server without
     * per-user libraries answers `followed` whatever was asked for, and the browse screen needs to
     * know it is looking at the shared list rather than a shelf.
     */
    val scope: String = LibraryScopeFollowed,
    @param:Json(name = "following_ids") val followingIds: List<Int> = emptyList(),
    val fictions: List<FictionSummary> = emptyList(),
    @param:Json(name = "continue_listening") val continueListening: List<ChapterSummary> = emptyList(),
    @param:Json(name = "recent_chapters") val recentChapters: List<ChapterSummary> = emptyList(),
)

data class ChaptersResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val fiction: FictionSummary,
    val total: Int = 0,
    val chapters: List<ChapterSummary> = emptyList(),
)

/** The `source_type` values the server sends. A fiction may carry a key newer than this build. */
object SourceType {
    const val RoyalRoad = "royalroad"
    const val Epub = "epub"
    const val Patreon = "patreon"
}

data class FictionSummary(
    val id: Int = 0,
    val title: String = "Untitled",
    val author: String? = null,
    val slug: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val rating: Double? = null,
    @param:Json(name = "rating_count") val ratingCount: Int? = null,
    @param:Json(name = "total_chapters") val totalChapters: Int = 0,
    @param:Json(name = "done_chapters") val doneChapters: Int = 0,
    @param:Json(name = "pending_chapters") val pendingChapters: Int = 0,
    @param:Json(name = "error_chapters") val errorChapters: Int = 0,
    @param:Json(name = "processing_chapters") val processingChapters: Int = 0,
    /**
     * Whether this fiction is on the caller's shelf.
     *
     * Absent on a server without per-user libraries, where every fiction is effectively followed —
     * hence the default. The follow control is gated on the capability, not on this, so the default
     * is never read on a server that cannot honour it.
     */
    val following: Boolean = true,
    /**
     * Which metadata fields a human has edited, from [EditableMetadataFields].
     *
     * The server stops refilling these from the source on every poll, which is the whole point of
     * editing one. Null rather than empty on a server that predates manual editing: "we were not
     * told" and "nothing has been edited" call for different UI, and reading the first as the second
     * would offer a description box whose contents the next poll silently discards. See
     * [supportsMetadataEditing].
     */
    @param:Json(name = "metadata_overrides") val metadataOverrides: List<String>? = null,
    /**
     * The edge-tts narrator this fiction is converted with, as the server spells it.
     *
     * Read-only here: changing it is an admin workflow with no mobile surface yet (#111). Showing
     * it is worth doing on its own — with several books in flight there is otherwise no way to tell
     * from a phone which voice you are listening to, or that two books share one.
     */
    val voice: String? = null,
    /** The synthesis rate baked into the MP3, e.g. `+0%`. Read-only, as [voice]. */
    val rate: String? = null,
    /**
     * Whether polling and conversion run for this fiction at all.
     *
     * Null rather than false on a server too old to send it: "we were not told" and "this book is
     * paused" want different UI, and defaulting the first to the second would put a warning on
     * every row. Only an explicit `false` is treated as paused.
     */
    val enabled: Boolean? = null,
    /**
     * Where the chapters come from — `royalroad`, `epub`, `patreon`, or a newer adapter's key.
     *
     * These behave differently enough that the distinction matters when something looks wrong: an
     * EPUB import has no remote chapter list to poll, and a Patreon fiction can have chapters the
     * account is not entitled to read.
     */
    @param:Json(name = "source_type") val sourceType: String? = null,
    /** When the server last checked the source for new chapters. ISO-8601, or null if never. */
    @param:Json(name = "last_polled_at") val lastPolledAt: String? = null,
) {
    /**
     * This fiction is switched off: the poller skips it and nothing new will be converted.
     *
     * Deliberately not "not enabled" — [enabled] is null on an older server, and treating that as
     * paused would warn about every book on it.
     */
    val isPaused: Boolean
        get() = enabled == false

    /**
     * How this fiction's chapters are obtained, in words, or null when it is not worth saying.
     *
     * Royal Road is the overwhelming default and returns null: labelling the ordinary case adds a
     * word to every row and distinguishes nothing. An unknown key is passed through rather than
     * hidden — a newer server's adapter is still more informative than silence.
     */
    val sourceTypeLabel: String?
        get() = when (sourceType) {
            null, "", SourceType.RoyalRoad -> null
            SourceType.Epub -> "EPUB import"
            SourceType.Patreon -> "Patreon"
            else -> sourceType
        }

    /**
     * "polled 20m ago", or null when the server never said.
     *
     * [now] is a parameter so this is testable without freezing the clock. Coarse on purpose: the
     * question it answers is "is polling still happening", and to two significant figures.
     */
    fun lastPolledLabel(now: Instant = Instant.now()): String? {
        val polled = parseServerInstant(lastPolledAt) ?: return null
        val seconds = Duration.between(polled, now).seconds
        return when {
            // A server clock a little ahead of the phone's is not worth reporting as a negative age.
            seconds < 90 -> "polled just now"
            seconds < 3_600 -> "polled ${seconds / 60}m ago"
            seconds < 86_400 -> "polled ${seconds / 3_600}h ago"
            else -> "polled ${seconds / 86_400}d ago"
        }
    }

    /** Fraction of chapters with audio ready, for progress indicators. */
    val readyFraction: Float
        get() = if (totalChapters > 0) (doneChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f

    /**
     * Whether the server that sent this row understands hand-edited metadata.
     *
     * The key's *presence* is the signal, and there is no capability flag standing in for it: the
     * body of `PATCH /api/mobile/fictions/{id}` is additive, so an older server accepts a
     * description, drops it, and answers the same `status: ok` as a newer one. The echoed fiction is
     * the only place the difference shows.
     */
    val supportsMetadataEditing: Boolean
        get() = metadataOverrides != null

    /** True when [field] is one the source no longer gets to overwrite. */
    fun isMetadataOverridden(field: String): Boolean =
        metadataOverrides?.any { it.equals(field, ignoreCase = true) } == true

    /** The edited fields in presentation order, ignoring any name this build has never heard of. */
    val overriddenFields: List<String>
        get() = EditableMetadataFields.filter(::isMetadataOverridden)
}

/**
 * The field names `metadata_overrides` and `clear_overrides` speak in.
 *
 * `cover_image_url` has no text box of its own — it is set by uploading an image — but it is
 * overridable like the rest, so handing metadata back to the source has to be able to name it.
 */
const val MetadataFieldTitle: String = "title"
const val MetadataFieldAuthor: String = "author"
const val MetadataFieldDescription: String = "description"
const val MetadataFieldCoverImageUrl: String = "cover_image_url"
const val MetadataFieldTags: String = "tags"

/** In the order the editor shows them, which is the order they are listed back to the user in. */
val EditableMetadataFields: List<String> = listOf(
    MetadataFieldTitle,
    MetadataFieldAuthor,
    MetadataFieldDescription,
    MetadataFieldCoverImageUrl,
    MetadataFieldTags,
)

/**
 * The `status` values the server sends for a chapter, as it spells them.
 *
 * Named rather than inlined because three files compare against them, and a typo in a string
 * literal comparison fails by silently never matching.
 */
object ChapterStatus {
    const val Pending = "pending"
    const val Processing = "processing"
    const val Done = "done"
    const val Error = "error"
}

/** The finer stage of a chapter that is [ChapterStatus.Processing]. */
object ChapterSubStatus {
    const val FetchingHtml = "fetching_html"
    const val Preprocessing = "preprocessing"
    const val Converting = "converting"
}

data class ChapterSummary(
    val id: Int = 0,
    @param:Json(name = "chapter_id") val apiChapterId: Int? = null,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "Untitled chapter",
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "display_number") val displayNumber: Double? = null,
    @param:Json(name = "player_index") val playerIndex: Int? = null,
    val status: String? = null,
    /**
     * Which stage of conversion a `processing` chapter has reached — `fetching_html`,
     * `preprocessing` or `converting`.
     *
     * Null on a chapter nobody is working on, and on a server too old to send it. Both read as
     * "no finer detail than [status]", which is what [statusLabel] falls back to.
     */
    @param:Json(name = "sub_status") val subStatus: String? = null,
    /** Percent complete, sent while a chapter is in `converting`. */
    @param:Json(name = "tts_progress") val ttsProgress: Int? = null,
    /**
     * Why a chapter in `error` failed.
     *
     * The server has always sent this; until now the client dropped it, so a failure read as the
     * bare word "error". Retrying is not something the app can do yet (#107), but knowing that a
     * chapter is locked behind a paywall rather than transiently broken is worth the row on its own.
     */
    @param:Json(name = "error_message") val errorMessage: String? = null,
    /**
     * Excluded chapters are ignored in counts, in the feed and in the player.
     *
     * The app does not request them (`include_excluded` defaults to false), so this is false on
     * every row today. It is decoded anyway so that a caller which *does* ask for them gets an
     * answer rather than a silently-dropped field.
     */
    val excluded: Boolean = false,
    val playable: Boolean = false,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    @param:Json(name = "audio_filesize") val audioFileSize: Long? = null,
    /**
     * Whether this chapter has read-along timings. Null on a server that predates the field, which
     * is why it is not a plain Boolean: "we were not told" and "there are none" call for different
     * behaviour, and defaulting the first to false would hide a working reader.
     */
    @param:Json(name = "has_timings") val hasTimings: Boolean? = null,
    val audio: AudioInfo? = null,
    val playback: PlaybackInfo? = null,
    val fiction: FictionSummary? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "fiction_author") val fictionAuthor: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @param:Json(name = "resume_seconds") val resumeSeconds: Double? = null,
    @param:Json(name = "resume_time_label") val resumeTimeLabel: String? = null,
    @param:Json(name = "resume_label") val resumeLabel: String? = null,
) {
    val resolvedChapterId: Int
        get() = id.takeIf { it > 0 } ?: apiChapterId ?: 0

    val resolvedFictionId: Int
        get() = fictionId.takeIf { it > 0 } ?: fiction?.id ?: 0

    val resolvedTitle: String
        get() = chapterTitle ?: title

    val resolvedPositionSeconds: Double
        get() = playback?.positionSeconds ?: resumeSeconds ?: 0.0

    val resolvedFictionTitle: String?
        get() = fiction?.title ?: fictionTitle

    val resolvedAuthor: String?
        get() = fiction?.author ?: fictionAuthor

    val resolvedCoverUrl: String?
        get() = fiction?.coverImageUrl ?: coverImageUrl

    val resolvedIsPlayed: Boolean
        get() = playback?.isPlayed ?: false

    /** A chapter whose conversion failed, and which therefore has a reason worth reading. */
    val hasError: Boolean
        get() = status == ChapterStatus.Error

    /**
     * What the row's status tag should say.
     *
     * The flat [status] is too coarse to be useful on its own: every chapter that is not yet
     * playable reads "pending" whether it is queued behind two hundred others or ninety percent
     * converted. This folds in [subStatus] and [ttsProgress], which the server has always sent.
     *
     * Deliberately lowercase — [dk.perspektiva.ttsroad.ui.AarisTag] uppercases what it is given,
     * and doing it twice is how a label ends up shouting in one place and not another.
     */
    val statusLabel: String
        get() = when {
            excluded -> "excluded"
            hasError -> "failed"
            status == ChapterStatus.Processing || subStatus != null -> when (subStatus) {
                ChapterSubStatus.FetchingHtml -> "fetching"
                ChapterSubStatus.Preprocessing -> "cleaning"
                // The percentage is the whole point of showing this stage rather than "processing":
                // it is the only one that takes long enough for progress to be worth watching.
                ChapterSubStatus.Converting ->
                    ttsProgress?.let { "converting $it%" } ?: "converting"

                else -> status ?: "processing"
            }

            else -> status ?: "pending"
        }

    /**
     * Seconds left in this chapter, or null when nothing in the payload can answer that.
     *
     * The server computes `max(0, duration - position)` and sends it as `remaining_seconds`, but not
     * on every endpoint and not on every version, so fall back to the same arithmetic locally.
     * Null rather than 0.0 when neither is available: a total built from chapters that never
     * reported a duration should read as "unknown", not as "nothing left to listen to".
     */
    val resolvedRemainingSeconds: Double?
        get() {
            playback?.remainingSeconds?.let { return it.coerceAtLeast(0.0) }
            val duration = audioDuration ?: return null
            return (duration - resolvedPositionSeconds).coerceIn(0.0, duration)
        }
}

data class AudioInfo(
    val filename: String? = null,
    val path: String? = null,
    val url: String,
    @param:Json(name = "requires_bearer_auth") val requiresBearerAuth: Boolean = true,
)

data class PlaybackInfo(
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "last_listened_at") val lastListenedAt: String? = null,
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double? = null,
    @param:Json(name = "remaining_label") val remainingLabel: String? = null,
)

data class PlaybackProgressRequest(
    @param:Json(name = "fiction_id") val fictionId: Int,
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    @param:Json(name = "is_played") val isPlayed: Boolean,
)

data class PlaybackProgressResponse(
    val status: String = "",
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
)

data class PlaybackMarkRequest(
    @param:Json(name = "chapter_ids") val chapterIds: List<Int>,
    val played: Boolean,
)

data class PlaybackMarkResponse(
    val status: String = "",
    val played: Boolean = false,
    @param:Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    val count: Int = 0,
)


/**
 * One entry in the server-side cross-library queue.
 *
 * Flattened on purpose: a cross-fiction queue is played from surfaces that know nothing about the
 * fiction in question, so every entry carries the fiction title, cover and audio descriptor rather
 * than assuming the caller can look them up.
 */
data class QueueItem(
    /** The *queue row* id, which is what `reorder` and `remove` take — not the chapter id. */
    val id: Int = 0,
    val position: Int = 0,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    val audio: AudioInfo? = null,
) {
    val resolvedTitle: String
        get() = chapterTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter"
}

/**
 * `GET /api/mobile/fictions/{id}/audio-hashes` — what the bytes of each converted chapter hash to.
 *
 * The point of the endpoint is that chapter audio is **not immutable**. Re-convert, retry, retag
 * and stale-text reconvert all rewrite an MP3 in place, and the URL does not change when they do —
 * so a chapter already sitting in the download store is silently the old narration until someone
 * deletes it by hand, which nothing gives them a reason to do (#109).
 */
data class AudioHashesResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val total: Int = 0,
    val chapters: List<AudioHash> = emptyList(),
)

data class AudioHash(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    /**
     * Null for chapters converted before hashing shipped, and for rows the server's backfill has
     * not reached. The backend's own docstring is explicit that this means **unknown**, not
     * changed — so a null must never mark a download stale. Getting that backwards would re-download
     * a whole library the first time it met an older server.
     */
    @param:Json(name = "audio_sha256") val audioSha256: String? = null,
    @param:Json(name = "audio_filesize") val audioFileSize: Long = 0L,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
)

/**
 * The shape every maintenance action answers with (#107, #112).
 *
 * One model for nine endpoints because they genuinely answer the same thing — "accepted, and here
 * is how much it is about to do" — and the counts differ only in which field carries them. Every
 * field is nullable or defaulted, so a route that has no count to report simply omits one rather
 * than needing its own model.
 *
 * The counts are the point. "Re-narrate every chapter" and "rewrite the tags" both come back as
 * `status: "ok"`, and the only thing that distinguishes a no-op from four hundred conversions is a
 * number the user should be shown.
 */
data class MaintenanceResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    /** Chapters requeued — by `retry-failed`, `retry-all-failed` or `reconvert-all`. */
    @param:Json(name = "reset_count") val resetCount: Int? = null,
    /** MP3s whose tags were rewritten by `retag`. */
    @param:Json(name = "file_count") val fileCount: Int? = null,
    /** Chapters the filter took out. Never un-excludes: a hand-excluded chapter had a reason. */
    @param:Json(name = "excluded_count") val excludedCount: Int? = null,
    /** Fictions touched by the library-wide retry. */
    val fictions: Int? = null,
    /** True when `poll` re-ingested the whole chapter list rather than the recent tail. */
    @param:Json(name = "full_ingest") val fullIngest: Boolean = false,
    /** How many chapters a partial poll re-read, when it took that branch. */
    @param:Json(name = "partial_sync") val partialSync: Int? = null,
    /** Set by `apply-chapter-filter` only, and only when there was no filter to apply. */
    val detail: String? = null,
    val excluded: Boolean? = null,
    /** The new URL, after `feed-token/rotate`. Saves re-fetching the feed list to read it back. */
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int? = null,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
)

/** `POST /api/mobile/chapters/{id}/exclude` — take a chapter off every feed, or put it back. */
data class ChapterExcludeRequest(val excluded: Boolean = true)

/**
 * `GET /api/mobile/feeds` — every podcast URL this account can hand to a podcast app (#115).
 *
 * Serving a private podcast feed is the feature TTSRoad is built around, and the phone is where a
 * podcast app lives. The only way to get a tokenised feed URL onto a phone used to be mailing it to
 * yourself from a laptop.
 */
data class FeedsResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    /** `followed` or `all`, echoed back — the same scoping `/library` uses. */
    val scope: String = LibraryScopeFollowed,
    val library: LibraryFeed = LibraryFeed(),
    val fictions: List<FictionFeed> = emptyList(),
)

/**
 * The account's own two links: every fiction newest-first, and an OPML of the per-fiction feeds.
 *
 * These carry a token derived from *this account*, which is why rotating them is self-service.
 * `account.html` calls them "a private token for this account; treat them like a password".
 */
data class LibraryFeed(
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int = 0,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
    @param:Json(name = "opml_url") val opmlUrl: String? = null,
)

/**
 * One fiction's feed URL.
 *
 * Its token is derived from the *fiction*, so it is the same string for every account — which is
 * why rotating one is an admin action and rotating the library pair is not.
 */
data class FictionFeed(
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "",
    val slug: String? = null,
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int = 0,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
)

/** The answer to rotating the account's library feed: the same payload with new URLs. */
data class LibraryFeedRotateResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "feed_token_version") val feedTokenVersion: Int = 0,
    @param:Json(name = "feed_url") val feedUrl: String? = null,
    @param:Json(name = "opml_url") val opmlUrl: String? = null,
)

/**
 * `GET /api/mobile/listening-state` — every position and chosen mark on the account (#116).
 *
 * The web account page puts the case better than a comment can: *"Audio can always be made again.
 * Where you are in a four-hundred-chapter serial cannot."* And the phone is where most of that
 * state is made — positions written by the media service every 15s, marks made from the car.
 *
 * [document] is deliberately an opaque map. This client neither reads nor rewrites it: it saves
 * what the server produced and posts it back unaltered, so a document from a newer server survives
 * a round trip through an older app rather than being silently trimmed to the fields it knows.
 */
data class ListeningStateExport(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val document: Map<String, Any?> = emptyMap(),
)

/**
 * What a restore actually did.
 *
 * The import is never destructive — a position only moves forward and bookmarks are added rather
 * than reconciled — so this reports additions, not a diff. The counts are what tell a six-month-old
 * backup restored over a live account from one that did nothing.
 */
data class ListeningStateImportResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val report: Map<String, Any?> = emptyMap(),
)

data class QueueResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val items: List<QueueItem> = emptyList(),
    val total: Int = 0,
    /**
     * The account's `queue_when_empty`: `stop` or `continue`. Read rather than decided locally —
     * `advance` already honours it server-side, which is the point of calling `advance` at all.
     */
    @param:Json(name = "when_empty") val whenEmpty: String = "stop",
    @param:Json(name = "max_items") val maxItems: Int = 0,
)

/**
 * The answer to `advance`: what should play next, with no curation from the driver.
 *
 * `status` is `playing` or `empty`. `source` says where the item came from — `queue` when it was
 * taken off the head, `continue` when the queue was empty and the account asked to keep going with
 * the oldest unplayed chapter.
 */
data class QueueAdvanceResponse(
    val status: String = "",
    val item: QueueItem? = null,
    val source: String? = null,
    val items: List<QueueItem> = emptyList(),
    val total: Int = 0,
)

/**
 * Every queue mutation goes through one POST with an `action`.
 *
 * One endpoint rather than five keeps the client's retry path single, and lets the server add
 * actions without the app needing new URLs.
 */
data class QueueRequest(
    val action: String,
    @param:Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    @param:Json(name = "item_ids") val itemIds: List<Int> = emptyList(),
    /** `end` or `next` — the difference between "after everything" and "play this after the current one". */
    val mode: String = QueueModeEnd,
    val source: String? = null,
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
)

const val QueueActionAdd: String = "add"
/**
 * Rewrite the whole order.
 *
 * Takes the complete ordered list of *row* ids rather than a move instruction. That is the server's
 * shape and it is the right one: two clients reordering the same queue cannot half-apply each
 * other's moves, and a row the server has since dropped simply does not appear in what comes back.
 */
const val QueueActionReorder: String = "reorder"
const val QueueActionRemove: String = "remove"
const val QueueActionClear: String = "clear"
const val QueueActionAdvance: String = "advance"
const val QueueModeEnd: String = "end"
const val QueueModeNext: String = "next"
const val QueueStatusPlaying: String = "playing"

/**
 * `GET /api/mobile/search?q=`.
 *
 * Grouped rather than flat, and the groups are always present even when empty — fictions, then
 * chapter titles, then narration text. The group order *is* the rank order; `score` says the same
 * thing numerically for a client that would rather have one list.
 */
data class SearchResponse(
    val query: String = "",
    val fictions: SearchGroup = SearchGroup(),
    val chapters: SearchGroup = SearchGroup(),
    val text: SearchGroup = SearchGroup(),
    /**
     * Whether the full-text index was available. False means the narration-text group fell back to
     * a slower or narrower match, which is worth saying rather than silently returning less.
     */
    val indexed: Boolean = false,
    val total: Int = 0,
)

data class SearchGroup(
    val items: List<SearchHit> = emptyList(),
    val total: Int = 0,
    /** The server stops counting at a cap; render "500+" rather than "500" when this is set. */
    val capped: Boolean = false,
    @param:Json(name = "has_more") val hasMore: Boolean = false,
)

/**
 * One hit. The same shape in all three groups, so a fiction hit simply has no chapter.
 *
 * Everything but `kind` is optional on purpose: a fiction hit carries no chapter id or snippet, and
 * a text hit carries no tags, and one strict field would fail the whole response.
 */
data class SearchHit(
    /** `fiction`, `chapter` or `text`. */
    val kind: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
    @param:Json(name = "chapter_id") val chapterId: Int? = null,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    val author: String? = null,
    @param:Json(name = "cover_image_url") val coverImageUrl: String? = null,
    /** The matching passage, for chapter-title and narration-text hits. */
    val snippet: String? = null,
    val playable: Boolean = false,
) {
    /** Never blank, so every row has something to show. */
    val resolvedTitle: String
        get() = when {
            !chapterTitle.isNullOrBlank() -> chapterTitle
            !fictionTitle.isNullOrBlank() -> fictionTitle
            else -> "Untitled"
        }
}

const val SearchKindFiction: String = "fiction"
const val SearchKindChapter: String = "chapter"
const val SearchKindText: String = "text"

/**
 * The two `scope` values `/api/mobile/library` accepts.
 *
 * `followed` is the default and is what a client that has never heard of follows gets. That is safe
 * because the backend's upgrade backfills a follow of every fiction for every existing account, so
 * an older app sees exactly what it saw before rather than an empty shelf.
 */
const val LibraryScopeFollowed: String = "followed"
const val LibraryScopeAll: String = "all"

/** `POST`/`DELETE /api/mobile/fictions/{id}/follow`. Both answer the resulting state. */
data class FollowResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val following: Boolean = false,
)

/**
 * `GET`/`PATCH /api/me/preferences`.
 *
 * A loose map for the same reason [CapabilitiesResponse] uses one: the server owns the vocabulary
 * and adds to it on its own schedule, so a strict model would fail to parse the whole blob over one
 * key this build has never heard of. The typed reads live in `AccountPreferences.kt`.
 */
data class AccountPreferencesResponse(
    val preferences: Map<String, Any?> = emptyMap(),
)

/**
 * One bookmark, exactly as `/api/bookmarks` and `/api/mobile/bookmarks` both return it.
 *
 * Chapter and fiction titles ride along in the payload precisely so an account-wide list can be
 * rendered without a second request per row — use them rather than looking the chapter up.
 *
 * Everything but the id is optional: the server returns nulls for a bookmark whose chapter has been
 * removed, and a list that failed to parse over one such row would be worse than one that shows it.
 */
data class Bookmark(
    val id: Int = 0,
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "fiction_id") val fictionId: Int? = null,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "position_label") val positionLabel: String? = null,
    val label: String? = null,
    val note: String? = null,
    val color: String? = null,
    /** `manual` for a mark the user made; `auto` is the jump-back breadcrumb, filtered out by default. */
    val kind: String = BookmarkKindManual,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "chapter_title") val chapterTitle: String? = null,
    @param:Json(name = "chapter_number") val chapterNumber: Double? = null,
    @param:Json(name = "fiction_title") val fictionTitle: String? = null,
) {
    /** Never blank, so an unlabelled mark still has something to show in the list. */
    val resolvedLabel: String
        get() = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: chapterTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Bookmark"
}

const val BookmarkKindManual: String = "manual"

/**
 * The jump-back breadcrumb kind — a position recorded because playback was there, not because
 * anyone chose it. Shares a table with `manual`, so anything rendering user-chosen marks must
 * filter, or a day of listening buries the handful the reader actually made.
 */
const val BookmarkKindAuto: String = "auto"

data class BookmarksResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "server_time") val serverTime: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val deleted: List<Int> = emptyList(),
)

/** POST and PATCH both answer the single written row under `bookmark`. */
data class BookmarkWriteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val bookmark: Bookmark? = null,
)

data class BookmarkDeleteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val id: Int = 0,
)

/**
 * A new bookmark. Only `chapter_id` is required by the server; the rest default.
 *
 * `kind` is sent explicitly rather than left to the server's default so that a mark made from the
 * player is always a `manual` one, and can never be mistaken for a jump-back breadcrumb.
 */
data class CreateBookmarkRequest(
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    val label: String? = null,
    val note: String? = null,
    val kind: String = BookmarkKindManual,
)

/**
 * A partial update.
 *
 * The server checks key *presence* — a key that is absent leaves the stored value alone. Moshi omits
 * null fields when serialising, so a null here means "do not touch this field", which lines the two
 * halves up exactly. To *clear* a value, send an empty string: the server trims it and stores null.
 */
data class UpdateBookmarkRequest(
    val label: String? = null,
    val note: String? = null,
)

/**
 * One item of `POST /api/mobile/playback/sync`.
 *
 * [clientUpdatedAt] is what makes the batch endpoint different from `/playback/progress`: the
 * server applies an item only if this stamp is *strictly newer* than what it already holds. Equal
 * loses, so two devices re-posting the same synced state do not take turns clobbering each other,
 * and a missing or unparseable stamp loses rather than being guessed at.
 */
data class PlaybackSyncItem(
    @param:Json(name = "chapter_id") val chapterId: Int,
    @param:Json(name = "position_seconds") val positionSeconds: Double,
    @param:Json(name = "is_played") val isPlayed: Boolean,
    @param:Json(name = "client_updated_at") val clientUpdatedAt: String,
)

data class PlaybackSyncRequest(
    val items: List<PlaybackSyncItem>,
)

/** What the server actually holds for a chapter after the batch was applied. */
data class PlaybackSyncState(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
    @param:Json(name = "last_listened_at") val lastListenedAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null,
    @param:Json(name = "client_updated_at") val clientUpdatedAt: String? = null,
)

data class PlaybackSyncAccepted(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    @param:Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @param:Json(name = "is_played") val isPlayed: Boolean = false,
)

/**
 * A rejected item, with the reason and the watermark that beat it.
 *
 * `reason` is one of `not_found`, `missing_client_updated_at`, `invalid_client_updated_at`, `empty`
 * or `stale`. Only `stale` means "someone else was newer"; the rest mean this client sent something
 * the server could not use, and retrying it unchanged would fail identically.
 */
data class PlaybackSyncRejected(
    @param:Json(name = "chapter_id") val chapterId: Int = 0,
    val reason: String = "",
    @param:Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
)

data class PlaybackSyncResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    @param:Json(name = "server_time") val serverTime: String? = null,
    val accepted: List<PlaybackSyncAccepted> = emptyList(),
    val rejected: List<PlaybackSyncRejected> = emptyList(),
    @param:Json(name = "server_state") val serverState: List<PlaybackSyncState> = emptyList(),
)

/**
 * `POST /api/mobile/fictions` — track a new fiction by URL or bare Royal Road id.
 *
 * Only `fiction_url` is sent. Voice, rate and the sync window all have server-side defaults, and a
 * phone is the wrong place to be choosing a TTS voice for a whole serial — the fiction screen and
 * the web console are both better homes for that than a paste-a-URL box.
 */
data class AddFictionRequest(
    @param:Json(name = "fiction_url") val fictionUrl: String,
)

/** What `POST` answers: the newly tracked row under `fiction`. */
data class FictionWriteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    val fiction: FictionSummary? = null,
)

/**
 * `DELETE /api/mobile/fictions/{id}`.
 *
 * The mobile route answers with a body where the web route answers `204`, precisely so a phone on a
 * flaky connection can tell "deleted" from "the request never arrived".
 */
data class FictionDeleteResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
    val status: String = "",
    @param:Json(name = "fiction_id") val fictionId: Int = 0,
    val deleted: Boolean = false,
)

/**
 * `PATCH /api/mobile/fictions/{id}` — correct what the source got wrong.
 *
 * Every field is optional and Moshi omits nulls, so a null here means "leave this alone" and lines
 * the wire up exactly with what the server checks. Two consequences worth keeping in mind:
 *
 * - An *empty string* is not null. It is how [author] and [description] are cleared, and the server
 *   stores null for it.
 * - Sending a field marks it hand-edited, so the next poll stops refilling it. Send only what
 *   actually changed — see `fictionMetadataPatch`, which is where that decision is made — or saving
 *   a form nobody touched would quietly freeze every field on it.
 *
 * [clearOverrides] is the undo: it removes names from the fiction's `metadata_overrides` so the
 * source may write them again. It does not restore the old text; only the next poll can do that.
 */
data class FictionUpdateRequest(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    @param:Json(name = "clear_overrides") val clearOverrides: List<String>? = null,
)
