package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

data class FictionNotificationSettings(
    val mode: String,
    @param:Json(name = "backlog_hours") val backlogHours: Double,
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double,
    @param:Json(name = "backlog_armed") val backlogArmed: Boolean? = null,
)

data class FictionNotificationSettingsRequest(
    val mode: String,
    @param:Json(name = "backlog_hours") val backlogHours: Double,
)

sealed interface FictionNotificationSettingsResult {
    data class Loaded(val settings: FictionNotificationSettings) : FictionNotificationSettingsResult
    data class Refused(val message: String) : FictionNotificationSettingsResult
    data object Unsupported : FictionNotificationSettingsResult
}

enum class FictionNotificationStatus { Every, Off, Armed, Waiting, Unknown }

const val NotificationModeEvery = "every"
const val NotificationModeOff = "off"
const val NotificationModeBacklog = "backlog"

fun validBacklogHours(value: String): Double? = value.trim().toDoubleOrNull()?.takeIf {
    it.isFinite() && it > 0.0 && it <= 1000.0
}

fun fictionNotificationStatus(settings: FictionNotificationSettings?): FictionNotificationStatus =
    when (settings?.mode) {
        NotificationModeEvery -> FictionNotificationStatus.Every
        NotificationModeOff -> FictionNotificationStatus.Off
        NotificationModeBacklog -> when (settings.backlogArmed) {
            true -> FictionNotificationStatus.Armed
            false -> FictionNotificationStatus.Waiting
            null -> FictionNotificationStatus.Unknown
        }
        else -> FictionNotificationStatus.Unknown
    }

fun notificationModeSummary(settings: FictionNotificationSettings?): String =
    when (fictionNotificationStatus(settings)) {
        FictionNotificationStatus.Every -> "Every chapter"
        FictionNotificationStatus.Off -> "Off"
        FictionNotificationStatus.Armed -> "Alarm armed"
        FictionNotificationStatus.Waiting -> "Alarm waiting"
        FictionNotificationStatus.Unknown -> "Status unavailable"
    }

fun notificationStatusDescription(settings: FictionNotificationSettings?): String {
    val hours = settings?.let { formatHours(it.backlogHours) }
    return when (fictionNotificationStatus(settings)) {
        FictionNotificationStatus.Every -> "Notifications on — every new chapter."
        FictionNotificationStatus.Off -> "Notifications off."
        FictionNotificationStatus.Armed -> "Backlog alarm armed — alerts at $hours."
        FictionNotificationStatus.Waiting -> "Backlog alarm waiting — listen below $hours to re-arm."
        FictionNotificationStatus.Unknown -> if (settings?.mode == NotificationModeBacklog) {
            "Backlog alarm — status unavailable."
        } else {
            "Notification status unavailable."
        }
    }
}

fun remainingBacklogLabel(seconds: Double): String? {
    if (!seconds.isFinite() || seconds < 0.0) return null
    if (seconds == 0.0) return "Ready to listen: nothing."
    if (seconds < 60.0) return "Ready to listen: under a minute."
    val minutes = kotlin.math.ceil(seconds / 60.0).toLong()
    val text = when {
        minutes < 60 -> "$minutes min"
        minutes % 60L == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    return "Ready to listen: $text."
}

private fun formatHours(hours: Double): String {
    val amount = if (hours % 1.0 == 0.0) "${hours.toLong()}" else "$hours"
    return "$amount ${if (hours == 1.0) "hour" else "hours"}"
}
