package app.melogold.android.service

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * tasks/0025: a track that failed for the network waits for it instead of being skipped — retried after the pause or
 * at once when the network is back; after the limit the wait gives up. Robolectric: the main looper's clock is paused,
 * so the pauses are measured without waiting.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkWaitTest {
    private val handler = Handler(Looper.getMainLooper())
    private val looper = shadowOf(Looper.getMainLooper())

    private fun failure(cause: Throwable, code: Int = PlaybackException.ERROR_CODE_UNSPECIFIED) =
        PlaybackException("failed", cause, code)

    @Test
    fun `no network, DNS and a cut TLS are the network, a refused address and the bot check are not`() {
        assertTrue(NetworkWait.isNetwork(failure(UnknownHostException("rr1.googlevideo.com"))))
        assertTrue(NetworkWait.isNetwork(failure(SSLHandshakeException("connection closed"))))
        assertTrue(NetworkWait.isNetwork(failure(RuntimeException("x"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))

        val refused = InvalidResponseCodeException(403, null, null, emptyMap(), DataSpec(Uri.parse("https://rr1.googlevideo.com/x")), ByteArray(0))
        assertFalse(NetworkWait.isNetwork(failure(refused, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertFalse(NetworkWait.isNetwork(BotCheckException(UnknownHostException("youtube.com"))))
        assertFalse(NetworkWait.isNetwork(failure(IllegalStateException("decoder"))))
    }

    @Test
    fun `the track is retried after the pause, at once when the network is back, and given up after the limit`() {
        val wait = NetworkWait(handler, delaysMs = listOf(2_000L), limitMs = 10_000L)
        var retries = 0
        val retry: () -> Unit = { retries++ }

        assertTrue(wait.onNetworkError("a", retry))
        looper.idleFor(Duration.ofMillis(1_900))
        assertEquals(0, retries, "not before the pause")
        looper.idleFor(Duration.ofMillis(200))
        assertEquals(1, retries, "after the pause")

        assertTrue(wait.onNetworkError("a", retry))
        wait.onNetworkAvailable(retry)
        assertEquals(2, retries, "at once when the network is back")
        looper.idleFor(Duration.ofSeconds(3))
        assertEquals(2, retries, "the pause was cancelled by the network")

        looper.idleFor(Duration.ofSeconds(10))
        assertFalse(wait.onNetworkError("a", retry), "the limit is reached: the caller stops on the error card")
        assertFalse(wait.isWaiting)
    }

    @Test
    fun `another track or a pause clears the wait`() {
        val wait = NetworkWait(handler, delaysMs = listOf(2_000L))
        var retries = 0
        assertTrue(wait.onNetworkError("a") { retries++ })
        wait.clear()
        looper.idleFor(Duration.ofSeconds(3))
        wait.onNetworkAvailable { retries++ }
        assertEquals(0, retries)
        assertFalse(wait.isWaiting)
    }
}
