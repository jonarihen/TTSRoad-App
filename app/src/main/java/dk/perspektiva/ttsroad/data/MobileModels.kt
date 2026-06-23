package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

data class LoginRequest(
    val username: String,
    val password: String,
    @Json(name = "device_name") val deviceName: String,
)

data class LoginResponse(
    val token: String,
    @Json(name = "token_type") val tokenType: String = "bearer",
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

data class MobileUser(
    val id: Int,
    val username: String,
    @Json(name = "is_admin") val isAdmin: Boolean = false,
)

data class ServerInfo(
    val name: String = "TTSRoad",
    @Json(name = "base_url") val baseUrl: String? = null,
    @Json(name = "api_version") val apiVersion: Int = 1,
)

data class LibraryResponse(
    @Json(name = "api_version") val apiVersion: Int = 1,
    val fictions: List<FictionSummary> = emptyList(),
    @Json(name = "continue_listening") val continueListening: List<ChapterSummary> = emptyList(),
    @Json(name = "recent_chapters") val recentChapters: List<ChapterSummary> = emptyList(),
)

data class ChaptersResponse(
    @Json(name = "api_version") val apiVersion: Int = 1,
    val fiction: FictionSummary,
    val total: Int = 0,
    val chapters: List<ChapterSummary> = emptyList(),
)

data class FictionSummary(
    val id: Int = 0,
    val title: String = "Untitled",
    val author: String? = null,
    val slug: String? = null,
    @Json(name = "cover_image_url") val coverImageUrl: String? = null,
    @Json(name = "total_chapters") val totalChapters: Int = 0,
    @Json(name = "done_chapters") val doneChapters: Int = 0,
)

data class ChapterSummary(
    val id: Int = 0,
    @Json(name = "fiction_id") val fictionId: Int = 0,
    val title: String = "Untitled chapter",
    @Json(name = "chapter_number") val chapterNumber: Double? = null,
    @Json(name = "display_number") val displayNumber: Double? = null,
    @Json(name = "player_index") val playerIndex: Int? = null,
    val status: String? = null,
    val playable: Boolean = false,
    @Json(name = "audio_duration") val audioDuration: Double? = null,
    @Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    @Json(name = "audio_filesize") val audioFileSize: Long? = null,
    val audio: AudioInfo? = null,
    val playback: PlaybackInfo? = null,
    val fiction: FictionSummary? = null,
    @Json(name = "fiction_title") val fictionTitle: String? = null,
    @Json(name = "fiction_author") val fictionAuthor: String? = null,
    @Json(name = "cover_image_url") val coverImageUrl: String? = null,
) {
    val resolvedFictionTitle: String?
        get() = fiction?.title ?: fictionTitle

    val resolvedAuthor: String?
        get() = fiction?.author ?: fictionAuthor

    val resolvedCoverUrl: String?
        get() = fiction?.coverImageUrl ?: coverImageUrl
}

data class AudioInfo(
    val filename: String? = null,
    val path: String? = null,
    val url: String,
    @Json(name = "requires_bearer_auth") val requiresBearerAuth: Boolean = true,
)

data class PlaybackInfo(
    @Json(name = "position_seconds") val positionSeconds: Double = 0.0,
    @Json(name = "is_played") val isPlayed: Boolean = false,
    @Json(name = "last_listened_at") val lastListenedAt: String? = null,
    @Json(name = "remaining_seconds") val remainingSeconds: Double? = null,
    @Json(name = "remaining_label") val remainingLabel: String? = null,
)

data class PlaybackProgressRequest(
    @Json(name = "fiction_id") val fictionId: Int,
    @Json(name = "chapter_id") val chapterId: Int,
    @Json(name = "position_seconds") val positionSeconds: Double,
    @Json(name = "is_played") val isPlayed: Boolean,
)

data class PlaybackProgressResponse(
    val status: String = "",
    @Json(name = "chapter_id") val chapterId: Int = 0,
)

data class PlaybackMarkRequest(
    @Json(name = "chapter_ids") val chapterIds: List<Int>,
    val played: Boolean,
)

data class PlaybackMarkResponse(
    val status: String = "",
    val played: Boolean = false,
    @Json(name = "chapter_ids") val chapterIds: List<Int> = emptyList(),
    val count: Int = 0,
)

