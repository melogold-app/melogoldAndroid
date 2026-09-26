package app.melogold.android.ui.kit

import androidx.media3.common.MediaItem
import app.melogold.android.models.DownloadState
import app.melogold.android.models.TrackDownload
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 0011: the selection and what its actions work on. */
@RunWith(RobolectricTestRunner::class)
class SelectionTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `new playlist is named after the album all the tracks share`() {
        assertEquals("Лунный свет", commonAlbum(listOf("Лунный свет", "Лунный свет ", "Лунный свет")))
    }

    @Test
    fun `different albums or an unknown one give no name`() {
        assertNull(commonAlbum(listOf("A", "B")))
        assertNull(commonAlbum(listOf("A", null)))
        assertNull(commonAlbum(listOf("A", " ")))
        assertNull(commonAlbum(emptyList()))
    }

    @Test
    fun `download skips what is downloaded or on its way, live streams and local files`() {
        val items = listOf("done0000000", "going000000", "failed00000", "cached00000", "new00000000", "live0000000")
            .map(::item) + item("local:42")
        val downloads = mapOf(
            "done0000000" to TrackDownload("done0000000", manual = true, state = DownloadState.Completed, requestedAt = 0L),
            "going000000" to TrackDownload("going000000", manual = true, state = DownloadState.Downloading, requestedAt = 0L),
            "failed00000" to TrackDownload("failed00000", manual = true, state = DownloadState.Failed, requestedAt = 0L),
            // Only in the cache, not downloaded by the user
            "cached00000" to TrackDownload("cached00000", manual = false, state = DownloadState.Completed, requestedAt = 0L)
        )

        val started = toDownload(items, downloads, liveIds = setOf("live0000000")).map { it.mediaId }

        assertEquals(listOf("failed00000", "cached00000", "new00000000"), started)
    }

    @Test
    fun `the selected tracks come in the order of the list, not of the taps`() {
        val selection = TrackSelection()
        selection.toggle("c")
        selection.toggle("a")

        assertEquals(listOf("a", "c"), selection.of(listOf("a", "b", "c")) { it })
    }

    @Test
    fun `unchecking the last track ends the selection`() {
        val selection = TrackSelection()
        selection.toggle("a")
        assertTrue(selection.active)
        assertTrue(selection.row("a").selected)
        assertTrue(selection.row("b").selecting)

        selection.toggle("a")
        assertFalse(selection.active)

        selection.selectAll(listOf("a", "b"))
        assertEquals(setOf("a", "b"), selection.ids)
        selection.clear()
        assertFalse(selection.active)
    }
}
