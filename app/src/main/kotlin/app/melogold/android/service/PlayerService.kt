package app.melogold.android.service

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Binder as AndroidBinder
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
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
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import app.melogold.android.Database
import app.melogold.android.Dependencies
import app.melogold.android.MainActivity
import app.melogold.android.MainApplication
import app.melogold.android.R
import app.melogold.android.data.downloads.ChunkedDataSource
import app.melogold.android.data.repo.applyingHidden
import app.melogold.android.data.repo.pendingMutations
import app.melogold.android.data.lyrics.LyricsPins
import app.melogold.android.data.lyrics.PIN_AFTER_MS
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.data.overrides.withOverride
import app.melogold.android.models.TrackOverride
import app.melogold.android.models.Event
import app.melogold.android.models.Format
import app.melogold.android.models.QueuedMediaItem
import app.melogold.android.models.Song
import app.melogold.android.playback.session.VoiceQueryResolver
import app.melogold.android.preferences.AppearancePreferences
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.transaction
import app.melogold.android.utils.ConditionalCacheDataSourceFactory
import app.melogold.android.utils.MAX_THUMBNAIL_SIZE
import app.melogold.android.utils.TimerJob
import app.melogold.android.utils.YouTubeDLResponse
import app.melogold.android.utils.YouTubeRadio
import app.melogold.android.utils.activityPendingIntent
import app.melogold.android.utils.asDataSource
import app.melogold.android.utils.defaultDataSource
import app.melogold.android.utils.findCause
import app.melogold.android.utils.findNextMediaItemById
import app.melogold.android.utils.forcePlayFromBeginning
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.get
import app.melogold.android.utils.handleUnknownErrors
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
import app.melogold.core.ui.utils.isAtLeastAndroid17
import app.melogold.core.ui.utils.isAtLeastAndroid6
import app.melogold.core.ui.utils.isAtLeastAndroid8
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.requests.playability
import app.melogold.providers.sponsorblock.SponsorBlock
import app.melogold.providers.sponsorblock.models.Action
import app.melogold.providers.sponsorblock.models.Category
import app.melogold.providers.sponsorblock.requests.segments
import java.util.UUID
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

const val LOCAL_KEY_PREFIX = "local:"
private const val TAG = "PlayerService"

@get:OptIn(UnstableApi::class)
val DataSpec.isLocal get() = key?.startsWith(LOCAL_KEY_PREFIX) == true

val MediaItem.isLocal get() = mediaId.startsWith(LOCAL_KEY_PREFIX)
val Song.isLocal get() = id.startsWith(LOCAL_KEY_PREFIX)

/** ♡ and the track loop: the buttons of the notification and of the system media controls next to the transport ones. */
private val LIKE_COMMAND = SessionCommand("app.melogold.android.LIKE", Bundle.EMPTY)
private val LOOP_COMMAND = SessionCommand("app.melogold.android.LOOP", Bundle.EMPTY)

/** The cover of the notification and of the lock screen, dp. */
private const val ARTWORK_SIZE = 512

/**
 * The player of the app: ExoPlayer and its Media3 [MediaSession] in a [MediaSessionService] (30.10.2026). The service
 * keeps the `mediaPlayback` foreground service, the media notification and the session of the system in step with
 * the player: in the foreground while it plays and for 10 minutes after a pause, started while the app is visible.
 * Android 17 silences the audio of an app in the background that lacks it (background audio hardening): the
 * hand-made foreground of the service inherited from ViMusic was not enough on One UI 9 — songs longer than three
 * minutes went silent (tasks/0034).
 *
 * The app's screens bind with an intent without an action and get [Binder]; media controllers (the notification, the
 * system, Android Auto through [PlayerMediaBrowserService]) talk to [mediaSession].
 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // intended in this class: it is a service
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService(), Player.Listener, PlaybackStatsListener.Callback {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var mediaSession: MediaSession
    private lateinit var cache: Cache
    private lateinit var player: ExoPlayer

    /** This player for the other devices of the account: what it plays, and what they ask of it (tasks/0018). */
    private var remote: PlayerRemote? = null

    private var timerJob: TimerJob? by mutableStateOf(null)
    private var radio: YouTubeRadio? = null

    /** Tracks skipped in a row because they failed; back to 0 once one plays (REWRITE §3.10.9). */
    private var failedInARow = 0

    /** tasks/0025: a track that failed for the network waits for it instead of being skipped. */
    private val networkWait by lazy { NetworkWait(handler) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var preferenceUpdaterJob: Job? = null
    private var volumeNormalizationJob: Job? = null
    private var sponsorBlockJob: Job? = null

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val binder = Binder()

    /** The equalizer of the system was told the audio session is open (an app like Wavelet attaches to it). */
    private var equalizerOpen = false

    /**
     * The system refused the foreground: playback was started while the app is in the background (an agent such as
     * Gemini, a command from another device after a long pause; Android 12+). Logged once until a new queue, a new
     * play or the app coming to the front.
     */
    @Volatile
    private var foregroundRefused = false

    /**
     * Paused because Android 17 refused the foreground in the background and would have played on in silence
     * ([pauseForRefusedForeground]): the music goes on when the app comes to the front.
     */
    private var resumeWhenVisible = false

    private val isAppVisible: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * The app came to the front: a foreground the system refused before is allowed now. The music paused for it
     * goes on; music that played on without it (before Android 17) gets it now — the notification is updated, and
     * Media3 asks for the foreground again.
     */
    private val appVisibility = LifecycleEventObserver { _, event ->
        if (event != Lifecycle.Event.ON_START || !foregroundRefused) return@LifecycleEventObserver
        foregroundRefused = false
        if (resumeWhenVisible) {
            resumeWhenVisible = false
            ServiceNotifications.backgroundPause.cancel(this)
            player.play()
        } else if (player.shouldBePlaying) {
            updateMediaButtons()
        }
    }

    /**
     * Done once the queue saved at the last stop is back in the player, or there was none: a command that sets a
     * queue waits for it, or the saved queue would replace it ([Binder.awaitQueueRestored]).
     */
    private val queueRestored = CompletableDeferred<Unit>()

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
            updateMediaButtons()
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private var poiTimestamp: Long? by mutableStateOf(null)

    /** The app's screens (an intent without an action) get [Binder]; media controllers get the session. */
    override fun onBind(intent: Intent?): IBinder? {
        val session = super.onBind(intent)
        return if (intent?.action == null) binder else session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()

        // The network is back (or the VPN reconnected): a track waiting for it is tried now (tasks/0025)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { networkWait.onNetworkAvailable(::retryAfterNetwork) }
            }
        }.also { callback ->
            runCatching { getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "The network callback is not registered: ${it.message}") }
        }

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

        // One cache for the app: downloads copy from it and screens count what it holds whole
        cache = Dependencies.application.container.playerCache
        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setHandleAudioBecomingNoisy(true)
            // Сетевой режим: кроме процессора держит и Wi‑Fi — с погашенным экраном Wi‑Fi не засыпает посреди трека
            // (иначе буфер кончается, а плеер «играет» без звука; поток идёт кусками по 512 КБ)
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibility)
        remote = PlayerRemote(this, player, stopRadio = { binder.stopRadio() })

        mediaSession = MediaSession.Builder(this, SessionPlayer(player))
            .setId(TAG)
            .setCallback(SessionCallback())
            .setSessionActivity(activityPendingIntent<MainActivity>())
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(this, DataSourceBitmapLoader(this))))
            .setMediaButtonPreferences(mediaButtons())
            .build()
        // The channel and the id of the notification it had before: the person's settings of it stay
        ServiceNotifications.default.upsertChannel(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(ServiceNotifications.default.id)
                .setChannelName(R.string.now_playing)
                .setNotificationId(checkNotNull(ServiceNotifications.default.notificationId))
                .build()
                .apply { setSmallIcon(R.drawable.app_icon) }
        )
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() = refuseForeground()
        })
        // The screens play through the binder, not a controller: the service is told of the session itself
        addSession(mediaSession)

        maybeResumePlaybackWhenDeviceConnected()

        // An override changed, here or on another device: the queue and the notification show it (tasks/0012)
        coroutineScope.launch {
            TrackOverrides.all.collect { overrides ->
                withContext(Dispatchers.Main) { applyOverrides(overrides) }
            }
        }

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
                updateMediaButtons()
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

    /**
     * The app swiped away from the recent apps: the music goes on only while it plays and «Stop when closed» is off.
     * Out of the foreground the service must stop (MediaSessionService), or the system crashes and restarts it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing() || !player.shouldBePlaying || PlayerPreferences.stopWhenClosed) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // A new play may ask for the foreground again
        if (playWhenReady) {
            foregroundRefused = false
            openEqualizer()
            if (resumeWhenVisible) {
                resumeWhenVisible = false
                ServiceNotifications.backgroundPause.cancel(this)
            }
        }
        // Paused while waiting for the network: the user decided, no more retries
        if (!playWhenReady) networkWait.clear()
        maybeSavePlayerQueue()
    }

    override fun onDestroy() {
        runCatching {
            maybeSavePlayerQueue()

            networkWait.clear()
            networkCallback?.let { callback ->
                runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(callback) }
            }

            ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibility)
            remote?.release()
            closeEqualizer()
            mediaSession.release()
            player.removeListener(this)
            player.stop()
            player.release()

            loudnessEnhancer?.release()
            preferenceUpdaterJob?.cancel()

            coroutineScope.cancel()
        }

        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // The placeholder cover of the screens follows the dark theme
        handler.post { bitmapProvider.setDefaultBitmap() }

        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val totalPlayTimeMs = playbackStats.totalPlayTimeMs
        if (totalPlayTimeMs < 5000) return

        val mediaItem = eventTime.timeline[eventTime.windowIndex].mediaItem

        // Played long enough with lyrics found automatically: they are pinned for every device (tasks/0013)
        if (totalPlayTimeMs >= PIN_AFTER_MS) query { runCatching { LyricsPins.pinPlayed(mediaItem.mediaId) } }

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
                            playTime = totalPlayTimeMs,
                            syncId = UUID.randomUUID().toString()
                        )
                    )
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Another track: nothing waits for the network any more
        networkWait.clear()

        // A new queue may ask for the foreground again
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) foregroundRefused = false

        // The track that ended may be whole in the cache now: "available offline"
        Dependencies.application.container.cachedTracks.refresh()

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

        // The cover for the screens (Binder.setBitmapListener); the notification loads its own (ArtworkBitmapLoader)
        bitmapProvider.load(mediaItem?.mediaMetadata?.artworkUri)
        // ♡ of the notification is the new track's
        updateMediaButtons()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        maybeSavePlayerQueue()
    }

    /** The track waiting for the network is prepared again: ExoPlayer resumes from the position it kept. */
    private fun retryAfterNetwork() {
        if (player.currentMediaItem == null) return
        Log.i(TAG, "Retrying ${player.currentMediaItem?.mediaId} after the network wait")
        player.prepare()
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

        // No network: the track waits for it on the same position instead of being skipped (tasks/0025). The wait
        // ran out — the player stays on the error card, the queue does not move
        val waiting = player.currentMediaItem
        if (waiting != null && NetworkWait.isNetwork(error)) {
            if (!networkWait.onNetworkError(waiting.mediaId, ::retryAfterNetwork)) {
                Log.w(TAG, "No network for ${NetworkWait.LIMIT_MS / 60_000} min: stopping on ${waiting.mediaId}")
                failedInARow = 0
            } else {
                val seconds = player.currentPosition / 1000
                Log.i(TAG, "No network: ${waiting.mediaId} waits at $seconds s: ${error.message}")
            }
            return
        }

        // Skipping stops where it would go round in circles: a queue of one track (with repeat,
        // "next" is the same track), a run of failures, e.g. without network (REWRITE §3.10.9), or YouTube's bot
        // check, which fails every track alike. The player then stays on the error card
        if (
            !player.hasNextMediaItem() || player.mediaItemCount < 2 || failedInARow >= MAX_FAILED_IN_A_ROW ||
            error.findCause<BotCheckException>() != null
        ) {
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

    /** Lays [overrides] over the items of the queue whose names changed; playback goes on. */
    private fun applyOverrides(overrides: Map<String, TrackOverride>) {
        var current = false
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val updated = item.withOverride(overrides[item.mediaId])
            if (updated === item || updated.mediaMetadata == item.mediaMetadata) continue
            player.replaceMediaItem(index, updated)
            if (index == player.currentMediaItemIndex) current = true
        }
        // The same track, new names: the notification and the media session show them
        if (current) mediaItemState.update { player.currentMediaItem }
    }

    private fun maybeRestorePlayerQueue() {
        transaction {
            var posted = false
            try {
                val queue = Database.queue()
                if (queue.isEmpty()) return@transaction
                Database.clearQueue()
                // The overrides as they are now: some may have changed since the queue was saved
                val overrides = queue.associate { it.mediaItem.mediaId to Database.trackOverride(it.mediaItem.mediaId) }

                val index = queue
                    .indexOfFirst { it.position != null }
                    .coerceAtLeast(0)

                posted = handler.post {
                    try {
                        // A queue set meanwhile (an agent, a voice command, a tap) wins over the saved one
                        if (player.mediaItemCount == 0) runCatching {
                            player.setMediaItems(
                                /* mediaItems = */
                                queue.map { item ->
                                    item.mediaItem.buildUpon()
                                        .setUri(item.mediaItem.mediaId)
                                        .setCustomCacheKey(item.mediaItem.mediaId)
                                        .build()
                                        .withOverride(overrides[item.mediaItem.mediaId])
                                },
                                /* startIndex = */
                                index,
                                /* startPositionMs = */
                                queue[index].position ?: C.TIME_UNSET
                            )
                            player.prepare()
                        }
                    } finally {
                        queueRestored.complete(Unit)
                    }
                }
            } finally {
                if (!posted) queueRestored.complete(Unit)
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

    private fun openEqualizer() {
        if (equalizerOpen) return
        equalizerOpen = true
        EqualizerIntentBundleAccessor.sendOpenEqualizer(player.audioSessionId)
    }

    private fun closeEqualizer() {
        if (!equalizerOpen) return
        equalizerOpen = false
        EqualizerIntentBundleAccessor.sendCloseEqualizer(player.audioSessionId)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (player.playbackState == Player.STATE_READY) {
            failedInARow = 0
            networkWait.clear()
        }
        // Nothing to play any more: the equalizer of the system lets the audio session go
        if (player.currentMediaItem == null) closeEqualizer()
    }

    /**
     * ♡ and the track loop next to the transport buttons: in the notification and the system media controls; Media3
     * gives them to the session of the system as custom actions too.
     */
    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (isLikedState.value) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(getString(R.string.like))
            .setSessionCommand(LIKE_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            if (PlayerPreferences.trackLoopEnabled) CommandButton.ICON_REPEAT_ONE else CommandButton.ICON_REPEAT_OFF
        )
            .setDisplayName(getString(R.string.queue_loop))
            .setSessionCommand(LOOP_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    )

    private fun updateMediaButtons() = handler.post {
        if (::mediaSession.isInitialized) mediaSession.setMediaButtonPreferences(mediaButtons())
    }

    /** The system refused the foreground (Media3 asked from the background, Android 12+). */
    private fun refuseForeground() {
        if (!foregroundRefused) {
            Log.w(TAG, "The system refused the foreground service for ${player.currentMediaItem?.mediaId}")
        }
        foregroundRefused = true
        pauseForRefusedForeground()
    }

    /**
     * Android 17 silences the audio of an app in the background without a foreground service, with no error: the
     * track would go on in silence (27.10.2026, One UI 9). Instead the music pauses, a notification says why, and
     * it goes on when the app comes to the front (tapping the notification opens it).
     */
    private fun pauseForRefusedForeground() = handler.post {
        if (!isAtLeastAndroid17 || isAppVisible || !player.playWhenReady) return@post
        Log.w(
            TAG,
            "Android 17 would silence the background audio without a foreground: pausing ${player.currentMediaItem?.mediaId}"
        )
        resumeWhenVisible = true
        player.pause()
        ServiceNotifications.backgroundPause.sendNotification(this) {
            this
                .setSmallIcon(R.drawable.app_icon)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(activityPendingIntent<MainActivity>())
                .setContentTitle(getString(R.string.background_pause_title))
                .setContentText(getString(R.string.background_pause_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.background_pause_text)))
        }
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

    /** The 48 bands of what plays now, for the living backdrop of "Now playing" (tasks/0029). */
    private val spectrumTap = SpectrumTap()

    private fun createRendersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder(applicationContext)
                // The "now playing" bars (AudioLevels) and the living backdrop (SpectrumTap) listen here
                .setAudioProcessors(arrayOf(TeeAudioProcessor(audioLevels), TeeAudioProcessor(spectrumTap)))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioOffloadSupportProvider(
                    DefaultAudioOffloadSupportProvider(applicationContext)
                )
                .build()
                .apply {
                    if (isAtLeastAndroid10) setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
                // Tells the tap the time of every buffer and the position that is heard
                .let { SpectrumAudioSink(it, spectrumTap) }
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

        /** The 48 bands of what plays now (the living backdrop of "Now playing"). */
        val spectrumTap: SpectrumTap
            get() = this@PlayerService.spectrumTap

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
                    val items = visible(radioData.process())

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

        /**
         * Plays the radio of [endpoint] as [playRadio] does, but waits for its first tracks (a voice command
         * answers with what plays): the first one, or null when YouTube Music has none and the queue stays as it
         * was. Throws when YouTube fails.
         */
        suspend fun playRadioNow(endpoint: NavigationEndpoint.Endpoint.Watch): MediaItem? {
            val radioData = YouTubeRadio(
                endpoint.videoId,
                endpoint.playlistId,
                endpoint.playlistSetVideoId,
                endpoint.params
            )
            val items = visible(radioData.next().getOrThrow())
            if (items.isEmpty()) return null

            withContext(Dispatchers.Main) {
                stopRadio()
                player.forcePlayFromBeginning(items)
                radio = radioData
            }
            return items.first()
        }

        /** Waits until the queue saved at the last stop is back in the player; at once when there was none. */
        suspend fun awaitQueueRestored() = queueRestored.await()

        /** "Listen here" (tasks/0018): plays the queue of another device from where it is and takes its session over. */
        fun listenHere(state: app.melogold.android.sync.api.PlaybackState) {
            remote?.takeOver(state)
        }

        /** Whether the system refused to play in the foreground: the app was in the background (an agent). */
        val foregroundRefused: Boolean
            get() = this@PlayerService.foregroundRefused

        /**
         * «включи X» from the system (REWRITE §3.14.3): Google Assistant, Android Auto, a media controller.
         * [extras] may say what the query is (`android.intent.extra.focus`); nothing to search for continues the
         * last queue. The same rule as the functions Gemini calls ([VoiceQueryResolver]).
         */
        fun playFromSearch(query: String?, extras: Bundle? = null) {
            val request = VoiceQueryResolver.request(query, extras)
            coroutineScope.launch { VoiceQueryResolver.playFromSystem(this@PlayerService, this@Binder, request) }
        }
    }

    /** The tracks of a radio without those the person hid or blacklisted. */
    private suspend fun visible(items: List<MediaItem>): List<MediaItem> {
        val hiding = emptySet<String>().applyingHidden(pendingMutations.pending.value)
        return Database.filterBlacklistedSongs(items).filter { it.mediaId !in hiding }
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

    /**
     * What the media controllers may do beyond the player and what they ask of it: ♡ and the track loop; «play X»
     * (Google Assistant, Android Auto); the lists of Android Auto by their ids ([PlayerMediaBrowserService]).
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(LIKE_COMMAND)
                    .add(LOOP_COMMAND)
                    .build()
            )
            .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                LIKE_COMMAND.customAction -> likeAction()
                LOOP_COMMAND.customAction -> loopAction()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val request = mediaItems.singleOrNull()?.takeIf { it.localConfiguration == null }
                ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

            // «включи X» (no id, maybe a query): the voice commands play by themselves (VoiceQueryResolver), so
            // Media3 is told there is nothing to set — it ignores a failed request of this kind
            if (request.mediaId.isEmpty()) {
                binder.playFromSearch(request.requestMetadata.searchQuery, request.requestMetadata.extras)
                return Futures.immediateFailedFuture(UnsupportedOperationException("played by the voice commands"))
            }

            // A list of Android Auto by its id: its tracks, from the one asked for
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            coroutineScope.launch {
                runCatching { PlayerMediaBrowserService.queueOf(request.mediaId) }
                    .onSuccess { queue ->
                        if (queue == null || queue.first.isEmpty()) {
                            result.setException(UnsupportedOperationException("nothing to play for ${request.mediaId}"))
                        } else {
                            val [items, index] = queue
                            result.set(MediaSession.MediaItemsWithStartPosition(items, index, C.TIME_UNSET))
                        }
                    }
                    .onFailure(result::setException)
            }
            return result
        }
    }

    companion object {
        private const val DEFAULT_CACHE_DIRECTORY = "audio-cache"

        /** Where the cache was before: the system's cache directory, which cleaners wipe as junk. */
        private const val OLD_CACHE_DIRECTORY = "exoplayer"
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

            // Android/data/<package>/files: not the system's cache, so "free up space" and cleaners do not wipe the
            // music as junk (tasks/0001-audio-cache.md); the app's own files when there is no external storage
            val directory = (getExternalFilesDir(null) ?: filesDir).resolve(directoryName).apply {
                if (!exists()) mkdirs()
            }

            // The old place goes once: it is only a cache, and it would keep the space for nothing
            cacheDir.resolve(OLD_CACHE_DIRECTORY).takeIf { it.exists() }?.let { old ->
                runCatching { SimpleCache.delete(old, createDatabaseProvider(context)) }
                    .onFailure { Log.w(TAG, "Could not delete the old cache", it) }
            }

            SimpleCache(directory, cacheEvictor, createDatabaseProvider(context))
        }

        /**
         * What to tell the person when no stream came for [mediaId]: YouTube's own reason when it gives one
         * ([unavailability]: the track is closed in the country YouTube placed the device in, age, removed), else
         * the generic "couldn't get the stream". The reason is logged with the country, for bug reports.
         */
        internal fun whyUnavailable(mediaId: String, ytDlpMessage: String?): PlaybackException {
            val playability = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(PLAYABILITY_TIMEOUT) { Innertube.playability(mediaId)?.getOrNull() }
                }
            }.getOrNull()
            val error = unavailability(playability, ytDlpMessage)
            Log.w(
                TAG,
                "No stream for $mediaId: ${error?.message ?: "no reason given"}; YouTube: ${playability?.status} " +
                    "${playability?.reason}, placed in ${playability?.country}, open in " +
                    "${playability?.availableCountries?.size} countries; yt-dlp: ${ytDlpMessage?.lineSequence()?.lastOrNull()}"
            )
            return error ?: VideoIdMismatchException()
        }

        /** The playability check waits at most this long; the error is shown anyway. */
        private val PLAYABILITY_TIMEOUT = 8.seconds

        /**
         * The stream from yt-dlp, when no stream client gave one ([reasons]: what they said). Its failure is
         * explained by [whyUnavailable], with the clients' words too.
         */
        private fun resolveWithYtDlp(mediaId: String, reasons: List<String>): ResolvedStream {
            val download = runCatching { Dependencies.runDownload(mediaId) }
            val info = download.mapCatching {
                YouTubeDLResponse.fromString(it)
            }.also { it.exceptionOrNull()?.printStackTrace() }.getOrNull()
            if (info?.id != mediaId) {
                val said = reasons + listOfNotNull(download.exceptionOrNull()?.message)
                throw whyUnavailable(mediaId, said.joinToString("\n").ifEmpty { null })
            }
            val format = info.formats?.firstOrNull { it.formatId == info.formatId }
            val uri = runCatching { info.url?.toUri() }.getOrNull() ?: throw UnplayableException()
            return ResolvedStream(
                uri = uri,
                contentLength = info.fileSize,
                itag = info.formatId?.toIntOrNull(),
                mimeType = null,
                bitrate = format?.abr?.let { it * 1000 }?.toLong(),
                loudnessDb = null,
                lastModified = null,
                durationMs = null,
                userAgent = null,
                source = "yt-dlp"
            )
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
            uriCache: UriCache<String, StreamMeta> = UriCache(),
            background: Boolean = false,
            onBotCheck: () -> Unit = {}
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

            // The chunk from here, or what is left of the file: the last chunk of a cached track is shorter, and
            // without this it would be asked of the network (resolving the stream again) although it is cached
            val cachedChunk = if (chunkLength != null && cache != null && !dataSpec.isLocal) {
                Database.contentLengthNow(mediaId)
                    ?.let { total -> (total - dataSpec.position).coerceIn(0L, chunkLength) }
                    ?.takeIf { it > 0 }
                    ?: chunkLength
            } else chunkLength

            if (
                dataSpec.isLocal || (
                    cachedChunk != null && cache != null && cache.isCached(
                        /* key = */
                        mediaId,
                        /* position = */
                        dataSpec.position,
                        /* length = */
                        cachedChunk
                    )
                    )
            ) {
                // Only the cached chunk: past it the cache would ask the network for the video id
                // itself, which is no address (a local file of that name, "open failed: ENOENT")
                if (dataSpec.isLocal || cachedChunk == null) dataSpec
                else dataSpec.subrange(
                    0,
                    if (dataSpec.length == C.LENGTH_UNSET.toLong()) cachedChunk else minOf(dataSpec.length, cachedChunk)
                )
            } else {
                uriCache[mediaId]?.takeUnless { it.uri.isExpiring() }?.let { cachedUri ->
                    dataSpec
                        .withUri(cachedUri.uri)
                        .withMediaUserAgent(cachedUri.meta.userAgent)
                        .ranged(cachedUri.meta.contentLength)
                } ?: run {
                    // One request of a stream client; yt-dlp (four or five requests) only when none of them gave
                    // a stream for a reason other than the bot check
                    val reasons = mutableListOf<String>()
                    val stream = try {
                        runBlocking(Dispatchers.IO) {
                            resolveDirect(mediaId, StreamClients.current, reasons, background)
                        } ?: resolveWithYtDlp(mediaId, reasons)
                    } catch (e: BotCheckException) {
                        onBotCheck()
                        throw e
                    }

                    val mediaItem = runCatching {
                        runBlocking(Dispatchers.IO) { findMediaItem(mediaId) }
                    }.getOrNull()

                    val extras = mediaItem?.mediaMetadata?.extras?.songBundle
                    if (extras?.durationText == null) {
                        stream.durationMs
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
                                    itag = stream.itag,
                                    mimeType = stream.mimeType,
                                    bitrate = stream.bitrate,
                                    loudnessDb = stream.loudnessDb,
                                    contentLength = stream.contentLength,
                                    lastModified = stream.lastModified
                                )
                            )
                        }
                    }

                    uriCache.push(
                        key = mediaId,
                        meta = StreamMeta(stream.contentLength, stream.userAgent),
                        uri = stream.uri
                    )

                    dataSpec
                        .withUri(stream.uri)
                        .withMediaUserAgent(stream.userAgent)
                        .ranged(stream.contentLength)
                }
            }
        }.handleUnknownErrors { error ->
            // The next attempt resolves the address again (a 403: expired, or another network), in a new YouTube
            // session: a flagged one gives only the first megabyte of every address (renewSessionAfterRefusal)
            uriCache.clear()
            renewSessionAfterRefusal(error)
        }
    }
}

/** A stream address of YouTube that expires in less than 5 minutes (`expire`, epoch seconds). */
private fun Uri.isExpiring(now: Long = System.currentTimeMillis()): Boolean =
    getQueryParameter("expire")?.toLongOrNull()?.let { it * 1000 - 5 * 60_000 < now } == true

/** What the address cache keeps next to a stream address: its length (for the chunks) and the User-Agent it wants. */
data class StreamMeta(val contentLength: Long?, val userAgent: String?)

/** The User-Agent googlevideo wants for this address (ANDROID_VR); without one the player's own stays. */
private fun DataSpec.withMediaUserAgent(userAgent: String?) =
    userAgent?.let { withAdditionalHeaders(mapOf("User-Agent" to it)) } ?: this

/**
 * The player as the media controllers see it (headphones, the notification, Android Auto): «next» and «previous»
 * as the app's own buttons (round the queue, past hidden explicit tracks), «stop» pauses, the speed is the setting.
 */
@OptIn(UnstableApi::class)
private class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    override fun seekToNext() = wrappedPlayer.forceSeekToNext()

    override fun seekToNextMediaItem() = wrappedPlayer.forceSeekToNext()

    override fun seekToPrevious() = wrappedPlayer.forceSeekToPrevious()

    override fun seekToPreviousMediaItem() = wrappedPlayer.forceSeekToPrevious(seekToStart = false)

    override fun stop() = wrappedPlayer.pause()

    override fun setPlaybackSpeed(speed: Float) {
        PlayerPreferences.speed = speed.coerceIn(0.01f..2f)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        setPlaybackSpeed(playbackParameters.speed)
}

/**
 * The cover of the notification and the lock screen: YouTube's thumbnail asked in [ARTWORK_SIZE] dp (the queue
 * keeps the small one of the lists).
 */
@OptIn(UnstableApi::class)
private class ArtworkBitmapLoader(context: Context, private val loader: BitmapLoader) : BitmapLoader by loader {
    private val size = (ARTWORK_SIZE * context.resources.displayMetrics.density)
        .roundToInt()
        .coerceAtMost(MAX_THUMBNAIL_SIZE)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = loader.loadBitmap(uri.thumbnail(size))
}
