package dk.perspektiva.ttsroad.data

import com.squareup.moshi.Json

data class FictionNotificationSettings(
    val mode: String,
    @param:Json(name = "backlog_hours") val backlogHours: Double,
    @param:Json(name = "remaining_seconds") val remainingSeconds: Double,
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

const val NotificationModeEvery = "every"
const val NotificationModeOff = "off"
const val NotificationModeBacklog = "backlog"

fun validBacklogHours(value: String): Double? = value.trim().toDoubleOrNull()?.takeIf {
    it.isFinite() && it > 0.0 && it <= 1000.0
}

fun notificationModeSummary(settings: FictionNotificationSettings?): String = when (settings?.mode) {
    NotificationModeEvery -> "Every chapter"
    NotificationModeOff -> "Off"
    NotificationModeBacklog -> "Wait for a ${formatHours(settings.backlogHours)} backlog"
    else -> "Choose when new chapters notify you"
}

fun remainingBacklogLabel(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0.0) return null
    val minutes = kotlin.math.ceil(seconds / 60.0).toLong()
    val text = when {
        minutes < 60 -> "$minutes min"
        minutes % 60L == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
    return "$text remaining until the backlog notice"
}

private fun formatHours(hours: Double): String =
    if (hours % 1.0 == 0.0) "${hours.toLong()}h" else "${hours}h"
