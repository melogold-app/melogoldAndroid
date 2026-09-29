package app.melogold.android.sync.remote

import app.melogold.android.sync.api.PlaybackHandoffInput
import app.melogold.android.sync.api.PlaybackPut
import app.melogold.android.sync.api.PlaybackState
import app.melogold.android.sync.api.TrackInput
import app.melogold.android.sync.isoTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Most tracks of the queue a state carries, and how many go before the current one (DESIGN §3.12.1). */
const val QUEUE_WINDOW = 200
private const val BEFORE_CURRENT = 50

/** The body of `PUT /playback/state` is at most 128 KiB (API §1.9); the client keeps under 120 (DESIGN §3.12.1). */
private const val BODY_LIMIT = 120 * 1024

/**
 * What the player has to say (tasks/0018): the whole queue by video ids (a change of it is a new `queueVersion`), where
 * in it, how far, whether it plays, the volume 0..100. [trackAt] makes the [TrackInput] of one of the tracks, only for
 * those that go into the request.
 */
class PlayerSnapshot(
    val videoIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long?,
    val playing: Boolean,
    val volume: Int?,
    val trackAt: (Int) -> TrackInput
)

/** How the server took a state. */
sealed interface PutOutcome {
    data object Applied : PutOutcome

    /** Someone else's state is newer (`newer_state`): nothing to do. */
    data object Newer : PutOutcome

    /** This device's session was taken over by another ("Listen here"): pause and start a new session. */
    data class HandedOff(val state: PlaybackState?) : PutOutcome

    /** `409 playback_queue_required`: the server has no queue of this session. */
    data object QueueRequired : PutOutcome

    /** `413`: the body was too large. */
    data object TooLarge : PutOutcome

    /** No network, a busy server: try again a little later. */
    data object Unreachable : PutOutcome
}

/** What the reporter needs from the outside: the player, the server, the clocks. */
interface ReporterPort {
    /** The player now, or null when there is nothing to tell (no queue, or the track is a file of this device). */
    suspend fun snapshot(): PlayerSnapshot?

    suspend fun send(put: PlaybackPut): PutOutcome

    /** Milliseconds on a clock that only goes forward: how long since the last request. */
    fun monotonicMs(): Long

    /** The time on the server now (`at`). */
    fun serverNowMs(): Long
}

/**
 * Tells the server what this device plays (API §4.9, DESIGN §3.12, tasks/0018), so that the other devices see it and can
 * control it:
 *
 * - on a change ([changed]: a track, play or pause, a seek, the queue, the volume) and every minute while playing (the
 *   heartbeat), never more often than once in [minIntervalMs]; a burst of changes is one request, the last state
 * - only after a sound really played in this session (DESIGN §3.12.2), and not while [paused] (this device is a remote
 *   for another)
 * - the queue goes only when it, or the session, is new since the last state the server took; `queueVersion` grows with
 *   every change of it; a window of [QUEUE_WINDOW] tracks around the current one, files of this device left out
 * - the position is not sent between requests: the others count it from `at`, `positionMs` and `playing`
 * - without a network only the last state is kept, and sent when it comes back if it is younger than 10 minutes
 */
class PlaybackReporter(
    private val port: ReporterPort,
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 1_000L,
    private val heartbeatMs: Long = 60_000L,
    private val retryMs: Long = 15_000L,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val onHandedOff: (PlaybackState?) -> Unit = {},
    private val sizeOf: (PlaybackPut) -> Int = { 0 }
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** This session, and the version of its queue: it grows with every change of the queue. */
    var sessionId: String = newSessionId()
        private set
    private var queueVersion = 0L
    private var lastQueueIds: List<String>? = null

    /** What the server has of the queue: of which session and version, and which tracks (their places in the queue). */
    private class Sent(val sessionId: String, val version: Long, val kept: List<Int>)

    private var sent: Sent? = null

    private var lastSentAt: Long? = null
    private var hasPlayed = false
    private var lastPlaying = false
    private var pendingHandoff: PlaybackHandoffInput? = null
    private var window = QUEUE_WINDOW
    private var retry: Job? = null

    /** While true nothing is sent: this device controls another one and its own state would hide that one's. */
    @Volatile
    var paused = false
        set(value) {
            val was = field
            field = value
            if (was && !value) changed()
        }

    init {
        scope.launch {
            for (ignored in signal) {
                val wait = lastSentAt?.let { it + minIntervalMs - port.monotonicMs() } ?: 0L
                if (wait > 0) delay(wait)
                // Everything that changed while waiting is in the state about to be read
                while (signal.tryReceive().isSuccess) {
                    // drained: the state read below has them all
                }
                flush()
            }
        }
        scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                if (lastPlaying && !paused) changed()
            }
        }
    }

    /** Something changed: a state goes out, soon. */
    fun changed() {
        signal.trySend(Unit)
    }

    /** A sound played: from now on this session is worth telling about. */
    fun soundPlayed() {
        if (!hasPlayed) {
            hasPlayed = true
            changed()
        }
    }

    /** "Listen here" on this device: the next state carries [from] and the queue, in a session of its own. */
    fun takeOver(from: PlaybackHandoffInput) {
        pendingHandoff = from
        newSession()
        hasPlayed = true
        changed()
    }

    /** Another session: a new id, and the queue goes again. */
    fun newSession() {
        sessionId = newSessionId()
        sent = null
    }

    private suspend fun flush(attempt: Int = 0) {
        if (paused || !hasPlayed) return
        val snapshot = port.snapshot() ?: return
        lastPlaying = snapshot.playing

        // A new queue is a new version of it
        if (lastQueueIds != snapshot.videoIds) {
            if (lastQueueIds != null) queueVersion++
            lastQueueIds = snapshot.videoIds
        }
        val built = build(snapshot) ?: return

        lastSentAt = port.monotonicMs()
        val put = built.put
        when (val outcome = port.send(put)) {
            PutOutcome.Applied -> {
                built.kept?.let { sent = Sent(put.sessionId, put.queueVersion, it) }
                if (put.handoffFrom != null) pendingHandoff = null
                window = QUEUE_WINDOW
            }

            PutOutcome.Newer -> Unit

            is PutOutcome.HandedOff -> {
                newSession()
                pendingHandoff = null
                onHandedOff(outcome.state)
            }

            PutOutcome.QueueRequired -> if (attempt < MAX_ATTEMPTS) {
                sent = null
                flush(attempt + 1)
            }

            PutOutcome.TooLarge -> if (attempt < MAX_ATTEMPTS && window > 1) {
                window = (window / 2).coerceAtLeast(1)
                sent = null
                flush(attempt + 1)
            }

            PutOutcome.Unreachable -> if (retry?.isActive != true) retry = scope.launch {
                // The last state is kept and tried again in a while; one older than 10 minutes is not worth telling
                val failedAt = port.monotonicMs()
                delay(retryMs)
                if (port.monotonicMs() - failedAt <= STALE_MS) changed()
            }
        }
    }

    private class Built(val put: PlaybackPut, val kept: List<Int>?)

    /**
     * The request for [snapshot]; null when there is nothing to send (the current track is not a video of YouTube).
     * The queue goes with it when the server does not have this session's queue in this version, or not this track in it.
     */
    private fun build(snapshot: PlayerSnapshot): Built? {
        val ids = snapshot.videoIds
        if (snapshot.index !in ids.indices || !ids[snapshot.index].isVideoId) return null

        val have = sent?.takeIf { it.sessionId == sessionId && it.version == queueVersion && snapshot.index in it.kept }
        val withQueue = have == null || pendingHandoff != null

        var size = window
        var kept = have?.kept
        var queue: List<TrackInput>? = null
        if (withQueue) {
            kept = keptOf(ids, snapshot.index, size)
            queue = kept.map(snapshot.trackAt)
        }
        var put = putOf(snapshot, kept!!, queue)

        // Shrinks the window around the current track until the body fits
        while (queue != null && sizeOf(put) > BODY_LIMIT && size > 1) {
            size = (size / 2).coerceAtLeast(1)
            kept = keptOf(ids, snapshot.index, size)
            queue = kept.map(snapshot.trackAt)
            put = putOf(snapshot, kept, queue)
        }
        return Built(put, kept.takeIf { queue != null })
    }

    private fun keptOf(ids: List<String>, index: Int, size: Int) =
        queueWindowOf(ids.size, index, size).filter { ids[it].isVideoId }

    private fun putOf(snapshot: PlayerSnapshot, kept: List<Int>, queue: List<TrackInput>?) = PlaybackPut(
        sessionId = sessionId,
        queueVersion = queueVersion,
        at = port.serverNowMs().isoTime(),
        index = kept.indexOf(snapshot.index),
        positionMs = snapshot.positionMs.coerceAtLeast(0L),
        durationMs = snapshot.durationMs?.takeIf { it > 0 },
        playing = snapshot.playing,
        queue = queue,
        handoffFrom = pendingHandoff,
        volume = snapshot.volume?.coerceIn(0, MAX_VOLUME)
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val STALE_MS = 10 * 60_000L
        const val MAX_VOLUME = 100
    }
}

/** A YouTube video id: 11 characters; files of the device (`local:…`) have none and are not shared. */
private val String.isVideoId get() = length == VIDEO_ID_LENGTH && !startsWith("local:")

private const val VIDEO_ID_LENGTH = 11

/**
 * The tracks of a queue of [size] that a state carries when the current one is [index]: [maxItems] of them from 50
 * before it (so `[index − 50, index + 149]` of a long queue), all of a short one. The current track is always in it.
 */
fun queueWindowOf(size: Int, index: Int, maxItems: Int = QUEUE_WINDOW): IntRange {
    if (size <= 0 || index !in 0 until size || maxItems <= 0) return IntRange.EMPTY
    val before = minOf(BEFORE_CURRENT, maxItems / 2)
    val start = (index - before).coerceAtLeast(0)
    val end = (start + maxItems).coerceAtMost(size)
    return start until end
}

/**
 * What the server made of a `PUT /playback/state` ([block]), as the reporter understands it: it took the state, another
 * device's is newer, this session was handed over, it wants the queue, the body was too large, or it could not be reached.
 * A refusal that sending again will not cure (an old protocol, an ended session) counts as [PutOutcome.Newer]: nothing to
 * do about it.
 */
suspend fun putOutcome(block: suspend () -> app.melogold.android.sync.api.PlaybackPutResult): PutOutcome = try {
    val result = block()
    ServerClock.update(result.serverTime)
    when {
        result.applied -> PutOutcome.Applied
        result.reason == "handed_off" -> PutOutcome.HandedOff(result.state)
        else -> PutOutcome.Newer
    }
} catch (e: app.melogold.android.sync.api.ApiException) {
    when {
        e.code == "playback_queue_required" -> PutOutcome.QueueRequired
        e.status == HTTP_TOO_LARGE -> PutOutcome.TooLarge
        e.isNetwork || e.status == HTTP_TOO_MANY || e.status >= HTTP_SERVER_ERROR -> PutOutcome.Unreachable
        else -> PutOutcome.Newer
    }
}

private const val HTTP_TOO_LARGE = 413
private const val HTTP_TOO_MANY = 429
private const val HTTP_SERVER_ERROR = 500
