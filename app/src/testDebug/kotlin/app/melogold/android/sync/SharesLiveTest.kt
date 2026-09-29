package app.melogold.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.data.repo.SharedPlaylists
import app.melogold.android.internal
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.TrackInput
import app.melogold.domain.share.ShareLinkParser
import app.melogold.domain.share.ShareRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Links to own playlists against a real Melogold server given by `-Pmelogold.testServer=http://127.0.0.1:8787`
 * (tasks/0017, API §4.11): a snapshot is made, opened by anyone without signing in, saved to a library, listed in "My
 * links" and deleted, when it stops opening. Every test registers a throwaway account and deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class SharesLiveTest {
    private val server = System.getProperty("melogold.testServer")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val random = SecureRandom()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val password = "throwaway ${hex(12)}"
    private var account: Account? = null

    init {
        ApplicationProvider.getApplicationContext<MainApplication>()
    }

    @Before
    fun emptyLibrary() = runBlocking(Dispatchers.IO) { if (server != null) Database.internal.clearAllTables() }

    @After
    fun deleteAccount() {
        val current = account ?: return
        runCatching { current.session?.accessToken?.let { post(server!!, "/auth/me/delete", """{"password":"$password"}""", it) } }
    }

    private fun io(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    private suspend fun signedIn(): Account = Account(context).also {
        it.setServer(server!!)
        it.register("share${hex(6)}", password)
        account = it
    }

    private fun track(index: Int) = TrackInput(
        videoId = "vid%08d".format(index).take(11),
        title = "Трек $index",
        artistsText = "Кино",
        albumTitle = "Группа крови",
        durationMs = 225_000,
        durationText = "3:45",
        thumbnailUrl = "https://i.ytimg.com/vi/vid$index/hqdefault.jpg",
        explicit = index == 2
    )

    @Test
    fun `an own playlist is shared, opened by anyone, saved, listed and deleted`() = io {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val account = signedIn()
        val shares = Shares(account)
        assertTrue(shares.available(), "the server says features.share")

        // The link: <server>/s/<code>, which the app reads back as the same playlist
        val made = assertIs<PlaylistShare.OnServer>(shares.sharePlaylist("Дорога", (1..3).map(::track) + TrackInput(videoId = "local:42", title = "файл")))
        assertTrue(made.url.startsWith("$server/s/"), made.url)
        val ref = assertNotNull(ShareLinkParser.parse("Дорога\n${made.url}"))
        assertEquals(ShareRef(server!!, made.shareId), ref)

        // Anyone without an account opens it on the server of the link, in order, with what the snapshot knows
        val other = MelogoldApi(server)
        val dto = try {
            other.share(ref.shareId)
        } finally {
            other.close()
        }
        assertEquals("Дорога", dto.name)
        assertEquals("playlist", dto.kind)
        assertEquals(listOf("vid00000001", "vid00000002", "vid00000003"), dto.tracks.map { it.videoId }, "the file of the device stayed here")
        assertEquals("Трек 2", dto.tracks[1].title)
        assertEquals("Кино", dto.tracks[0].artistsText)
        assertEquals("Группа крови", dto.tracks[0].albumTitle)
        assertEquals("3:45", dto.tracks[0].durationText)
        assertEquals(true, dto.tracks[1].explicit)
        assertEquals(made.url, dto.url)

        // The app asks the server of the link, whichever it is: the account's own, or another address (a foreign one)
        assertEquals("Дорога", shares.open(ref).name)
        val foreign = ShareRef("http://localhost:${server.substringAfterLast(':')}", ref.shareId)
        assertEquals("Дорога", shares.open(foreign).name)

        // The page for browsers carries the deep link, which reads as the same playlist
        val page = get(server, "/s/${ref.shareId}")
        assertTrue("Дорога" in page, "the name is on the page")
        val deepLink = Regex("""melogold://share\?[^"'<\s]+""").find(page.replace("&amp;", "&"))?.value
        assertEquals(ref, ShareLinkParser.parse(assertNotNull(deepLink, "the button Open in Melogold")))

        // Save to Library: an own playlist in that order
        val id = assertNotNull(SharedPlaylists.save(dto))
        assertEquals(dto.tracks.map { it.videoId }, Database.playlistSongs(id).first().map { it.id })

        // My links: it is there, new ones first
        val second = assertIs<PlaylistShare.OnServer>(shares.sharePlaylist("Второй", listOf(track(9))))
        assertEquals(listOf(second.shareId, made.shareId), shares.mine().map { it.shareId })

        // Delete: the link stops opening for everyone
        shares.delete(made.shareId)
        val error = runCatching { shares.open(foreign) }.exceptionOrNull()
        assertEquals("share_not_found", (error as? ApiException)?.code)
        assertEquals(404, (error as? ApiException)?.status)
        assertEquals(listOf(second.shareId), shares.mine().map { it.shareId })
        // A link that was never made says the same
        val never = runCatching { shares.open(ShareRef(server!!, "zzzzzzzzzz")) }.exceptionOrNull()
        assertEquals("share_not_found", (never as? ApiException)?.code)
    }

    @Test
    fun `signed out, an own playlist is shared as a list on YouTube even if the server can make links`() = io {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val signedOut = Account(context).also { it.setServer(server!!) }

        val shared = Shares(signedOut).sharePlaylist("Дорога", (1..3).map(::track))

        assertIs<PlaylistShare.OnYouTube>(shared)
    }

    @Test
    fun `at two hundred links the server says so and the person is told to delete old ones`() = io {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val shares = Shares(signedIn())

        repeat(200) { index -> assertIs<PlaylistShare.OnServer>(shares.sharePlaylist("Плейлист $index", listOf(track(index + 1)))) }
        val over = shares.sharePlaylist("Лишний", listOf(track(500)))

        assertEquals(PlaylistShare.LimitReached(200), over)
        // Deleting one makes room
        shares.delete(shares.mine().first().shareId)
        assertIs<PlaylistShare.OnServer>(shares.sharePlaylist("Снова", listOf(track(501))))
    }

    private fun get(url: String?, path: String): String {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Accept-Language", "ru")
            val status = connection.responseCode
            val text = (if (status < HTTP_ERROR) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(status < HTTP_ERROR) { "$path: HTTP $status" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, path: String, body: String, token: String? = null): String {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status < HTTP_ERROR) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(status < HTTP_ERROR) { "$path: HTTP $status $text" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val HTTP_TIMEOUT_MS = 35_000
        const val HTTP_ERROR = 400
    }
}
