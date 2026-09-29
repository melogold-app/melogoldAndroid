package app.melogold.android.sync.remote

import app.melogold.android.sync.api.PlaybackCommandPayload
import app.melogold.android.sync.api.TrackDto

/** What the player of this device can do when another device asks (tasks/0018), without the player itself. */
interface PlayerPort {
    /** Whether it plays or is about to: the play button shows "pause". */
    val playWhenReady: Boolean

    fun play()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    /** The volume of the system for music, 0..100. */
    fun setVolume(percent: Int)

    /** Plays [tracks] from [index] on; the queue this device had is gone. */
    fun playQueue(tracks: List<TrackDto>, index: Int)

    /** Stops: the sound ends, the queue stays where it is. */
    fun stop()
}

/**
 * Does what a `playback.command` asks of this device's player (API §4.9, tasks/0018): play, pause, toggle, next, previous,
 * seek, volume, play_queue, stop. A command that lacks what its action needs, or is not one of these, is left alone:
 * the server checked it, a newer one may know actions this app does not.
 */
class RemoteCommandExecutor(private val port: PlayerPort) {
    /** Returns whether the command was carried out. */
    fun execute(command: PlaybackCommandPayload): Boolean {
        when (command.action) {
            ACTION_PLAY -> port.play()
            ACTION_PAUSE -> port.pause()
            ACTION_TOGGLE -> if (port.playWhenReady) port.pause() else port.play()
            ACTION_NEXT -> port.next()
            ACTION_PREVIOUS -> port.previous()
            ACTION_STOP -> port.stop()
            ACTION_SEEK -> port.seekTo((command.positionMs ?: return false).coerceAtLeast(0L))
            ACTION_VOLUME -> port.setVolume((command.volume ?: return false).coerceIn(0, MAX_PERCENT))
            ACTION_PLAY_QUEUE -> {
                val queue = command.queue?.takeIf { it.isNotEmpty() } ?: return false
                val index = command.index ?: return false
                if (index !in queue.indices) return false
                port.playQueue(queue, index)
            }

            else -> return false
        }
        return true
    }

    companion object {
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_SEEK = "seek"
        const val ACTION_VOLUME = "volume"
        const val ACTION_PLAY_QUEUE = "play_queue"
        const val ACTION_STOP = "stop"
        private const val MAX_PERCENT = 100
    }
}

/**
 * Whether the "Controlled by «Pixel 7 Pro»" notice may show now: not more often than every 30 s (tasks/0018), so that a
 * volume slider dragged on another device does not become a stream of them.
 */
class NoticeThrottle(private val intervalMs: Long = 30_000L) {
    private var last: Long? = null

    fun allow(now: Long): Boolean {
        val before = last
        if (before != null && now - before < intervalMs) return false
        last = now
        return true
    }
}
