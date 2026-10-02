package app.melogold.android.sync.remote

import app.melogold.android.sync.Account
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.PlaybackHandoff
import app.melogold.android.sync.api.PlaybackState
import app.melogold.android.sync.api.PlaybackUpdatedPayload
import app.melogold.android.sync.api.PlaybackSummary
import app.melogold.android.sync.api.RemoteCommand
import app.melogold.android.sync.api.RemoteDevice
import app.melogold.android.sync.api.TrackDto
import app.melogold.android.sync.api.TrackInput
import app.melogold.android.sync.epochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** The device this one controls (tasks/0018): its id, its name and its kind for the icon. */
data class RemoteTarget(val deviceId: String, val name: String, val platform: String)

/**
 * The devices music can be started on from here (tasks/0023). A watch is not one: the watch app has to be opened by
 * hand on the watch (watchOS does not start it on a command) and plays only to headphones next to it. The user
 * (2026-10-01): «бред включать музыку на часах удалённо».
 */
fun playable(devices: List<RemoteDevice>): List<RemoteDevice> = devices.filter { it.platform != "watchos" }

/**
 * What the controlled device plays, as the last events and the last read say. [positionMs] was true at [atMs] (the
 * time of the server); [positionAt] counts on from it while it [playing].
 */
data class RemoteNow(
    val deviceId: String,
    val track: TrackDto?,
    val index: Int,
    val queueLength: Int,
    val positionMs: Long,
    val durationMs: Long?,
    val playing: Boolean,
    val atMs: Long,
    val volume: Int?,
    val rev: Long
) {
    fun positionAt(nowMs: Long) = extrapolatePosition(positionMs, atMs, playing, nowMs, durationMs)
}

fun PlaybackSummary.toNow() = RemoteNow(
    deviceId = deviceId,
    track = track,
    index = index,
    queueLength = queueLength,
    positionMs = positionMs,
    durationMs = durationMs,
    playing = playing,
    atMs = runCatching { at.epochMs() }.getOrDefault(ServerClock.now()),
    volume = volume,
    rev = rev
)

fun PlaybackState.toNow() = RemoteNow(
    deviceId = deviceId,
    track = queue.getOrNull(index),
    index = index,
    queueLength = queue.size,
    positionMs = positionMs,
    durationMs = durationMs,
    playing = playing,
    atMs = runCatching { at.epochMs() }.getOrDefault(ServerClock.now()),
    volume = volume,
    rev = rev
)

/** Something the person is told when a command could not reach the device. */
sealed interface RemoteNotice {
    /** `409 device_offline`: "«MacBook Air» is offline"; the remote turns off. */
    data class Offline(val name: String) : RemoteNotice

    /** `409 remote_control_disabled`: "Control is off on «MacBook Air»"; the remote turns off. */
    data class Disabled(val name: String) : RemoteNotice

    /** The device is not on the account any more (`404`), or something else went wrong. */
    data object Failed : RemoteNotice
}

/**
 * This device as a remote for another one of the account (API §4.9 "Пульт", tasks/0018): which device it controls, what
 * that one plays (from `playback.updated` and `GET /playback/state`), and the commands (`POST /playback/commands`) that
 * go one after another. While a device is chosen the player of this device does not tell the server what it plays.
 */
class RemoteControl(
    private val account: Account,
    private val playbackUpdated: Flow<PlaybackUpdatedPayload>,
    private val liveConnected: Flow<Unit>,
    private val scope: CoroutineScope,
    private val newCommandId: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutableTarget = MutableStateFlow<RemoteTarget?>(null)
    private val mutableNow = MutableStateFlow<RemoteNow?>(null)
    private val mutableNotices = MutableSharedFlow<RemoteNotice>(extraBufferCapacity = NOTICES_BUFFER)
    private val commands = Channel<RemoteCommand>(Channel.UNLIMITED)

    /** The device that is controlled, or null: the player of this device plays here. */
    val target: StateFlow<RemoteTarget?> = mutableTarget.asStateFlow()

    /** What it plays; null while it is not known. */
    val now: StateFlow<RemoteNow?> = mutableNow.asStateFlow()

    val notices: SharedFlow<RemoteNotice> = mutableNotices

    val active get() = mutableTarget.value != null

    init {
        // One command at a time, in the order the person made them
        scope.launch { for (command in commands) deliver(command) }
        scope.launch { playbackUpdated.collect { payload -> onUpdated(payload.cleared, payload.state) } }
        scope.launch { liveConnected.collect { if (active) refresh() } }
    }

    /** Whether the server lets devices control each other (`features.remote`) and this device is signed in. */
    suspend fun available(): Boolean =
        account.session != null && runCatching { account.serverInfo().features.remote != null }.getOrDefault(false)

    /** The other devices of the account music can be started on, with whether they are online and what they play. */
    suspend fun devices(): List<RemoteDevice> = account.authorized { api, token ->
        playable(api.remoteDevices(token).also { ServerClock.update(it.serverTime) }.devices)
    }

    /** The player becomes the remote of [device]. */
    fun connect(device: RemoteDevice) {
        mutableTarget.value = RemoteTarget(device.deviceId, device.name, device.platform)
        mutableNow.value = device.playing?.takeIf { it.deviceId == device.deviceId }?.toNow()
        scope.launch { refresh() }
    }

    /** The player controls this device again; the other one plays on. */
    fun disconnect() {
        mutableTarget.value = null
        mutableNow.value = null
    }

    private suspend fun refresh() {
        val target = mutableTarget.value ?: return
        val state = try {
            account.authorized { api, token ->
                api.playbackState(token).also { ServerClock.update(it.serverTime) }.state
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return
        }
        if (state != null && state.deviceId == target.deviceId && mutableTarget.value == target) mutableNow.value = state.toNow()
    }

    private fun onUpdated(cleared: Boolean, state: PlaybackSummary?) {
        val target = mutableTarget.value ?: return
        if (cleared) {
            mutableNow.value = null
            return
        }
        if (state == null || state.deviceId != target.deviceId) return
        // An event older than what is known is the past
        if (state.rev <= (mutableNow.value?.rev ?: Long.MIN_VALUE)) return
        mutableNow.value = state.toNow()
    }

    // region Commands

    fun play() = command(RemoteCommandExecutor.ACTION_PLAY, optimistic = { it.copy(playing = true) })

    fun pause() = command(RemoteCommandExecutor.ACTION_PAUSE, optimistic = { it.copy(playing = false) })

    fun toggle() = command(RemoteCommandExecutor.ACTION_TOGGLE, optimistic = { it.copy(playing = !it.playing) })

    fun next() = command(RemoteCommandExecutor.ACTION_NEXT)

    fun previous() = command(RemoteCommandExecutor.ACTION_PREVIOUS)

    fun stop() = command(RemoteCommandExecutor.ACTION_STOP, optimistic = { it.copy(playing = false) })

    fun seekTo(positionMs: Long) = command(
        RemoteCommandExecutor.ACTION_SEEK,
        positionMs = positionMs,
        optimistic = { it.copy(positionMs = positionMs, atMs = ServerClock.now()) }
    )

    /** The volume of the device, 0..100. */
    fun setVolume(percent: Int) = command(
        RemoteCommandExecutor.ACTION_VOLUME,
        volume = percent.coerceIn(0, MAX_PERCENT),
        optimistic = { it.copy(volume = percent.coerceIn(0, MAX_PERCENT)) }
    )

    /**
     * Plays [tracks] from [index] on the controlled device (a tap on a track of a list while the remote is on): the
     * queue is a window of at most 200 tracks around it, files of this device left out. Returns false when no device
     * is controlled and the player plays it here; true when the command was taken (or could not be made).
     */
    fun playQueue(tracks: List<TrackInput>, index: Int): Boolean {
        val target = mutableTarget.value ?: return false
        if (index !in tracks.indices) return true
        val ids = tracks.map { it.videoId }
        val kept = queueWindowOf(ids.size, index).filter { ids[it].length == VIDEO_ID_LENGTH && !ids[it].startsWith("local:") }
        val at = kept.indexOf(index)
        if (at < 0) {
            mutableNotices.tryEmit(RemoteNotice.Failed)
            return true
        }
        enqueue(
            RemoteCommand(
                commandId = newCommandId(),
                targetDeviceId = target.deviceId,
                action = RemoteCommandExecutor.ACTION_PLAY_QUEUE,
                queue = kept.map(tracks::get),
                index = at
            )
        )
        return true
    }

    private fun command(
        action: String,
        positionMs: Long? = null,
        volume: Int? = null,
        optimistic: ((RemoteNow) -> RemoteNow)? = null
    ) {
        val target = mutableTarget.value ?: return
        // What the person sees does not wait for the device: it is corrected by what the device reports
        mutableNow.value?.let { current -> optimistic?.let { mutableNow.value = it(current.copy(positionMs = current.positionAt(ServerClock.now()), atMs = ServerClock.now())) } }
        enqueue(
            RemoteCommand(
                commandId = newCommandId(),
                targetDeviceId = target.deviceId,
                action = action,
                positionMs = positionMs,
                volume = volume
            )
        )
    }

    private fun enqueue(command: RemoteCommand) {
        commands.trySend(command)
    }

    private suspend fun deliver(command: RemoteCommand) {
        val target = mutableTarget.value?.takeIf { it.deviceId == command.targetDeviceId } ?: return
        try {
            account.authorized { api, token -> api.sendCommand(token, command) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when (e.code) {
                "device_offline" -> {
                    mutableNotices.tryEmit(RemoteNotice.Offline(target.name))
                    disconnect()
                }

                "remote_control_disabled" -> {
                    mutableNotices.tryEmit(RemoteNotice.Disabled(target.name))
                    disconnect()
                }

                "device_not_found" -> {
                    mutableNotices.tryEmit(RemoteNotice.Failed)
                    disconnect()
                }

                else -> mutableNotices.tryEmit(RemoteNotice.Failed)
            }
        }
    }

    // endregion

    /**
     * "Listen here": this device takes the playback of the controlled one over ([takeOver] plays it here and tells the
     * server) and stops being its remote. Returns whether there was something to take.
     */
    suspend fun listenHere(takeOver: (PlaybackState) -> Unit): Boolean {
        val target = mutableTarget.value ?: return false
        val state = try {
            account.authorized { api, token -> api.playbackState(token).also { ServerClock.update(it.serverTime) }.state }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            mutableNotices.tryEmit(RemoteNotice.Failed)
            return false
        }
        if (state == null || state.deviceId != target.deviceId || state.queue.isEmpty()) return false
        takeOver(state)
        disconnect()
        return true
    }

    private companion object {
        const val NOTICES_BUFFER = 4
        const val MAX_PERCENT = 100
        const val VIDEO_ID_LENGTH = 11
    }
}

/**
 * Whether this device has to give way to another that took its session over ("Listen here", DESIGN §3.12.6): the
 * `handoffFrom` of the state names this device and its current session, and is less than 5 minutes old.
 */
fun shouldGiveWay(from: PlaybackHandoff?, myDeviceId: String?, mySessionId: String, serverNowMs: Long): Boolean {
    if (from == null || myDeviceId == null) return false
    if (from.deviceId != myDeviceId || from.sessionId != mySessionId) return false
    val at = runCatching { from.at.epochMs() }.getOrNull() ?: return false
    return serverNowMs - at < HANDOFF_MAX_AGE_MS
}

private const val HANDOFF_MAX_AGE_MS = 5 * 60_000L
