package app.melogold.android.ui.shell

import app.melogold.domain.server.MelogoldLink
import app.melogold.domain.share.ShareRef
import app.melogold.providers.innertube.links.LinkTarget
import app.melogold.providers.songlink.MusicLinkKind
import app.melogold.providers.songlink.MusicService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** tasks/0017: what a link, a pasted or a shared text is for the app, in the order the app tries. */
class AppLinksTest {
    private val ref = ShareRef("https://music.example.com", "a1B2c3D4e5")

    @Test
    fun `links of YouTube and YouTube Music stay what they were`() {
        assertEquals(
            AppLink.YouTube(LinkTarget.Video("dQw4w9WgXcQ")),
            classifyLink("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(AppLink.YouTube(LinkTarget.Album("MPREb_abc")), classifyLink("https://music.youtube.com/browse/MPREb_abc"))
        assertEquals(AppLink.YouTube(LinkTarget.Channel("UCabc")), classifyLink("https://music.youtube.com/channel/UCabc"))
        assertEquals(AppLink.YouTube(LinkTarget.Playlist("PLabc")), classifyLink("https://music.youtube.com/playlist?list=PLabc"))
    }

    @Test
    fun `words are a search, not a link`() {
        assertEquals(AppLink.YouTube(LinkTarget.Search("кино группа крови")), classifyLink("кино группа крови"))
    }

    @Test
    fun `server and device links of the app are app links (API 7_2)`() {
        val server = assertIs<AppLink.App>(classifyLink("melogold://server?v=1&url=https%3A%2F%2Fmusic.example.com"))
        assertEquals(MelogoldLink.Server("https://music.example.com", insecure = false, serverId = null), server.link)
        val invite = assertIs<AppLink.App>(
            classifyLink(
                "melogold://link?v=1&mode=invite&server=https%3A%2F%2Fmusic.example.com" +
                    "&sid=6f1c2c0e-8a3b-4f7e-9c1d-2b5e7a9f0c11&token=q3JdV0hZxK2mP9sT4uW7yB1cE5fH8jL0nR3vX6zA2dG"
            )
        )
        assertIs<MelogoldLink.DeviceLink>(invite.link)
        assertIs<AppLink.YouTube>(classifyLink("melogold://unknown?v=1"))
    }

    @Test
    fun `the link of the app opens the shared playlist`() {
        assertEquals(
            AppLink.SharedPlaylist(ref),
            classifyLink("melogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5")
        )
        assertEquals(AppLink.SharedPlaylist(ref), classifyLink("Дорога — Кино\nmelogold://share?v=1&url=https%3A%2F%2Fmusic.example.com&id=a1B2c3D4e5"))
    }

    @Test
    fun `a broken link of the app is a link nobody can open, not a search for its text`() {
        val link = classifyLink("melogold://share?v=1&id=a1B2c3D4e5")

        assertIs<LinkTarget.Unsupported>(assertIs<AppLink.YouTube>(link).target)
        assertIs<LinkTarget.Unsupported>(assertIs<AppLink.YouTube>(classifyLink("melogold://other")).target)
    }

    @Test
    fun `the page of a shared playlist, on any server, is the same playlist`() {
        assertEquals(AppLink.SharedPlaylist(ref), classifyLink("https://music.example.com/s/a1B2c3D4e5"))
        assertEquals(AppLink.SharedPlaylist(ref), classifyLink("Слушай\nhttps://music.example.com/s/a1B2c3D4e5"))
        assertEquals(
            AppLink.SharedPlaylist(ShareRef("http://192.168.1.50:8080", "a1B2c3D4e5")),
            classifyLink("http://192.168.1.50:8080/s/a1B2c3D4e5")
        )
    }

    @Test
    fun `tracks, albums and playlists of other services are told apart`() {
        fun other(text: String) = assertIs<AppLink.OtherService>(classifyLink(text)).link.let { it.service to it.kind }

        assertEquals(MusicService.Spotify to MusicLinkKind.Track, other("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Album, other("https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Playlist, other("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Track, other("https://music.apple.com/us/album/x/1559523357?i=1559523359"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Track, other("https://music.yandex.ru/album/1179999/track/609676"))
        assertEquals(MusicService.Deezer to MusicLinkKind.Track, other("https://www.deezer.com/en/track/3135556"))
        assertEquals(MusicService.Tidal to MusicLinkKind.Album, other("https://tidal.com/browse/album/491206010"))
    }

    @Test
    fun `a link nobody knows stays unsupported`() {
        val link = classifyLink("https://example.com/whatever")

        assertIs<LinkTarget.Unsupported>(assertIs<AppLink.YouTube>(link).target)
    }
}
