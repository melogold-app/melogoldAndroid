package app.melogold.android.sync.remote

import app.melogold.android.sync.epochMs

/**
 * The clock of the Melogold server as this device sees it (API §1.5: `serverTime` is in the answers): playback times
 * (`at`) are told on it, so devices whose clocks differ still agree on where a track is. Every `serverTime` the app gets
 * moves the offset; until one comes it is 0.
 */
object ServerClock {
    @Volatile
    private var offsetMs = 0L

    /** The time on the server now, epoch milliseconds. */
    fun now(local: Long = System.currentTimeMillis()): Long = local + offsetMs

    /** Learns from a `serverTime` that has just come: the server's time minus this device's. */
    fun update(serverTime: String, local: Long = System.currentTimeMillis()) {
        runCatching { offsetMs = serverTime.epochMs() - local }
    }

    /** For tests: back to no offset. */
    fun reset() {
        offsetMs = 0L
    }
}

/** Where a track is now: [positionMs] as it was at [atMs] (server time), moving on while [playing] (DESIGN §3.12.5). */
fun extrapolatePosition(positionMs: Long, atMs: Long, playing: Boolean, nowMs: Long, durationMs: Long?): Long {
    val moved = if (playing) (nowMs - atMs).coerceIn(0L, MAX_EXTRAPOLATION_MS) else 0L
    val position = positionMs + moved
    // Not past the end: the last second is the player's to say
    return durationMs?.takeIf { it > 0 }?.let { position.coerceAtMost((it - LAST_SECOND_MS).coerceAtLeast(0L)) } ?: position
}

private const val MAX_EXTRAPOLATION_MS = 3 * 60_000L
private const val LAST_SECOND_MS = 1_000L
