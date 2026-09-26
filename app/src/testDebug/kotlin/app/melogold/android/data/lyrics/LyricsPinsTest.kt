package app.melogold.android.data.lyrics

import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsPin
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.Song
import app.melogold.android.models.SyncedLyricsPin
import app.melogold.android.sync.pinChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VIDEO = "dQw4w9WgXcQ"

/** tasks/0013: lyrics found automatically are pinned by a reference, the same on every device. */
@RunWith(RobolectricTestRunner::class)
class LyricsPinsTest {
    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun io(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    private val lrc = "[00:01.00]Never gonna give you up"

    @Before
    fun empty() = io {
        Database.internal.clearAllTables()
        Database.insert(Song(id = VIDEO, title = "Never Gonna Give You Up", artistsText = "Rick Astley", durationText = "3:33", thumbnailUrl = null))
    }

    @Test
    fun `the synced side is pinned with its provider id and its later shift`() {
        val pin = pinOf(
            Lyrics(VIDEO, fixed = "plain", synced = lrc, startTime = 1500, fixedSource = LyricsSource.YouTubeMusic,
                syncedSource = LyricsSource.LrcLib, fixedRef = "MPLYt_x", syncedRef = "123456"),
            now = 7L
        )

        assertEquals(LyricsPin(VIDEO, "lrclib", "123456", startTimeMs = 1500, updatedAt = 7L), pin)
    }

    @Test
    fun `plain lyrics alone are pinned by their own id`() {
        val pin = pinOf(Lyrics(VIDEO, fixed = "plain", synced = "", fixedSource = LyricsSource.YouTubeMusic, fixedRef = "MPLYt_x"), now = 1L)

        assertEquals("youtube_music", pin?.source)
        assertEquals("MPLYt_x", pin?.ref)
        assertNull(pin?.startTimeMs)
    }

    @Test
    fun `own lyrics, lyrics without an id and shared ones are never pinned`() {
        assertNull(pinOf(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, syncedRef = "1", chosen = true), 1L))
        assertNull(pinOf(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.User), 1L))
        assertNull(pinOf(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib), 1L))
        assertNull(pinOf(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.Melogold, syncedRef = "1"), 1L))
    }

    @Test
    fun `a played track pins its lyrics once, the first pin stays`() = io {
        Database.upsert(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.KuGou, syncedRef = "42:abc"))
        LyricsPins.pinPlayed(VIDEO)
        assertEquals("42:abc", Database.lyricsPin(VIDEO)?.ref)

        // Found again differently here: the account keeps the first pin
        Database.upsert(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, syncedRef = "999"))
        LyricsPins.pinPlayed(VIDEO)
        assertEquals("kugou", Database.lyricsPin(VIDEO)?.source)
        assertEquals("42:abc", Database.lyricsPin(VIDEO)?.ref)
    }

    @Test
    fun `a later start of the pinned lyrics moves the pin along`() = io {
        val lyrics = Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, syncedRef = "123")
        Database.upsert(lyrics)
        LyricsPins.pinPlayed(VIDEO)

        LyricsPins.shifted(lyrics, 2500)

        assertEquals(2500, Database.lyricsPin(VIDEO)?.startTimeMs)
    }

    @Test
    fun `lyrics show a pin by either side`() {
        val pin = LyricsPin(VIDEO, "lrclib", "123", updatedAt = 1L)
        assertTrue(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, syncedRef = "123").shows(pin))
        assertFalse(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.LrcLib, syncedRef = "999").shows(pin))
        assertFalse(Lyrics(VIDEO, fixed = "", synced = lrc, syncedSource = LyricsSource.KuGou, syncedRef = "123").shows(pin))
    }

    @Test
    fun `new and changed pins are sent, removed ones by id`() {
        val (changed, removed) = pinChanges(
            local = listOf(
                LyricsPin("same0000000", "lrclib", "1", updatedAt = 1L),
                LyricsPin("shifted0000", "lrclib", "2", startTimeMs = 500, updatedAt = 1L),
                LyricsPin("new00000000", "kugou", "3:k", updatedAt = 1L)
            ),
            synced = listOf(
                SyncedLyricsPin("same0000000", "lrclib", "1", null),
                SyncedLyricsPin("shifted0000", "lrclib", "2", null),
                SyncedLyricsPin("gone0000000", "youtube_music", "MPLYt", null)
            )
        )

        assertEquals(listOf("shifted0000", "new00000000"), changed.map { it.videoId })
        assertEquals(listOf("gone0000000"), removed)
    }
}
