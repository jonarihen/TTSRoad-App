package dk.perspektiva.ttsroad.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The account-level preference vocabulary, and the rules for reconciling it with this phone.
 *
 * The server owns the vocabulary (`app/services/preferences.py`): every key declares a range or a
 * menu, out-of-range values are clamped or snapped, and the PATCH response echoes the stored blob.
 * This file is the client half of that contract — the key names, the same ranges, and the mapping
 * between the server's values and the richer types the app's own UI is built on.
 *
 * ## What syncs, and what deliberately does not
 *
 * **Reader appearance syncs.** Font size, theme and highlight follow the account, because
 * appearance following you between the browser and the phone is the whole point of storing it on
 * the account. Desktop already does this.
 *
 * **`hide_played` syncs.** The 0.10.0 changelog called this out: the app's remembered chapter
 * filter and the web's Hide played are the same intent stored twice, disagreeing with each other.
 *
 * **`sleep_timer_default_minutes` is adopted.** Not "unsynced" but missing outright — the app had
 * no concept of a default duration at all.
 *
 * **`auto_mark_played` syncs.** The web has honoured it since it was introduced and the app ignored
 * it, marking a chapter played past 96% regardless. That was not merely unsupported here: the
 * phone writes `is_played` to the same `ChapterPlaybackState` rows the browser reads, so unticking
 * the box in a browser was overridden by the device doing most of the listening.
 *
 * **The four device player keys stay local.** `playback_speed`, `skip_interval_seconds`,
 * `skip_silence` and `volume_boost` remain in [PlaybackPreferences] on this phone. The backend
 * declares them as a cross-repo contract, and following that would be defensible, but a phone on
 * earbuds and a laptop on speakers genuinely want different values, and desktop made the same call
 * for the same reason. This is a decision rather than an oversight; reversing it is a change to
 * [SyncedPlayerKeys] and the setters that feed it, not a redesign.
 *
 * **`reader_line_height` syncs.** It did not until the reader grew a spacing control to attach it
 * to (#122). Unlike the theme and highlight mappings below, this one is not lossy in either
 * direction: both ends store the same multiplier over the same 1.3–2.4 range, so the value that
 * comes back is the value that was sent.
 *
 * ## Lossy mappings, and why reads do not write
 *
 * The app's reader vocabulary is richer than the server's in two places: four highlight modes
 * against three, and three themes against three that do not line up. Projecting a local value onto
 * a server value therefore loses information, and blindly adopting whatever comes back would
 * silently rewrite a setting the user chose — [reconciledHighlight] and friends exist to stop that.
 * The rule throughout is: **adopt the server's value only when it differs from what the local value
 * already projects to.** A local `SentenceOnly` projects to `"sentence"`, so a server holding
 * `"sentence"` leaves it alone; a server holding `"word"` is a real change made elsewhere and wins.
 *
 * The other half of that rule is that a sync read never PATCHes. Nothing here echoes a value back,
 * so a mapping that is lossy in one direction cannot ping-pong between two clients.
 */
object AccountPreferenceKeys {
    const val HidePlayed = "hide_played"
    const val AutoMarkPlayed = "auto_mark_played"
    const val SleepTimerDefaultMinutes = "sleep_timer_default_minutes"
    const val ReaderFontSize = "reader_font_size"
    const val ReaderLineHeight = "reader_line_height"
    const val ReaderTheme = "reader_theme"
    const val ReaderHighlight = "reader_highlight"

    /**
     * What playback does when the cross-library queue runs dry: `stop`, or keep going with the
     * oldest unplayed chapter in the library.
     *
     * Not in [Synced], and not an oversight. Every other key here has a local copy this phone shows
     * with no network; this one has none, because the decision is made *server-side* inside
     * `advance` — which is the point of calling `advance` rather than picking the next chapter
     * locally. So the app reads the current value off the queue payload and writes it with a plain
     * PATCH, and there is nothing to reconcile.
     */
    const val QueueWhenEmpty = "queue_when_empty"

    /** Keys this client reads and writes. Anything else on the account is left untouched. */
    val Synced: Set<String> = setOf(
        HidePlayed,
        AutoMarkPlayed,
        SleepTimerDefaultMinutes,
        ReaderFontSize,
        ReaderLineHeight,
        ReaderTheme,
        ReaderHighlight,
    )
}

/**
 * The player keys the server declares that this client deliberately keeps on the device.
 *
 * Named rather than merely absent, so the decision above is greppable and a future change is one
 * list rather than an archaeology exercise.
 */
val SyncedPlayerKeys: Set<String> = emptySet()

/** The server's `sleep_timer_default_minutes` menu. 0 means "ask me every time". */
val SleepTimerDefaultOptions: List<Int> = listOf(0, 5, 15, 30, 45, 60)

const val DefaultSleepTimerMinutes: Int = 0

/**
 * Whether finishing a chapter marks it played without being asked.
 *
 * True to match the server's own default for `auto_mark_played`, so a phone that has never synced
 * behaves as the account would have told it to.
 */
const val DefaultAutoMarkPlayed: Boolean = true

/**
 * The server's `reader_font_size` range, and the size its default corresponds to.
 *
 * The app stores a *scale* on top of the system font size while the server stores an absolute
 * point size, so the two need a fixed point to convert through. 19 is the server's declared
 * default, and the app's declared default scale is 1.0, so they are the same reading — that is
 * what makes [readerFontSizeOf] and [readerFontScaleOf] inverses at the default.
 */
const val ServerReaderFontSizeDefault: Int = 19
const val MinServerReaderFontSize: Int = 14
const val MaxServerReaderFontSize: Int = 30

/** Snap to the nearest offered duration rather than dropping an unknown one on the floor. */
fun sanitizeSleepTimerMinutes(minutes: Int): Int =
    SleepTimerDefaultOptions.minByOrNull { abs(it - minutes) } ?: DefaultSleepTimerMinutes

/**
 * The app's font *scale* as the server's absolute point size.
 *
 * The two ranges do not quite cover each other. The app's scale floor of 0.8 is 15pt, so the
 * server's smallest size — 14 — is not expressible from this client, and a 14 set in the browser
 * reads back here as the app's smallest size instead. That is a display difference of one point at
 * the very bottom of the range, and it is *stable*: the value round-trips to the same pair on every
 * subsequent sync rather than drifting, and because a pull never PATCHes, the phone never writes
 * its 15 over the browser's 14.
 */
fun readerFontSizeOf(scale: Float): Int =
    (sanitizeReaderFontScale(scale) * ServerReaderFontSizeDefault)
        .roundToInt()
        .coerceIn(MinServerReaderFontSize, MaxServerReaderFontSize)

/** The server's absolute point size as the app's font scale. */
fun readerFontScaleOf(size: Int): Float =
    sanitizeReaderFontScale(
        size.coerceIn(MinServerReaderFontSize, MaxServerReaderFontSize).toFloat() /
            ServerReaderFontSizeDefault,
    )

/**
 * The app's theme as the server's.
 *
 * [ReaderTheme.Night] is a dark theme with dimmed text and the server has no equivalent, so it
 * projects to `dark` — the same value [ReaderTheme.Console] projects to. That collision is exactly
 * why reconciliation compares projections instead of adopting blindly: a server holding `dark`
 * cannot tell the two apart and so must not overwrite either.
 */
fun serverReaderTheme(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.Console -> "dark"
    ReaderTheme.Paper -> "sepia"
    ReaderTheme.Night -> "dark"
}

/** The server's theme as the app's. `light` has no exact match; Paper is the app's bright page. */
fun readerThemeFromServer(value: String?): ReaderTheme? = when (value) {
    "dark" -> ReaderTheme.Console
    "sepia" -> ReaderTheme.Paper
    "light" -> ReaderTheme.Paper
    else -> null
}

/**
 * The app's highlight mode as the server's.
 *
 * [HighlightGranularity.SentenceAndWord] projects to `sentence`: it draws a sentence band, and the
 * word accent is a detail inside it rather than a different mode. The server's `word` means the
 * word *alone*, which is [HighlightGranularity.WordOnly].
 */
fun serverReaderHighlight(granularity: HighlightGranularity): String = when (granularity) {
    HighlightGranularity.SentenceAndWord -> "sentence"
    HighlightGranularity.SentenceOnly -> "sentence"
    HighlightGranularity.WordOnly -> "word"
    HighlightGranularity.Off -> "off"
}

/** The server's highlight mode as the app's, taking the app's default as the canonical `sentence`. */
fun readerHighlightFromServer(value: String?): HighlightGranularity? = when (value) {
    "sentence" -> DefaultHighlightGranularity
    "word" -> HighlightGranularity.WordOnly
    "off" -> HighlightGranularity.Off
    else -> null
}

/** The app's chapter filter as the server's `hide_played`. Only Unplayed hides played chapters. */
fun serverHidePlayed(filter: ChapterFilter): Boolean = filter == ChapterFilter.Unplayed

/**
 * The server's `hide_played` as the app's chapter filter, given what is set locally.
 *
 * `false` maps to [ChapterFilter.All] *unless* the local filter already shows played chapters —
 * [ChapterFilter.Ready] also projects to `false`, and turning it into All would drop a filter the
 * server has no opinion about.
 */
fun chapterFilterFromServer(hidePlayed: Boolean, local: ChapterFilter): ChapterFilter = when {
    hidePlayed -> ChapterFilter.Unplayed
    serverHidePlayed(local) -> ChapterFilter.All
    else -> local
}

/**
 * A value read out of the preferences blob.
 *
 * Moshi parses every JSON number as a Double and the server tolerates several spellings of a
 * boolean, so the reads below are deliberately permissive in the same directions the server is.
 */
fun Map<String, Any?>.preferenceBool(key: String): Boolean? = when (val raw = this[key]) {
    is Boolean -> raw
    is Number -> raw.toDouble() != 0.0
    is String -> when (raw.trim().lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> null
    }
    else -> null
}

fun Map<String, Any?>.preferenceInt(key: String): Int? = when (val raw = this[key]) {
    is Number -> raw.toInt()
    is String -> raw.trim().toDoubleOrNull()?.toInt()
    else -> null
}

/**
 * A fractional value, for the keys the server declares as `number` rather than `integer`.
 *
 * Separate from [preferenceInt] rather than folded into it: truncating a line height of 1.75 to 1
 * would be silently catastrophic, and the two call sites want genuinely different types.
 */
fun Map<String, Any?>.preferenceNumber(key: String): Float? = when (val raw = this[key]) {
    // A boolean is an int in some languages but never a line height; reject rather than read as 1.
    is Boolean -> null
    is Number -> raw.toFloat().takeIf { !it.isNaN() }
    is String -> raw.trim().toFloatOrNull()?.takeIf { !it.isNaN() }
    else -> null
}

fun Map<String, Any?>.preferenceString(key: String): String? =
    (this[key] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * The local preference values this client keeps in step with the account.
 *
 * A snapshot rather than a store: [reconcileAccountPreferences] takes what is on the phone and what
 * the server holds and returns what the phone should now show, and the caller writes only the
 * fields that actually moved.
 */
data class SyncedPreferences(
    val chapterFilter: ChapterFilter = ChapterFilter.All,
    val autoMarkPlayed: Boolean = DefaultAutoMarkPlayed,
    val sleepTimerDefaultMinutes: Int = DefaultSleepTimerMinutes,
    val readerFontScale: Float = DefaultReaderFontScale,
    val readerLineHeight: Float = DefaultReaderLineHeight,
    val readerTheme: ReaderTheme = DefaultReaderTheme,
    val readerHighlight: HighlightGranularity = DefaultHighlightGranularity,
)

/**
 * What this phone should show, given what it shows now and what the account holds.
 *
 * Every field follows the same rule: keep [local] unless the server holds a value that the local
 * one does not already project to. A key the server has never been told about is absent from the
 * blob, reads as null, and leaves the local value alone — which is what makes the first sync after
 * an upgrade a no-op rather than a reset to the server's defaults.
 */
fun reconcileAccountPreferences(
    server: Map<String, Any?>,
    local: SyncedPreferences,
): SyncedPreferences {
    val hidePlayed = server.preferenceBool(AccountPreferenceKeys.HidePlayed)
    val autoMarkPlayed = server.preferenceBool(AccountPreferenceKeys.AutoMarkPlayed)
    val sleepMinutes = server.preferenceInt(AccountPreferenceKeys.SleepTimerDefaultMinutes)
    val fontSize = server.preferenceInt(AccountPreferenceKeys.ReaderFontSize)
    val lineHeight = server.preferenceNumber(AccountPreferenceKeys.ReaderLineHeight)
    val theme = readerThemeFromServer(server.preferenceString(AccountPreferenceKeys.ReaderTheme))
    val highlight =
        readerHighlightFromServer(server.preferenceString(AccountPreferenceKeys.ReaderHighlight))

    return SyncedPreferences(
        chapterFilter = if (hidePlayed == null) {
            local.chapterFilter
        } else {
            chapterFilterFromServer(hidePlayed, local.chapterFilter)
        },
        // A plain boolean with no lossy projection, so the general rule collapses to "take the
        // server's answer when it has one".
        autoMarkPlayed = autoMarkPlayed ?: local.autoMarkPlayed,
        // No projection to compare against: both ends store the same multiplier over the same
        // range, so "the server has a value" is the whole condition.
        readerLineHeight = lineHeight
            ?.let(::sanitizeReaderLineHeight)
            ?: local.readerLineHeight,
        sleepTimerDefaultMinutes = sleepMinutes
            ?.let(::sanitizeSleepTimerMinutes)
            ?: local.sleepTimerDefaultMinutes,
        // Compare through the projection: a stored 1.0 scale is 19pt, and a server holding 19
        // is agreeing rather than instructing, so the finer local value survives.
        readerFontScale = if (fontSize == null || fontSize == readerFontSizeOf(local.readerFontScale)) {
            local.readerFontScale
        } else {
            readerFontScaleOf(fontSize)
        },
        readerTheme = if (theme == null ||
            serverReaderTheme(local.readerTheme) ==
            server.preferenceString(AccountPreferenceKeys.ReaderTheme)
        ) {
            local.readerTheme
        } else {
            theme
        },
        readerHighlight = if (highlight == null ||
            serverReaderHighlight(local.readerHighlight) ==
            server.preferenceString(AccountPreferenceKeys.ReaderHighlight)
        ) {
            local.readerHighlight
        } else {
            highlight
        },
    )
}

/**
 * The PATCH body for one changed setting.
 *
 * Deliberately per-key. The server merges what it is given and leaves the rest of the row alone, so
 * sending the whole snapshot would let a phone that has been offline overwrite settings changed in
 * the browser with its own stale copies — the failure the batched-progress work in #61 is about,
 * in a different store.
 */
fun chapterFilterPatch(filter: ChapterFilter): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.HidePlayed to serverHidePlayed(filter))

fun autoMarkPlayedPatch(enabled: Boolean): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.AutoMarkPlayed to enabled)

fun sleepTimerDefaultPatch(minutes: Int): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.SleepTimerDefaultMinutes to sanitizeSleepTimerMinutes(minutes))

fun readerFontScalePatch(scale: Float): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.ReaderFontSize to readerFontSizeOf(scale))

fun readerLineHeightPatch(height: Float): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.ReaderLineHeight to sanitizeReaderLineHeight(height))

fun readerThemePatch(theme: ReaderTheme): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.ReaderTheme to serverReaderTheme(theme))

fun readerHighlightPatch(granularity: HighlightGranularity): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.ReaderHighlight to serverReaderHighlight(granularity))

/** Stop at the end of the queue. The server's default: waking to an unrelated book is a surprise. */
const val QueueWhenEmptyStop: String = "stop"

/** Keep going with the oldest unplayed chapter in the library. */
const val QueueWhenEmptyContinue: String = "continue"

/**
 * Snap to a value the server declares, so an unknown spelling cannot be written back.
 *
 * The server would reject or clamp it anyway; refusing here means a queue payload from a newer
 * server that grew a third option shows as [QueueWhenEmptyStop] rather than as a blank control —
 * and, more importantly, that opening the picker and closing it cannot overwrite that third option
 * with a guess.
 */
fun sanitizeQueueWhenEmpty(value: String?): String =
    if (value?.trim()?.lowercase() == QueueWhenEmptyContinue) {
        QueueWhenEmptyContinue
    } else {
        QueueWhenEmptyStop
    }

fun queueWhenEmptyPatch(value: String): Map<String, Any?> =
    mapOf(AccountPreferenceKeys.QueueWhenEmpty to sanitizeQueueWhenEmpty(value))
