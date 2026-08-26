package dk.perspektiva.ttsroad.data

/**
 * Turning `GET /api/mobile/storage` into the Settings card that sits beside the app's own cache
 * figures (#124).
 *
 * Two rules run through this file. The first is that **no byte count is ever formatted here**: every
 * `…_bytes` arrives with a `…_label` the server already rendered, and re-deriving one would let the
 * phone and the browser describe the same file two different ways. The byte counts are used for the
 * one thing a label cannot do — working out how full the volume is — and for ordering, and for
 * nothing else.
 *
 * The second is that this is read-only and stays that way. The orphan scan, the orphan delete, the
 * voice-sample delete, the excluded-audio delete and the per-fiction audio delete have no mobile
 * mirror on the server and none is planned: they are irreversible, and a confirmation dialog on a
 * phone held one-handed is not where an irreversible delete of somebody's audio library belongs.
 * Seeing the numbers is most of the value; acting on them is a job for a laptop.
 */

/** The storage payload, arranged the way the card draws it. */
data class ServerStorageOverview(
    /** Fictions actually holding audio, largest first — the server's own ordering, kept. */
    val rows: List<FictionStorageRow>,
    /**
     * How many fictions the server listed with no audio on disk at all.
     *
     * Reported as a count rather than as rows of "0 B". They are real fictions — freshly added, or
     * still converting — and a disk-usage table is not where they are worth a line each; but
     * dropping them silently would make someone scroll a list looking for a book that is on the
     * server and not in the table.
     */
    val emptyFictions: Int,
    /** 0..1 of the volume in use, for the hairline. Zero when the server reported no volume size. */
    val usedFraction: Float,
    /** Why exports are or are not on offer, or null when ffmpeg is present. */
    val encoderNote: String?,
)

/**
 * The payload as an overview, or null when there is nothing to show yet.
 *
 * Null covers both "not loaded" and "this server has no such endpoint"; the card distinguishes them
 * because only it knows which of the two it is looking at.
 */
fun serverStorageOverview(response: ServerStorageResponse?): ServerStorageOverview? {
    if (response == null) return null
    val (used, empty) = response.perFiction.partition { it.audioBytes > 0L }
    return ServerStorageOverview(
        rows = used,
        emptyFictions = empty.size,
        usedFraction = volumeUsedFraction(response),
        encoderNote = serverStorageEncoderNote(response),
    )
}

/**
 * The share of the volume in use, 0..1.
 *
 * The one figure on this card the server does not send a label for, because it is not a size. It is
 * derived from the two byte counts rather than from the labels for the obvious reason: "1.4 TB" and
 * "312 GB" cannot be divided.
 *
 * A server reporting a zero-byte volume is reporting something this client cannot draw, so the bar
 * reads empty rather than full — an accidental "disk full" is the worse of the two lies.
 */
fun volumeUsedFraction(response: ServerStorageResponse): Float {
    val total = response.volumeTotalBytes
    if (total <= 0L) return 0f
    val used = (total - response.volumeFreeBytes).coerceIn(0L, total)
    return (used.toDouble() / total.toDouble()).toFloat()
}

/**
 * What to say about a server that cannot currently encode, or null when it can.
 *
 * Reported per request rather than as a capability, and the distinction is the same one
 * [audiobookExportEncoderNote] makes: `audiobook_export` says the route exists, `ffmpeg_available`
 * says the machine behind it has the tool. Repeated on this card because the storage page is where
 * an operator meets exports — without it, a missing ffmpeg first surfaces as a failure after
 * someone has already chosen a fiction and asked for one.
 */
fun serverStorageEncoderNote(response: ServerStorageResponse?): String? {
    if (response == null || response.ffmpegAvailable) return null
    return "This server has no ffmpeg, so it cannot encode new audiobook exports. Anything it " +
        "already made still counts against the total below."
}

/**
 * Whether this account, on this server, may look at the disk figures.
 *
 * The same two-part gate [canReadServerLogs] uses, and separately advertised: a deployment may
 * reasonably expose one of these read-outs and not the other, and nothing about seeing the log
 * implies seeing the volume's free space.
 */
fun canReadServerStorage(capabilities: ServerCapabilities, isAdmin: Boolean): Boolean =
    capabilities.storage && isAdmin
