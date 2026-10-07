package app.melogold.android.service

import app.melogold.providers.innertube.models.PlayerResponse.StreamingData.AdaptiveFormat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The format of a stream client's answer, what counts as YouTube's bot check, the memory of a closed address.
 * Robolectric: a PlaybackException reads the Android clock.
 */
@RunWith(RobolectricTestRunner::class)
class DirectStreamsTest {
    private fun format(itag: Int, mime: String, bitrate: Long, url: String? = "https://rr1.googlevideo.com/$itag") =
        AdaptiveFormat(
            itag = itag, mimeType = mime, bitrate = bitrate, averageBitrate = null, contentLength = 1000,
            audioQuality = null, approxDurationMs = 200_000, lastModified = null, loudnessDb = null,
            audioSampleRate = null, url = url, signatureCipher = if (url == null) "s=abc" else null
        )

    private val opus = format(251, "audio/webm; codecs=\"opus\"", 160_000)
    private val aac = format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000)
    private val video = format(137, "video/mp4; codecs=\"avc1\"", 4_000_000)

    @Test
    fun `opus first, then AAC, never video`() {
        assertEquals(251, pickAudio(listOf(video, aac, opus))?.itag)
        assertEquals(140, pickAudio(listOf(video, aac))?.itag)
        assertNull(pickAudio(listOf(video)))
    }

    @Test
    fun `only formats with a direct address`() {
        val cipheredOpus = format(251, "audio/webm; codecs=\"opus\"", 160_000, url = null)
        assertEquals(140, pickAudio(listOf(cipheredOpus, aac))?.itag)
        val other = format(600, "audio/webm; codecs=\"opus\"", 70_000)
        assertEquals(600, pickAudio(listOf(cipheredOpus, other))?.itag)
    }

    @Test
    fun `the bot check, not age or a private video`() {
        assertTrue(isBotCheck("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot"))
        assertTrue(isBotCheck("LOGIN_REQUIRED", "Войдите в аккаунт, чтобы подтвердить, что вы не бот"))
        assertTrue(isBotCheck("LOGIN_REQUIRED", null))
        assertFalse(isBotCheck("LOGIN_REQUIRED", "Sign in to confirm your age"))
        assertFalse(isBotCheck("LOGIN_REQUIRED", "This video is private"))
        assertFalse(isBotCheck("UNPLAYABLE", "Video unavailable"))
        assertFalse(isBotCheck("OK", null))
    }

    @Test
    fun `a closed address is not asked again in the background`() = kotlinx.coroutines.runBlocking {
        BlockedAddress.mark()
        try {
            // No client is asked: the list is empty, and still the answer is the bot check, not "nothing found"
            val error = runCatching { resolveDirect("dQw4w9WgXcQ", emptyList(), mutableListOf(), background = true) }
                .exceptionOrNull()
            assertTrue(error is BotCheckException)
            // The player (a user's action) is not stopped by the memory: with no clients it simply finds nothing
            assertNull(resolveDirect("dQw4w9WgXcQ", emptyList(), mutableListOf(), background = false))
        } finally {
            BlockedAddress.clear()
        }
        assertFalse(BlockedAddress.isBlocked)
    }

    private fun refused(code: Int) = java.io.IOException(
        "wrapped",
        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            code, null, null, emptyMap(), androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://rr1.googlevideo.com/x")),
            ByteArray(0)
        )
    )

    @Test
    fun `a refused address drops the YouTube session, other answers keep it`() {
        val kept = app.melogold.providers.innertube.requests.StreamVisitor
        kept.visitorData = "flagged"
        assertFalse(renewSessionAfterRefusal(refused(416)))
        assertFalse(renewSessionAfterRefusal(java.io.IOException("timeout")))
        assertEquals("flagged", kept.visitorData)

        assertTrue(renewSessionAfterRefusal(refused(403)))
        assertNull(kept.visitorData, "a fresh address of the flagged session is cut after the first megabyte too")
    }
}
