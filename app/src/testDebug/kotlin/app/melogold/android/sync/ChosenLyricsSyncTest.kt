package app.melogold.android.sync

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.Song
import app.melogold.android.sync.api.LyricsText
import app.melogold.android.sync.api.MyLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Lyrics the user chose in "Find other lyrics" are their own whatever the provider (tasks/0009): they go up with their
 * real source, and a chosen version coming from another device (Windows 0.1.10 sends `lrclib`) is kept, not deleted
 * from the server at the next upload.
 */
@RunWith(RobolectricTestRunner::class)
class ChosenLyricsSyncTest {
    private val sync by lazy { LyricsSync(Account(ApplicationProvider.getApplicationContext<MainApplication>())) }

    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun io(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    private val lrc = "[00:01.00]Звезда по имени Солнце"

    @Before
    fun empty() = io {
        Database.internal.clearAllTables()
        Database.insert(
            Song(id = VIDEO, title = "Звезда по имени Солнце", artistsText = "Кино", durationText = "3:45", thumbnailUrl = null)
        )
    }

    @Test
    fun `a chosen LrcLib text goes up as lrclib`() = io {
        Database.upsert(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, chosen = true))

        val upload = sync.pendingUploads().single()
        assertIs<LyricsSync.Upload.Put>(upload)
        assertEquals(lrc, upload.body.synced)
        assertEquals("lrclib", upload.body.syncedSource)
    }

    @Test
    fun `the same text found automatically is not the user's and stays here`() = io {
        Database.upsert(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib))

        assertEquals(emptyList(), sync.pendingUploads())
    }

    @Test
    fun `a chosen lrclib version from another device is kept here and on the server`() = io {
        sync.apply(
            MyLyrics(
                id = "l1",
                videoId = VIDEO,
                rev = 7,
                deleted = false,
                text = LyricsText(synced = lrc, syncedFormat = "lrc", syncedSource = "lrclib"),
                updatedAt = "2026-09-26T10:00:00.000Z"
            )
        )

        val row = Database.lyricsNow(VIDEO)
        assertTrue(row?.chosen == true, "the version from the server is chosen")
        assertEquals(LyricsSource.LrcLib, row?.syncedSource)
        // Nothing to send: neither a PUT (the snapshot has it) nor the DELETE that erased the choice before
        assertEquals(emptyList(), sync.pendingUploads())
    }

    private companion object {
        const val VIDEO = "MIG0Cz4wqN4"
    }
}
