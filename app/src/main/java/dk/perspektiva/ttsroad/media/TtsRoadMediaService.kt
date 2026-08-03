package dk.perspektiva.ttsroad.media

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.MainActivity
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LibraryResponse
import dk.perspektiva.ttsroad.data.DefaultSkipIntervalMs
import dk.perspektiva.ttsroad.data.PlaybackPreferences
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.VolumeBoost
import dk.perspektiva.ttsroad.data.parseSessionEnd
import dk.perspektiva.ttsroad.player.PlaybackFailure
import dk.perspektiva.ttsroad.player.ShakeDetector
import dk.perspektiva.ttsroad.player.SleepTimerAction
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerMode
import dk.perspektiva.ttsroad.player.classifyPlaybackError
import dk.perspektiva.ttsroad.player.retryDelayMs
import dk.perspektiva.ttsroad.player.skipTargetMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Media3 marks much of its session and data-source surface @UnstableApi. The whole class works
// against it, so opt in once here rather than annotating each member and still missing the
// constructor and property references lint reports separately.
@OptIn(UnstableApi::class)
class TtsRoadMediaService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tokenStore: TokenStore
    private lateinit var repository: TtsRoadRepository
    private lateinit var preferences: PlaybackPreferences
    private lateinit var player: ExoPlayer
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var session: MediaLibrarySession
    private lateinit var sleepTimer: SleepTimerController
    private var shakeDetector: ShakeDetector? = null
    private var lastLibrary: LibraryResponse? = null

    // Automatic recovery from a dropped stream. Reset once playback is healthy again, so a second
    // outage later in the night gets a fresh set of attempts rather than giving up immediately.
    private var retryJob: Job? = null
    private var retryAttempt = 0

    @Volatile
    private var authHeader: String? = null

    // onCustomCommand is not suspending, so the -30s/+30s buttons on the notification, lockscreen
    // and car transport read the preference from here rather than the DataStore.
    @Volatile
    private var skipIntervalMs: Long = DefaultSkipIntervalMs

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        tokenStore = ServiceLocator.tokenStore(this)
        repository = ServiceLocator.repository(this)
        sleepTimer = ServiceLocator.sleepTimer()
        preferences = ServiceLocator.playbackPreferences(this)
        serviceScope.launch {
            tokenStore.session.collectLatest { authHeader = it.authorizationHeader }
        }
        player = createPlayer()
        startAudioTuning()
        // Speed lives in the service, not the UI: the player is recreated on a swipe-away, a
        // process kill, or a reboot, and the car can start playback with no UI running at all.
        // Applying it here is what makes it survive all three.
        serviceScope.launch {
            preferences.prefs
                .map { it.speed }
                .distinctUntilChanged()
                .collect { player.setPlaybackSpeed(it) }
        }
        serviceScope.launch {
            preferences.prefs
                .map { it.skipIntervalMs }
                .distinctUntilChanged()
                .collect { skipIntervalMs = it }
        }
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        serviceScope.launch { saveCurrentProgress(forcePlayed = true) }
                    }
                    // Playing again means whatever broke has healed, so the next failure starts
                    // its backoff from the top rather than inheriting an exhausted counter.
                    if (playbackState == Player.STATE_READY) {
                        retryAttempt = 0
                        retryJob?.cancel()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) {
                        serviceScope.launch { saveCurrentProgress(forcePlayed = false) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlayerError(error)
                }
            },
        )
        startProgressTicker()
        startSleepTimer()
        session = MediaLibrarySession.Builder(this, player, BrowserCallback(this))
            .setSessionActivity(playerActivityIntent())
            .setMediaButtonPreferences(TtsRoadSessionCommands.mediaButtonPreferences())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        shakeDetector?.stop()
        sleepTimer.cancel()
        // Release the effect before the player: it is attached to the player's audio session, and
        // leaking an AudioEffect holds a global slot other apps then cannot use.
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        session.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // Content intent for the media notification and the car's "open app" affordance. Without it the
    // notification body is not clickable at all.
    private fun playerActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        MainActivity.playerIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createPlayer(): ExoPlayer {
        // The Authorization header is resolved per-request from the latest session token, so the
        // player keeps working across logout/login without being recreated.
        val resolvingFactory = ResolvingDataSource.Factory(
            DefaultHttpDataSource.Factory(),
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val header = authHeader ?: return dataSpec
                    return dataSpec.withAdditionalHeaders(mapOf("Authorization" to header))
                }
            },
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(resolvingFactory)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /**
     * Apply the two TTS-specific audio settings, and keep applying them as they change.
     *
     * The player is recreated on a swipe-away, a process kill and a reboot, so this has to live in
     * the service rather than the UI — the car can start playback with no UI running at all.
     *
     * The audio session id is generated here and set on the player rather than read back from it.
     * That way the id is fixed for the life of the service, so [LoudnessEnhancer] is attached once
     * and never has to be torn down and rebuilt when the player switches output.
     *
     * Opted in because setSkipSilenceEnabled and setAudioSessionId are still marked unstable in
     * media3 1.10.0.
     */
    @OptIn(UnstableApi::class)
    private fun startAudioTuning() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sessionId = audioManager?.generateAudioSessionId()
            ?.takeIf { it != AudioManager.ERROR }
        if (sessionId != null) {
            player.audioSessionId = sessionId
            // Effect creation is genuinely device-dependent — some devices have no spare effect
            // slots, and some ROMs refuse outright. A missing enhancer must not stop playback.
            loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        }

        serviceScope.launch {
            preferences.prefs
                .map { it.skipSilence }
                .distinctUntilChanged()
                .collect { player.skipSilenceEnabled = it }
        }

        serviceScope.launch {
            preferences.prefs
                .map { it.volumeBoost }
                .distinctUntilChanged()
                .collect(::applyVolumeBoost)
        }
    }

    private fun applyVolumeBoost(boost: VolumeBoost) {
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            enhancer.setTargetGain(boost.gainMillibels)
            enhancer.enabled = boost != VolumeBoost.Off
        }
    }

    /**
     * Decide what a playback failure means and act on it.
     *
     * This has to live in the service: a [PlaybackException] relayed to a controller across the
     * binder keeps its `errorCode` but loses its cause, so the HTTP status — the thing that tells a
     * revoked token apart from a server hiccup — is only readable here.
     *
     * Opted in because HttpDataSource is still marked unstable in media3 1.10.0.
     */
    @OptIn(UnstableApi::class)
    private fun handlePlayerError(error: PlaybackException) {
        val httpFailure = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()

        when (classifyPlaybackError(error.errorCode, httpFailure?.responseCode)) {
            PlaybackFailure.Unauthorized -> {
                // Same conclusion as a 401 on an API call: the stored token cannot be used again,
                // so drop it and let the session observer fall back to the login screen. Retrying
                // would just burn battery against a server that will keep saying no.
                retryJob?.cancel()
                retryAttempt = 0
                // Bearer-authenticated /audio/... requests carry the same structured 401 body as
                // the JSON API, so go through the repository rather than clearing the token here:
                // that is what carries the reason to the login screen instead of dropping the user
                // there with no explanation.
                val body = httpFailure?.responseBody?.toString(Charsets.UTF_8)
                serviceScope.launch { repository.endSession(parseSessionEnd(body)) }
            }

            is PlaybackFailure.Transient -> scheduleRetry()

            is PlaybackFailure.Permanent -> {
                retryJob?.cancel()
                retryAttempt = 0
            }
        }
    }

    /**
     * Re-prepare after a backoff so a tunnel or a Wi-Fi handover heals without the user touching
     * anything. Deliberately does not call play(): prepare() resumes on its own when playWhenReady
     * was set, so a stream that died while paused stays paused.
     */
    private fun scheduleRetry() {
        val delayMs = retryDelayMs(retryAttempt + 1) ?: return
        retryAttempt++
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            delay(delayMs)
            player.prepare()
        }
    }

    private fun startProgressTicker() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveCurrentProgress(forcePlayed = false)
            }
        }
    }

    /**
     * Drive the sleep timer from the service, so it keeps counting down with the app backgrounded
     * and the screen off. The controller owns the decisions; this only applies them to the player.
     *
     * Opted in because pauseAtEndOfMediaItems is still marked unstable in media3 1.10.0.
     */
    @OptIn(UnstableApi::class)
    private fun startSleepTimer() {
        // The next tick picks the extension up and lifts the volume back out of the fade.
        shakeDetector = ShakeDetector(this) { sleepTimer.extend(SleepTimerController.ExtendMs) }

        // Tick only while a timer is actually armed. The fade needs a half-second tick to be
        // smooth, but this app's whole job is playing all night, and waking twice a second for a
        // timer nobody set would cost far more than the fade is worth.
        serviceScope.launch {
            sleepTimer.state
                .map { it.isArmed }
                .distinctUntilChanged()
                .collectLatest { armed ->
                    if (!armed) {
                        // One tick on disarm, so a cancel (or an expiry) mid-fade lifts the volume
                        // back to full instead of leaving the player permanently ducked.
                        tickSleepTimer()
                        return@collectLatest
                    }
                    while (isActive) {
                        tickSleepTimer()
                        delay(SLEEP_TIMER_TICK_MS)
                    }
                }
        }

        // "End of chapter" leans on the player to stop at the boundary rather than auto-advancing.
        serviceScope.launch {
            sleepTimer.state
                .map { it.mode == SleepTimerMode.EndOfChapter }
                .distinctUntilChanged()
                .collect { player.pauseAtEndOfMediaItems = it }
        }

        // Listen for the shake only while the audio is fading — the rest of the night it is off.
        serviceScope.launch {
            sleepTimer.state
                .map { it.isFading }
                .distinctUntilChanged()
                .collect { fading -> if (fading) shakeDetector?.start() else shakeDetector?.stop() }
        }
    }

    private fun tickSleepTimer() {
        applySleepTimerAction(
            sleepTimer.tick(
                nowMs = System.currentTimeMillis(),
                isPlaying = player.isPlaying,
                chapterRemainingMs = chapterRemainingMs(),
            ),
        )
    }

    private fun applySleepTimerAction(action: SleepTimerAction) {
        when (action) {
            SleepTimerAction.None -> Unit
            is SleepTimerAction.SetVolume -> player.volume = action.volume
            SleepTimerAction.Expire -> {
                player.pause()
                // Restore after pausing, so the next play doesn't start silent.
                player.volume = 1f
            }
        }
    }

    private fun chapterRemainingMs(): Long? {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return (duration - player.currentPosition).coerceAtLeast(0L)
    }

    private suspend fun saveCurrentProgress(forcePlayed: Boolean) {
        val mediaItem = player.currentMediaItem ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val extras = mediaItem.mediaMetadata.extras
        val fictionId = extras?.getInt("fiction_id")?.takeIf { it > 0 }
        val chapterId = extras?.getInt("chapter_id")?.takeIf { it > 0 }

        // Record a wall-clock → position snapshot so the user can jump back to where they fell
        // asleep. Done here because this runs on the 15s tick, on pause, and at chapter end. The
        // fiction/chapter ids let "jump back" reload the fiction even after playback has stopped.
        ServiceLocator.playbackHistory(this).record(
            timestamp = System.currentTimeMillis(),
            mediaId = mediaItem.mediaId,
            fictionId = fictionId ?: 0,
            chapterId = chapterId ?: 0,
            title = mediaItem.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Chapter",
            fictionTitle = mediaItem.mediaMetadata.albumTitle?.toString(),
            positionMs = position,
        )

        if (fictionId == null || chapterId == null) return
        val nearComplete = duration?.let { total ->
            position >= total - 20_000L || position.toDouble() / total.toDouble() >= 0.96
        } ?: false

        runCatching {
            repository.saveProgress(
                fictionId = fictionId,
                chapterId = chapterId,
                positionSeconds = position / 1000.0,
                isPlayed = forcePlayed || nearComplete,
            )
        }
    }

    private suspend fun serverUrl(): String = tokenStore.current().serverUrl

    private suspend fun library(): LibraryResponse? {
        val loaded = runCatching { repository.library() }.getOrNull()
        if (loaded != null) {
            lastLibrary = loaded
        }
        return loaded ?: lastLibrary
    }

    private suspend fun fictionChapters(fictionId: Int): Pair<FictionSummary, List<ChapterSummary>>? {
        val response = runCatching {
            repository.chapters(fictionId = fictionId, playableOnly = true)
        }.getOrNull() ?: return null
        return response.fiction to response.chapters
    }

    /**
     * Expand a single chapter selection (e.g. tapping a chapter in the Android Auto browse tree)
     * into its whole fiction, so the car gets the same next/previous and auto-advance behaviour as
     * the in-app player. Returns null if the fiction can't be loaded, so the caller can fall back
     * to playing just the selected item.
     */
    private suspend fun buildFictionQueue(
        fictionId: Int,
        startChapterId: Int,
    ): MediaSession.MediaItemsWithStartPosition? {
        val (fiction, chapters) = fictionChapters(fictionId) ?: return null
        val serverUrl = serverUrl()
        val built = chapters.mapNotNull { chapter ->
            TtsRoadMediaItems.chapter(chapter, fiction, serverUrl)?.let { chapter to it }
        }
        if (built.isEmpty()) return null

        val startIndex = built.indexOfFirst { it.first.resolvedChapterId == startChapterId }
            .coerceAtLeast(0)
        val startPositionMs = built[startIndex].first.resolvedPositionSeconds
            .takeIf { it > 0.0 }
            ?.let { (it * 1000).toLong() }
            ?: 0L

        return MediaSession.MediaItemsWithStartPosition(
            built.map { it.second },
            startIndex,
            startPositionMs,
        )
    }

    /**
     * Resolve a spoken query to a fiction and return its queue positioned at the resume point.
     *
     * Deliberately refuses a weak match: starting an unrelated book because a misheard query
     * happened to share a tag is worse, while driving, than the request simply not working.
     */
    private suspend fun queueForSpokenQuery(
        query: String,
    ): MediaSession.MediaItemsWithStartPosition? {
        val library = library() ?: return null
        val fiction = resolveSpokenFiction(library.fictions, query) ?: return null
        return buildFictionQueue(fiction.id, resumeChapterId(library, fiction.id))
    }

    /**
     * Where to start a fiction the user asked for by name: wherever they left off, or the first
     * chapter. 0 is a safe fallback — [buildFictionQueue] coerces an unmatched id to the start.
     */
    private fun resumeChapterId(library: LibraryResponse, fictionId: Int): Int =
        library.continueListening
            .firstOrNull { it.resolvedFictionId == fictionId }
            ?.resolvedChapterId
            ?: 0

    /** Fictions and chapters matching a car search, as browse items. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        val library = library() ?: return emptyList()
        val serverUrl = serverUrl()
        val fictions = searchFictions(library.fictions, query)
        val chapters = searchChapters(
            library.continueListening + library.recentChapters,
            query,
        ).distinctBy { it.resolvedChapterId }

        return fictions.map { TtsRoadMediaItems.fictionFolder(it, serverUrl) } +
            chapters.mapNotNull { chapter ->
                val fiction = chapter.fiction
                    ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId }
                TtsRoadMediaItems.chapter(chapter, fiction, serverUrl)
            }
    }

    /** The queue to resume when the car (or a media button) asks to play with nothing loaded. */
    private suspend fun resumeQueue(): MediaSession.MediaItemsWithStartPosition? {
        val library = library() ?: return null
        val chapter = library.continueListening.firstOrNull() ?: return null
        val fictionId = chapter.resolvedFictionId.takeIf { it > 0 } ?: return null
        return buildFictionQueue(fictionId, chapter.resolvedChapterId)
    }

    @OptIn(UnstableApi::class)
    private class BrowserCallback(
        private val service: TtsRoadMediaService,
    ) : MediaLibrarySession.Callback {
        // The 30-second skips are custom commands, so every controller — the notification, the
        // lockscreen, Android Auto — has to be granted them explicitly or their buttons arrive
        // disabled.
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(TtsRoadSessionCommands.skipBackCommand)
                        .add(TtsRoadSessionCommands.skipForwardCommand)
                        .build(),
                )
                .build()

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val delta = when (customCommand.customAction) {
                TtsRoadSessionCommands.SkipBack -> -service.skipIntervalMs
                TtsRoadSessionCommands.SkipForward -> service.skipIntervalMs
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            val player = session.player
            player.seekTo(skipTargetMs(player.currentPosition, player.duration, delta))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            service.serviceScope.future {
                LibraryResult.ofItem(TtsRoadMediaItems.root(), params)
            }

        // Controllers (the app UI and Android Auto) send media items back across the binder without
        // their playback URI. Restore it from the request metadata we stashed when building them.
        private fun restoreItem(item: MediaItem): MediaItem {
            val uri = item.requestMetadata.mediaUri
            return if (uri != null) item.buildUpon().setUri(uri).build() else item
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(::restoreItem).toMutableList())

        // Invoked when a controller sets what to play. When a single chapter is selected — e.g. from
        // the Android Auto browse tree — expand it into its whole fiction so next/previous and
        // auto-advance work in the car. A multi-item set (the in-app player already sending a queue)
        // passes straight through.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            service.serviceScope.future {
                val single = mediaItems.singleOrNull()

                // "Hey Google, play Ashes of Aether on TTSRoad" arrives as a single item carrying
                // only the spoken query — no media id, no extras. Resolve it to a fiction and start
                // it at its resume point, which is the only safe way to start something new while
                // driving.
                val spoken = single?.requestMetadata?.searchQuery
                if (!spoken.isNullOrBlank()) {
                    service.queueForSpokenQuery(spoken)?.let { return@future it }
                }

                val extras = single?.mediaMetadata?.extras
                val fictionId = extras?.getInt("fiction_id", 0)?.takeIf { it > 0 }
                val chapterId = extras?.getInt("chapter_id", 0)?.takeIf { it > 0 }
                if (fictionId != null && chapterId != null) {
                    service.buildFictionQueue(fictionId, chapterId)?.let { return@future it }
                }
                MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map(::restoreItem),
                    startIndex,
                    startPositionMs,
                )
            }

        // Pressing play in the car with nothing loaded resumes the most recent "continue listening"
        // chapter within its fiction queue. A failed future tells the system there's nothing to resume.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            service.serviceScope.future {
                service.resumeQueue() ?: throw UnsupportedOperationException("Nothing to resume")
            }

        // Media3 splits searching in two: onSearch does the work and reports how many results
        // exist, then the browser asks for the page it wants. The result is cached between the two
        // so the library is not fetched and matched twice per spoken search.
        private var cachedQuery: String? = null
        private var cachedResults: List<MediaItem> = emptyList()

        private suspend fun results(query: String): List<MediaItem> {
            if (query == cachedQuery) return cachedResults
            val found = service.searchItems(query)
            cachedQuery = query
            cachedResults = found
            return found
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            service.serviceScope.future {
                val found = results(query)
                session.notifySearchResultChanged(browser, query, found.size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            service.serviceScope.future {
                LibraryResult.ofItemList(page(results(query), page, pageSize), params)
            }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            service.serviceScope.future {
                val items = when {
                    parentId == TtsRoadMediaIds.Root -> rootChildren()
                    parentId == TtsRoadMediaIds.Continue -> continueItems()
                    parentId == TtsRoadMediaIds.Fictions -> fictionFolders()
                    parentId == TtsRoadMediaIds.Recent -> recentItems()
                    parentId.startsWith(TtsRoadMediaIds.FictionPrefix) -> fictionChildren(parentId)
                    else -> emptyList()
                }
                LibraryResult.ofItemList(page(items, page, pageSize), styleFor(parentId, params))
            }

        /**
         * How the car should draw this node's children. Fictions are cover-led and read far better
         * as a grid; everything else is an ordered list where the title carries the meaning.
         *
         * The hints ride on the returned [LibraryParams], which is where a media browser looks for
         * per-node styling.
         */
        private fun styleFor(parentId: String, params: LibraryParams?): LibraryParams {
            val grid = parentId == TtsRoadMediaIds.Fictions
            return LibraryParams.Builder()
                .setExtras(TtsRoadMediaItems.contentStyle(browsableGrid = grid, playableGrid = false))
                .setRecent(params?.isRecent == true)
                .setOffline(params?.isOffline == true)
                .setSuggested(params?.isSuggested == true)
                .build()
        }

        private fun rootChildren(): List<MediaItem> = listOf(
            TtsRoadMediaItems.folder(
                mediaId = TtsRoadMediaIds.Continue,
                title = "Continue Listening",
                subtitle = "Resume active chapters",
            ),
            TtsRoadMediaItems.folder(
                mediaId = TtsRoadMediaIds.Fictions,
                title = "Fictions",
                subtitle = "Browse by story",
            ),
            TtsRoadMediaItems.folder(
                mediaId = TtsRoadMediaIds.Recent,
                title = "Recent",
                subtitle = "Latest ready chapters",
            ),
        )

        private suspend fun continueItems(): List<MediaItem> {
            val library = service.library() ?: return emptyList()
            val serverUrl = service.serverUrl()
            return library.continueListening.mapNotNull { chapter ->
                val fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId }
                TtsRoadMediaItems.chapter(chapter, fiction, serverUrl)
            }
        }

        private suspend fun fictionFolders(): List<MediaItem> {
            val library = service.library() ?: return emptyList()
            val serverUrl = service.serverUrl()
            return library.fictions.map { TtsRoadMediaItems.fictionFolder(it, serverUrl) }
        }

        private suspend fun recentItems(): List<MediaItem> {
            val library = service.library() ?: return emptyList()
            val serverUrl = service.serverUrl()
            return library.recentChapters.mapNotNull { chapter ->
                val fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.resolvedFictionId }
                TtsRoadMediaItems.chapter(chapter, fiction, serverUrl)
            }
        }

        private suspend fun fictionChildren(parentId: String): List<MediaItem> {
            val fictionId = TtsRoadMediaIds.fictionId(parentId) ?: return emptyList()
            val (fiction, chapters) = service.fictionChapters(fictionId) ?: return emptyList()
            val serverUrl = service.serverUrl()
            return chapters.mapNotNull { TtsRoadMediaItems.chapter(it, fiction, serverUrl) }
        }

        private fun page(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
            if (pageSize <= 0) return items
            val from = page.toLong() * pageSize.toLong()
            if (from >= items.size) return emptyList()
            val to = (from + pageSize).coerceAtMost(items.size.toLong())
            return items.subList(from.toInt(), to.toInt())
        }
    }

    private companion object {
        /** Short enough that the sleep timer's fade-out is smooth rather than stepped. */
        const val SLEEP_TIMER_TICK_MS = 500L
    }
}

