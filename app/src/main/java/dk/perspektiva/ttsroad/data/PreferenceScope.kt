package dk.perspektiva.ttsroad.data

/**
 * Where a group of settings is kept, in the words shown under its controls.
 *
 * Pure functions rather than string literals inline in the sheets, for two reasons. The app now
 * keeps settings in three different places — this phone, the account, and both — and the line that
 * says which is the only way a user can tell them apart. And a literal in a composable can only be
 * checked by running the app, which is how the reader's footer came to claim the opposite of what
 * the code did for two releases (#103).
 */
object PreferenceScope {
    /**
     * Reader appearance: text size, spacing, page colour, highlight.
     *
     * These follow the account wherever the server can hold them, and fall back to this phone where
     * it cannot. Both halves are true and which one applies is not something the user can guess, so
     * the line says outright which it is.
     */
    fun reader(syncsWithAccount: Boolean): String = if (syncsWithAccount) {
        "Follows your account, so the browser reads the same way. Kept on this phone too, " +
            "so it still works offline."
    } else {
        "Kept on this phone. This server cannot hold reader settings on your account."
    }

    /**
     * The four device player keys — speed, skip interval, skip silence, volume boost.
     *
     * Deliberately never synced: see `AccountPreferences.kt`. The line exists because "kept across
     * restarts" was all the old copy said, which left the reader of it to assume either answer.
     */
    const val DevicePlayer: String = "Kept on this phone, not on your account."

    /** A setting that follows the account whenever the server can hold it. */
    fun account(syncsWithAccount: Boolean): String = if (syncsWithAccount) {
        "Follows your account, so the browser agrees."
    } else {
        "Kept on this phone. This server cannot hold it on your account."
    }
}
