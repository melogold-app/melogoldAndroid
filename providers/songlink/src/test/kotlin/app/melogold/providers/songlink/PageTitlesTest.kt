package app.melogold.providers.songlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun page(name: String) = checkNotNull(PageTitlesTest::class.java.getResourceAsStream("/songlink/$name.html")) { name }
    .use { it.readBytes().decodeToString() }

/** tasks/0017: the words to search for, taken from the head of the page of a link (real heads, cut to the tags read). */
class PageTitlesTest {
    private fun query(service: MusicService, kind: MusicLinkKind, name: String) =
        PageTitles.searchText(MusicServiceLink(service, kind, "https://example.test/x"), page(name))

    @Test
    fun `Spotify says the name in og title and the artist first in the description`() {
        assertEquals("Never Gonna Give You Up Rick Astley", query(MusicService.Spotify, MusicLinkKind.Track, "spotify-track"))
        assertEquals("Whenever You Need Somebody Rick Astley", query(MusicService.Spotify, MusicLinkKind.Album, "spotify-album"))
        assertEquals("Rick Astley", query(MusicService.Spotify, MusicLinkKind.Artist, "spotify-artist"))
    }

    @Test
    fun `Apple Music says both in the title, with an invisible mark in front`() {
        assertEquals("Never Gonna Give You Up Rick Astley", query(MusicService.AppleMusic, MusicLinkKind.Track, "apple-track"))
        assertEquals("3 Originals Rick Astley", query(MusicService.AppleMusic, MusicLinkKind.Album, "apple-album"))
    }

    @Test
    fun `Yandex Music has the name and the artist after a dot in the description`() {
        assertEquals("Never Gonna Give You Up Rick Astley", query(MusicService.YandexMusic, MusicLinkKind.Track, "yandex-track"))
        assertEquals("De Verdade Bokaloka", query(MusicService.YandexMusic, MusicLinkKind.Album, "yandex-album"))
    }

    @Test
    fun `Tidal and Deezer put the artist and the title in one line`() {
        assertEquals("Never Gonna Give You Up Rick Astley", query(MusicService.Tidal, MusicLinkKind.Track, "tidal-track"))
        assertEquals("Daft Punk Harder, Better, Faster, Stronger", query(MusicService.Deezer, MusicLinkKind.Track, "deezer-track"))
    }

    @Test
    fun `SoundCloud gives a title of its own and so nothing to search for`() {
        assertNull(query(MusicService.SoundCloud, MusicLinkKind.Track, "soundcloud-track"))
    }

    @Test
    fun `entities and attribute order do not matter, a long text is cut`() {
        val html = """<html><head><TITLE>Tom &amp; Jerry &#8211; Theme - song and lyrics by A&#x27;B | Spotify</TITLE>
            <meta content="x" property="og:title"></head></html>"""
        assertEquals("Tom & Jerry – Theme A'B", PageTitles.searchText(MusicServiceLink(MusicService.Spotify, MusicLinkKind.Track, "u"), html))

        val long = "<title>" + "a".repeat(300) + " - song by B | Spotify</title>"
        assertEquals(120, PageTitles.searchText(MusicServiceLink(MusicService.Spotify, MusicLinkKind.Track, "u"), long)?.length)
        assertNull(PageTitles.searchText(MusicServiceLink(MusicService.Spotify, MusicLinkKind.Artist, "u"), "<html></html>"))
    }
}
