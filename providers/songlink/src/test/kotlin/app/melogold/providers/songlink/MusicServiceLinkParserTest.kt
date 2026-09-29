package app.melogold.providers.songlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** tasks/0017: which links of other services Melogold recognizes, and what each is. */
class MusicServiceLinkParserTest {
    private fun kind(url: String) = MusicServiceLinkParser.parse(url)?.let { it.service to it.kind }

    @Test
    fun `Spotify tracks, albums, artists and playlists, with or without a language in front`() {
        assertEquals(MusicService.Spotify to MusicLinkKind.Track, kind("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Track, kind("https://open.spotify.com/intl-de/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Album, kind("https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Artist, kind("https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Playlist, kind("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(kind("https://open.spotify.com/show/abc"), "a podcast is not music")
    }

    @Test
    fun `a short link of Spotify is Spotify, what it is is found by following it`() {
        assertEquals(MusicService.Spotify to MusicLinkKind.Unknown, kind("https://spotify.link/abc123XYZ"))
        assertEquals(MusicService.Spotify to MusicLinkKind.Unknown, kind("https://spotify.app.link/abc123XYZ"))
    }

    @Test
    fun `Apple Music songs, albums, the track of an album, artists and playlists`() {
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Track, kind("https://music.apple.com/us/song/never-gonna-give-you-up/1559523359"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Album, kind("https://music.apple.com/us/album/whenever-you-need-somebody/1559523357"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Track, kind("https://music.apple.com/ru/album/whenever-you-need-somebody/1559523357?i=1559523359"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Artist, kind("https://music.apple.com/us/artist/rick-astley/669771"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Playlist, kind("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"))
        assertEquals(MusicService.AppleMusic to MusicLinkKind.Track, kind("https://itunes.apple.com/us/song/x/1559523359"))
    }

    @Test
    fun `Yandex Music in every domain of it`() {
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Track, kind("https://music.yandex.ru/album/1179999/track/609676"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Track, kind("https://music.yandex.com/track/609676"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Album, kind("https://music.yandex.ru/album/1179999"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Artist, kind("https://music.yandex.by/artist/36800"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Playlist, kind("https://music.yandex.ru/users/yamusic-daily/playlists/1000"))
        assertEquals(MusicService.YandexMusic to MusicLinkKind.Playlist, kind("https://music.yandex.ru/playlists/3fa85f64-5717-4562-b3fc-2c963f66afa6"))
    }

    @Test
    fun `Deezer, Tidal and SoundCloud`() {
        assertEquals(MusicService.Deezer to MusicLinkKind.Track, kind("https://www.deezer.com/en/track/3135556"))
        assertEquals(MusicService.Deezer to MusicLinkKind.Track, kind("https://deezer.com/track/3135556"))
        assertEquals(MusicService.Deezer to MusicLinkKind.Album, kind("https://www.deezer.com/ru/album/302127"))
        assertEquals(MusicService.Deezer to MusicLinkKind.Unknown, kind("https://link.deezer.com/s/32abc"))
        assertEquals(MusicService.Tidal to MusicLinkKind.Track, kind("https://tidal.com/browse/track/491206012"))
        assertEquals(MusicService.Tidal to MusicLinkKind.Album, kind("https://listen.tidal.com/album/491206010"))
        assertEquals(MusicService.SoundCloud to MusicLinkKind.Track, kind("https://soundcloud.com/rick-astley-official/never-gonna-give-you-up-4"))
        assertEquals(MusicService.SoundCloud to MusicLinkKind.Playlist, kind("https://soundcloud.com/user/sets/my-set"))
        assertEquals(MusicService.SoundCloud to MusicLinkKind.Artist, kind("https://soundcloud.com/rick-astley-official"))
    }

    @Test
    fun `the link is found in a shared text and the punctuation after it is not the link`() {
        val link = MusicServiceLinkParser.parse("Слушай, это огонь: https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT.")

        assertEquals("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", link?.url)
    }

    @Test
    fun `YouTube, other hosts and words are not links of other services`() {
        assertNull(MusicServiceLinkParser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(MusicServiceLinkParser.parse("https://example.com/track/1"))
        assertNull(MusicServiceLinkParser.parse("open.spotify.com/track/abc"), "no scheme")
        assertNull(MusicServiceLinkParser.parse("что-то ещё"))
        assertNull(MusicServiceLinkParser.parse(""))
    }
}
