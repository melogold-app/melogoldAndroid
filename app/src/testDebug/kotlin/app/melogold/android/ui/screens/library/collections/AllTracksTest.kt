package app.melogold.android.ui.screens.library.collections

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.DownloadState
import app.melogold.android.models.Event
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.TrackDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * "All tracks" of the Library: what was played, liked, put in a playlist or downloaded, not hidden and not the
 * tracks of an album that was only opened; the last played first, then the liked.
 */
@RunWith(RobolectricTestRunner::class)
class AllTracksTest {
    init {
        ApplicationProvider.getApplicationContext<MainApplication>()
    }

    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Before
    fun emptyLibrary() = io { Database.internal.clearAllTables() }

    @Test
    fun `all tracks are the library, not the catalog`() = io {
        listOf(PLAYED_LONG_AGO, PLAYED_TODAY, LIKED, IN_PLAYLIST, DOWNLOADED, HIDDEN, ONLY_OPENED).forEach { id ->
            Database.insert(
                Song(
                    id = id,
                    title = id,
                    durationText = "3:00",
                    thumbnailUrl = null,
                    likedAt = if (id == LIKED) 1_726_000_000_000L else null,
                    blacklisted = id == HIDDEN
                )
            )
        }
        Database.insertEvents(
            listOf(
                Event(songId = PLAYED_LONG_AGO, timestamp = 1_700_000_000_000L, playTime = 60_000),
                Event(songId = PLAYED_TODAY, timestamp = 1_726_100_000_000L, playTime = 60_000),
                Event(songId = HIDDEN, timestamp = 1_726_100_000_001L, playTime = 60_000)
            )
        )
        val playlist = Database.insert(Playlist(name = "Дорога"))
        Database.insertSongPlaylistMaps(listOf(SongPlaylistMap(songId = IN_PLAYLIST, playlistId = playlist, position = 0)))
        Database.upsert(TrackDownload(videoId = DOWNLOADED, state = DownloadState.Queued, requestedAt = 1L))

        val tracks = Database.allTracks().first().map { it.id }

        assertEquals(listOf(PLAYED_TODAY, LIKED, PLAYED_LONG_AGO), tracks.take(3), "the last played, then the liked")
        assertEquals(setOf(PLAYED_LONG_AGO, PLAYED_TODAY, LIKED, IN_PLAYLIST, DOWNLOADED), tracks.toSet())
        assertEquals(5, Database.allTracksCount().first())
    }

    private companion object {
        const val PLAYED_LONG_AGO = "aaaaaaaaaa1"
        const val PLAYED_TODAY = "aaaaaaaaaa2"
        const val LIKED = "aaaaaaaaaa3"
        const val IN_PLAYLIST = "aaaaaaaaaa4"
        const val DOWNLOADED = "aaaaaaaaaa5"
        const val HIDDEN = "aaaaaaaaaa6"
        const val ONLY_OPENED = "aaaaaaaaaa7"
    }
}
