package app.melogold.providers.songlink

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** tasks/0017: song.link first, then the title of the page; a playlist and an artist never go to song.link. */
class ExternalLinkResolverTest {
    private val track = MusicServiceLink(MusicService.Spotify, MusicLinkKind.Track, "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")

    private class Fakes(songLinkStatus: HttpStatusCode, songLinkBody: String, val pageBody: String?) {
        val songLinkRequests = mutableListOf<String>()
        val pageRequests = mutableListOf<String>()
        val songLink = SongLinkClient(
            apiKey = "k",
            pauseMs = 0,
            http = HttpClient(
                MockEngine { request ->
                    songLinkRequests += request.url.toString()
                    respond(songLinkBody, songLinkStatus)
                }
            )
        )
        val pages = PageFetcher(
            HttpClient(
                MockEngine { request ->
                    pageRequests += request.url.toString()
                    if (pageBody == null) respond("", HttpStatusCode.NotFound)
                    else respond(pageBody, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                }
            )
        )
        val resolver get() = ExternalLinkResolver(songLink, pages)
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/songlink/$name")).use { it.readBytes().decodeToString() }

    @Test
    fun `song-link knows the track on YouTube Music, that is where it leads and the page is not read`() = runTest {
        val fakes = Fakes(HttpStatusCode.OK, fixture("answer-track.json"), pageBody = "<title>never read</title>")

        val resolution = fakes.resolver.resolve(track)

        assertEquals(ExternalResolution.OnYouTube("https://music.youtube.com/watch?v=dQw4w9WgXcQ", isAlbum = false), resolution)
        assertTrue(fakes.pageRequests.isEmpty())
    }

    @Test
    fun `an album leads to its playlist and says it is an album`() = runTest {
        val fakes = Fakes(HttpStatusCode.OK, fixture("answer-album.json"), pageBody = null)
        val album = track.copy(kind = MusicLinkKind.Album)

        val resolution = fakes.resolver.resolve(album)

        assertEquals(
            ExternalResolution.OnYouTube("https://music.youtube.com/playlist?list=OLAK5uy_kAbCdEfGhIjKlMnOpQrStUvWxYz", isAlbum = true),
            resolution
        )
    }

    @Test
    fun `song-link knows the name but not a YouTube link, so it is searched for`() = runTest {
        val fakes = Fakes(HttpStatusCode.OK, fixture("answer-no-youtube.json"), pageBody = null)

        assertEquals(ExternalResolution.Search("Группа крови Кино"), fakes.resolver.resolve(track))
        assertTrue(fakes.pageRequests.isEmpty())
    }

    @Test
    fun `when song-link does not answer the title of the page is what is searched for`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway)) {
            val fakes = Fakes(status, "{}", pageBody = fixtureHtml("spotify-track"))

            assertEquals(ExternalResolution.Search("Never Gonna Give You Up Rick Astley"), fakes.resolver.resolve(track), "$status")
            assertEquals(listOf(track.url), fakes.pageRequests, "$status")
        }
    }

    @Test
    fun `an artist is looked for by the name on its page, song-link is not asked`() = runTest {
        val fakes = Fakes(HttpStatusCode.OK, fixture("answer-track.json"), pageBody = fixtureHtml("spotify-artist"))

        val resolution = fakes.resolver.resolve(track.copy(kind = MusicLinkKind.Artist))

        assertEquals(ExternalResolution.Search("Rick Astley"), resolution)
        assertTrue(fakes.songLinkRequests.isEmpty())
    }

    @Test
    fun `a page that cannot be read or says nothing is nothing`() = runTest {
        assertEquals(ExternalResolution.NotFound, Fakes(HttpStatusCode.NotFound, "{}", pageBody = null).resolver.resolve(track))
        assertEquals(ExternalResolution.NotFound, Fakes(HttpStatusCode.NotFound, "{}", pageBody = "<html></html>").resolver.resolve(track))
    }

    private fun fixtureHtml(name: String) = fixture("$name.html")
}
