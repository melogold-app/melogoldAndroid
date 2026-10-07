package app.melogold.android.service

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import app.melogold.android.utils.findCause
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * tasks/0025: without network the track waits for it instead of being skipped. The player stays on the track (ExoPlayer
 * keeps the position after an error) and prepares it again as soon as the system reports a network
 * ([onNetworkAvailable]), otherwise after [delaysMs]. After [limitMs] the wait gives up: the player stays on the error
 * card, the queue does not move.
 *
 * 07.10.2026: the VPN tunnel let no traffic through for 30 s — a TLS handshake cut, then "no network" — and the
 * player skipped the track, though the network was back a second later.
 */
internal class NetworkWait(
    private val handler: Handler,
    private val delaysMs: List<Long> = DELAYS_MS,
    private val limitMs: Long = LIMIT_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private var mediaId: String? = null
    private var since = 0L
    private var attempt = 0
    private var pending: Runnable? = null

    val isWaiting: Boolean get() = mediaId != null

    /**
     * A network error on [id]: [retry] is scheduled after the next pause.
     * @return false when the wait has run out — the caller stops on the error card instead of skipping
     */
    fun onNetworkError(id: String, retry: () -> Unit): Boolean {
        val time = now()
        if (mediaId != id) {
            mediaId = id
            since = time
            attempt = 0
        }
        if (time - since >= limitMs) {
            clear()
            return false
        }
        val delay = delaysMs[attempt.coerceAtMost(delaysMs.lastIndex)]
        attempt++
        schedule(delay) { if (mediaId == id) retry() }
        return true
    }

    /** The system reported a network: the waiting track is tried now, not after the pause. */
    fun onNetworkAvailable(retry: () -> Unit) {
        if (mediaId == null) return
        cancelPending()
        retry()
    }

    /** The track played, or the user moved on: nothing to wait for. */
    fun clear() {
        mediaId = null
        cancelPending()
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        cancelPending()
        val runnable = Runnable {
            pending = null
            action()
        }
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    companion object {
        val DELAYS_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
        const val LIMIT_MS = 10 * 60 * 1000L

        /**
         * The cause is the network (no connection, DNS, a cut connection or TLS, a timeout), not YouTube and not the
         * video: a refused address (4xx), the bot check and an unplayable video keep their own handling.
         */
        fun isNetwork(error: PlaybackException): Boolean {
            if (error is BotCheckException || error.findCause<BotCheckException>() != null) return false
            val video = error is UnplayableException ||
                error is VideoIdMismatchException ||
                error is RestrictedVideoException
            if (video) return false
            if (error.findCause<InvalidResponseCodeException>() != null) return false
            if (
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ) return true
            return generateSequence<Throwable>(error) { it.cause }.any {
                it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
                    it is SocketTimeoutException || it is SSLException || it is SocketException
            }
        }
    }
}
