package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

data class LoginRequest(
    val username: String,
    val password: String,
    @param:Json(name = "device_name") val deviceName: String,
    @param:Json(name = "totp_code") val totpCode: String? = null,
)

data class LoginResponse(
    val token: String,
    @param:Json(name = "token_type") val tokenType: String = "bearer",
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
    @param:Json(name = "is_admin") val isAdmin: Boolean = false,
)

data class ServerInfo(
    val name: String = "TTSRoad",
    @param:Json(name = "base_url") val baseUrl: String? = null,
    @param:Json(name = "api_version") val apiVersion: Int = 1,
)

data class LibraryResponse(
    @param:Json(name = "api_version") val apiVersion: Int = 1,
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
) {
    /** Fraction of chapters with audio ready, for progress indicators. */
    val readyFraction: Float
        get() = if (totalChapters > 0) (doneChapters.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
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
    val playable: Boolean = false,
    @param:Json(name = "audio_duration") val audioDuration: Double? = null,
    @param:Json(name = "audio_duration_label") val audioDurationLabel: String? = null,
    @param:Json(name = "audio_filesize") val audioFileSize: Long? = null,
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

