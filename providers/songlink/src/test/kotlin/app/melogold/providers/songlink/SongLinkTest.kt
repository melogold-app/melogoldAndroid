package app.melogold.providers.songlink

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String) = checkNotNull(SongLinkTest::class.java.getResourceAsStream("/songlink/$name")) { name }
    .use { it.readBytes().decodeToString() }

/** tasks/0017: the answers of song.link, and the client that asks for them one at a time. */
class SongLinkTest {
    // region The answers

    @Test
    fun `a track has its link on YouTube Music, the name and the artist`() {
        val answer = parseSongLink(fixture("answer-track.json"))!!

        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", answer.youtubeUrl)
        assertEquals("Never Gonna Give You Up", answer.title, "the entity of the link, not the video's long name")
        assertEquals("Rick Astley", answer.artist)
        assertEquals(false, answer.isAlbum)
        assertEquals("Never Gonna Give You Up Rick Astley", answer.searchText)
    }

    @Test
    fun `an album has the playlist of its album on YouTube Music`() {
        val answer = parseSongLink(fixture("answer-album.json"))!!

        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_kAbCdEfGhIjKlMnOpQrStUvWxYz", answer.youtubeUrl)
        assertEquals(true, answer.isAlbum)
        assertEquals("Whenever You Need Somebody Rick Astley", answer.searchText)
    }

    @Test
    fun `without YouTube Music it is the link on YouTube`() {
        assertEquals("https://www.youtube.com/watch?v=gAjR4_CbPpQ", parseSongLink(fixture("answer-only-youtube.json"))!!.youtubeUrl)
    }

    @Test
    fun `without either it is still a name to search for`() {
        val answer = parseSongLink(fixture("answer-no-youtube.json"))!!

        assertNull(answer.youtubeUrl)
        assertEquals("Группа крови Кино", answer.searchText)
    }

    @Test
    fun `what is not an answer is nothing`() {
        assertNull(parseSongLink(""))
        assertNull(parseSongLink("not json"))
        assertNull(parseSongLink("""{"statusCode":401,"code":"PUBLIC_API_ACCESS_DEPRECATED"}"""))
        assertNull(parseSongLink("""{"linksByPlatform":{"spotify":{"url":"https://open.spotify.com/track/x"}}}"""))
        assertNull(parseSongLink("[]"))
    }

    // endregion

    // region The client

    private class MemoryCache : SongLinkCache {
        val entries = mutableMapOf<String, String>()
        override fun get(url: String) = entries[url]
        override fun put(url: String, body: String) {
            entries[url] = body
        }
    }

    private class Server(val respond: (Int) -> Pair<HttpStatusCode, String>) {
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.toString()
                val (status, body) = respond(requests.size)
                respond(body, status, headersOf("Content-Type", "application/json"))
            }
        )
    }

    private val link = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"

    @Test
    fun `it asks with the link, the key and the version of the API, and gets the answer`() = runTest {
        val server = Server { HttpStatusCode.OK to fixture("answer-track.json") }
        val client = SongLinkClient(apiKey = "secret", http = server.client, pauseMs = 0)

        val result = client.resolve(link)

        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", assertIs<SongLinkResult.Found>(result).answer.youtubeUrl)
        val url = server.requests.single()
        assertTrue(url.startsWith("https://api.song.link/v1-alpha.1/links?"), url)
        assertTrue("url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F4cOdK2wGLETKBW3PvgPWqT" in url, url)
        assertTrue("key=secret" in url, url)
    }

    @Test
    fun `without a key it does not ask at all, the free access is over`() = runTest {
        val server = Server { HttpStatusCode.OK to fixture("answer-track.json") }

        assertEquals(SongLinkResult.Failed(SongLinkFailure.KeyRequired), SongLinkClient(apiKey = null, http = server.client).resolve(link))
        assertEquals(SongLinkResult.Failed(SongLinkFailure.KeyRequired), SongLinkClient(apiKey = "  ", http = server.client).resolve(link))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a refused key is remembered and nothing is asked again`() = runTest {
        val server = Server { HttpStatusCode.Unauthorized to """{"statusCode":401,"code":"PUBLIC_API_ACCESS_DEPRECATED"}""" }
        val client = SongLinkClient(apiKey = "wrong", http = server.client, pauseMs = 0)

        assertEquals(SongLinkResult.Failed(SongLinkFailure.KeyRequired), client.resolve(link))
        assertEquals(SongLinkResult.Failed(SongLinkFailure.KeyRequired), client.resolve("$link?x=1"))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `not known, too many requests and a broken server are told apart`() = runTest {
        var status = HttpStatusCode.NotFound
        val server = Server { status to "{}" }
        val client = SongLinkClient(apiKey = "k", http = server.client, pauseMs = 0)

        assertEquals(SongLinkResult.Failed(SongLinkFailure.NotFound), client.resolve("$link?a"))
        status = HttpStatusCode.BadRequest
        assertEquals(SongLinkResult.Failed(SongLinkFailure.NotFound), client.resolve("$link?b"))
        status = HttpStatusCode.TooManyRequests
        assertEquals(SongLinkResult.Failed(SongLinkFailure.Unavailable), client.resolve("$link?c"))
        status = HttpStatusCode.BadGateway
        assertEquals(SongLinkResult.Failed(SongLinkFailure.Unavailable), client.resolve("$link?d"))
        status = HttpStatusCode.OK
        assertEquals(SongLinkResult.Failed(SongLinkFailure.NotFound), client.resolve("$link?e"), "a 200 that is not an answer")
    }

    @Test
    fun `no network is unavailable, not a crash`() = runTest {
        val client = SongLinkClient(apiKey = "k", http = HttpClient(MockEngine { throw java.io.IOException("offline") }), pauseMs = 0)

        assertEquals(SongLinkResult.Failed(SongLinkFailure.Unavailable), client.resolve(link))
    }

    @Test
    fun `an answer is kept for a day and the same link is not asked twice`() = runTest {
        val server = Server { HttpStatusCode.OK to fixture("answer-track.json") }
        val cache = MemoryCache()
        val client = SongLinkClient(apiKey = "k", http = server.client, cache = cache, pauseMs = 0)

        client.resolve(link)
        val again = client.resolve(link)

        assertEquals(1, server.requests.size)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", assertIs<SongLinkResult.Found>(again).answer.youtubeUrl)
        assertTrue(link in cache.entries)
    }

    @Test
    fun `a failure is not kept`() = runTest {
        val server = Server { n -> if (n == 1) HttpStatusCode.BadGateway to "" else HttpStatusCode.OK to fixture("answer-track.json") }
        val cache = MemoryCache()
        val client = SongLinkClient(apiKey = "k", http = server.client, cache = cache, pauseMs = 0)

        assertIs<SongLinkResult.Failed>(client.resolve(link))
        assertIs<SongLinkResult.Found>(client.resolve(link))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `requests go one at a time, with a pause between them`() = runTest {
        var clock = 0L
        val server = Server { HttpStatusCode.OK to fixture("answer-track.json") }
        val client = SongLinkClient(apiKey = "k", http = server.client, now = { clock }, pauseMs = 1_500)

        client.resolve("$link?1")
        // The second comes at once: it waits out the pause, and the virtual time moves by it
        val before = testScheduler.currentTime
        clock = 100
        client.resolve("$link?2")
        assertTrue(testScheduler.currentTime - before >= 1_400, "waited ${testScheduler.currentTime - before} ms")
        assertEquals(2, server.requests.size)
    }

    // endregion
}
