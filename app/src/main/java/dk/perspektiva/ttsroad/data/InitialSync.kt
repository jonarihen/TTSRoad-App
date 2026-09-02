package dk.perspektiva.ttsroad.data

/**
 * How much of a fiction's backlog to convert when it is first tracked.
 *
 * This exists because leaving it out was a real cost rather than a missing nicety. `POST
 * /api/mobile/fictions` takes `sync_limit` and `sync_direction`; the app sent neither, and the
 * backend reads their absence as *every chapter* — `add_fiction` branches on `if body.sync_limit:`
 * and otherwise polls the whole thing. So adding a long serial from the phone queued its entire
 * backlog for narration, hours of TTS nobody asked for, while the same body posted by the web form
 * defaulted to the newest 25.
 *
 * Kept out of the composable so the arithmetic — what a blank field means, what happens at the
 * server's ceiling — can be tested without a screen.
 */

/** Which end of the chapter list [InitialSync.limit] counts from. */
enum class SyncDirection(
    /** What the API expects. Never derived from [label], which is display text and will move. */
    val wire: String,
    val label: String,
) {
    /** The newest chapters — catching up on a serial you already read to the front of. */
    Last("last", "NEWEST"),

    /** The oldest chapters — starting a book from the beginning. */
    First("first", "OLDEST"),
    ;

    companion object {
        val Default: SyncDirection = Last
    }
}

/**
 * The chosen window, where a null [limit] means the whole backlog.
 *
 * Null rather than a sentinel like 0 because that is exactly what the wire means: the server
 * treats a missing `sync_limit` as "all", so "all" is the absence of a number here too, and the
 * request body needs no translation step that could disagree with the server about it.
 */
data class InitialSync(val limit: Int?, val direction: SyncDirection = SyncDirection.Default) {

    /** True when this converts the whole book — the setting that used to be the only one available. */
    val isEverything: Boolean get() = limit == null

    /** The line under the control, saying what will actually happen. */
    val summary: String
        get() = when (limit) {
            null -> "Every chapter, however long the book is"
            1 -> "The ${direction.label.lowercase()} chapter only"
            else -> "The $limit ${direction.label.lowercase()} chapters"
        }

    companion object {
        /**
         * The newest 25.
         *
         * Deliberately the web form's default rather than the API's. The API defaults to
         * everything because a missing field cannot mean anything else; the web form has always
         * put `Last 25` in front of the person adding the book, and matching the *form* is what
         * makes the two clients agree in practice.
         */
        val Default: InitialSync = InitialSync(limit = 25, direction = SyncDirection.Last)

        /** What the web form's number input accepts, and therefore what this one does. */
        const val MinLimit: Int = 1
        const val MaxLimit: Int = 9999
    }
}

/**
 * Read what was typed into the chapter-count field.
 *
 * A blank or unparseable field keeps the previous count rather than silently becoming "all": those
 * two are one backspace apart, and one of them starts hours of narration. Out-of-range values are
 * clamped to the same bounds the web input declares.
 */
fun parseSyncLimit(typed: String, fallback: Int): Int {
    val digits = typed.trim().toIntOrNull() ?: return fallback
    return digits.coerceIn(InitialSync.MinLimit, InitialSync.MaxLimit)
}

/**
 * Everything `POST /api/mobile/fictions` accepts besides the URL.
 *
 * All of it optional, and null means "let the server decide" for every field — which keeps this
 * additive against a server too old to read one of them, and keeps the app from inventing a
 * default that disagrees with `settings.DEFAULT_VOICE`.
 */
data class AddFictionOptions(
    val sync: InitialSync = InitialSync.Default,
    val voice: String? = null,
    val rate: String? = null,
    val enabled: Boolean = true,
) {
    /** True when the rate as typed is not one edge-tts will take. Blank is fine — it means "leave it". */
    val rateProblem: String? get() = voiceRateProblem(rate)

    /** The wire body for [sync], voice and rate. Blank text fields are sent as absent, not as "". */
    fun toRequest(url: String): AddFictionRequest = AddFictionRequest(
        fictionUrl = url,
        voice = voice?.takeIf { it.isNotBlank() },
        // Normalised rather than sent as typed: the server stores whatever string it is handed
        // without checking it, so "10" reaching the database unsigned does not fail on save — it
        // fails hours later as a chapter that will not narrate.
        rate = normaliseVoiceRate(rate),
        enabled = enabled,
        syncLimit = sync.limit,
        // Meaningless without a limit, and sending it anyway would suggest to a reader of the
        // request log that a direction was in play when the whole backlog was being fetched.
        syncDirection = sync.limit?.let { sync.direction.wire },
    )
}
