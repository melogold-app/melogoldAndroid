package app.melogold.android.data.repo

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Song
import app.melogold.android.sync.api.ArtistRef
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.sync.api.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** tasks/0017: "Save to Library" of a playlist by a link. */
@RunWith(RobolectricTestRunner::class)
class SharedPlaylistsTest {
    init {
        ApplicationProvider.getApplicationContext<MainApplication>()
    }

    private fun io(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    @Before
    fun emptyLibrary() = io { Database.internal.clearAllTables() }

    private fun track(id: String, title: String, artists: String? = "Кино") = TrackDto(
        videoId = id,
        title = title,
        artistsText = artists,
        artists = listOf(ArtistRef("UCkino", "Кино")),
        albumId = "MPREb_album",
        albumTitle = "Группа крови",
        durationText = "4:45",
        thumbnailUrl = "https://i.example/$id.jpg",
        explicit = id == "aaaaaaaaaa2"
    )

    private val share = ShareDto(
        shareId = "a1B2c3D4e5",
        kind = "playlist",
        name = "Дорога",
        url = "https://music.example.com/s/a1B2c3D4e5",
        tracks = listOf(
            track("aaaaaaaaaa1", "Группа крови"),
            track("aaaaaaaaaa2", "Пачка сигарет"),
            TrackDto(videoId = "aaaaaaaaaa3", title = "aaaaaaaaaa3", metadataStub = true),
            track("aaaaaaaaaa1", "Группа крови")
        ),
        createdAt = "2026-09-30T10:00:00.000Z"
    )

    @Test
    fun `the tracks become the songs the app plays, a stub is named by its id and a repeat is left out`() {
        val songs = share.songs()

        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa2", "aaaaaaaaaa3"), songs.map { it.id })
        assertEquals("Группа крови", songs[0].title)
        assertEquals("Кино", songs[0].artistsText)
        assertEquals("4:45", songs[0].durationText)
        assertEquals(true, songs[1].explicit)
        assertEquals("aaaaaaaaaa3", songs[2].title)
    }

    @Test
    fun `saving makes an own playlist in the order of the link, with their metadata`() = io {
        val id = assertNotNull(SharedPlaylists.save(share))

        val playlist = Database.playlistPreviews(app.melogold.core.data.enums.PlaylistSortBy.DateAdded, app.melogold.core.data.enums.SortOrder.Descending).first()
            .single { it.id == id }
        assertEquals("Дорога", playlist.name)
        assertEquals(3, playlist.songCount)

        val songs = Database.playlistSongs(id).first()
        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa2", "aaaaaaaaaa3"), songs.map { it.id })
        assertEquals("Пачка сигарет", songs[1].title)
        assertEquals("https://i.example/aaaaaaaaaa1.jpg", songs[0].thumbnailUrl)
        assertEquals("Кино", Database.songArtistInfo("aaaaaaaaaa1").single().name)
        assertEquals("Группа крови", Database.songAlbumInfo("aaaaaaaaaa1")?.name)
    }

    @Test
    fun `a track the library has keeps what it has and gets what it lacks`() = io {
        Database.insert(Song(id = "aaaaaaaaaa1", title = "Моё название", artistsText = null, durationText = null, thumbnailUrl = null))

        SharedPlaylists.save(share)

        val song = assertNotNull(Database.songNow("aaaaaaaaaa1"))
        assertEquals("Моё название", song.title)
        assertEquals("Кино", song.artistsText)
        assertEquals("4:45", song.durationText)
        assertEquals("https://i.example/aaaaaaaaaa1.jpg", song.thumbnailUrl)
    }

    @Test
    fun `saving twice makes two playlists, each of its own`() = io {
        val first = assertNotNull(SharedPlaylists.save(share))
        val second = assertNotNull(SharedPlaylists.save(share))

        assertTrue(first != second)
        assertEquals(3, Database.playlistSongs(first).first().size)
        assertEquals(3, Database.playlistSongs(second).first().size)
    }

    @Test
    fun `a name that is blank falls back to the one of the link`() = io {
        val id = assertNotNull(SharedPlaylists.save(share, name = "   "))

        val playlist = Database.playlistPreviews(app.melogold.core.data.enums.PlaylistSortBy.DateAdded, app.melogold.core.data.enums.SortOrder.Descending).first()
            .single { it.id == id }
        assertEquals("Дорога", playlist.name)
    }
}
