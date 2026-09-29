package app.melogold.domain.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** tasks/0017: the links the app shares and the links to a shared playlist that it opens. */
class ShareLinksTest {
    @Test
    fun `a track of the catalog is on YouTube Music, a plain video on YouTube`() {
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", ShareLinks.track("dQw4w9WgXcQ", isMusic = true))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", ShareLinks.track("dQw4w9WgXcQ", isMusic = false))
    }

    @Test
    fun `an album is a browse page, an artist a channel of music or of YouTube`() {
        assertEquals("https://music.youtube.com/browse/MPREb_abc123", ShareLinks.album("MPREb_abc123"))
        assertEquals("https://music.youtube.com/channel/UCkinoBrowse", ShareLinks.artist("UCkinoBrowse", channel = false))
        assertEquals("https://www.youtube.com/channel/UCsomeChannel", ShareLinks.artist("UCsomeChannel", channel = true))
    }

    @Test
    fun `a playlist of YouTube drops the VL of a browse id`() {
        assertEquals("https://music.youtube.com/playlist?list=PLabc", ShareLinks.playlist("VLPLabc"))
        assertEquals("https://music.youtube.com/playlist?list=RDCLAK5uy_x", ShareLinks.playlist("RDCLAK5uy_x"))
    }

    @Test
    fun `an own playlist without a server is the first fifty videos as one list`() {
        val ids = (1..60).map { "video%06d".format(it).padEnd(11, 'x').take(11) }

        val link = ShareLinks.watchVideos(ids)!!

        assertEquals("https://www.youtube.com/watch_videos?video_ids=", link.substringBefore("=") + "=")
        val listed = link.substringAfter("video_ids=").split(",")
        assertEquals(50, listed.size)
        assertEquals(ids.take(50), listed)
    }

    @Test
    fun `local files and broken ids are left out of the list, and nothing left is no link`() {
        val link = ShareLinks.watchVideos(listOf("local:42", "dQw4w9WgXcQ", "short", "a1B2c3D4e5F"))

        assertEquals("https://www.youtube.com/watch_videos?video_ids=dQw4w9WgXcQ,a1B2c3D4e5F", link)
        assertNull(ShareLinks.watchVideos(listOf("local:1", "nope")))
        assertNull(ShareLinks.watchVideos(emptyList()))
    }

    @Test
    fun `the message is the title, the artist and the link`() {
        assertEquals("Кино — Группа крови\nhttps://x.example/a", ShareLinks.message("Кино", "Группа крови", "https://x.example/a"))
        assertEquals("Дорога\nhttps://x.example/a", ShareLinks.message("  Дорога ", null, "https://x.example/a"))
        assertEquals("Дорога\nhttps://x.example/a", ShareLinks.message("Дорога", "  ", "https://x.example/a"))
        assertEquals("https://x.example/a", ShareLinks.message("", null, "https://x.example/a"))
    }

    @Test
    fun `the link of the app carries the server and the id, percent-encoded`() {
        assertEquals(
            "melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5",
            ShareLinks.melogoldShare("https://music.example.com", "a1B2c3D4e5")
        )
    }

    // region Opening

    private val ref = ShareRef("https://music.example.com", "a1B2c3D4e5")

    @Test
    fun `the deep link of the app is read, with its server`() {
        assertEquals(ref, ShareLinkParser.parse("melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"))
        // Any order, unknown parameters ignored, a server with a path prefix and a port
        assertEquals(
            ShareRef("https://host.example:8443/melogold", "a1B2c3D4e5"),
            ShareLinkParser.parse("melogold://share?id=a1B2c3D4e5&x=1&url=https%3A%2F%2FHOST.example%3A8443%2Fmelogold%2F&v=1")
        )
        // What the round trip of the builder gives back
        assertEquals(ref, ShareLinkParser.parse(ShareLinks.melogoldShare(ref.serverUrl, ref.shareId)))
    }

    @Test
    fun `the deep link needs version 1, a valid server and a valid id`() {
        assertNull(ShareLinkParser.parse("melogold://share?url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"), "no version")
        assertNull(ShareLinkParser.parse("melogold://share?v=2&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"))
        assertNull(ShareLinkParser.parse("melogold://share?v=1&id=a1B2c3D4e5"), "no server")
        assertNull(ShareLinkParser.parse("melogold://share?v=1&url=ftp%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"))
        assertNull(ShareLinkParser.parse("melogold://share?v=1&url=http%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"), "plain http to the internet")
        assertNull(ShareLinkParser.parse("melogold://share?v=1&url=https%3A%2F%2Fu%3Ap%40music.example.com&id=a1B2c3D4e5"), "a login in the address")
        assertNull(ShareLinkParser.parse("melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=short"))
        assertNull(ShareLinkParser.parse("melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5-"))
        assertNull(ShareLinkParser.parse("melogold://server?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"), "another host of the scheme")
    }

    @Test
    fun `the link of the page is read, wherever the server is`() {
        assertEquals(ref, ShareLinkParser.parse("https://music.example.com/s/a1B2c3D4e5"))
        assertEquals(ref, ShareLinkParser.parse("https://MUSIC.example.com/s/a1B2c3D4e5/"))
        assertEquals(ShareRef("https://music.example.com/melogold", "a1B2c3D4e5"), ShareLinkParser.parse("https://music.example.com/melogold/s/a1B2c3D4e5"))
        assertEquals(ShareRef("http://192.168.1.50:8080", "a1B2c3D4e5"), ShareLinkParser.parse("http://192.168.1.50:8080/s/a1B2c3D4e5"), "plain http at home")
        assertEquals(ref, ShareLinkParser.parse("https://music.example.com/s/a1B2c3D4e5?utm=1#top"))
    }

    @Test
    fun `it is found in a shared text, after the title and before the punctuation`() {
        assertEquals(ref, ShareLinkParser.parse("Дорога\nhttps://music.example.com/s/a1B2c3D4e5"))
        assertEquals(ref, ShareLinkParser.parse("Смотри: (https://music.example.com/s/a1B2c3D4e5)."))
        assertEquals(ref, ShareLinkParser.parse("Открой в приложении melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5."))
    }

    @Test
    fun `other links are not the link of a shared playlist`() {
        assertNull(ShareLinkParser.parse("https://music.example.com/shares/a1B2c3D4e5"))
        assertNull(ShareLinkParser.parse("https://music.example.com/s/tooShort"))
        assertNull(ShareLinkParser.parse("https://music.example.com/s/waytoolongidhere"))
        assertNull(ShareLinkParser.parse("https://music.example.com/x/s/a1B2c3D4e5/extra"))
        assertNull(ShareLinkParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ShareLinkParser.parse("http://music.example.com/s/a1B2c3D4e5"), "plain http to the internet")
        assertNull(ShareLinkParser.parse("just words"))
        assertNull(ShareLinkParser.parse(""))
    }

    // endregion
}
