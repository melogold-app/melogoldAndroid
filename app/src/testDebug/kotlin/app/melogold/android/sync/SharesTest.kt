package app.melogold.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.models.Song
import app.melogold.android.models.TrackOverride
import app.melogold.android.sync.api.TrackInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0017: what an own playlist is shared as, without a server to make a link on. */
@RunWith(RobolectricTestRunner::class)
class SharesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun input(id: String) = TrackInput(videoId = id, title = id)

    private fun ids(count: Int) = (1..count).map { "video%06d".format(it).padEnd(11, 'x').take(11) }

    @Test
    fun `signed out, the playlist is a list of its first fifty videos on YouTube`() = runBlocking(Dispatchers.IO) {
        val shares = Shares(Account(context))

        val shared = shares.sharePlaylist("Дорога", ids(70).map(::input))

        val onYouTube = assertIs<PlaylistShare.OnYouTube>(shared)
        assertEquals(50, onYouTube.shown)
        assertEquals(70, onYouTube.total)
        assertTrue(onYouTube.url.startsWith("https://www.youtube.com/watch_videos?video_ids="), onYouTube.url)
        assertEquals(ids(50), onYouTube.url.substringAfter("video_ids=").split(","))
    }

    @Test
    fun `files of the device are not shared, a playlist of nothing else has nothing to share`() = runBlocking(Dispatchers.IO) {
        val shares = Shares(Account(context))

        assertEquals(PlaylistShare.NoTracks, shares.sharePlaylist("Файлы", listOf(input("local:42"), input("local:43"))))
        assertEquals(PlaylistShare.NoTracks, shares.sharePlaylist("Пусто", emptyList()))
        val mixed = assertIs<PlaylistShare.OnYouTube>(shares.sharePlaylist("Смесь", listOf(input("local:42"), input("dQw4w9WgXcQ"))))
        assertEquals(1, mixed.total)
    }

    @Test
    fun `it does not know a server it cannot ask`() = runBlocking(Dispatchers.IO) {
        val account = Account(context).also { it.setServer("http://127.0.0.1:1") }

        assertEquals(false, Shares(account).available())
    }

    @Test
    fun `a song goes into a snapshot with the names the person gave it`() {
        val song = Song(
            id = "abcdefghijk",
            title = "Artist — Song (fan upload)",
            artistsText = "Some Channel",
            durationText = "3:45",
            thumbnailUrl = "https://i.example/a.jpg",
            explicit = true
        )

        val plain = song.toShareInput()
        assertEquals("Artist — Song (fan upload)", plain.title)
        assertEquals("Some Channel", plain.artistsText)
        assertEquals(225_000L, plain.durationMs)
        assertEquals("3:45", plain.durationText)
        assertEquals(true, plain.explicit)
        assertNull(plain.albumTitle)

        val named = song.toShareInput(TrackOverride("abcdefghijk", title = "Song", artistsText = "Artist", albumTitle = "Lost album", updatedAt = 1))
        assertEquals("Song", named.title)
        assertEquals("Artist", named.artistsText)
        assertEquals("Lost album", named.albumTitle)

        assertNull(song.copy(explicit = false, durationText = null).toShareInput().explicit, "false is left out")
        assertNull(song.copy(durationText = null).toShareInput().durationMs)
    }
}
