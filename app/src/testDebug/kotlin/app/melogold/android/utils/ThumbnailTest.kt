package app.melogold.android.utils

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Artwork sizes (REWRITE §4.8.2): video frames are 16:9 without black bars at every size. */
class ThumbnailTest {
    @Test
    fun `a video frame is mqdefault up to 360 px and hq720 above`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", url.thumbnail(720))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", url.thumbnail(120))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", url.thumbnail(360))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", url.thumbnail(361))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", url.thumbnail(MAX_THUMBNAIL_SIZE * 2))
    }

    @Test
    fun `signed crop parameters are dropped with the name they were signed for`() {
        val signed = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" +
            "?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLBzI6hs"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", signed.thumbnail(720))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", signed.thumbnail(120))

        val sd = "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg?sqp=-oaymwEWCJADEOEBIAQqCghqEJQEGHgg6AJIWg&rs=x"
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", sd.thumbnail(544))
    }

    @Test
    fun `webp and protocol-relative frames become the jpg frames`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp".thumbnail(1080)
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            "//i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg".thumbnail(100)
        )
    }

    @Test
    fun `covers are not video frames`() {
        val lh3 = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        assertFalse(lh3.isVideoFrame)
        assertEquals("$lh3-w544-h544", lh3.thumbnail(544))
        assertEquals("$lh3-w544-h544", lh3.squareThumbnail(544))

        val yt3 = "https://yt3.ggpht.com/abc=s88"
        assertEquals("$yt3-w88-h88-s88", yt3.thumbnail(88))

        val content = "content://media/external/audio/albumart/1"
        assertEquals(content, content.thumbnail(720))
        assertFalse("https://i.ytimg.com/an_webp/dQw4w9WgXcQ/mqdefault_6s.webp".isVideoFrame)
    }

    @Test
    fun `a square picks the frame by its height`() {
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertTrue(url.isVideoFrame)
        assertEquals("dQw4w9WgXcQ", url.videoFrameId)
        // A 56 dp row: 180 px of mqdefault cover it
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", url.squareThumbnail(147))
        // 300 px high need more than mqdefault has
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", url.squareThumbnail(300))
    }
}
