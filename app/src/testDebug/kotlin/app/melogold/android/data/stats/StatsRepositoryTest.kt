package app.melogold.android.data.stats

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.Event
import app.melogold.android.models.Song
import app.melogold.android.models.SongAlbumMap
import app.melogold.android.models.SongArtistMap
import app.melogold.android.models.TrackOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** tasks/0016: the queries behind Insights, on a real Room database: what they read, and what the counting makes of it. */
@RunWith(RobolectricTestRunner::class)
class StatsRepositoryTest {
    init {
        ApplicationProvider.getApplicationContext<MainApplication>()
    }

    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 30)

    private fun io(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { block() }

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun emptyLibrary() = io { Database.internal.clearAllTables() }

    private fun song(id: String, title: String, artists: String?) =
        Song(id = id, title = title, artistsText = artists, durationText = "3:00", thumbnailUrl = "https://i.example/$id.jpg")

    private fun load(
        period: StatsPeriod,
        offset: Int = 0,
        overrides: Map<String, TrackOverride> = emptyMap(),
        includes: (String?) -> Boolean = { true }
    ) = StatsRepository.load(statsWindow(period, offset, today, zone), overrides, zone, today, includes)

    @Test
    fun `the counting reads events, tracks, the first artist and the album from Room`() = io {
        Database.insert(song("aaaaaaaaaa1", "First", "Kino, Guest"))
        Database.insert(song("aaaaaaaaaa2", "Second", null))
        // Two artists in the order YouTube gave them: the first saved is the one that counts
        Database.insert(
            listOf(Artist(id = "UCkino", name = "Кино"), Artist(id = "UCguest", name = "Guest")),
            listOf(SongArtistMap("aaaaaaaaaa1", "UCkino"), SongArtistMap("aaaaaaaaaa1", "UCguest"))
        )
        Database.insert(Album(id = "MPREb_a", title = "Album A"), SongAlbumMap("aaaaaaaaaa1", "MPREb_a", position = null))
        Database.insertEvents(
            listOf(
                Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-02T10:00:00"), playTime = 180_000),
                Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-03T10:00:00"), playTime = 120_000),
                Event(songId = "aaaaaaaaaa2", timestamp = at("2026-09-03T11:00:00"), playTime = 60_000),
                Event(songId = "aaaaaaaaaa2", timestamp = at("2026-08-03T11:00:00"), playTime = 300_000)
            )
        )

        val september = load(StatsPeriod.Month)

        assertEquals(3, september.plays)
        assertEquals(360_000, september.totalMs)
        assertEquals(300_000L, september.previousMs)
        assertEquals(listOf("First", "Second"), september.topTracks.map { it.title })
        assertEquals(listOf("Кино"), september.topArtists.map { it.name }, "the first of the map, the track without one has none")
        assertEquals("UCkino", september.topArtists.single().id)
        assertEquals(listOf("Album A"), september.topAlbums.map { it.title })
        assertEquals("MPREb_a", september.topAlbums.single().id)
        assertEquals(1, september.discoveries?.count, "the second track was first heard in August")
        assertEquals(at("2026-08-03T11:00:00"), september.earliest)
    }

    @Test
    fun `the devices are filtered as History does, by the id on the server`() = io {
        Database.insert(song("aaaaaaaaaa1", "First", "Kino"))
        Database.insertEvents(
            listOf(
                Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-02T10:00:00"), playTime = 100_000, deviceId = null),
                Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-02T11:00:00"), playTime = 200_000, deviceId = "mac-id"),
                Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-02T12:00:00"), playTime = 400_000, deviceId = "me-id")
            )
        )

        assertEquals(700_000, load(StatsPeriod.Month).totalMs)
        assertEquals(500_000, load(StatsPeriod.Month) { it == null || it == "me-id" }.totalMs)
        assertEquals(200_000, load(StatsPeriod.Month) { it == "mac-id" }.totalMs)
        assertEquals(0, load(StatsPeriod.Month) { it == "gone" }.plays)
    }

    @Test
    fun `the own names of a track are laid over what Room has`() = io {
        Database.insert(song("aaaaaaaaaa1", "Artist — Song (fan upload)", "Some Channel"))
        Database.insertEvents(listOf(Event(songId = "aaaaaaaaaa1", timestamp = at("2026-09-02T10:00:00"), playTime = 100_000)))
        val overrides = mapOf(
            "aaaaaaaaaa1" to TrackOverride("aaaaaaaaaa1", title = "Song", artistsText = "Artist", albumTitle = "Lost album", updatedAt = 1)
        )

        val september = load(StatsPeriod.Month, overrides = overrides)

        assertEquals("Song", september.topTracks.single().title)
        assertEquals(listOf("Artist"), september.topArtists.map { it.name })
        assertEquals(listOf("Lost album"), september.topAlbums.map { it.title })
        assertNull(september.topAlbums.single().id)
    }

    @Test
    fun `an empty history is an empty period`() = io {
        val september = load(StatsPeriod.Month)

        assertEquals(0, september.plays)
        assertNull(september.earliest)
        assertEquals(30, september.bars.size)
    }

    @Test
    fun `plays of a year are counted for the card of the year`() = io {
        Database.insert(song("aaaaaaaaaa1", "First", "Kino"))
        Database.insertEvents(listOf(Event(songId = "aaaaaaaaaa1", timestamp = at("2026-03-01T10:00:00"), playTime = 1_000)))
        val from = at("2026-01-01T00:00:00")
        val to = at("2027-01-01T00:00:00")

        assertEquals(1, Database.playsBetween(from, to).first())
        assertEquals(0, Database.playsBetween(at("2025-01-01T00:00:00"), from).first())
    }
}
