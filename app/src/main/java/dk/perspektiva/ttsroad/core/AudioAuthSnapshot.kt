package dk.perspektiva.ttsroad.core

import androidx.media3.datasource.DataSpec
import dk.perspektiva.ttsroad.data.SessionState
import kotlinx.coroutines.CompletableDeferred

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class AudioAuthSnapshot(
    val serverUrl: String = "",
    val authorizationHeader: String? = null,
    /**
     * The account the credential signs in as. A token rotation keeps it; a different user signing
     * in to the same backend changes it, and audio, recovery and progress for the previous
     * account's queue must then never ride on the new account's bearer token.
     */
    val account: String? = null,
) {
    fun resolve(dataSpec: DataSpec): DataSpec {
        val headers = dataSpec.httpRequestHeaders
            .filterKeys { !it.equals("Authorization", ignoreCase = true) }
            .toMutableMap()
        val authenticated = authorizationHeader != null &&
            ServerUrls.isSameOrigin(dataSpec.uri.toString(), serverUrl)
        if (authenticated) {
            headers["Authorization"] = requireNotNull(authorizationHeader)
        }
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(headers)
            .setCustomData(if (authenticated) this else null)
            .build()
    }
}

/** The audio credential of [this] session, including which account it belongs to. */
internal fun SessionState.audioAuthSnapshot(): AudioAuthSnapshot =
    AudioAuthSnapshot(serverUrl, authorizationHeader, username)

/**
 * The media service's current [AudioAuthSnapshot], and whether a real session has set it yet.
 *
 * On a fresh-process start — Android Auto connecting, a media button — a controller can hand the
 * service a server-stamped item before the persisted session has been read. Checked against the
 * blank default, every such item looks like another server's and is dropped, so ownership checks
 * wait with [awaitCurrent] until the first [publish]. Readers that cannot suspend (the data-source
 * resolver) use [current]; nothing reaches them before an item has passed that check.
 */
internal class SessionAudioAuth {
    @Volatile
    var current: AudioAuthSnapshot = AudioAuthSnapshot()
        private set

    private val published = CompletableDeferred<Unit>()

    fun publish(snapshot: AudioAuthSnapshot) {
        current = snapshot
        published.complete(Unit)
    }

    suspend fun awaitCurrent(): AudioAuthSnapshot {
        published.await()
        return current
    }
}
