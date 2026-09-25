package app.melogold.android.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder as AndroidBinder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import app.melogold.android.Database
import app.melogold.android.Dependencies
import app.melogold.android.MainActivity
import app.melogold.android.MainApplication
import app.melogold.android.R
import app.melogold.android.data.downloads.ChunkedDataSource
import app.melogold.android.data.repo.applyingHidden
import app.melogold.android.data.repo.pendingMutations
import app.melogold.android.models.Event
import app.melogold.android.models.Format
import app.melogold.android.models.QueuedMediaItem
import app.melogold.android.models.Song
import app.melogold.android.preferences.AppearancePreferences
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.transaction
import app.melogold.android.utils.ActionReceiver
import app.melogold.android.utils.ConditionalCacheDataSourceFactory
import app.melogold.android.utils.MAX_THUMBNAIL_SIZE
import app.melogold.android.utils.TimerJob
import app.melogold.android.utils.YouTubeDLResponse
import app.melogold.android.utils.YouTubeRadio
import app.melogold.android.utils.activityPendingIntent
import app.melogold.android.utils.asDataSource
import app.melogold.android.utils.broadcastPendingIntent
import app.melogold.android.utils.defaultDataSource
import app.melogold.android.utils.findCause
import app.melogold.android.utils.findNextMediaItemById
import app.melogold.android.utils.forcePlayFromBeginning
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.get
import app.melogold.android.utils.handleUnknownErrors
import app.melogold.android.utils.intent
import app.melogold.android.utils.mediaItems
import app.melogold.android.utils.shouldBePlaying
import app.melogold.android.utils.thumbnail
import app.melogold.android.utils.timer
import app.melogold.android.utils.toast
import app.melogold.compose.preferences.SharedPreferencesProperty
import app.melogold.core.data.enums.ExoPlayerDiskCacheSize
import app.melogold.core.data.utils.UriCache
import app.melogold.core.ui.utils.EqualizerIntentBundleAccessor
import app.melogold.core.ui.utils.isAtLeastAndroid10
import app.melogold.core.ui.utils.isAtLeastAndroid12
import app.melogold.core.ui.utils.isAtLeastAndroid13
import app.melogold.core.ui.utils.isAtLeastAndroid6
import app.melogold.core.ui.utils.isAtLeastAndroid8
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.models.bodies.PlayerBody
import app.melogold.providers.innertube.models.bodies.SearchBody
import app.melogold.providers.innertube.requests.player
import app.melogold.providers.innertube.requests.searchPage
import app.melogold.providers.innertube.utils.from
import app.melogold.providers.sponsorblock.SponsorBlock
import app.melogold.providers.sponsorblock.models.Action
import app.melogold.providers.sponsorblock.models.Category
import app.melogold.providers.sponsorblock.requests.segments
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

const val LOCAL_KEY_PREFIX = "local:"
private const val TAG = "PlayerService"

@get:OptIn(UnstableApi::class)
val DataSpec.isLocal get() = key?.startsWith(LOCAL_KEY_PREFIX) == true

val MediaItem.isLocal get() = mediaId.startsWith(LOCAL_KEY_PREFIX)
val Song.isLocal get() = id.startsWith(LOCAL_KEY_PREFIX)

private const val LIKE_ACTION = "app.melogold.android.LIKE"
private const val LOOP_ACTION = "app.melogold.android.LOOP"

@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // intended in this class: it is a service
@OptIn(UnstableApi::class)
class PlayerService : Service(), Player.Listener, PlaybackStatsListener.Callback {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var mediaSession: MediaSession
    private lateinit var cache: Cache
    private lateinit var player: ExoPlayer

    private val defaultActions =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_PLAY_FROM_SEARCH

    private val stateBuilder
        get() = PlaybackState.Builder().setActions(
            defaultActions.let {
                if (isAtLeastAndroid12) it or PlaybackState.ACTION_SET_PLAYBACK_SPEED else it
            }
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */
                LIKE_ACTION,
                /* name   = */
                getString(R.string.like),
                /* icon   = */
                if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline
            ).build()
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */
                LOOP_ACTION,
                /* name   = */
                getString(R.string.queue_loop),
                /* icon   = */
                if (PlayerPreferences.trackLoopEnabled) R.drawable.repeat_on else R.drawable.repeat
            ).build()
        )

    private val playbackStateMutex = Mutex()
    private val metadataBuilder = MediaMetadata.Builder()

    private var timerJob: TimerJob? by mutableStateOf(null)
    private var radio: YouTubeRadio? = null

    /** Tracks skipped in a row because they failed; back to 0 once one plays (REWRITE §3.10.9). */
    private var failedInARow = 0

    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var preferenceUpdaterJob: Job? = null
    private var volumeNormalizationJob: Job? = null
    private var sponsorBlockJob: Job? = null

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val binder = Binder()

    private var isNotificationStarted = false
    private val notificationActionReceiver = NotificationActionReceiver()

    private val mediaItemState = MutableStateFlow<MediaItem?>(null)
    private val isLikedState = mediaItemState
        .flatMapMerge { item ->
            item?.mediaId?.let {
                Database
                    .likedAt(it)
                    .distinctUntilChanged()
                    .cancellable()
            } ?: flowOf(null)
        }
        .map { it != null }
        .onEach {
            updateNotification()
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private var poiTimestamp: Long? by mutableStateOf(null)

    override fun onBind(intent: Intent?): AndroidBinder = binder

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()

        notificationActionReceiver.register(flags = ContextCompat.RECEIVER_EXPORTED)

        bitmapProvider = BitmapProvider(
            getBitmapSize = {
                (512 * resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtMost(MAX_THUMBNAIL_SIZE)
            },
            getColor = { isSystemInDarkMode ->
                if (isSystemInDarkMode) Color.BLACK else Color.WHITE
            },
            context = this
        )

        cache = createCache(this)
        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                /* audioAttributes = */
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */
                PlayerPreferences.handleAudioFocus
            )
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                addListener(this@PlayerService)
                addAnalyticsListener(
                    PlaybackStatsListener(
                        /* keepHistory = */
                        false,
                        /* callback = */
                        this@PlayerService
                    )
                )
            }

        updateRepeatMode()
        maybeRestorePlayerQueue()

        mediaSession = MediaSession(baseContext, TAG).apply {
            setCallback(SessionCallback())
            setPlaybackState(stateBuilder.build())
            setSessionActivity(activityPendingIntent<MainActivity>())
            isActive = true
        }

        coroutineScope.launch {
            var first = true
            combine(mediaItemState, isLikedState) { mediaItem, _ ->
                // work around NPE in other processes
                if (first) {
                    first = false
                    return@combine
                }

                if (mediaItem == null) return@combine
                withContext(Dispatchers.Main) {
                    updatePlaybackState()
                    updateNotification()
                }
            }.collect()
        }

        maybeResumePlaybackWhenDeviceConnected()

        preferenceUpdaterJob = coroutineScope.launch {
            fun <T : Any> subscribe(
                prop: SharedPreferencesProperty<T>,
                callback: (T) -> Unit
            ) = launch { prop.stateFlow.collectLatest { handler.post { callback(it) } } }

            subscribe(PlayerPreferences.queueLoopEnabledProperty) { updateRepeatMode() }
            subscribe(PlayerPreferences.resumePlaybackWhenDeviceConnectedProperty) {
                maybeResumePlaybackWhenDeviceConnected()
            }
            subscribe(PlayerPreferences.speedProperty) {
                player.setPlaybackSpeed(it.coerceAtLeast(0.01f))
            }
            subscribe(PlayerPreferences.trackLoopEnabledProperty) {
                updateRepeatMode()
                updateNotification()
            }
            subscribe(PlayerPreferences.volumeNormalizationBaseGainProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.volumeNormalizationProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.sponsorBlockEnabledProperty) { maybeSponsorBlock() }
            subscribe(PlayerPreferences.handleAudioFocusProperty) {
                player.setAudioAttributes(player.audioAttributes, it)
            }
        }
    }

    private fun updateRepeatMode() {
        player.repeatMode = when {
            PlayerPreferences.trackLoopEnabled -> Player.REPEAT_MODE_ONE
            PlayerPreferences.queueLoopEnabled -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.shouldBePlaying || PlayerPreferences.stopWhenClosed) {
            broadcastPendingIntent<NotificationDismissReceiver>().send()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        maybeSavePlayerQueue()

    override fun onDestroy() {
        runCatching {
            maybeSavePlayerQueue()

            player.removeListener(this)
            player.stop()
            player.release()

            unregisterReceiver(notificationActionReceiver)

            mediaSession.isActive = false
            mediaSession.release()
            cache.release()

            loudnessEnhancer?.release()
            preferenceUpdaterJob?.cancel()

            coroutineScope.cancel()
        }

        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        handler.post {
            if (!bitmapProvider.setDefaultBitmap() || player.currentMediaItem == null) return@post
            updateNotification()
        }

        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val totalPlayTimeMs = playbackStats.totalPlayTimeMs
        if (totalPlayTimeMs < 5000) return

        val mediaItem = eventTime.timeline[eventTime.windowIndex].mediaItem

        if (!DataPreferences.pausePlaytime) {
            query {
                runCatching {
                    Database.incrementTotalPlayTimeMs(mediaItem.mediaId, totalPlayTimeMs)
                }
            }
        }

        if (!DataPreferences.pauseHistory) {
            query {
                runCatching {
                    Database.insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = System.currentTimeMillis(),
                            playTime = totalPlayTimeMs
                        )
                    )
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (
            AppearancePreferences.hideExplicit &&
            mediaItem?.mediaMetadata?.extras?.songBundle?.explicit == true
        ) {
            player.forceSeekToNext()
            return
        }

        mediaItemState.update { mediaItem }

        maybeRecoverPlaybackError()
        maybeNormalizeVolume()
        maybeProcessRadio()

        with(bitmapProvider) {
            when {
                mediaItem == null -> load(null)
                mediaItem.mediaMetadata.artworkUri == lastUri -> bitmapProvider.load(lastUri)
            }
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            updateMediaSessionQueue(player.currentTimeline)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        updateMediaSessionQueue(timeline)
        maybeSavePlayerQueue()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        if (
            error.findCause<InvalidResponseCodeException>()?.responseCode == 416
        ) {
            player.pause()
            player.prepare()
            player.play()
            return
        }

        // Skipping stops where it would go round in circles: a queue of one track (with repeat,
        // "next" is the same track) or a run of failures, e.g. without network (REWRITE §3.10.9).
        // The player then stays on the error card
        if (!player.hasNextMediaItem() || player.mediaItemCount < 2 || failedInARow >= MAX_FAILED_IN_A_ROW) {
            failedInARow = 0
            return
        }
        failedInARow++

        val prev = player.currentMediaItem ?: return
        player.seekToNextMediaItem()

        ServiceNotifications.autoSkip.sendNotification(this) {
            this
                .setSmallIcon(R.drawable.app_icon)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOnlyAlertOnce(false)
                .setContentIntent(activityPendingIntent<MainActivity>())
                .setContentText(
                    prev.mediaMetadata.title?.let {
                        getString(R.string.skip_on_error_notification, it)
                    } ?: getString(R.string.skip_on_error_notification_unknown_song)
                )
                .setContentTitle(getString(R.string.skip_on_error))
        }
    }

    private fun updateMediaSessionQueue(timeline: Timeline) = mediaSession.setQueue(
        List(timeline.windowCount) { index ->
            val mediaItem = timeline.getWindow(index, Timeline.Window()).mediaItem
            MediaSession.QueueItem(
                MediaDescription.Builder()
                    .setMediaId(mediaItem.mediaId)
                    .setTitle(mediaItem.mediaMetadata.title)
                    .setSubtitle(mediaItem.mediaMetadata.artist)
                    .setIconUri(mediaItem.mediaMetadata.artworkUri)
                    .build(),
                index.toLong()
            )
        }
    )

    private fun maybeRecoverPlaybackError() {
        if (player.playerError != null) player.prepare()
    }

    private fun maybeProcessRadio() {
        if (player.mediaItemCount - player.currentMediaItemIndex > 3) return

        radio?.let { radio ->
            coroutineScope.launch(Dispatchers.Main) {
                player.addMediaItems(radio.process())
            }
        }
    }

    private fun maybeSavePlayerQueue() {
        val mediaItems = player.currentTimeline.mediaItems
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItemPosition = player.currentPosition

        transaction {
            runCatching {
                Database.clearQueue()
                Database.insert(
                    mediaItems.mapIndexed { index, mediaItem ->
                        QueuedMediaItem(
                            mediaItem = mediaItem,
                            position = if (index == mediaItemIndex) mediaItemPosition else null
                        )
                    }
                )
            }
        }
    }

    private fun maybeRestorePlayerQueue() {
        transaction {
            val queue = Database.queue()
            if (queue.isEmpty()) return@transaction
            Database.clearQueue()

            val index = queue
                .indexOfFirst { it.position != null }
                .coerceAtLeast(0)

            handler.post {
                runCatching {
                    player.setMediaItems(
                        /* mediaItems = */
                        queue.map { item ->
                            item.mediaItem.buildUpon()
                                .setUri(item.mediaItem.mediaId)
                                .setCustomCacheKey(item.mediaItem.mediaId)
                                .build()
                        },
                        /* startIndex = */
                        index,
                        /* startPositionMs = */
                        queue[index].position ?: C.TIME_UNSET
                    )
                    player.prepare()

                    isNotificationStarted = true
                    startForegroundService(this@PlayerService, intent<PlayerService>())
                    startForeground()
                }
            }
        }
    }

    private fun maybeNormalizeVolume() {
        if (!PlayerPreferences.volumeNormalization) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob?.invokeOnCompletion { volumeNormalizationJob = null }
            player.volume = 1f
            return
        }

        runCatching {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }.onFailure { return }

        val songId = player.currentMediaItem?.mediaId ?: return
        volumeNormalizationJob?.cancel()
        volumeNormalizationJob = coroutineScope.launch {
            runCatching {
                fun Float?.toMb() = ((this ?: 0f) * 100).toInt()

                Database.loudnessDb(songId).cancellable().collectLatest { loudness ->
                    val loudnessMb = loudness.toMb().let {
                        if (it !in -2000..2000) {
                            withContext(Dispatchers.Main) {
                                toast(
                                    getString(
                                        R.string.loudness_normalization_extreme,
                                        getString(R.string.format_db, (it / 100f).toString())
                                    )
                                )
                            }

                            0
                        } else {
                            it
                        }
                    }

                    withContext(Dispatchers.Main) {
                        loudnessEnhancer?.setTargetGain(
                            PlayerPreferences.volumeNormalizationBaseGain.toMb() - loudnessMb
                        )
                        loudnessEnhancer?.enabled = true
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // TODO: evaluate CyclomaticComplexMethod threshold
    private fun maybeSponsorBlock() {
        poiTimestamp = null

        if (!PlayerPreferences.sponsorBlockEnabled) {
            sponsorBlockJob?.cancel()
            sponsorBlockJob?.invokeOnCompletion { sponsorBlockJob = null }
            return
        }

        sponsorBlockJob?.cancel()
        sponsorBlockJob = coroutineScope.launch {
            mediaItemState.onStart { emit(mediaItemState.value) }.collectLatest { mediaItem ->
                poiTimestamp = null
                val videoId = mediaItem?.mediaId
                    ?.removePrefix("https://youtube.com/watch?v=")
                    ?.takeIf { it.isNotBlank() } ?: return@collectLatest

                SponsorBlock
                    .segments(videoId)
                    ?.onSuccess { segments ->
                        poiTimestamp =
                            segments.find { it.category == Category.PoiHighlight }?.start?.inWholeMilliseconds
                    }
                    ?.map { segments ->
                        segments
                            .sortedBy { it.start.inWholeMilliseconds }
                            .filter { it.action == Action.Skip }
                    }
                    ?.mapCatching { segments ->
                        suspend fun posMillis() =
                            withContext(Dispatchers.Main) { player.currentPosition }

                        suspend fun speed() =
                            withContext(Dispatchers.Main) { player.playbackParameters.speed }

                        suspend fun seek(millis: Long) =
                            withContext(Dispatchers.Main) { player.seekTo(millis) }

                        val ctx = currentCoroutineContext()
                        val lastSegmentEnd =
                            segments.lastOrNull()?.end?.inWholeMilliseconds ?: return@mapCatching

                        @Suppress("LoopWithTooManyJumpStatements")
                        do {
                            if (lastSegmentEnd < posMillis()) {
                                yield()
                                continue
                            }

                            val nextSegment =
                                segments.firstOrNull { posMillis() < it.end.inWholeMilliseconds }
                                    ?: continue

                            // Wait for next segment
                            if (nextSegment.start.inWholeMilliseconds > posMillis()) {
                                val timeNextSegment =
                                    nextSegment.start.inWholeMilliseconds - posMillis()
                                val speed = speed().toDouble()
                                delay((timeNextSegment / speed).milliseconds)
                            }

                            if (posMillis().milliseconds !in nextSegment.start..nextSegment.end) {
                                // Player is not in the segment for some reason, maybe the user seeked in the meantime
                                yield()
                                continue
                            }

                            seek(nextSegment.end.inWholeMilliseconds)
                        } while (ctx.isActive)
                    }?.onFailure {
                        it.printStackTrace()
                    }
            }
        }
    }

    private fun maybeShowSongCoverInLockScreen() = handler.post {
        val bitmap = bitmapProvider.bitmap
        val uri = player.mediaMetadata.artworkUri?.toString()?.thumbnail(512)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ART_URI, uri)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri)

        if (isAtLeastAndroid13 && player.currentMediaItemIndex == 0) {
            metadataBuilder.putText(
                MediaMetadata.METADATA_KEY_TITLE,
                "${player.mediaMetadata.title} "
            )
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun maybeResumePlaybackWhenDeviceConnected() {
        if (!isAtLeastAndroid6) return

        if (!PlayerPreferences.resumePlaybackWhenDeviceConnected) {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
            return
        }
        if (audioManager == null) audioManager = getSystemService<AudioManager>()

        audioDeviceCallback = object : AudioDeviceCallback() {
            private fun canPlayMusic(audioDeviceInfo: AudioDeviceInfo) =
                audioDeviceInfo.isSink && (
                    audioDeviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    )
                    .let {
                        if (!isAtLeastAndroid8) {
                            it
                        } else {
                            it || audioDeviceInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                    }

            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (!player.isPlaying && addedDevices.any(::canPlayMusic)) player.play()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = Unit
        }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    private fun openEqualizer() =
        EqualizerIntentBundleAccessor.sendOpenEqualizer(player.audioSessionId)

    private fun closeEqualizer() =
        EqualizerIntentBundleAccessor.sendCloseEqualizer(player.audioSessionId)

    private fun updatePlaybackState() = coroutineScope.launch {
        playbackStateMutex.withLock {
            withContext(Dispatchers.Main) {
                mediaSession.setPlaybackState(
                    stateBuilder
                        .setActiveQueueItemId(player.currentMediaItemIndex.toLong())
                        .setState(
                            player.androidPlaybackState,
                            player.currentPosition,
                            player.playbackParameters.speed,
                            SystemClock.elapsedRealtime()
                        )
                        .setBufferedPosition(player.bufferedPosition)
                        .build()
                )
            }
        }
    }

    private val Player.androidPlaybackState
        get() = when (playbackState) {
            Player.STATE_BUFFERING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_NONE
        }

    // legacy behavior may cause inconsistencies, but not available on sdk 24 or lower
    @Suppress("DEPRECATION")
    override fun onEvents(player: Player, events: Player.Events) {
        if (player.playbackState == Player.STATE_READY) failedInARow = 0

        if (player.duration != C.TIME_UNSET) {
            mediaSession.setMetadata(
                metadataBuilder
                    .putText(
                        MediaMetadata.METADATA_KEY_TITLE,
                        player.mediaMetadata.title?.toString().orEmpty()
                    )
                    .putText(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        player.mediaMetadata.artist?.toString().orEmpty()
                    )
                    .putText(
                        MediaMetadata.METADATA_KEY_ALBUM,
                        player.mediaMetadata.albumTitle?.toString().orEmpty()
                    )
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                    .build()
            )
        }

        updatePlaybackState()

        if (
            !events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_IS_LOADING_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED
            )
        ) {
            return
        }

        val notification = notification()

        if (notification == null) {
            isNotificationStarted = false
            stopForeground(false)
            closeEqualizer()
            ServiceNotifications.default.cancel(this)
            return
        }

        if (player.shouldBePlaying && !isNotificationStarted) {
            isNotificationStarted = true
            startForegroundService(this@PlayerService, intent<PlayerService>())
            startForeground()
            openEqualizer()
        } else {
            if (!player.shouldBePlaying) {
                isNotificationStarted = false
                stopForeground(false)
                closeEqualizer()
            }
            updateNotification()
        }
    }

    private fun notification(): (NotificationCompat.Builder.() -> NotificationCompat.Builder)? {
        if (player.currentMediaItem == null) return null

        val mediaMetadata = player.mediaMetadata

        bitmapProvider.load(mediaMetadata.artworkUri) {
            maybeShowSongCoverInLockScreen()
            updateNotification()
        }

        return {
            @Suppress("DEPRECATION")
            this
                .setContentTitle(mediaMetadata.title?.toString().orEmpty())
                .setContentText(mediaMetadata.artist?.toString().orEmpty())
                .setSubText(player.playerError?.message)
                .setLargeIcon(bitmapProvider.bitmap)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSmallIcon(
                    player.playerError?.let { R.drawable.alert_circle } ?: R.drawable.app_icon
                )
                .setOngoing(false)
                .setContentIntent(
                    activityPendingIntent<MainActivity>(flags = PendingIntent.FLAG_UPDATE_CURRENT)
                )
                .setDeleteIntent(broadcastPendingIntent<NotificationDismissReceiver>())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(
                    R.drawable.play_skip_back,
                    getString(R.string.skip_back),
                    notificationActionReceiver.previous.pendingIntent
                )
                .let {
                    if (player.shouldBePlaying) {
                        it.addAction(
                            R.drawable.pause,
                            getString(R.string.pause),
                            notificationActionReceiver.pause.pendingIntent
                        )
                    } else {
                        it.addAction(
                            R.drawable.play,
                            getString(R.string.play),
                            notificationActionReceiver.play.pendingIntent
                        )
                    }
                }
                .addAction(
                    R.drawable.play_skip_forward,
                    getString(R.string.skip_forward),
                    notificationActionReceiver.next.pendingIntent
                )
                .addAction(
                    if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline,
                    getString(R.string.like),
                    notificationActionReceiver.like.pendingIntent
                )
                .addAction(
                    if (PlayerPreferences.trackLoopEnabled) R.drawable.repeat_on else R.drawable.repeat,
                    getString(R.string.queue_loop),
                    notificationActionReceiver.loop.pendingIntent
                )
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .setMediaSession(MediaSessionCompat.Token.fromToken(mediaSession.sessionToken))
                )
        }
    }

    private fun updateNotification() = runCatching {
        handler.post {
            notification()?.let { ServiceNotifications.default.sendNotification(this, it) }
        }
    }

    /**
     * Should strictly be called on the main thread!
     */
    private fun startForeground() {
        notification()
            ?.let { ServiceNotifications.default.startForeground(this, it) }
    }

    private fun createMediaSourceFactory() = DefaultMediaSourceFactory(
        /* dataSourceFactory = */
        // Downloads first, read-only: a downloaded track plays without a network (REWRITE §4.7.2)
        CacheDataSource.Factory()
            .setCache((application as MainApplication).container.downloads.cache)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setUpstreamDataSourceFactory(
                createYouTubeDataSourceResolverFactory(
                    findMediaItem = { videoId ->
                        withContext(Dispatchers.Main) {
                            player.findNextMediaItemById(videoId)
                        }
                    },
                    context = applicationContext,
                    cache = cache
                )
            ),
        /* extractorsFactory = */
        DefaultExtractorsFactory()
    ).setLoadErrorHandlingPolicy(
        object : DefaultLoadErrorHandlingPolicy() {
            override fun isEligibleForFallback(exception: IOException) = true
        }
    )

    /** How loud what plays now is, for the "now playing" bars. */
    private val audioLevels = AudioLevels()

    private fun createRendersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder(applicationContext)
                // The "now playing" bars listen here (AudioLevels)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(audioLevels)))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioOffloadSupportProvider(
                    DefaultAudioOffloadSupportProvider(applicationContext)
                )
                .build()
                .apply {
                    if (isAtLeastAndroid10) setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
        }
    }

    @Stable
    inner class Binder : AndroidBinder() {
        val player: ExoPlayer
            get() = this@PlayerService.player

        val cache: Cache
            get() = this@PlayerService.cache

        val mediaSession
            get() = this@PlayerService.mediaSession

        val sleepTimerMillisLeft: StateFlow<Long?>?
            get() = timerJob?.millisLeft

        /** The loudness of the bands of what plays now (the "now playing" bars). */
        val audioLevels: AudioLevels
            get() = this@PlayerService.audioLevels

        private var radioJob: Job? = null

        var isLoadingRadio by mutableStateOf(false)
            private set

        val poiTimestamp get() = this@PlayerService.poiTimestamp

        fun setBitmapListener(listener: ((Bitmap?) -> Unit)?) = bitmapProvider.setListener(listener)

        fun startSleepTimer(delayMillis: Long) {
            timerJob?.cancel()

            timerJob = coroutineScope.timer(delayMillis) {
                ServiceNotifications.sleepTimer.sendNotification(this@PlayerService) {
                    this
                        .setContentTitle(getString(R.string.sleep_timer_ended))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(true)
                        .setSmallIcon(R.drawable.app_icon)
                }

                handler.post {
                    player.pause()
                    player.stop()
                }
            }
        }

        fun cancelSleepTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        fun setupRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = true)

        fun playRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = false)

        private fun startRadio(endpoint: NavigationEndpoint.Endpoint.Watch?, justAdd: Boolean) {
            radioJob?.cancel()
            radio = null

            YouTubeRadio(
                endpoint?.videoId,
                endpoint?.playlistId,
                endpoint?.playlistSetVideoId,
                endpoint?.params
            ).let { radioData ->
                isLoadingRadio = true
                radioJob = coroutineScope.launch {
                    val hiding = emptySet<String>().applyingHidden(pendingMutations.pending.value)
                    val items = radioData.process()
                        .let { Database.filterBlacklistedSongs(it) }
                        .filter { it.mediaId !in hiding }

                    withContext(Dispatchers.Main) {
                        if (justAdd) {
                            player.addMediaItems(items.drop(1))
                        } else {
                            player.forcePlayFromBeginning(items)
                        }
                    }

                    radio = radioData
                    isLoadingRadio = false
                }
            }
        }

        fun stopRadio() {
            isLoadingRadio = false
            radioJob?.cancel()
            radio = null
        }


        fun playFromSearch(query: String) {
            coroutineScope.launch {
                Innertube.searchPage(
                    body = SearchBody(
                        query = query,
                        params = Innertube.SearchFilter.Song.value
                    ),
                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                )
                    ?.getOrNull()
                    ?.items
                    ?.firstOrNull()
                    ?.info
                    ?.endpoint
                    ?.let { playRadio(it) }
            }
        }
    }

    private fun likeAction() = mediaItemState.value?.let { mediaItem ->
        query {
            runCatching {
                Database.like(
                    songId = mediaItem.mediaId,
                    likedAt = if (isLikedState.value) null else System.currentTimeMillis()
                )
            }
        }
    }.let { }

    private fun loopAction() {
        PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = player.play()
        override fun onPause() = player.pause()
        override fun onSkipToPrevious() = runCatching(player::forceSeekToPrevious).let { }
        override fun onSkipToNext() = runCatching(player::forceSeekToNext).let { }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)
        override fun onStop() = player.pause()
        override fun onRewind() = player.seekToDefaultPosition()
        override fun onSkipToQueueItem(id: Long) =
            runCatching { player.seekToDefaultPosition(id.toInt()) }.let { }

        override fun onSetPlaybackSpeed(speed: Float) {
            PlayerPreferences.speed = speed.coerceIn(0.01f..2f)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) return
            binder.playFromSearch(query)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            when (action) {
                LIKE_ACTION -> likeAction()
                LOOP_ACTION -> loopAction()
            }
        }
    }

    inner class NotificationActionReceiver internal constructor() :
        ActionReceiver("app.melogold.android") {
        val pause by action { _, _ ->
            player.pause()
        }
        val play by action { _, _ ->
            player.play()
        }
        val next by action { _, _ ->
            player.forceSeekToNext()
        }
        val previous by action { _, _ ->
            player.forceSeekToPrevious()
        }
        val like by action { _, _ ->
            likeAction()
        }
        val loop by action { _, _ ->
            loopAction()
        }
    }

    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = with(context) {
            stopService(intent<PlayerService>())
            Unit
        }
    }

    companion object {
        private const val DEFAULT_CACHE_DIRECTORY = "exoplayer"
        private const val MAX_FAILED_IN_A_ROW = 3
        private const val DEFAULT_CHUNK_LENGTH = 512 * 1024L

        fun createDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)
        fun createCache(
            context: Context,
            directoryName: String = DEFAULT_CACHE_DIRECTORY,
            size: ExoPlayerDiskCacheSize = DataPreferences.exoPlayerDiskCacheMaxSize
        ) = with(context) {
            val cacheEvictor = when (size) {
                ExoPlayerDiskCacheSize.Unlimited -> NoOpCacheEvictor()
                else -> LeastRecentlyUsedCacheEvictor(size.bytes)
            }

            val directory = cacheDir.resolve(directoryName).apply {
                if (!exists()) mkdir()
            }

            SimpleCache(directory, cacheEvictor, createDatabaseProvider(context))
        }

        /**
         * Resolves a video id into its stream. With [cache], what it reads is cached there; without
         * one (downloads, which have a cache of their own) it reads the network only.
         */
        @Suppress("CyclomaticComplexMethod")
        fun createYouTubeDataSourceResolverFactory(
            context: Context,
            cache: Cache?,
            chunkLength: Long? = DEFAULT_CHUNK_LENGTH,
            findMediaItem: suspend (videoId: String) -> MediaItem? = { null },
            uriCache: UriCache<String, Long?> = UriCache()
        ): DataSource.Factory = ResolvingDataSource.Factory(
            cache?.let {
                ConditionalCacheDataSourceFactory(
                    cacheDataSourceFactory = it.asDataSource,
                    upstreamDataSourceFactory = context.defaultDataSource,
                    shouldCache = { spec -> !spec.isLocal }
                )
            } ?: ChunkedDataSource.Factory(context.defaultDataSource)
        ) { dataSpec ->
            val mediaId = dataSpec.key?.removePrefix("https://youtube.com/watch?v=")
                ?: error("A key must be set")

            fun DataSpec.ranged(contentLength: Long?) = contentLength?.let {
                if (chunkLength == null) return@let null

                val start = dataSpec.uriPositionOffset
                val length = (contentLength - start).coerceAtMost(chunkLength)
                val rangeText = "$start-${start + length}"

                this.subrange(start, length)
                    .withAdditionalHeaders(mapOf("Range" to "bytes=$rangeText"))
            } ?: this

            if (
                dataSpec.isLocal || (
                    chunkLength != null && cache != null && cache.isCached(
                        /* key = */
                        mediaId,
                        /* position = */
                        dataSpec.position,
                        /* length = */
                        chunkLength
                    )
                    )
            ) {
                // Only the cached chunk: past it the cache would ask the network for the video id
                // itself, which is no address (a local file of that name, "open failed: ENOENT")
                if (dataSpec.isLocal || chunkLength == null) dataSpec
                else dataSpec.subrange(
                    0,
                    if (dataSpec.length == C.LENGTH_UNSET.toLong()) chunkLength else minOf(dataSpec.length, chunkLength)
                )
            } else {
                uriCache[mediaId]?.takeUnless { it.uri.isExpiring() }?.let { cachedUri ->
                    dataSpec
                        .withUri(cachedUri.uri)
                        .ranged(cachedUri.meta)
                } ?: run {
                    val body = runBlocking(Dispatchers.IO) {
                        Innertube.player(PlayerBody(videoId = mediaId))
                    }?.getOrNull()
                    val youtubeFormat = body?.streamingData?.highestQualityFormat

                    val info = runCatching {
                        Dependencies.runDownload(mediaId)
                    }.mapCatching {
                        YouTubeDLResponse.fromString(it)
                    }.also { it.exceptionOrNull()?.printStackTrace() }.getOrNull()
                    if (info?.id != mediaId) throw VideoIdMismatchException()
                    val format = info.formats?.firstOrNull { it.formatId == info.formatId }

                    val uri =
                        runCatching { info.url?.toUri() }.getOrNull() ?: throw UnplayableException()

                    val mediaItem = runCatching {
                        runBlocking(Dispatchers.IO) { findMediaItem(mediaId) }
                    }.getOrNull()

                    val extras = mediaItem?.mediaMetadata?.extras?.songBundle
                    if (extras?.durationText == null) {
                        body
                            ?.streamingData
                            ?.highestQualityFormat
                            ?.approxDurationMs
                            ?.div(1000)
                            ?.let(DateUtils::formatElapsedTime)
                            ?.removePrefix("0")
                            ?.let { durationText ->
                                extras?.durationText = durationText
                                Database.updateDurationText(mediaId, durationText)
                            }
                    }

                    transaction {
                        runCatching {
                            mediaItem?.let(Database::insert)
                            Database.insert(
                                Format(
                                    songId = mediaId,
                                    itag = info.formatId?.toIntOrNull(),
                                    mimeType = youtubeFormat?.mimeType,
                                    bitrate = format?.abr?.let { it * 1000 }?.toLong(),
                                    loudnessDb = body?.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                    contentLength = info.fileSize,
                                    lastModified = youtubeFormat?.lastModified
                                )
                            )
                        }
                    }

                    uriCache.push(
                        key = mediaId,
                        meta = info.fileSize,
                        uri = uri
                    )

                    dataSpec
                        .withUri(uri)
                        .ranged(info.fileSize)
                }
            }
        }.handleUnknownErrors {
            // The next attempt resolves the address again (a 403: expired, or another network)
            uriCache.clear()
        }
    }
}

/** A stream address of YouTube that expires in less than 5 minutes (`expire`, epoch seconds). */
private fun Uri.isExpiring(now: Long = System.currentTimeMillis()): Boolean =
    getQueryParameter("expire")?.toLongOrNull()?.let { it * 1000 - 5 * 60_000 < now } == true
