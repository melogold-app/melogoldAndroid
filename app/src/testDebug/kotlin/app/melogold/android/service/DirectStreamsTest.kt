package app.melogold.android.service

import app.melogold.providers.innertube.models.PlayerResponse.StreamingData.AdaptiveFormat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The format of a stream client's answer, and what counts as YouTube's bot check. */
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
}
