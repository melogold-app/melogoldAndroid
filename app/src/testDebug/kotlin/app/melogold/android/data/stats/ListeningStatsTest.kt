package app.melogold.android.data.stats

import app.melogold.android.models.Song
import app.melogold.android.models.TrackOverride
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0016: the periods and the counting of the statistics, with no database and no screen. */
class ListeningStatsTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = moscow) =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun song(id: String, title: String = id, artists: String? = "Artist $id") =
        Song(id = id, title = title, artistsText = artists, durationText = "3:00", thumbnailUrl = "https://i.example/$id.jpg")

    private fun play(id: String, time: String, minutes: Long = 3, device: String? = null, zone: ZoneId = moscow) =
        StatEvent(id, at(time, zone), minutes * MINUTE, device)

    private fun input(
        events: List<StatEvent>,
        songs: List<Song> = events.map { it.songId }.distinct().map { song(it) },
        artists: List<StatArtistLink> = emptyList(),
        albums: List<StatAlbumLink> = emptyList(),
        overrides: List<TrackOverride> = emptyList(),
        firstPlays: Map<String, Long> = events.groupBy { it.songId }.mapValues { (_, list) -> list.minOf { it.timestamp } }
    ) = StatsInput(
        events = events,
        songs = songs.associateBy { it.id },
        artists = artists.associateBy { it.songId },
        albums = albums.associateBy { it.songId },
        overrides = overrides.associateBy { it.videoId },
        firstPlays = firstPlays
    )

    private val today = LocalDate.of(2026, 9, 30) // a Wednesday

    private fun stats(input: StatsInput, period: StatsPeriod, offset: Int = 0, includes: (String?) -> Boolean = { true }) =
        computeStats(input, statsWindow(period, offset, today, moscow), moscow, today, includes)

    // region Periods

    @Test
    fun `a week starts on Monday and ends the next Monday`() {
        val week = statsWindow(StatsPeriod.Week, 0, today, moscow)

        assertEquals(LocalDate.of(2026, 9, 28), week.firstDay)
        assertEquals(LocalDate.of(2026, 10, 5), week.endDay)
        assertEquals(7, week.days)
        assertEquals(at("2026-09-28T00:00:00"), week.start)
        assertEquals(at("2026-10-05T00:00:00"), week.end)
        // The last millisecond of Sunday is the week before, the first of Monday this one
        assertTrue(week.contains(at("2026-09-28T00:00:00")))
        assertTrue(!week.contains(at("2026-09-28T00:00:00") - 1))
        assertTrue(week.contains(at("2026-10-05T00:00:00") - 1))
        assertTrue(!week.contains(at("2026-10-05T00:00:00")))
    }

    @Test
    fun `on a Monday and on a Sunday the week is the same one`() {
        val monday = statsWindow(StatsPeriod.Week, 0, LocalDate.of(2026, 9, 28), moscow)
        val sunday = statsWindow(StatsPeriod.Week, 0, LocalDate.of(2026, 10, 4), moscow)

        assertEquals(monday, sunday)
        assertEquals(LocalDate.of(2026, 9, 28), monday.firstDay)
    }

    @Test
    fun `months and years are the calendar's, leap years too`() {
        val september = statsWindow(StatsPeriod.Month, 0, today, moscow)
        assertEquals(30, september.days)
        assertEquals(at("2026-09-01T00:00:00"), september.start)
        assertEquals(at("2026-10-01T00:00:00"), september.end)

        val february = statsWindow(StatsPeriod.Month, -7, today, moscow)
        assertEquals(LocalDate.of(2026, 2, 1), february.firstDay)
        assertEquals(28, february.days)
        assertEquals(29, statsWindow(StatsPeriod.Month, 0, LocalDate.of(2028, 2, 10), moscow).days)

        assertEquals(365, statsWindow(StatsPeriod.Year, 0, today, moscow).days)
        val leap = statsWindow(StatsPeriod.Year, -2, today, moscow)
        assertEquals(LocalDate.of(2024, 1, 1), leap.firstDay)
        assertEquals(366, leap.days)
    }

    @Test
    fun `stepping back walks through the periods and crosses a year`() {
        val january = statsWindow(StatsPeriod.Month, 0, LocalDate.of(2027, 1, 15), moscow)
        val december = january.previous(moscow)!!
        assertEquals(LocalDate.of(2026, 12, 1), december.firstDay)
        assertEquals(-1, december.offset)
        assertEquals(LocalDate.of(2026, 11, 1), december.previous(moscow)!!.firstDay)
        assertEquals(-2, december.previous(moscow)!!.offset)

        val week = statsWindow(StatsPeriod.Week, 0, LocalDate.of(2027, 1, 1), moscow)
        assertEquals(LocalDate.of(2026, 12, 28), week.firstDay)
        assertEquals(LocalDate.of(2026, 12, 21), week.previous(moscow)!!.firstDay)

        assertEquals(LocalDate.of(2025, 1, 1), statsWindow(StatsPeriod.Year, 0, today, moscow).previous(moscow)!!.firstDay)
        assertNull(statsWindow(StatsPeriod.AllTime, 0, today, moscow).previous(moscow))
    }

    @Test
    fun `the boundaries are local midnights even across a change of the clocks`() {
        // Berlin turns the clocks back on 25 October 2026: that day has 25 hours
        val october = statsWindow(StatsPeriod.Month, 0, LocalDate.of(2026, 10, 12), berlin)

        assertEquals(LocalDateTime.parse("2026-10-01T00:00:00").atZone(berlin).toInstant().toEpochMilli(), october.start)
        assertEquals(LocalDateTime.parse("2026-11-01T00:00:00").atZone(berlin).toInstant().toEpochMilli(), october.end)
        assertEquals(31 * 24 * HOUR + HOUR, october.end - october.start)
        // 23:30 on the last evening is October, 00:30 the next night is November
        assertTrue(october.contains(at("2026-10-31T23:30:00", berlin)))
        assertTrue(!october.contains(at("2026-11-01T00:30:00", berlin)))
    }

    @Test
    fun `all time has no dates and no offset`() {
        val all = statsWindow(StatsPeriod.AllTime, 5, today, moscow)

        assertEquals(0, all.offset)
        assertNull(all.firstDay)
        assertEquals(0L, all.start)
        assertTrue(all.contains(at("2001-01-01T00:00:00")))
        assertTrue(all.contains(at("2099-12-31T00:00:00")))
    }

    @Test
    fun `the card of the year appears from December to January and speaks of the year that ends`() {
        assertEquals(2026, wrappedSeasonYear(LocalDate.of(2026, 12, 1)))
        assertEquals(2026, wrappedSeasonYear(LocalDate.of(2026, 12, 31)))
        assertEquals(2026, wrappedSeasonYear(LocalDate.of(2027, 1, 1)))
        assertEquals(2026, wrappedSeasonYear(LocalDate.of(2027, 1, 31)))
        assertNull(wrappedSeasonYear(LocalDate.of(2027, 2, 1)))
        assertNull(wrappedSeasonYear(LocalDate.of(2026, 11, 30)))
    }

    // endregion

    // region The numbers

    @Test
    fun `plays, time and tracks of the period only`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-08-31T23:59:00"), // the month before
            play("aaaaaaaaaa1", "2026-09-01T00:00:00", minutes = 4),
            play("aaaaaaaaaa2", "2026-09-15T12:00:00", minutes = 2),
            play("aaaaaaaaaa2", "2026-09-30T23:59:00", minutes = 6),
            play("aaaaaaaaaa3", "2026-10-01T00:00:00") // the month after
        )

        val september = stats(input(events), StatsPeriod.Month)

        assertEquals(3, september.plays)
        assertEquals(12 * MINUTE, september.totalMs)
        assertEquals(2, september.tracks)
        assertEquals(listOf("aaaaaaaaaa2", "aaaaaaaaaa1"), september.topTracks.map { it.song.id }, "the longest first")
        assertEquals(8 * MINUTE, september.topTracks.first().ms)
        assertEquals(2, september.topTracks.first().plays)
    }

    @Test
    fun `a tie in time goes to the more plays and then to the title`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 3),
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 1),
            play("aaaaaaaaaa2", "2026-09-02T12:00:00", minutes = 2),
            play("aaaaaaaaaa3", "2026-09-02T13:00:00", minutes = 3)
        )
        val songs = listOf(song("aaaaaaaaaa1", "Zebra"), song("aaaaaaaaaa2", "Mid"), song("aaaaaaaaaa3", "Apple"))

        val ranked = stats(input(events, songs), StatsPeriod.Month).topTracks.map { it.title }

        assertEquals(listOf("Mid", "Apple", "Zebra"), ranked)
    }

    @Test
    fun `the filter of devices counts one device's plays, this one's being those without an id or with its own`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 3, device = null),
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 5, device = "me"),
            play("aaaaaaaaaa3", "2026-09-02T12:00:00", minutes = 7, device = "mac")
        )
        val data = input(events)

        assertEquals(15 * MINUTE, stats(data, StatsPeriod.Month).totalMs)
        val here = stats(data, StatsPeriod.Month) { it == null || it == "me" }
        assertEquals(8 * MINUTE, here.totalMs)
        assertEquals(2, here.plays)
        val mac = stats(data, StatsPeriod.Month) { it == "mac" }
        assertEquals(7 * MINUTE, mac.totalMs)
        assertEquals(listOf("aaaaaaaaaa3"), mac.topTracks.map { it.song.id })
    }

    @Test
    fun `hidden tracks and local files are counted, they are the person's history`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00"),
            play("local:42", "2026-09-02T11:00:00")
        )
        val songs = listOf(song("aaaaaaaaaa1").copy(blacklisted = true), song("local:42"))

        val september = stats(input(events, songs), StatsPeriod.Month)

        assertEquals(2, september.plays)
        assertEquals(setOf("aaaaaaaaaa1", "local:42"), september.topTracks.map { it.song.id }.toSet())
    }

    @Test
    fun `the comparison is with the same period before`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-08-10T10:00:00", minutes = 100),
            play("aaaaaaaaaa1", "2026-09-10T10:00:00", minutes = 112)
        )

        val september = stats(input(events), StatsPeriod.Month)

        assertEquals(100 * MINUTE, september.previousMs)
        assertEquals(12, september.changePercent)
        // Earlier than that is nothing to compare with
        assertEquals(0L, stats(input(events), StatsPeriod.Month, offset = -1).previousMs)
        assertNull(stats(input(events), StatsPeriod.Month, offset = -1).changePercent, "an empty period before says nothing")
        // A drop is negative; the percent is rounded
        val fewer = listOf(
            play("aaaaaaaaaa1", "2026-08-10T10:00:00", minutes = 300),
            play("aaaaaaaaaa1", "2026-09-10T10:00:00", minutes = 199)
        )
        assertEquals(-34, stats(input(fewer), StatsPeriod.Month).changePercent)
        assertNull(stats(input(events), StatsPeriod.AllTime).previousMs)
        assertNull(stats(input(events), StatsPeriod.AllTime).changePercent)
    }

    @Test
    fun `the comparison of a week is with the week before and of a year with the year before`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-22T10:00:00", minutes = 50), // last week
            play("aaaaaaaaaa1", "2026-09-29T10:00:00", minutes = 75), // this week
            play("aaaaaaaaaa1", "2025-05-05T10:00:00", minutes = 40)
        )

        assertEquals(50, stats(input(events), StatsPeriod.Week).changePercent)
        assertEquals(40 * MINUTE, stats(input(events), StatsPeriod.Year).previousMs)
        // 125 minutes this year against 40 the year before: +212.5 %
        assertEquals(213, stats(input(events), StatsPeriod.Year).changePercent)
    }

    // endregion

    // region Artists and albums

    @Test
    fun `the artist without a map is the first of the line, cut at a comma, an ampersand, feat or ft`() {
        assertEquals("Noize MC", firstArtist("Noize MC, Oxxxymiron"))
        assertEquals("Noize MC", firstArtist("Noize MC & Oxxxymiron"))
        assertEquals("Noize MC", firstArtist("Noize MC feat. Oxxxymiron"))
        assertEquals("Noize MC", firstArtist("Noize MC FT. Oxxxymiron"))
        assertEquals("Noize MC", firstArtist("  Noize MC  "))
        assertEquals("Tom&Jerry", firstArtist("Tom&Jerry"), "an ampersand without spaces is a name")
        assertEquals("Kino", firstArtist("Kino, Viktor Tsoi & Co feat. Someone"))
        assertNull(firstArtist("   "))
        assertNull(firstArtist(null))
    }

    @Test
    fun `artists are counted by the map, else by the line of the track`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 10),
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 5),
            play("aaaaaaaaaa3", "2026-09-02T12:00:00", minutes = 4)
        )
        val songs = listOf(
            song("aaaaaaaaaa1", artists = "Kino, Guest"),
            song("aaaaaaaaaa2", artists = "Kino feat. Other"),
            song("aaaaaaaaaa3", artists = null)
        )
        val artists = listOf(StatArtistLink("aaaaaaaaaa1", "UCkino", "Кино", "https://i.example/kino.jpg"))

        val september = stats(input(events, songs, artists), StatsPeriod.Month)

        // The mapped "Кино" and the line "Kino" are different names; the track without an artist is nobody's
        assertEquals(listOf("Кино", "Kino"), september.topArtists.map { it.name })
        assertEquals("UCkino", september.topArtists[0].id)
        assertEquals("https://i.example/kino.jpg", september.topArtists[0].thumbnailUrl)
        assertNull(september.topArtists[1].id, "a name from a line has no page to open")
        assertEquals("https://i.example/aaaaaaaaaa2.jpg", september.topArtists[1].thumbnailUrl, "the cover of its top track")
        assertEquals(2, september.artists)
        assertEquals(3, september.tracks)
    }

    @Test
    fun `tracks of one artist add up to one artist with plays and time`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 3),
            play("aaaaaaaaaa1", "2026-09-03T10:00:00", minutes = 3),
            play("aaaaaaaaaa2", "2026-09-03T11:00:00", minutes = 4)
        )
        val links = listOf("aaaaaaaaaa1", "aaaaaaaaaa2").map { StatArtistLink(it, "UCsame", "Same", null) }

        val artist = stats(input(events, artists = links), StatsPeriod.Month).topArtists.single()

        assertEquals(3, artist.plays)
        assertEquals(10 * MINUTE, artist.ms)
        assertEquals(2, artist.tracks)
    }

    @Test
    fun `an album is the map's, a track without one is in no album`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 3),
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 4),
            play("aaaaaaaaaa3", "2026-09-02T12:00:00", minutes = 9)
        )
        val albums = listOf(
            StatAlbumLink("aaaaaaaaaa1", "MPREb_one", "Album One", "https://i.example/one.jpg"),
            StatAlbumLink("aaaaaaaaaa2", "MPREb_one", "Album One", null)
        )

        val september = stats(input(events, albums = albums), StatsPeriod.Month)

        val album = september.topAlbums.single()
        assertEquals("Album One", album.title)
        assertEquals("MPREb_one", album.id)
        assertEquals(7 * MINUTE, album.ms)
        assertEquals(2, album.tracks)
        assertEquals("https://i.example/one.jpg", album.thumbnailUrl)
        assertEquals(1, september.albums)
    }

    @Test
    fun `an own album puts the track in it, and joins the album of the same name with its id`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 10), // fan upload, own album
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 4), // on YouTube's album of that name
            play("aaaaaaaaaa3", "2026-09-02T12:00:00", minutes = 2) // moved from one album to another
        )
        val albums = listOf(
            StatAlbumLink("aaaaaaaaaa2", "MPREb_lost", "Lost album", "https://i.example/lost.jpg"),
            StatAlbumLink("aaaaaaaaaa3", "MPREb_old", "Old album", null)
        )
        val overrides = listOf(
            TrackOverride("aaaaaaaaaa1", albumTitle = "lost ALBUM", updatedAt = 1),
            TrackOverride("aaaaaaaaaa3", albumTitle = "Mixtape", updatedAt = 1)
        )

        val tops = stats(input(events, albums = albums, overrides = overrides), StatsPeriod.Month).topAlbums

        assertEquals(listOf("lost ALBUM", "Mixtape"), tops.map { it.title }, "the name that met first is shown")
        assertEquals(14 * MINUTE, tops[0].ms)
        assertEquals(2, tops[0].tracks)
        assertEquals("MPREb_lost", tops[0].id, "the same name is YouTube's album and its page opens")
        assertNull(tops[1].id, "an album of the person's own has no page")
        assertTrue(tops.none { it.title == "Old album" })
    }

    @Test
    fun `an own artist replaces the map's and the line, and the names of the person and of YouTube meet`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T10:00:00", minutes = 10),
            play("aaaaaaaaaa2", "2026-09-02T11:00:00", minutes = 4),
            play("aaaaaaaaaa3", "2026-09-02T12:00:00", minutes = 1)
        )
        val songs = listOf(
            song("aaaaaaaaaa1", artists = "Some Channel"),
            song("aaaaaaaaaa2", artists = "Some Channel"),
            song("aaaaaaaaaa3", artists = "Kino")
        )
        val links = listOf(StatArtistLink("aaaaaaaaaa3", "UCkino", "Kino", null))
        val overrides = listOf(
            TrackOverride("aaaaaaaaaa1", artistsText = "Kino, Guest", updatedAt = 1),
            TrackOverride("aaaaaaaaaa2", artistsText = "Some Band", updatedAt = 1)
        )

        val artists = stats(input(events, songs, links, overrides = overrides), StatsPeriod.Month).topArtists

        assertEquals(listOf("Kino", "Some Band"), artists.map { it.name })
        assertEquals(11 * MINUTE, artists[0].ms, "the own 'Kino' and the mapped one are one artist")
        assertEquals("UCkino", artists[0].id)
        assertTrue(artists.none { it.name == "Some Channel" })
    }

    @Test
    fun `an own title shows in the tops, the track stays the same`() {
        val events = listOf(play("aaaaaaaaaa1", "2026-09-02T10:00:00"))
        val overrides = listOf(TrackOverride("aaaaaaaaaa1", title = "Lost Song", artistsText = "Lost Band", updatedAt = 1))

        val track = stats(input(events, overrides = overrides), StatsPeriod.Month).topTracks.single()

        assertEquals("Lost Song", track.title)
        assertEquals("Lost Band", track.artist)
        assertEquals("aaaaaaaaaa1", track.song.id)
        assertEquals("aaaaaaaaaa1", track.song.title, "what is stored stays YouTube's")
    }

    @Test
    fun `the tops hold fifty at most`() {
        val events = (1..70).map { play("track%07d".format(it), "2026-09-02T10:00:00", minutes = it.toLong()) }

        val september = stats(input(events), StatsPeriod.Month)

        assertEquals(70, september.tracks)
        assertEquals(50, september.topTracks.size)
        assertEquals(50, september.topArtists.size)
        assertEquals(70, september.artists, "the count is of all, the top is cut")
        assertEquals("track%07d".format(70), september.topTracks.first().song.id)
    }

    // endregion

    // region When

    @Test
    fun `a week has seven bars and each play lands on its local day`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-28T00:10:00", minutes = 1),
            play("aaaaaaaaaa1", "2026-09-28T23:50:00", minutes = 2),
            play("aaaaaaaaaa1", "2026-10-04T12:00:00", minutes = 4)
        )

        val week = stats(input(events), StatsPeriod.Week)

        assertContentEquals(listOf(3L, 0, 0, 0, 0, 0, 4).map { it * MINUTE }, week.bars.map { it.ms })
        assertEquals(LocalDate.of(2026, 9, 28), week.bars.first().date)
        assertEquals(LocalDate.of(2026, 10, 4), week.bars.last().date)
        assertEquals(LocalDate.of(2026, 10, 4), week.busiestBar?.date)
    }

    @Test
    fun `a play at night is the local day's, not the UTC one`() {
        // 00:30 in Moscow on the 2nd is still the 1st in UTC
        val events = listOf(play("aaaaaaaaaa1", "2026-09-02T00:30:00", minutes = 5))

        val september = stats(input(events), StatsPeriod.Month)

        assertEquals(30, september.bars.size)
        assertEquals(5 * MINUTE, september.bars[1].ms)
        assertEquals(0L, september.bars[0].ms)
        assertEquals(5 * MINUTE, september.hours[0])
    }

    @Test
    fun `a month has a bar per day and a year a bar per month`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-02-14T12:00:00", minutes = 5),
            play("aaaaaaaaaa1", "2026-09-30T12:00:00", minutes = 7)
        )

        assertEquals(30, stats(input(events), StatsPeriod.Month).bars.size)
        assertEquals(28, stats(input(events), StatsPeriod.Month, offset = -7).bars.size)
        val year = stats(input(events), StatsPeriod.Year)
        assertEquals(12, year.bars.size)
        assertEquals(5 * MINUTE, year.bars[1].ms)
        assertEquals(7 * MINUTE, year.bars[8].ms)
        assertEquals(LocalDate.of(2026, 9, 1), year.busiestBar?.date)
    }

    @Test
    fun `all time has a bar per year with the empty ones between and up to this year`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2023-06-01T12:00:00", minutes = 5),
            play("aaaaaaaaaa1", "2025-06-01T12:00:00", minutes = 7)
        )

        val bars = stats(input(events), StatsPeriod.AllTime).bars

        assertEquals(listOf(2023, 2024, 2025, 2026), bars.map { it.date.year })
        assertContentEquals(listOf(5L, 0, 7, 0).map { it * MINUTE }, bars.map { it.ms })
        assertTrue(stats(input(emptyList()), StatsPeriod.AllTime).bars.isEmpty())
    }

    @Test
    fun `the hours of the day are the local hours and the favorite part is the loudest one`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-02T09:15:00", minutes = 10),
            play("aaaaaaaaaa1", "2026-09-03T21:05:00", minutes = 20),
            play("aaaaaaaaaa1", "2026-09-04T21:50:00", minutes = 15),
            play("aaaaaaaaaa1", "2026-09-05T14:00:00", minutes = 30)
        )

        val september = stats(input(events), StatsPeriod.Month)

        assertEquals(24, september.hours.size)
        assertEquals(10 * MINUTE, september.hours[9])
        assertEquals(35 * MINUTE, september.hours[21])
        assertEquals(21, september.peakHour, "the hour with the most, 21:00 has 35 minutes and 14:00 30")
        assertEquals(DayPart.Evening, september.favoriteDayPart)
        assertEquals(DayPart.Morning, favoriteDayPart(List(24) { if (it == 7) 5L else 0L }))
        assertNull(favoriteDayPart(List(24) { 0L }))
    }

    // endregion

    // region Discoveries

    @Test
    fun `discoveries are tracks first played in the period, the most played five of them`() {
        val old = "aaaaaaaaaa0"
        val events = listOf(play(old, "2026-05-01T10:00:00", minutes = 60), play(old, "2026-09-10T10:00:00", minutes = 60)) +
            (1..7).map { play("aaaaaaaaaa$it", "2026-09-05T10:00:00", minutes = it.toLong()) }

        val september = stats(input(events), StatsPeriod.Month)

        val discoveries = assertNotNull(september.discoveries)
        assertEquals(7, discoveries.count, "the old track is not new, however much it played")
        assertEquals(5, discoveries.top.size)
        assertEquals((7 downTo 3).map { "aaaaaaaaaa$it" }, discoveries.top.map { it.song.id })
        assertNull(stats(input(events), StatsPeriod.AllTime).discoveries, "in all time everything was new once")
    }

    @Test
    fun `a track first heard on another device is not new here either`() {
        val events = listOf(
            play("aaaaaaaaaa1", "2026-09-10T10:00:00", device = "mac")
        )
        // The Mac played it in August, this month is the second time; the earliest play is of any device
        val first = mapOf("aaaaaaaaaa1" to at("2026-08-01T10:00:00"))

        val september = stats(input(events, firstPlays = first), StatsPeriod.Month)

        assertEquals(0, september.discoveries?.count)
        assertEquals(at("2026-08-01T10:00:00"), september.earliest)
    }

    @Test
    fun `there is an earlier period to look at while the history goes back to it`() {
        val events = listOf(play("aaaaaaaaaa1", "2026-07-20T10:00:00"))

        assertTrue(stats(input(events), StatsPeriod.Month).hasEarlier)
        assertTrue(stats(input(events), StatsPeriod.Month, offset = -1).hasEarlier)
        assertTrue(!stats(input(events), StatsPeriod.Month, offset = -2).hasEarlier, "July is the first")
        assertTrue(!stats(input(events), StatsPeriod.AllTime).hasEarlier)
        assertTrue(!stats(input(emptyList()), StatsPeriod.Month).hasEarlier)
    }

    @Test
    fun `an empty period is empty and says so`() {
        val september = stats(input(emptyList()), StatsPeriod.Month)

        assertTrue(september.isEmpty)
        assertEquals(0L, september.totalMs)
        assertTrue(september.topTracks.isEmpty() && september.topArtists.isEmpty() && september.topAlbums.isEmpty())
        assertNull(september.busiestBar)
        assertNull(september.peakHour)
        assertNull(september.favoriteDayPart)
    }

    @Test
    fun `percent change is rounded and needs something to compare with`() {
        assertEquals(12, percentChange(112, 100))
        assertEquals(-50, percentChange(50, 100))
        assertEquals(0, percentChange(100, 100))
        assertEquals(-100, percentChange(0, 100))
        assertEquals(33, percentChange(4, 3))
        assertNull(percentChange(5, 0))
        assertNull(percentChange(5, null))
    }

    // endregion

    @Test
    fun `fifty thousand plays are counted in well under 300 ms`() {
        val songCount = 6_000
        val songs = (0 until songCount).map { song("song%07d".format(it), artists = "Artist ${it % 900}, Guest") }
        val artists = (0 until songCount step 2).map { StatArtistLink("song%07d".format(it), "UC${it % 700}", "Artist ${it % 700}", null) }
        val albums = (0 until songCount step 3).map { StatAlbumLink("song%07d".format(it), "MPREb_${it % 400}", "Album ${it % 400}", null) }
        val overrides = (0 until songCount step 50).map { TrackOverride("song%07d".format(it), albumTitle = "Own ${it % 40}", updatedAt = 1) }
        val random = java.util.Random(7)
        val start = at("2026-01-01T00:00:00")
        val span = at("2026-12-31T23:59:00") - start
        val events = (0 until 50_000).map {
            StatEvent(
                songId = "song%07d".format(random.nextInt(songCount)),
                timestamp = start + (random.nextDouble() * span).toLong(),
                playTime = 60_000L + random.nextInt(180_000),
                deviceId = if (random.nextInt(4) == 0) "mac" else null
            )
        }
        val data = input(events, songs, artists, albums, overrides)
        val window = statsWindow(StatsPeriod.Year, 0, today, moscow)

        // A first run warms the classes up, the second is what a person waits for
        computeStats(data, window, moscow, today)
        val begun = System.nanoTime()
        val year = computeStats(data, window, moscow, today)
        val took = (System.nanoTime() - begun) / 1_000_000

        assertEquals(50_000, year.plays)
        assertTrue(took < 300, "50 000 plays took $took ms")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
