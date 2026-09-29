package app.melogold.android.service

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.melogold.android.Dependencies
import app.melogold.android.R
import app.melogold.android.data.repo.toSong
import app.melogold.android.sync.PlaybackActivity
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.PlaybackCommandPayload
import app.melogold.android.sync.api.PlaybackHandoffInput
import app.melogold.android.sync.api.PlaybackPut
import app.melogold.android.sync.api.PlaybackState
import app.melogold.android.sync.api.PlaybackSummary
import app.melogold.android.sync.api.TrackDto
import app.melogold.android.sync.epochMs
import app.melogold.android.sync.remote.NoticeThrottle
import app.melogold.android.sync.remote.PlaybackReporter
import app.melogold.android.sync.remote.PlayerPort
import app.melogold.android.sync.remote.PlayerSnapshot
import app.melogold.android.sync.remote.PutOutcome
import app.melogold.android.sync.remote.RemoteCommandExecutor
import app.melogold.android.sync.remote.toTrackInput
import app.melogold.android.sync.remote.ReporterPort
import app.melogold.android.sync.remote.ServerClock
import app.melogold.android.sync.remote.extrapolatePosition
import app.melogold.android.sync.remote.putOutcome
import app.melogold.android.sync.remote.shouldGiveWay
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.shouldBePlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private const val TAG = "PlayerRemote"

/**
 * The player of this device for the other devices of the account (tasks/0018): it says what plays
 * ([PlaybackReporter], `PUT /playback/state`), does what they ask (`playback.command`, [RemoteCommandExecutor]) and
 * gives way when one of them says "Listen here" (`playback.updated` with this session named in `handoffFrom`).
 * It lives as long as the [PlayerService]; while it does and the player has a queue, the live stream stays open in the
 * background ([PlaybackActivity]).
 */
class PlayerRemote(
    private val context: Context,
    private val player: ExoPlayer,
    private val stopRadio: () -> Unit
) : Player.Listener, ReporterPort, PlayerPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = Dependencies.application.container
    private val audio = context.getSystemService<AudioManager>()
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val executor = RemoteCommandExecutor(this)
    private val notice = NoticeThrottle()

    /** Whether the server keeps playback states (`features.playback`), asked once in a while. */
    @Volatile
    private var supported = true

    private val reporter = PlaybackReporter(
        port = this,
        scope = scope,
        onHandedOff = { state -> gaveWay(state?.deviceName) },
        sizeOf = { put -> json.encodeToString(put).toByteArray().size }
    )

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reporter.changed()
    }

    private val appVisibility = LifecycleEventObserver { _, event ->
        // The app goes to the background: the state goes out at once (DESIGN §3.12.2)
        if (event == Lifecycle.Event.ON_STOP) reporter.changed()
    }

    init {
        player.addListener(this)
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibility)

        val sync = container.sync
        scope.launch { sync.playbackCommands.collect(::onCommand) }
        scope.launch { sync.playbackUpdated.collect { payload -> payload.state?.let(::onOtherPlayback) } }
        // This device controlling another must not put its own state over that one's
        scope.launch { container.remote.target.collect { target -> reporter.paused = target != null } }
        PlaybackActivity.active.value = player.mediaItemCount > 0
    }

    fun release() {
        PlaybackActivity.active.value = false
        runCatching {
            player.removeListener(this)
            context.contentResolver.unregisterContentObserver(volumeObserver)
            ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibility)
        }
        scope.cancel()
    }

    // region What this player says

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = changed()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) reporter.soundPlayed()
        changed()
    }

    override fun onPlaybackStateChanged(playbackState: Int) = changed()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = changed()

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = changed()

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) changed()
    }

    private fun changed() {
        // The stream stays open while this player has a queue, paused too: a device that paused it can start it again
        PlaybackActivity.active.value = player.mediaItemCount > 0
        reporter.changed()
    }

    override suspend fun snapshot(): PlayerSnapshot? {
        val count = player.mediaItemCount
        if (count == 0 || player.currentMediaItemIndex !in 0 until count) return null
        val items = List(count) { player.getMediaItemAt(it) }
        return PlayerSnapshot(
            videoIds = items.map { it.mediaId },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 },
            playing = player.shouldBePlaying && player.playerError == null,
            volume = volumePercent(),
            trackAt = { index -> items[index].toTrackInput() }
        )
    }

    override suspend fun send(put: PlaybackPut): PutOutcome {
        val account = container.account
        if (account.session == null) return PutOutcome.Newer
        if (!supported) return PutOutcome.Newer

        val info = try {
            account.serverInfo()
        } catch (e: ApiException) {
            // The server cannot be asked now: the state is kept and tried again
            return PutOutcome.Unreachable
        }
        if (info.features.playback == null) {
            supported = false
            return PutOutcome.Newer
        }
        return putOutcome { account.authorized { api, token -> api.putPlaybackState(token, put) } }
    }

    override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun serverNowMs(): Long = ServerClock.now()

    private fun volumePercent(): Int? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * PERCENT / max.toFloat()).roundToInt()
    }

    // endregion

    // region What is asked of it

    private fun onCommand(command: PlaybackCommandPayload) {
        if (!executor.execute(command)) {
            Log.i(TAG, "Not carried out: ${command.action}")
            return
        }
        // "Controlled by «Pixel 7 Pro»", not more often than every 30 s
        if (notice.allow(monotonicMs())) {
            val name = command.fromDeviceName?.takeIf { it.isNotBlank() } ?: return
            Toast.makeText(context, context.getString(R.string.remote_controlled_by, name), Toast.LENGTH_SHORT).show()
        }
    }

    override val playWhenReady get() = player.playWhenReady

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
    }

    override fun pause() = player.pause()

    override fun next() = player.forceSeekToNext()

    override fun previous() = player.forceSeekToPrevious()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setVolume(percent: Int) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // The system shows its own slider only when asked to; a command from another device is silent
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / PERCENT.toFloat()).roundToInt(), 0) }
            .onFailure { Log.w(TAG, "The volume could not be set", it) }
    }

    override fun playQueue(tracks: List<TrackDto>, index: Int) {
        stopRadio()
        val items = tracks.map { it.toSong().asMediaItem }
        player.setMediaItems(items, index, C.TIME_UNSET)
        player.playWhenReady = true
        player.prepare()
    }

    override fun stop() = player.pause()

    // endregion

    // region Handing over

    /**
     * Another device says "Listen here" (`playback.updated` naming this device and this session in `handoffFrom`, less
     * than 5 minutes ago): this one stops and lets it play (DESIGN §3.12.6).
     */
    private fun onOtherPlayback(state: PlaybackSummary) {
        if (shouldGiveWay(state.handoffFrom, container.account.session?.deviceId, reporter.sessionId, ServerClock.now())) {
            gaveWay(state.deviceName)
        }
    }

    private fun gaveWay(deviceName: String?) {
        player.pause()
        reporter.newSession()
        val name = deviceName?.takeIf { it.isNotBlank() } ?: return
        Toast.makeText(context, context.getString(R.string.remote_continued_on, name), Toast.LENGTH_LONG).show()
    }

    /**
     * "Listen here" (tasks/0018): plays what [state] (the queue of another device, read from the server) plays, from
     * where it is, and tells the server that this device took the session over; that device then stops.
     */
    fun takeOver(state: PlaybackState) {
        val queue = state.queue.filter { it.videoId.isNotEmpty() }
        if (queue.isEmpty()) return
        stopRadio()
        val index = state.index.coerceIn(0, queue.lastIndex)
        val position = extrapolatePosition(state.positionMs, state.at.epochMs(), state.playing, ServerClock.now(), state.durationMs)
        player.setMediaItems(queue.map { it.toSong().asMediaItem }, index, position)
        player.playWhenReady = true
        player.prepare()
        reporter.takeOver(PlaybackHandoffInput(state.deviceId, state.sessionId))
    }

    // endregion

    private companion object {
        const val PERCENT = 100
    }
}
