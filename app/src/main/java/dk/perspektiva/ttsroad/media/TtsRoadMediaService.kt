package dk.perspektiva.ttsroad.media

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.glance.appwidget.updateAll
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import dk.perspektiva.ttsroad.data.BookmarkKindAuto
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LibraryResponse
import dk.perspektiva.ttsroad.data.DefaultSkipIntervalMs
import dk.perspektiva.ttsroad.data.DownloadPreferences
import dk.perspektiva.ttsroad.data.FictionSpeedPreferences
import dk.perspektiva.ttsroad.data.PlaybackPreferences
import dk.perspektiva.ttsroad.data.QueueStatusPlaying
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import dk.perspektiva.ttsroad.data.VolumeBoost
import dk.perspektiva.ttsroad.data.effectiveSpeed
import dk.perspektiva.ttsroad.data.libraryMoved
import dk.perspektiva.ttsroad.data.mergeLibraryDelta
import dk.perspektiva.ttsroad.data.parseSessionEnd
import dk.perspektiva.ttsroad.player.BreadcrumbPruneIntervalMs
import dk.perspektiva.ttsroad.player.PendingProgressStore
import dk.perspektiva.ttsroad.player.PlaybackFailure
import dk.perspektiva.ttsroad.player.PlayedThreshold
import dk.perspektiva.ttsroad.player.ProgressSync
import dk.perspektiva.ttsroad.player.ShakeDetector
import dk.perspektiva.ttsroad.player.SleepTimerAction
import dk.perspektiva.ttsroad.player.SleepTimerController
import dk.perspektiva.ttsroad.player.SleepTimerMode
import dk.perspektiva.ttsroad.widget.NowPlayingSnapshot
import dk.perspektiva.ttsroad.widget.NowPlayingStore
import dk.perspektiva.ttsroad.widget.NowPlayingWidget
import dk.perspektiva.ttsroad.widget.nowPlayingSnapshotOf
import dk.perspektiva.ttsroad.player.ServerBreadcrumbIntervalMs
import dk.perspektiva.ttsroad.player.breadcrumbsToPrune
import dk.perspektiva.ttsroad.player.shouldWriteBreadcrumb
import dk.perspektiva.ttsroad.player.classifyPlaybackError
import dk.perspektiva.ttsroad.player.retryDelayMs
import dk.perspektiva.ttsroad.player.skipTargetMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private lateinit var downloadPreferences: DownloadPreferences
    private lateinit var player: ExoPlayer
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var session: MediaLibrarySession
    private lateinit var sleepTimer: SleepTimerController
    private lateinit var fictionSpeeds: FictionSpeedPreferences
    private lateinit var nowPlayingStore: NowPlayingStore

    /** The book currently playing, or null. Drives the per-fiction speed; see [effectiveSpeed]. */
    private val currentFictionId = MutableStateFlow<Int?>(null)
    private lateinit var pendingProgress: PendingProgressStore
    private lateinit var progressSync: ProgressSync
    private var shakeDetector: ShakeDetector? = null
    private var lastLibrary: LibraryResponse? = null
    private var lastLibraryCursor: String? = null

    // Automatic recovery from a dropped stream. Reset once playback is healthy again, so a second
    // outage later in the night gets a fresh set of attempts rather than giving up immediately.
    private var retryJob: Job? = null
    private var retryAttempt = 0

    // The chapter the keep-ahead window was last planned around; see moveKeepAheadWindow.
    private var lastKeepAheadChapterId: Int? = null

    // Throttles for the server-side jump-back trail; see player/PlaybackBreadcrumbs.kt.
    private var lastBreadcrumbAt: Long? = null
    private var lastBreadcrumbPruneAt: Long? = null

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
        fictionSpeeds = ServiceLocator.fictionSpeedPreferences(this)
        preferences = ServiceLocator.playbackPreferences(this)
        downloadPreferences = ServiceLocator.downloadPreferences(this)
        pendingProgress = ServiceLocator.pendingProgress(this)
        progressSync = ServiceLocator.progressSync(this)
        nowPlayingStore = NowPlayingStore(this)
        // Anything recorded while the last session was offline is still waiting. Flush it before
        // playback adds to it, so a reconnect settles the backlog rather than deepening it.
        serviceScope.launch { progressSync.flush() }
        serviceScope.launch {
            tokenStore.session.collectLatest { state ->
                authHeader = state.authorizationHeader
                // Account state and the snapshot are separate files. Remove the latter explicitly
                // on sign-out so a later process can never show the previous account's book, even
                // for the instant before its DataStore read finishes.
                if (!state.isLoggedIn) {
                    nowPlayingStore.clear()
                    runCatching { NowPlayingWidget().updateAll(this@TtsRoadMediaService) }
                }
            }
        }
        player = createPlayer()
        startAudioTuning()
        // Speed lives in the service, not the UI: the player is recreated on a swipe-away, a
        // process kill, or a reboot, and the car can start playback with no UI running at all.
        // Applying it here is what makes it survive all three.
        //
        // Three sources, because a book may have a pace of its own: the global speed, the override
        // map, and which book is playing. Combining them here rather than resolving the speed when
        // it is chosen is what makes moving to a different book — by auto-advance, from the car, or
        // from a notification — change the pace without anything having to ask.
        serviceScope.launch {
            combine(
                preferences.prefs.map { it.speed },
                fictionSpeeds.overrides,
                currentFictionId,
            ) { global, overrides, fictionId -> effectiveSpeed(global, overrides, fictionId) }
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
                        serviceScope.launch {
                            saveCurrentProgress(forcePlayed = true)
                            // STATE_ENDED means the whole loaded queue is done, not one chapter,
                            // so this is the end of the book. Only now does the server queue get
                            // a say — everything before this point is the local queue, untouched.
                            advanceServerQueue()
                        }
                    } else {
                        // READY is when duration first becomes trustworthy; BUFFERING also matters
                        // after restoring a queue before isPlaying has changed.
                        serviceScope.launch { publishNowPlaying(forcePlaying = null) }
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
                    } else {
                        // Do not wait for the first 15-second progress tick to turn Play into Pause.
                        serviceScope.launch { publishNowPlaying(forcePlaying = true) }
                    }
                }

                /**
                 * Which book is playing, for the per-fiction speed above.
                 *
                 * Fires for a queue being set and for auto-advance alike, so moving from one book
                 * to another applies the new book's pace without the UI being involved — which is
                 * the case that matters, since the car and the notification can both do it.
                 */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentFictionId.value = mediaItem?.mediaMetadata?.extras
                        ?.getInt("fiction_id")
                        ?.takeIf { it > 0 }
                    // A chapter title and cover should change as soon as auto-advance does. When a
                    // queue is cleared, publishNowPlaying preserves the last item and marks it
                    // stopped, which is the widget's "last heard" state.
                    serviceScope.launch {
                        publishNowPlaying(forcePlaying = if (mediaItem == null) false else null)
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // Widget skips and seeks should move the progress display immediately.
                    serviceScope.launch { publishNowPlaying(forcePlaying = null) }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    // Extrapolation uses speed, so changing it invalidates the stored baseline.
                    serviceScope.launch { publishNowPlaying(forcePlaying = null) }
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
        // The overflow buttons depend on what the server can hold, and that is not known when the
        // session is built — discovery is asynchronous, and a start from the car runs with no UI to
        // have done it. Republishing the buttons when the answer arrives is what makes each one
        // appear on a server that supports it and stay absent on one that does not.
        serviceScope.launch {
            repository.currentCapabilities
                .map { it.bookmarks to it.pronunciationReports }
                .distinctUntilChanged()
                .collect { (bookmarks, pronunciationReports) ->
                    session.setMediaButtonPreferences(
                        TtsRoadSessionCommands.mediaButtonPreferences(
                            bookmarks = bookmarks,
                            pronunciationReports = pronunciationReports,
                        ),
                    )
                }
        }
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
        // Read through the offline cache before the network. A downloaded chapter then plays with
        // the server unreachable, and a streamed one is not re-fetched when it is replayed — with no
        // other change to how playback works, because the auth injection above still runs for every
        // byte the cache does not already have.
        val cachingFactory = ServiceLocator.offlineDownloads(this).readThroughFactory(resolvingFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(cachingFactory)
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

    /**
     * Mark the current moment, for the bookmark button on the notification, the lockscreen and the
     * Android Auto transport.
     *
     * Hearing a line worth keeping while driving is exactly the moment neither the web's bookmarks
     * page nor an in-app list can serve, and this is the whole point of the feature on a phone: one
     * press, no unlocking, no leaving the current screen, playback untouched.
     *
     * The moment is read here and synchronously — before anything suspends — because that is what
     * makes the mark land where the press did rather than wherever the network round trip finished.
     */
    @OptIn(UnstableApi::class)
    private fun bookmarkCurrentMoment(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<SessionResult> {
        val target = bookmarkTargetFor(
            chapterId = player.currentMediaItem?.mediaMetadata?.extras?.getInt("chapter_id"),
            positionMs = player.currentPosition,
        ) ?: return Futures.immediateFuture(
            bookmarkResult(session, controller, BookmarkOutcome.NothingPlaying),
        )

        return serviceScope.future {
            val outcome = runCatching {
                repository.createBookmark(
                    chapterId = target.chapterId,
                    positionSeconds = target.positionSeconds,
                )
            }.fold(
                // Null is not a failure to report as one: it is this server having no bookmarks
                // capability, which is a different thing to say than "could not save".
                onSuccess = { if (it == null) BookmarkOutcome.Unsupported else BookmarkOutcome.Written },
                onFailure = { BookmarkOutcome.Failed },
            )
            bookmarkResult(session, controller, outcome)
        }
    }

    /**
     * Turn an outcome into a result, telling the controller about it when there is something to say.
     *
     * A failure is sent as a [SessionError] as well as returned, because the two reach different
     * places: the return value answers the controller that issued the command, while [MediaSession
     * .sendError] is what surfaces a message in the car and on the notification.
     */
    private fun bookmarkResult(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        outcome: BookmarkOutcome,
    ): SessionResult {
        if (outcome == BookmarkOutcome.Written) return SessionResult(SessionResult.RESULT_SUCCESS)
        val code = when (outcome) {
            BookmarkOutcome.NothingPlaying -> SessionError.ERROR_INVALID_STATE
            BookmarkOutcome.Unsupported -> SessionError.ERROR_NOT_SUPPORTED
            // Everything else is the network: offline, a dead server, an expired token.
            else -> SessionError.ERROR_IO
        }
        val error = SessionError(code, outcome.message)
        session.sendError(controller, error)
        return SessionResult(error)
    }

    /**
     * Capture "that word was pronounced wrong, here", for the flag button on the notification, the
     * lockscreen and the Android Auto overflow (#125).
     *
     * This is the point of the whole feature rather than a car-shaped extra of it. Wanting a
     * pronunciation rule starts with *hearing* the mispronunciation, which happens in a car, on
     * headphones, forty chapters into a serial — and by the time there is a keyboard the spelling
     * is gone and so is the chapter. So the press stores the moment, not a rule: the rule editor,
     * the dry run and the impact list are desk work and stay on the web.
     *
     * Everything worth having is read synchronously, before anything suspends, exactly as
     * [bookmarkCurrentMoment] does — including the word, which is why it comes from
     * [TtsRoadRepository.loadedReadAlong] rather than a fetch. Playback is never touched.
     */
    @OptIn(UnstableApi::class)
    private fun reportPronunciationHere(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<SessionResult> {
        val target = pronunciationReportTargetFor(player) ?: return Futures.immediateFuture(
            pronunciationReportResult(session, controller, PronunciationReportOutcome.NothingPlaying),
        )
        // Null in the ordinary case — a car has no reader open — and the contract expects that: a
        // report with no word still names ten seconds for a human to listen to. Nothing here is
        // allowed to fail the capture, so the lookup neither fetches nor throws.
        val word = pronunciationWordAt(
            document = repository.loadedReadAlong(target.chapterId),
            positionSeconds = target.positionSeconds,
        )

        return serviceScope.future {
            val outcome = pronunciationReportOutcomeFor(
                runCatching {
                    repository.createPronunciationReport(
                        chapterId = target.chapterId,
                        positionSeconds = target.positionSeconds,
                        fictionId = target.fictionId,
                        word = word,
                    )
                },
            )
            pronunciationReportResult(session, controller, outcome)
        }
    }

    /**
     * The [bookmarkResult] shape, with one difference worth the duplication: a refusal carries the
     * server's own sentence. The open-report ceiling is the one failure a listener can act on, and
     * "clear some in the browser" is only useful if it actually reaches them.
     */
    private fun pronunciationReportResult(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        outcome: PronunciationReportOutcome,
    ): SessionResult {
        val code = pronunciationReportErrorCode(outcome)
            ?: return SessionResult(SessionResult.RESULT_SUCCESS)
        val error = SessionError(code, outcome.message)
        session.sendError(controller, error)
        return SessionResult(error)
    }

    private fun chapterRemainingMs(): Long? {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return (duration - player.currentPosition).coerceAtLeast(0L)
    }

    /**
     * Persist what the launcher needs and ask every placed widget to redraw.
     *
     * There is deliberately no controller kept alive by the widget. The media service is the one
     * source of truth, and this tiny note survives when Android later kills its process. If the
     * queue is cleared, retain the last chapter but mark it stopped so the useful "last heard"
     * state does not collapse into "nothing played".
     */
    private suspend fun publishNowPlaying(forcePlaying: Boolean?) {
        val now = System.currentTimeMillis()
        val current = nowPlayingSnapshotOf(
            player = player,
            isPlaying = forcePlaying ?: player.isPlaying,
            updatedAt = now,
        )
        if (current != null) {
            nowPlayingStore.write(current)
        } else {
            nowPlayingStore.read()?.let { previous ->
                nowPlayingStore.write(previous.copy(isPlaying = false, updatedAt = now))
            }
        }
        runCatching { NowPlayingWidget().updateAll(this) }
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

        // The home-screen widget reads a note rather than a player, because the launcher draws it
        // in a process where this one is usually dead (#150). Written from here so it rides the
        // same 15s tick, pause and chapter-end path that already exists — no second ticker.
        publishNowPlaying(forcePlaying = null)

        if (fictionId == null || chapterId == null) return
        // The account's `auto_mark_played`. The web player has honoured it since it was introduced
        // and this client did not, marking past 96% regardless — and because the phone writes
        // is_played to the same rows the browser reads, unticking the box in a browser was being
        // overridden by the device doing most of the listening (#119).
        //
        // forcePlayed is deliberately outside the gate: the preference is about the automatic path,
        // which is how the web reads it too. Pressing "mark played" still marks it played.
        val nearComplete = PlayedThreshold.reached(
            positionMs = position,
            durationMs = duration,
            autoMarkEnabled = preferences.current().autoMarkPlayed,
        )

        // Queue first, then try to send. The write used to be a bare `runCatching` around the post,
        // so a position recorded with no connection was simply lost — and the next successful write
        // carried an unstamped position the server could not order against one reached in the
        // browser meanwhile, and so overwrote it. The queue is what makes the position survive
        // being offline, and the stamp is what lets the server decide who is actually newer.
        pendingProgress.record(
            fictionId = fictionId,
            chapterId = chapterId,
            positionSeconds = position / 1000.0,
            isPlayed = forcePlayed || nearComplete,
        )
        progressSync.flush()

        writeBreadcrumb(chapterId = chapterId, positionMs = position)
        moveKeepAheadWindow(fictionId = fictionId, chapterId = chapterId)
    }

    /**
     * Drop a coarse jump-back breadcrumb on the server, so the moment is findable from the browser
     * and from any other device on this account.
     *
     * Deliberately much rarer than the local snapshot above — see [ServerBreadcrumbIntervalMs]. The
     * local store keeps its full-resolution trail either way; this is the cross-device overlay.
     *
     * Every failure here is swallowed. A breadcrumb is a convenience, and the caller's real job is
     * saving the listening position — an offline phone must not lose that to a failed extra write.
     */
    private suspend fun writeBreadcrumb(chapterId: Int, positionMs: Long) {
        val now = System.currentTimeMillis()
        if (!shouldWriteBreadcrumb(lastBreadcrumbAt, now)) return
        // Claimed before the call rather than after it, so a slow or hanging write cannot let the
        // next tick queue a second one behind it.
        lastBreadcrumbAt = now

        val written = runCatching {
            repository.createBookmark(
                chapterId = chapterId,
                positionSeconds = positionMs / 1000.0,
                kind = BookmarkKindAuto,
            )
        }.getOrNull()
        // Null means this server has no bookmarks capability at all. Stop reaching for it: there is
        // nothing to prune either, and the next tick would only repeat the same failed call.
        if (written == null) return

        if (!shouldWriteBreadcrumb(lastBreadcrumbPruneAt, now, BreadcrumbPruneIntervalMs)) return
        lastBreadcrumbPruneAt = now
        runCatching {
            val stale = breadcrumbsToPrune(repository.breadcrumbs().orEmpty())
            for (id in stale) repository.deleteBookmark(id)
        }
    }

    /**
     * Keep the next few chapters on disk, so losing signal mid-book is not the end of playback.
     *
     * Hung off the progress save rather than a listener because this is exactly the set of moments
     * that matter — the 15s tick, a pause, and the end of a chapter — and it needs the same
     * fiction/chapter ids that call has already dug out of the item's extras.
     *
     * Only acts when the chapter changes. The window cannot move within a chapter, so re-planning
     * every 15s would spend a request per tick to reach the same answer.
     *
     * Every failure is swallowed for the reason the whole method is optional: the caller's real job
     * is saving the listening position, and a phone with no signal must not lose that because a
     * chapter listing could not be fetched.
     */
    private suspend fun moveKeepAheadWindow(fictionId: Int, chapterId: Int) {
        if (chapterId == lastKeepAheadChapterId) return
        val keepAhead = runCatching { downloadPreferences.current().keepAheadChapters }
            .getOrDefault(0)
        // Claimed even when the feature is off, so switching it on mid-chapter still takes effect at
        // the next chapter rather than never — and so an off setting costs one preference read per
        // chapter rather than one per tick.
        lastKeepAheadChapterId = chapterId
        if (keepAhead <= 0) return

        val chapters = fictionChapters(fictionId)?.second ?: return
        ServiceLocator.offlineDownloads(this).applyKeepAhead(
            chapters = chapters,
            currentChapterId = chapterId,
            keepAhead = keepAhead,
            fictionId = fictionId,
            serverUrl = serverUrl(),
        )
    }

    /**
     * At the end of the loaded queue, ask the server what should play next.
     *
     * Deliberately the *only* place the server queue touches playback. Everything up to here is the
     * local per-fiction queue behaving exactly as it always has — tap a chapter, get the whole book
     * in order, auto-advance within it. This runs once that book is finished, which is the moment
     * the app previously just stopped.
     *
     * The decision is the server's rather than this client's: `advance` pops the queue head, and
     * when the queue is empty it consults the account's `queue_when_empty` — `continue` gives the
     * oldest unplayed chapter in the library, `stop` gives nothing. Deciding locally would make the
     * phone and the browser disagree about what comes after a book.
     *
     * Silent on every failure. A server with no queue, an unreachable one, or an empty answer all
     * mean the same thing here: stop, which is what would have happened anyway.
     */
    private suspend fun advanceServerQueue() {
        val response = runCatching { repository.advanceQueue() }.getOrNull() ?: return
        if (response.status != QueueStatusPlaying) return
        val next = response.item ?: return
        val item = TtsRoadMediaItems.queueItem(next, serverUrl()) ?: return

        player.setMediaItem(item)
        next.positionSeconds
            .takeIf { it > 0.0 }
            ?.let { player.seekTo((it * 1000).toLong()) }
        player.prepare()
        player.play()
    }

    private suspend fun serverUrl(): String = tokenStore.current().serverUrl

    private suspend fun library(): LibraryResponse? {
        // Android Auto can start the service in a fresh process without MainActivity having run
        // capability discovery. Do it here once so the car benefits from delta sync too.
        if (repository.currentCapabilities.value.advertised.isEmpty()) {
            repository.refreshCurrentCapabilities()
        }
        val previous = lastLibrary
        val cursor = lastLibraryCursor
        val loaded = runCatching {
            if (previous != null && cursor != null &&
                repository.currentCapabilities.value.deltaSync
            ) {
                val index = repository.deltaSync(cursor)
                val refreshed = if (index?.libraryMoved() == true) {
                    mergeLibraryDelta(previous, repository.library(updatedSince = cursor))
                } else {
                    previous
                }
                if (index != null) {
                    lastLibraryCursor = index.serverTime
                    refreshed.copy(serverTime = index.serverTime)
                } else {
                    refreshed
                }
            } else {
                repository.library().also { lastLibraryCursor = it.serverTime }
            }
        }.getOrNull()
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
                        // Granted regardless of the bookmarks capability: whether the *button* is
                        // offered is decided by the media button preferences, and a controller that
                        // asks anyway gets a "not supported" it can show rather than silence.
                        .add(TtsRoadSessionCommands.bookmarkCommand)
                        // Same reasoning, and the same split: the capability decides the button,
                        // this decides whether an assistant or a stale controller asking for it
                        // gets an answer at all.
                        .add(TtsRoadSessionCommands.reportPronunciationCommand)
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
            // Handled apart from the seeks below: these write to the server rather than the player,
            // so they answer asynchronously and never touch the playback position.
            if (customCommand.customAction == TtsRoadSessionCommands.Bookmark) {
                return service.bookmarkCurrentMoment(session, controller)
            }
            if (customCommand.customAction == TtsRoadSessionCommands.ReportPronunciation) {
                return service.reportPronunciationHere(session, controller)
            }
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
                    parentId == TtsRoadMediaIds.Queue -> queueItems()
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

        /**
         * Up Next is offered only when the server can actually back it — the reason the capability
         * has its own flag. A node that was always present would be an empty dead end in the car
         * on every server that has no cross-library queue.
         */
        private suspend fun rootChildren(): List<MediaItem> = listOfNotNull(
            TtsRoadMediaItems.folder(
                mediaId = TtsRoadMediaIds.Queue,
                title = "Up Next",
                subtitle = "Your queue, across books",
            ).takeIf { service.repository.currentCapabilities.value.queue },
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

        private suspend fun queueItems(): List<MediaItem> {
            val queue = runCatching { service.repository.queue() }.getOrNull() ?: return emptyList()
            val serverUrl = service.serverUrl()
            return queue.items.mapNotNull { TtsRoadMediaItems.queueItem(it, serverUrl) }
        }

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

