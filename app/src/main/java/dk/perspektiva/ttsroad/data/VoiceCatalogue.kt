package dk.perspektiva.ttsroad.data

import java.util.Locale

/**
 * Turning `GET /api/mobile/voices` into something a thumb can pick from (#156).
 *
 * The server publishes the edge-tts catalogue: several hundred names across a hundred-odd locales,
 * spelled `en-US-BrianNeural`. On a browser that is a long select; on a phone a flat list of it is
 * not a control at all, so everything here exists to make it one — grouped by locale, the locale
 * that matters first, each name shortened to the part a human reads.
 *
 * Two things in this file are decisions rather than mechanics, and both are about honesty.
 *
 * The first is [canPickVoice]. Listing voices is open to any signed-in account and *applying* one is
 * admin-gated by the `PATCH`, so the capability alone is the wrong gate: it would draw a picker for
 * a regular account whose save is a 403. Both halves, or nothing.
 *
 * The second is that **changing the voice does not re-narrate anything**. The chapters that exist
 * keep the audio they were made with; the new voice applies to whatever the server converts next.
 * Making it retroactive means re-converting every chapter, which is hours of TTS and already has its
 * own control under `fiction_maintenance`. [voiceChangeConsequence] is that sentence, and it is
 * shown at the point of the change rather than left for someone to discover.
 */

/** Both halves of the gate: the server has the catalogue, and this account may store a choice. */
fun canPickVoice(capabilities: ServerCapabilities, isAdmin: Boolean): Boolean =
    capabilities.voiceCatalogue && isAdmin

/**
 * One narrator, as the picker draws it.
 *
 * [name] is what gets stored and is never derived from anything else. [shortName] is only ever a
 * label — `en-US-BrianNeural` shown as "Brian" — and both are on screen, because two locales'
 * "Brian" are different narrators and the full name is the only thing that distinguishes them.
 */
data class VoiceChoice(
    val name: String,
    val shortName: String,
    val locale: String,
    val gender: String?,
) {
    /** The line under the name: "Female · en-GB-SoniaNeural", or just the name when no gender came. */
    val detail: String
        get() = gender?.takeIf { it.isNotBlank() }?.let { "$it  ·  $name" } ?: name
}

/** The voices of one locale, under the name that locale has in the reader's own language. */
data class VoiceGroup(
    /** The server's own tag — `en-US` — which is what the grouping is keyed on. */
    val locale: String,
    /** "English (United States)", or the raw tag when the platform cannot name it. */
    val label: String,
    val voices: List<VoiceChoice>,
)

/** Voices whose row carried no locale at all. Kept rather than dropped: the name still works. */
const val UnknownVoiceLocale: String = "other"

/**
 * The catalogue as groups, ordered so the useful one is first.
 *
 * The ordering is the whole point and it is not alphabetical:
 *
 * 1. The locale of [current] — the voice this fiction already uses. Someone opening the picker is
 *    usually moving to a neighbouring voice, and starting anywhere else means scrolling to where
 *    they already were.
 * 2. The locales matching the phone's own language. A Danish phone reading English serials still
 *    wants `da-DK` near the top, and the alternative is a hundred locales of alphabet first.
 * 3. Everything else by label, so the tail is at least predictable.
 *
 * [query] filters on name, locale tag and locale label, so both "sonia" and "british" find
 * `en-GB-SoniaNeural`. An empty result is an empty list — the caller says what that means.
 *
 * A row with a blank [MobileVoice.name] is dropped: it cannot be stored, so offering it would be
 * offering a choice whose save fails.
 */
fun voiceGroups(
    voices: List<MobileVoice>?,
    current: String? = null,
    query: String = "",
    locale: Locale = Locale.getDefault(),
): List<VoiceGroup> {
    if (voices.isNullOrEmpty()) return emptyList()
    val currentLocale = voices.firstOrNull { it.name == current?.trim() }?.localeTag
    val deviceLanguage = locale.language.lowercase(Locale.ROOT)
    val wanted = query.trim().lowercase(Locale.ROOT)

    val groups = voices
        .filter { it.name.isNotBlank() }
        .groupBy { it.localeTag }
        .map { (tag, rows) ->
            VoiceGroup(
                locale = tag,
                label = voiceLocaleLabel(tag, locale),
                // By the name a human reads, not by the wire name: `en-US-AvaNeural` and
                // `en-US-AvaMultilingualNeural` belong next to each other.
                voices = rows.map { it.asChoice(tag) }.sortedBy { it.shortName.lowercase(Locale.ROOT) },
            )
        }
        .filter { it.voices.isNotEmpty() }

    val matched = if (wanted.isEmpty()) groups else groups.mapNotNull { group ->
        val hits = group.voices.filter { choice ->
            choice.name.lowercase(Locale.ROOT).contains(wanted) ||
                group.locale.lowercase(Locale.ROOT).contains(wanted) ||
                group.label.lowercase(Locale.ROOT).contains(wanted) ||
                choice.gender?.lowercase(Locale.ROOT)?.contains(wanted) == true
        }
        group.copy(voices = hits).takeIf { hits.isNotEmpty() }
    }

    return matched.sortedWith(
        compareBy(
            { if (it.locale == currentLocale) 0 else 1 },
            { if (it.locale.substringBefore('-').lowercase(Locale.ROOT) == deviceLanguage) 0 else 1 },
            { it.label.lowercase(Locale.ROOT) },
        ),
    )
}

/**
 * Which group should already be open, or null when none should be.
 *
 * The one holding [current], so the voice in force is on screen without scrolling and without
 * hunting — "which is it now" and "what else is near it" are the same question. With no current
 * voice there is nothing to be near, and the first group opens instead.
 */
fun initiallyExpandedVoiceLocale(groups: List<VoiceGroup>, current: String?): String? {
    val name = current?.trim().orEmpty()
    if (name.isNotEmpty()) {
        groups.firstOrNull { group -> group.voices.any { it.name == name } }?.let { return it.locale }
    }
    return groups.firstOrNull()?.locale
}

/** `en-US` as "English (United States)", or the tag itself when the platform has no name for it. */
fun voiceLocaleLabel(tag: String, locale: Locale = Locale.getDefault()): String {
    if (tag == UnknownVoiceLocale) return "Other"
    val named = runCatching { Locale.forLanguageTag(tag).getDisplayName(locale) }.getOrNull()
    return named?.takeIf { it.isNotBlank() && it != tag } ?: tag
}

/** The locale this voice is filed under — the server's tag, or [UnknownVoiceLocale]. */
private val MobileVoice.localeTag: String
    get() = locale?.trim()?.takeIf { it.isNotEmpty() } ?: UnknownVoiceLocale

private fun MobileVoice.asChoice(tag: String) = VoiceChoice(
    name = name.trim(),
    shortName = shortVoiceName(name.trim(), tag),
    locale = tag,
    gender = gender?.trim()?.takeIf { it.isNotEmpty() },
)

/**
 * `en-US-BrianNeural` as "Brian".
 *
 * The locale prefix is stripped by the voice's own tag rather than by a pattern, which is what makes
 * `zh-CN-liaoning-XiaobeiNeural` come out as "Xiaobei": its locale really is `zh-CN-liaoning`, and a
 * two-letter-plus-region pattern would leave the region in the label. Anything that shortens to
 * nothing keeps its full name — a label is not worth losing the identity over.
 */
fun shortVoiceName(name: String, locale: String?): String {
    val prefix = locale?.takeIf { it.isNotBlank() && it != UnknownVoiceLocale }?.let { "$it-" }
    val stripped = when {
        prefix != null && name.startsWith(prefix, ignoreCase = true) -> name.substring(prefix.length)
        else -> name.substringAfterLast('-')
    }
    return stripped.removeSuffix("Neural").trim().ifEmpty { name }
}

/**
 * The rate as the server should store it, or null when this is not a rate.
 *
 * `[+-]NNN%` is the form edge-tts takes and the same expression the web console validates with
 * (`VP_RATE_RE` in `app.js`). A bare number is accepted and signed — "10" is unambiguously "+10%",
 * and typing the plus on a phone keyboard is a keystroke nobody should have to find.
 *
 * This matters more than it looks: the `PATCH` stores whatever string it is handed without checking
 * it, so a typo here does not fail on save — it fails hours later, on the next conversion, as a
 * chapter that will not narrate.
 */
fun normaliseVoiceRate(input: String?): String? {
    val text = input?.trim().orEmpty()
    if (text.isEmpty()) return null
    val body = text.removeSuffix("%").trim()
    val sign = when {
        body.startsWith("+") -> 1
        body.startsWith("-") -> -1
        else -> 1
    }
    val digits = body.removePrefix("+").removePrefix("-")
    if (digits.isEmpty() || digits.length > 3 || !digits.all { it.isDigit() }) return null
    val magnitude = digits.toInt()
    return if (sign < 0 && magnitude > 0) "-$magnitude%" else "+$magnitude%"
}

/** Why that rate cannot be sent, or null when it can. Empty is not a problem — it means "leave it". */
fun voiceRateProblem(input: String?): String? = when {
    input.isNullOrBlank() -> null
    normaliseVoiceRate(input) != null -> null
    else -> "A rate reads like +0%, +25% or -10%."
}

/**
 * What of a narration choice is worth sending for [fiction], or null when nothing is.
 *
 * Diffed separately from the metadata patch, and merged into the same body by [withNarration],
 * because the two are different promises. Every metadata field a `PATCH` sets is recorded as
 * hand-edited and stops being refreshed from the source; `voice` and `rate` are not, because no poll
 * has ever written them. What they change is what the *next* conversion sounds like.
 *
 * An empty voice is never sent. Unlike an author or a synopsis, "" is not how a voice is cleared —
 * the server would store the empty string and the next conversion would fail on it.
 */
fun fictionNarrationPatch(
    fiction: FictionSummary,
    voice: String?,
    rate: String?,
): FictionUpdateRequest? {
    val wantedVoice = voice?.trim()
        ?.takeIf { it.isNotEmpty() && it != fiction.voice?.trim().orEmpty() }
    val wantedRate = normaliseVoiceRate(rate)
        ?.takeIf { it != normaliseVoiceRate(fiction.rate) }
    if (wantedVoice == null && wantedRate == null) return null
    return FictionUpdateRequest(voice = wantedVoice, rate = wantedRate)
}

/**
 * The metadata diff and the narration diff as one request body.
 *
 * One `PATCH` rather than two: the server echoes the whole fiction back and the editor adopts it, so
 * two requests would mean two echoes and a window where the screen shows half a save.
 */
fun withNarration(
    metadata: FictionUpdateRequest?,
    narration: FictionUpdateRequest?,
): FictionUpdateRequest? = when {
    narration == null -> metadata
    else -> (metadata ?: FictionUpdateRequest()).copy(
        voice = narration.voice,
        rate = narration.rate,
    )
}

/**
 * What this change will and will not do, in words, at the point of making it.
 *
 * Null when nothing about the narration is changing. Otherwise it names the two halves that a
 * picker silently implies are one: the chapters that exist keep the audio they were made with, and
 * the choice applies to whatever is converted next. Re-doing the rest is "Re-narrate every chapter"
 * under Maintain — hours of TTS, and deliberately a separate decision.
 */
fun voiceChangeConsequence(fiction: FictionSummary, voice: String?, rate: String?): String? {
    val patch = fictionNarrationPatch(fiction, voice, rate) ?: return null
    val what = when {
        patch.voice != null && patch.rate != null -> "This voice and rate are"
        patch.voice != null -> "This voice is"
        else -> "This rate is"
    }
    val existing = when (fiction.doneChapters) {
        0 -> "Nothing has been converted yet, so nothing is affected."
        1 -> "The one chapter already converted keeps the audio it was made with."
        else ->
            "The ${fiction.doneChapters} chapters already converted keep the audio they were made " +
                "with — nothing is re-narrated."
    }
    return "$existing $what used for whatever the server converts next. To change what already " +
        "exists, use Re-narrate every chapter under Maintain: that is every chapter again, from " +
        "scratch."
}
