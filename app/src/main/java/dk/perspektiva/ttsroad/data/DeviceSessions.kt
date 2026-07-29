package dk.perspektiva.ttsroad.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Display helpers for the devices screen.
 *
 * The mobile API sends ISO-8601 `Z` timestamps. They are turned into text here rather than in the
 * composables so they can be tested without a Compose harness, and every entry point takes an
 * explicit clock so the tests do not depend on the time of day they run.
 *
 * Anything unparseable or missing renders as "-": a session with no `last_used_at` is normal (it
 * has never been used), and a timestamp the app cannot read is not worth failing the screen over.
 */

private const val Unknown = "-"

private val AbsoluteFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

fun parseServerInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Absolute local date and time, for "created" where the exact day is what matters. */
fun deviceTimestampLabel(value: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = parseServerInstant(value) ?: return Unknown
    return AbsoluteFormat.format(instant.atZone(zone))
}

/**
 * Elapsed time, for "last used" — recognising your own phone in a list is much easier from
 * "12 minutes ago" than from a date.
 */
fun deviceLastUsedLabel(value: String?, now: Instant = Instant.now()): String {
    val instant = parseServerInstant(value) ?: return Unknown
    val elapsed = Duration.between(instant, now)
    return when {
        // A clock skew between phone and server should not read as "in the future".
        elapsed.isNegative || elapsed.toMinutes() < 1 -> "Just now"
        elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()} min ago"
        elapsed.toHours() < 24 -> "${elapsed.toHours()}h ago"
        elapsed.toDays() == 1L -> "Yesterday"
        else -> "${elapsed.toDays()} days ago"
    }
}

/**
 * How long this session has left. Tokens renew silently on use, so a device in regular use always
 * shows the full window — a short number here means "this one is going stale".
 */
fun deviceExpiryLabel(value: String?, now: Instant = Instant.now()): String {
    val instant = parseServerInstant(value) ?: return Unknown
    val remaining = Duration.between(now, instant)
    return when {
        remaining.isNegative || remaining.isZero -> "Expired"
        remaining.toHours() < 1 -> "Expires in ${remaining.toMinutes().coerceAtLeast(1)} min"
        remaining.toDays() < 1 -> "Expires in ${remaining.toHours()}h"
        remaining.toDays() == 1L -> "Expires tomorrow"
        else -> "Expires in ${remaining.toDays()} days"
    }
}

/** True when the session is usable — the only kind worth offering a revoke button for. */
val DeviceSession.isActive: Boolean
    get() = status.equals("active", ignoreCase = true)
