package dk.perspektiva.ttsroad.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Read a timestamp the backend produced.
 *
 * FastAPI is not consistent about the zone suffix — the same field can arrive as `...Z`, with an
 * offset, or naive — so all three are tried before giving up. Naive values are read as UTC, which
 * is what the backend stores. Returns null rather than throwing, because a device row with one
 * unreadable date is still worth showing.
 */
fun parseServerInstant(iso: String?): Instant? {
    val text = iso?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { Instant.parse(text) }
        .recoverCatching { OffsetDateTime.parse(text).toInstant() }
        .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
        .getOrNull()
}

private const val TimestampPattern = "d MMM yyyy, HH:mm"

/**
 * A server timestamp as a short local date and time, or null if there is nothing usable to show.
 *
 * Rendered in the phone's zone: "last used" only means something measured against the clock the
 * user is looking at.
 */
fun formatServerTimestamp(
    iso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val instant = parseServerInstant(iso) ?: return null
    return DateTimeFormatter.ofPattern(TimestampPattern, locale)
        .withZone(zone)
        .format(instant)
}

/**
 * How much life a session has left, in whole days.
 *
 * Deliberately coarse: tokens last 90 days and renew on every authenticated request, so the exact
 * hour is noise. The point is only to tell a session that is about to lapse from one that is fine.
 */
fun formatExpiresIn(iso: String?, nowMs: Long): String? {
    val expiry = parseServerInstant(iso) ?: return null
    val remainingMs = expiry.toEpochMilli() - nowMs
    if (remainingMs <= 0L) return "expired"
    val days = remainingMs / 86_400_000L
    return when (days) {
        0L -> "expires today"
        1L -> "expires in 1 day"
        else -> "expires in $days days"
    }
}
