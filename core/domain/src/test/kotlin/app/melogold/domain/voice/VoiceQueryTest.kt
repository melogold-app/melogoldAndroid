package app.melogold.domain.voice

import org.junit.Test
import kotlin.test.assertEquals

/** REWRITE §3.14.3, step 1: what «включи X» asks for, by `android.intent.extra.focus`. */
class VoiceQueryTest {
    @Test
    fun `free text is a song`() {
        assertEquals(VoiceRequest.Song("Кино звезда"), VoiceQuery.request(focus = null, query = " Кино звезда "))
        assertEquals(VoiceRequest.Song("Кино"), VoiceQuery.request(VoiceQuery.FOCUS_ANY, query = null, text = "Кино"))
    }

    @Test
    fun `an artist, an album and a playlist go to their commands`() {
        assertEquals(
            VoiceRequest.Artist("Кино"),
            VoiceQuery.request(VoiceQuery.FOCUS_ARTIST, query = "Кино", artist = "Кино")
        )
        assertEquals(
            VoiceRequest.Album("Группа крови"),
            VoiceQuery.request(VoiceQuery.FOCUS_ALBUM, query = "группа крови", album = "Группа крови")
        )
        assertEquals(
            VoiceRequest.Playlist("Дорога"),
            VoiceQuery.request(VoiceQuery.FOCUS_PLAYLIST, query = "дорога", playlist = "Дорога")
        )
    }

    @Test
    fun `a title with its artist, and a genre, are songs`() {
        assertEquals(
            VoiceRequest.Song("Группа крови Кино"),
            VoiceQuery.request(VoiceQuery.FOCUS_TITLE, query = "группа крови", title = "Группа крови", artist = "Кино")
        )
        assertEquals(VoiceRequest.Song("джаз"), VoiceQuery.request(VoiceQuery.FOCUS_GENRE, query = null, genre = "джаз"))
    }

    @Test
    fun `a focused field that is missing falls back to the query`() {
        assertEquals(VoiceRequest.Song("Кино"), VoiceQuery.request(VoiceQuery.FOCUS_ARTIST, query = "Кино"))
        assertEquals(VoiceRequest.Song("что-то"), VoiceQuery.request("vnd.android.cursor.item/unknown", query = "что-то"))
    }

    @Test
    fun `nothing to search for continues the last queue`() {
        assertEquals(VoiceRequest.Resume, VoiceQuery.request(focus = null, query = null))
        assertEquals(VoiceRequest.Resume, VoiceQuery.request(VoiceQuery.FOCUS_ANY, query = "  ", text = ""))
    }
}
