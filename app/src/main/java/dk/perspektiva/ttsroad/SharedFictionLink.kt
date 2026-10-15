package dk.perspektiva.ttsroad

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text handed to the app through Android's Share menu (#347).
 *
 * Browsers share a page as its URL, often with the page title in front ("Mother of Learning
 * https://www.royalroad.com/fiction/21220/…"), so the link is found inside the text rather than
 * expected to be all of it. Which sites the server can actually read is the server's call, as it is
 * for a pasted URL — this only decides whether there is a link to offer at all.
 */
sealed interface SharedFictionLink {
    data class Link(val url: String) : SharedFictionLink

    /** Something was shared, but it holds no web address. */
    data object NotALink : SharedFictionLink
}

private val WebLink = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** The first web link in [text], or [SharedFictionLink.NotALink]; null when nothing was shared. */
internal fun parseSharedFictionLink(text: String?): SharedFictionLink? {
    if (text.isNullOrBlank()) return null
    val match = WebLink.find(text) ?: return SharedFictionLink.NotALink
    // Sentence punctuation that rode along with a link pasted into prose is not part of it.
    return SharedFictionLink.Link(match.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?'))
}

/**
 * Read a share intent once. The text is removed from the intent afterwards, so recreating the
 * activity (rotation, theme change) does not open the add sheet a second time.
 */
internal fun consumeSharedFictionLink(intent: Intent?): SharedFictionLink? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    intent.removeExtra(Intent.EXTRA_TEXT)
    intent.action = null
    return parseSharedFictionLink(text)
}

/** What to do with a shared link, given who is signed in to what. */
internal enum class SharedLinkAction {
    /** Not signed in: keep it until sign-in, and say so. */
    WaitForSignIn,

    /** Capabilities not known yet: keep it and decide once discovery answers. */
    WaitForServer,

    /** Open the add-fiction sheet with the link filled in. */
    OpenAddSheet,

    /** This account or server cannot add fictions; explain and drop the link. */
    CannotAdd,

    /** The share held no link; explain and drop it. */
    NotALink,
}

internal fun sharedLinkAction(
    link: SharedFictionLink,
    signedIn: Boolean,
    capabilitiesResolved: Boolean,
    isAdmin: Boolean,
    canAddByUrl: Boolean,
): SharedLinkAction = when {
    link is SharedFictionLink.NotALink -> SharedLinkAction.NotALink
    !signedIn -> SharedLinkAction.WaitForSignIn
    !capabilitiesResolved -> SharedLinkAction.WaitForServer
    isAdmin && canAddByUrl -> SharedLinkAction.OpenAddSheet
    else -> SharedLinkAction.CannotAdd
}

internal const val SharedNotALinkMessage =
    "That share didn't include a web link. Share the fiction's page from your browser instead."
internal const val SharedSignInFirstMessage = "Sign in first - the shared fiction will open after that."
internal const val SharedCannotAddMessage =
    "Only an admin account on a server that can add fictions can add one from a link."

/**
 * The shared link waiting to be put into the add-fiction sheet.
 *
 * Process-wide because it crosses an activity that may be recreated, a sign-in that swaps the
 * whole UI tree, and a navigation to Browse before the sheet that takes it exists.
 */
internal object SharedFictionInbox {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(url: String) {
        _pending.value = url
    }

    /** Take the waiting link, if any, so it opens exactly one sheet. */
    fun take(): String? {
        val url = _pending.value ?: return null
        return url.takeIf { _pending.compareAndSet(it, null) }
    }

    fun clear() {
        _pending.value = null
    }
}
