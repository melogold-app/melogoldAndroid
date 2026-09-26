package app.melogold.android

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.models.Song
import app.melogold.core.ui.utils.SongBundleAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A track first saved without its cover, artists or length (played from a link: dQw4w9WgXcQ, jNQXAC9IVRw) gets
 * them when it comes again with them, and a row keeps what it already has.
 */
@RunWith(RobolectricTestRunner::class)
class SongFillMissingTest {
    init {
        ApplicationProvider.getApplicationContext<MainApplication>()
    }

    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private suspend fun song(id: String) = assertNotNull(Database.song(id).first(), id)

    @Before
    fun emptyLibrary() = io { Database.internal.clearAllTables() }

    @Test
    fun `a track played from a link gets its cover, artists and length when it comes again`() = io {
        Database.insert(item(LINK))
        assertEquals(null, song(LINK).thumbnailUrl, "a link knows only the id")

        Database.insert(item(LINK, title = "Never Gonna Give You Up", artists = "Rick Astley", duration = "3:34",
            cover = COVER))

        val row = song(LINK)
        assertEquals(COVER, row.thumbnailUrl)
        assertEquals("Rick Astley", row.artistsText)
        assertEquals("3:34", row.durationText)
        assertEquals(LINK, row.title, "the title the row has stays")
    }

    @Test
    fun `an empty cover or empty artists count as missing`() = io {
        Database.insert(Song(id = OTHER, title = "Me at the zoo", artistsText = "", durationText = "", thumbnailUrl = ""))

        Database.insert(item(OTHER, artists = "jawed", duration = "0:19", cover = COVER))

        val row = song(OTHER)
        assertEquals(COVER, row.thumbnailUrl)
        assertEquals("jawed", row.artistsText)
        assertEquals("0:19", row.durationText)
    }

    @Test
    fun `what a row has is not overwritten, nor its like and play time`() = io {
        Database.insert(
            Song(
                id = LINK,
                title = "Never Gonna Give You Up",
                artistsText = "Rick Astley",
                durationText = "3:34",
                thumbnailUrl = COVER,
                likedAt = 1_726_000_000_000L,
                totalPlayTimeMs = 60_000
            )
        )

        Database.insert(item(LINK, artists = "Someone else", duration = "9:99", cover = "https://example.com/other.jpg"))
        Database.insert(item(LINK))

        val row = song(LINK)
        assertEquals(COVER, row.thumbnailUrl)
        assertEquals("Rick Astley", row.artistsText)
        assertEquals("3:34", row.durationText)
        assertEquals(1_726_000_000_000L, row.likedAt)
        assertEquals(60_000L, row.totalPlayTimeMs)
    }

    private fun item(
        id: String,
        title: String = id,
        artists: String? = null,
        duration: String? = null,
        cover: String? = null
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artists)
                .setArtworkUri(cover?.toUri())
                .setExtras(SongBundleAccessor.bundle { durationText = duration })
                .build()
        )
        .build()

    private companion object {
        const val LINK = "dQw4w9WgXcQ"
        const val OTHER = "jNQXAC9IVRw"
        const val COVER = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    }
}
