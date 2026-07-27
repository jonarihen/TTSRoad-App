package dk.perspektiva.ttsroad.media

import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dk.perspektiva.ttsroad.MainActivity
import dk.perspektiva.ttsroad.core.ServiceLocator
import dk.perspektiva.ttsroad.data.ChapterSummary
import dk.perspektiva.ttsroad.data.FictionSummary
import dk.perspektiva.ttsroad.data.LibraryResponse
import dk.perspektiva.ttsroad.data.TokenStore
import dk.perspektiva.ttsroad.data.TtsRoadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TtsRoadMediaService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tokenStore: TokenStore
    private lateinit var repository: TtsRoadRepository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var lastLibrary: LibraryResponse? = null

    @Volatile
    private var authHeader: String? = null

    override fun onCreate() {
        super.onCreate()
        tokenStore = ServiceLocator.tokenStore(this)
        repository = ServiceLocator.repository(this)
        serviceScope.launch {
            tokenStore.session.collectLatest { authHeader = it.authorizationHeader }
        }
        player = createPlayer()
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        serviceScope.launch { saveCurrentProgress(forcePlayed = true) }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) {
                        serviceScope.launch { saveCurrentProgress(forcePlayed = false) }
                    }
                }
            },
        )
        startProgressTicker()
        session = MediaLibrarySession.Builder(this, player, BrowserCallback(this))
            .setSessionActivity(playerActivityIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
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

    private fun startProgressTicker() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveCurrentProgress(forcePlayed = false)
            }
        }
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

    /** The queue to resume when the car (or a media button) asks to play with nothing loaded. */
    private suspend fun resumeQueue(): MediaSession.MediaItemsWithStartPosition? {
        val library = library() ?: return null
        val chapter = library.continueListening.firstOrNull() ?: return null
        val fictionId = chapter.resolvedFictionId.takeIf { it > 0 } ?: return null
        return buildFictionQueue(fictionId, chapter.resolvedChapterId)
    }

    private class BrowserCallback(
        private val service: TtsRoadMediaService,
    ) : MediaLibrarySession.Callback {
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
                LibraryResult.ofItemList(page(items, page, pageSize), params)
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
            return library.fictions.map(TtsRoadMediaItems::fictionFolder)
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
}

