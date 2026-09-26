package app.melogold.android.data.overrides

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.melogold.android.models.SyncedOverride
import app.melogold.android.models.TrackOverride
import app.melogold.android.sync.overrideChanges
import app.melogold.core.ui.utils.SongBundleAccessor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** tasks/0012: the user's own names of a track over YouTube's. */
@RunWith(RobolectricTestRunner::class)
class TrackOverridesTest {
    private val youTube = MediaItem.Builder()
        .setMediaId("abcdefghijk")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Artist — Song (live 2014, fan upload)")
                .setArtist("Some Channel")
                .setExtras(SongBundleAccessor.bundle { durationText = "4:14" })
                .build()
        )
        .build()

    private fun override(title: String? = null, artist: String? = null, album: String? = null) =
        TrackOverride("abcdefghijk", title, artist, album, updatedAt = 1L)

    @Test
    fun `an override shows, YouTube's names stay underneath`() {
        val item = youTube.withOverride(override(title = "Song", album = "Lost Album"))

        assertEquals("Song", item.mediaMetadata.title)
        assertEquals("Some Channel", item.mediaMetadata.artist, "a field without an override shows YouTube's")
        assertEquals("Lost Album", item.mediaMetadata.albumTitle)
        assertEquals("Artist — Song (live 2014, fan upload)", item.originalTitle)
        assertEquals("Some Channel", item.originalArtist)
        assertNull(item.originalAlbum)
    }

    @Test
    fun `another override replaces the first, not YouTube's names`() {
        val item = youTube.withOverride(override(title = "Song")).withOverride(override(artist = "Artist"))

        assertEquals("Artist — Song (live 2014, fan upload)", item.mediaMetadata.title)
        assertEquals("Artist", item.mediaMetadata.artist)
        assertEquals("Artist — Song (live 2014, fan upload)", item.originalTitle)
    }

    @Test
    fun `without the override the item goes back to YouTube's names`() {
        val item = youTube.withOverride(override(title = "Song")).withOverride(null)

        assertEquals("Artist — Song (live 2014, fan upload)", item.mediaMetadata.title)
        assertEquals("Some Channel", item.mediaMetadata.artist)
        assertEquals("4:14", item.mediaMetadata.extras?.getString("durationText"), "the other extras stay")
        assertSame(youTube, youTube.withOverride(null), "an item that never had one is left as it is")
    }

    @Test
    fun `fields are trimmed, cut to 500 without splitting a pair, blank is none`() {
        assertEquals("Song", TrackOverride.clean("  Song \n"))
        assertNull(TrackOverride.clean("   "))
        assertNull(TrackOverride.clean(null))
        assertEquals("a".repeat(499), TrackOverride.clean("a".repeat(499) + "😀tail"))
        assertEquals(500, TrackOverride.clean("b".repeat(600))?.length)
    }

    @Test
    fun `new and changed overrides are sent, removed ones by id, local files never`() {
        val local = listOf(
            override(title = "Same").copy(videoId = "same0000000"),
            override(title = "Changed").copy(videoId = "changed0000"),
            override(album = "New").copy(videoId = "new00000000"),
            override(title = "Local").copy(videoId = "local:42")
        )
        val synced = listOf(
            SyncedOverride("same0000000", "Same", null, null),
            SyncedOverride("changed0000", "Old", null, null),
            SyncedOverride("gone0000000", "Gone", null, null)
        )

        val (changed, removed) = overrideChanges(local, synced)

        assertEquals(listOf("changed0000", "new00000000"), changed.map { it.videoId })
        assertEquals(listOf("gone0000000"), removed)
    }
}
