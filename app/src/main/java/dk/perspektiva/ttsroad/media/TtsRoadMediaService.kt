package dk.perspektiva.ttsroad.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(UnstableApi::class)
class TtsRoadMediaService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tokenStore: TokenStore
    private lateinit var repository: TtsRoadRepository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var lastLibrary: LibraryResponse? = null

    override fun onCreate() {
        super.onCreate()
        tokenStore = ServiceLocator.tokenStore(this)
        repository = ServiceLocator.repository(this)
        player = createPlayer()
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        serviceScope.launch { saveCurrentProgress(forcePlayed = true) }
                    }
                }
            },
        )
        startProgressTicker()
        session = MediaLibrarySession.Builder(this, player, BrowserCallback(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createPlayer(): ExoPlayer {
        val authHeader = runBlocking { tokenStore.current().authorizationHeader }
        val headers = authHeader?.let { mapOf("Authorization" to it) }.orEmpty()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private fun startProgressTicker() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                saveCurrentProgress(forcePlayed = false)
            }
        }
    }

    private suspend fun saveCurrentProgress(forcePlayed: Boolean) {
        if (!player.isPlaying && !forcePlayed) return
        val mediaItem = player.currentMediaItem ?: return
        val extras = mediaItem.mediaMetadata.extras ?: return
        val fictionId = extras.getInt("fiction_id").takeIf { it > 0 } ?: return
        val chapterId = extras.getInt("chapter_id").takeIf { it > 0 } ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = player.currentPosition.coerceAtLeast(0L)
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
            return library.continueListening.mapNotNull { chapter ->
                val fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.fictionId }
                TtsRoadMediaItems.chapter(chapter, fiction)
            }
        }

        private suspend fun fictionFolders(): List<MediaItem> {
            val library = service.library() ?: return emptyList()
            return library.fictions.map(TtsRoadMediaItems::fictionFolder)
        }

        private suspend fun recentItems(): List<MediaItem> {
            val library = service.library() ?: return emptyList()
            return library.recentChapters.mapNotNull { chapter ->
                val fiction = chapter.fiction ?: library.fictions.firstOrNull { it.id == chapter.fictionId }
                TtsRoadMediaItems.chapter(chapter, fiction)
            }
        }

        private suspend fun fictionChildren(parentId: String): List<MediaItem> {
            val fictionId = TtsRoadMediaIds.fictionId(parentId) ?: return emptyList()
            val (fiction, chapters) = service.fictionChapters(fictionId) ?: return emptyList()
            return chapters.mapNotNull { TtsRoadMediaItems.chapter(it, fiction) }
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

