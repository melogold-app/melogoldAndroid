package app.melogold.android.data.stats

import app.melogold.android.models.Song
import app.melogold.android.models.TrackOverride
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** A play of the History as the statistics read it (tasks/0016): what, when, how long, on which device (`null`: here). */
data class StatEvent(val songId: String, val timestamp: Long, val playTime: Long, val deviceId: String?)

/** The first artist of a track in the map of its artists, with what the artist has to show. */
data class StatArtistLink(val songId: String, val artistId: String, val name: String?, val thumbnailUrl: String?)

/** The album of a track in the map of its albums. */
data class StatAlbumLink(val songId: String, val albumId: String, val title: String?, val thumbnailUrl: String?)

/** When a track was first played, on any device. */
data class FirstPlay(val songId: String, val firstAt: Long)

/** Everything the counting reads, as Room gives it. */
class StatsInput(
    val events: List<StatEvent>,
    val songs: Map<String, Song>,
    val artists: Map<String, StatArtistLink>,
    val albums: Map<String, StatAlbumLink>,
    val overrides: Map<String, TrackOverride>,
    val firstPlays: Map<String, Long>
)

/** A track of the tops: [title] and [artist] with the person's own names laid over; [song] plays it. */
data class TopTrack(val song: Song, val title: String, val artist: String?, val plays: Int, val ms: Long)

/** An artist of the tops. [id] is the browse id on YouTube Music; without one (a name the person wrote) it is null. */
data class TopArtist(val key: String, val id: String?, val name: String, val thumbnailUrl: String?, val plays: Int, val ms: Long, val tracks: Int)

/** An album of the tops, as [TopArtist]. */
data class TopAlbum(val key: String, val id: String?, val title: String, val thumbnailUrl: String?, val plays: Int, val ms: Long, val tracks: Int)

/** One bar of "When you listened": the day, the month or the year that starts on [date], and how long it played. */
data class StatsBar(val date: LocalDate, val ms: Long)

/** Tracks heard for the first time in the period and the most played of them. */
data class Discoveries(val count: Int, val top: List<TopTrack>)

/** Parts of the day of "Time of day". */
enum class DayPart(val hours: IntRange) { Night(0..5), Morning(6..11), Afternoon(12..17), Evening(18..23) }

/**
 * The statistics of one period (tasks/0016): the numbers, the tops (at most [TOP_LIMIT] each), the bars over the days,
 * months or years, the hours of the day, and the discoveries.
 *
 * @param previousMs how long the period before it played, for the comparison; null for all time
 * @param bars the days of a week or a month, the 12 months of a year, the years of all time
 * @param hours how long played in each hour of the day, 24 of them
 * @param discoveries null for all time: everything was new once
 * @param earliest the time of the first play in the History, on any device
 */
data class ListeningStats(
    val window: StatsWindow,
    val totalMs: Long,
    val plays: Int,
    val tracks: Int,
    val artists: Int,
    val albums: Int,
    val previousMs: Long?,
    val topTracks: List<TopTrack>,
    val topArtists: List<TopArtist>,
    val topAlbums: List<TopAlbum>,
    val bars: List<StatsBar>,
    val hours: List<Long>,
    val discoveries: Discoveries?,
    val earliest: Long? = null
) {
    val isEmpty get() = plays == 0

    /** Whether there is anything before this period to look at: the history starts before it. */
    val hasEarlier: Boolean get() = window.period != StatsPeriod.AllTime && earliest != null && window.start > earliest

    /** "+12 %" against the period before; null without one or when it had nothing. */
    val changePercent: Int? get() = percentChange(totalMs, previousMs)

    /** The most played month, day or year of [bars]; null without plays. */
    val busiestBar: StatsBar? get() = bars.maxByOrNull { it.ms }?.takeIf { it.ms > 0 }

    /** The part of the day with the most listening, and the hour with the most; null without plays. */
    val favoriteDayPart: DayPart? get() = favoriteDayPart(hours)
    val peakHour: Int? get() = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
}

const val TOP_LIMIT = 50

/** How much more (or less) [current] is than [previous] in whole percent; null when there is nothing to compare with. */
fun percentChange(current: Long, previous: Long?): Int? =
    previous?.takeIf { it > 0 }?.let { ((current - it) * PERCENT / it.toDouble()).roundToInt() }

fun favoriteDayPart(hours: List<Long>): DayPart? {
    val byPart = DayPart.entries.associateWith { part -> part.hours.sumOf { hours.getOrElse(it) { 0L } } }
    return byPart.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
}

private const val PERCENT = 100.0
private const val HOURS_PER_DAY = 24
private const val MONTHS_PER_YEAR = 12
private const val DISCOVERIES_SHOWN = 5

private val ARTIST_SEPARATORS = listOf(", ", " & ", " feat. ", " ft. ")

/**
 * The first artist of a line of artists: "Noize MC feat. Oxxxymiron" is "Noize MC". Null for nothing to speak of.
 */
fun firstArtist(artistsText: String?): String? {
    val text = artistsText?.trim().orEmpty()
    if (text.isEmpty()) return null
    var end = text.length
    ARTIST_SEPARATORS.forEach { separator ->
        val at = text.indexOf(separator, ignoreCase = true)
        if (at in 1 until end) end = at
    }
    return text.substring(0, end).trim().takeIf { it.isNotEmpty() }
}

private fun keyOf(name: String) = name.trim().lowercase(Locale.ROOT)

private class Tally {
    var plays = 0
    var ms = 0L
}

/**
 * An artist or an album being added up. Its picture is the one YouTube has for it, else the cover of its most played
 * track (the first added).
 */
private class Group(var id: String?, val name: String) {
    var plays = 0
    var ms = 0L
    var tracks = 0
    var pictureUrl: String? = null
    var trackCoverUrl: String? = null

    val thumbnailUrl get() = pictureUrl ?: trackCoverUrl

    fun learn(id: String?, pictureUrl: String?, trackCoverUrl: String?) {
        if (this.id == null) this.id = id
        if (this.pictureUrl == null) this.pictureUrl = pictureUrl
        if (this.trackCoverUrl == null) this.trackCoverUrl = trackCoverUrl
    }
}

/**
 * Counts the plays of [window] on the devices [includes] says yes to. A play belongs to the period its time falls in,
 * in [zone]; the period before it is counted for the comparison from the same events.
 *
 * The artist of a track is, in this order: the one the person wrote for it (tasks/0012), the first of the map of its
 * artists, the first of its `artistsText`. The album: the one the person wrote, else the one of its map, else none.
 * A name the person wrote joins the album or artist of the same name that YouTube has.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun computeStats(
    input: StatsInput,
    window: StatsWindow,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    includes: (deviceId: String?) -> Boolean = { true }
): ListeningStats {
    val previousWindow = window.previous(zone)
    var previousMs = 0L
    var totalMs = 0L
    var plays = 0
    val perSong = HashMap<String, Tally>()
    val hours = LongArray(HOURS_PER_DAY)
    val first = window.firstDay
    val byIndex = when (window.period) {
        StatsPeriod.Week, StatsPeriod.Month -> LongArray(window.days)
        StatsPeriod.Year -> LongArray(MONTHS_PER_YEAR)
        StatsPeriod.AllTime -> LongArray(0)
    }
    val byYear = java.util.TreeMap<Int, Long>()

    input.events.forEach { event ->
        if (!includes(event.deviceId)) return@forEach
        val time = event.playTime.coerceAtLeast(0)
        if (previousWindow != null && previousWindow.contains(event.timestamp)) {
            previousMs += time
            return@forEach
        }
        if (!window.contains(event.timestamp)) return@forEach

        totalMs += time
        plays++
        val tally = perSong.getOrPut(event.songId) { Tally() }
        tally.plays++
        tally.ms += time

        val local = Instant.ofEpochMilli(event.timestamp).atZone(zone)
        hours[local.hour] += time
        when (window.period) {
            StatsPeriod.Week, StatsPeriod.Month -> {
                val day = (local.toLocalDate().toEpochDay() - first!!.toEpochDay()).toInt()
                if (day in byIndex.indices) byIndex[day] += time
            }

            StatsPeriod.Year -> byIndex[local.monthValue - 1] += time
            StatsPeriod.AllTime -> byYear.merge(local.year, time, Long::plus)
        }
    }

    val ranked = perSong.entries
        .mapNotNull { (id, tally) ->
            input.songs[id]?.let { song ->
                val override = input.overrides[id]
                TopTrack(
                    song = song,
                    title = override?.title ?: song.title,
                    artist = override?.artistsText ?: song.artistsText,
                    plays = tally.plays,
                    ms = tally.ms
                )
            }
        }
        .sortedWith(compareByDescending<TopTrack> { it.ms }.thenByDescending { it.plays }.thenBy { it.title })

    val artistGroups = LinkedHashMap<String, Group>()
    val albumGroups = LinkedHashMap<String, Group>()
    ranked.forEach { track ->
        val id = track.song.id
        val override = input.overrides[id]

        val link = input.artists[id]
        val artistName = when {
            override?.artistsText != null -> firstArtist(override.artistsText)
            else -> link?.name?.takeIf { it.isNotBlank() } ?: firstArtist(track.song.artistsText)
        }
        if (artistName != null) {
            // The id of the artist YouTube has stays only while the name is still the one it gave
            val artistId = link?.artistId?.takeIf { link.name != null && keyOf(link.name) == keyOf(artistName) }
            val group = artistGroups.getOrPut(keyOf(artistName)) { Group(artistId, artistName) }
            group.add(track)
            // A name the person wrote that YouTube has too gets its page and its picture
            group.learn(artistId, link?.thumbnailUrl.takeIf { artistId != null }, track.song.thumbnailUrl)
        }

        val albumLink = input.albums[id]
        val albumTitle = override?.albumTitle ?: albumLink?.title?.takeIf { it.isNotBlank() }
        if (albumTitle != null) {
            val albumId = albumLink?.albumId?.takeIf { albumLink.title != null && keyOf(albumLink.title) == keyOf(albumTitle) }
            val group = albumGroups.getOrPut(keyOf(albumTitle)) { Group(albumId, albumTitle) }
            group.add(track)
            group.learn(albumId, albumLink?.thumbnailUrl.takeIf { albumId != null }, track.song.thumbnailUrl)
        }
    }
    val artists = artistGroups.values.sortedForTop()
    val albums = albumGroups.values.sortedForTop()

    val discoveries = if (window.period == StatsPeriod.AllTime) null else {
        val fresh = ranked.filter { input.firstPlays[it.song.id]?.let(window::contains) == true }
        Discoveries(count = fresh.size, top = fresh.take(DISCOVERIES_SHOWN))
    }

    return ListeningStats(
        window = window,
        totalMs = totalMs,
        plays = plays,
        tracks = ranked.size,
        artists = artists.size,
        albums = albums.size,
        previousMs = previousWindow?.let { previousMs },
        topTracks = ranked.take(TOP_LIMIT),
        topArtists = artists.take(TOP_LIMIT).map { TopArtist(keyOf(it.name), it.id, it.name, it.thumbnailUrl, it.plays, it.ms, it.tracks) },
        topAlbums = albums.take(TOP_LIMIT).map { TopAlbum(keyOf(it.name), it.id, it.name, it.thumbnailUrl, it.plays, it.ms, it.tracks) },
        bars = bars(window, byIndex, byYear, today),
        hours = hours.toList(),
        discoveries = discoveries,
        earliest = input.firstPlays.values.minOrNull()
    )
}

private fun Group.add(track: TopTrack) {
    plays += track.plays
    ms += track.ms
    tracks++
}

private fun Collection<Group>.sortedForTop() =
    sortedWith(compareByDescending<Group> { it.ms }.thenByDescending { it.plays }.thenBy { it.name })

private fun bars(window: StatsWindow, byIndex: LongArray, byYear: java.util.TreeMap<Int, Long>, today: LocalDate): List<StatsBar> {
    val first = window.firstDay
    return when (window.period) {
        StatsPeriod.Week, StatsPeriod.Month -> byIndex.indices.map { StatsBar(first!!.plusDays(it.toLong()), byIndex[it]) }
        StatsPeriod.Year -> byIndex.indices.map { StatsBar(first!!.plusMonths(it.toLong()), byIndex[it]) }
        StatsPeriod.AllTime -> if (byYear.isEmpty()) emptyList() else {
            val last = maxOf(byYear.lastKey(), today.year)
            (byYear.firstKey()..last).map { StatsBar(LocalDate.of(it, 1, 1), byYear[it] ?: 0L) }
        }
    }
}
