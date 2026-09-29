package app.melogold.android.ui.screens.library.stats

import app.melogold.android.data.stats.ListeningStats
import app.melogold.android.data.stats.StatAlbumLink
import app.melogold.android.data.stats.StatArtistLink
import app.melogold.android.data.stats.StatEvent
import app.melogold.android.data.stats.StatsInput
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.data.stats.computeStats
import app.melogold.android.data.stats.statsWindow
import app.melogold.android.models.Song
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random

/** A listening history made up for the pictures of Insights: a year of evenings with a dozen artists. */
object StatsFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private class Track(val id: String, val title: String, val artist: String, val artistId: String, val album: String?, val weight: Int)

    private val tracks = listOf(
        Track("kinoGroupKr1", "Группа крови", "Кино", "UCkino", "Группа крови", 9),
        Track("kinoZvezda01", "Звезда по имени Солнце", "Кино", "UCkino", "Группа крови", 7),
        Track("kinoPachka01", "Пачка сигарет", "Кино", "UCkino", "Звезда по имени Солнце", 6),
        Track("kishKukla001", "Кукла колдуна", "Король и Шут", "UCkish", "Как в старой сказке", 8),
        Track("kishLesnik01", "Лесник", "Король и Шут", "UCkish", "Как в старой сказке", 6),
        Track("kishDom00001", "Дом при дороге", "Король и Шут", "UCkish", null, 3),
        Track("noizeVydyh01", "Выдыхай", "Noize MC", "UCnoize", "Hard Reset", 7),
        Track("noizeDestroy", "Устрой дестрой", "Noize MC", "UCnoize", "Hard Reset", 4),
        Track("noizeJvachka", "Жвачка", "Noize MC", "UCnoize", null, 2),
        Track("mumiyDelfin1", "Дельфины", "Мумий Тролль", "UCmumiy", "Морская", 5),
        Track("mumiyNevesta", "Невеста", "Мумий Тролль", "UCmumiy", "Морская", 3),
        Track("lenExponat01", "Экспонат", "Ленинград", "UCleningrad", "Пуля", 4),
        Track("lenWww000001", "WWW", "Ленинград", "UCleningrad", null, 2),
        Track("splinVyhoda1", "Выхода нет", "Сплин", "UCsplin", "Гранатовый альбом", 5),
        Track("splinRomans1", "Романс", "Сплин", "UCsplin", "Гранатовый альбом", 3),
        Track("dtOsen000001", "Что такое осень", "ДДТ", "UCddt", "Актриса Весна", 4),
        Track("dtRodina0001", "Родина", "ДДТ", "UCddt", "Актриса Весна", 2),
        Track("akvGorod0001", "Город золотой", "Аквариум", "UCakvarium", "Дети Декабря", 3),
        Track("akvNebo00001", "Небо и трава", "Аквариум", "UCakvarium", null, 2),
        Track("chaifRock001", "Не спеши", "Чайф", "UCchaif", "Оранжевое настроение", 2),
        Track("nautilusBra1", "Крылья", "Наутилус Помпилиус", "UCnautilus", "Разлука", 2),
        Track("bgKozhan0001", "Иванушка", "Би-2", "UCbi2", "Би-2", 2),
        Track("agataMoya001", "Как на войне", "Агата Кристи", "UCagata", "Опиум", 2),
        Track("zveriRayon01", "Районы, кварталы", "Звери", "UCzveri", "Районы-кварталы", 1),
        Track("kanikuly0001", "Каникулы", "Ундервуд", "UCunder", null, 1)
    )

    val songs: List<Song> = tracks.map {
        Song(id = it.id, title = it.title, artistsText = it.artist, durationText = "3:30", thumbnailUrl = "https://i.example/${it.id}.jpg")
    }

    private val artists = tracks.map { StatArtistLink(it.id, it.artistId, it.artist, null) }

    private val albums = tracks.filter { it.album != null }.map { StatAlbumLink(it.id, "MPREb_${it.album!!.hashCode().toUInt()}", it.album, null) }

    /** About 3 500 plays through 2026 with a liking for evenings and for March and September. */
    val events: List<StatEvent> by lazy {
        val random = Random(2026)
        val weights = tracks.flatMap { track -> List(track.weight) { track } }
        (0 until EVENT_COUNT).map {
            val track = weights[random.nextInt(weights.size)]
            val month = when (random.nextInt(12)) { 0 -> 3; 1 -> 9; else -> 1 + random.nextInt(12) }
            val day = 1 + random.nextInt(if (month == 2) 28 else 30)
            val hour = if (random.nextInt(10) < 6) 18 + random.nextInt(6) else random.nextInt(24)
            val time = LocalDateTime.of(2026, month, day, hour, random.nextInt(60))
            StatEvent(track.id, time.atZone(zone).toInstant().toEpochMilli(), 90_000L + random.nextInt(150_000), null)
        }
    }

    val input by lazy {
        StatsInput(
            events = events,
            songs = songs.associateBy { it.id },
            artists = artists.associateBy { it.songId },
            albums = albums.associateBy { it.songId },
            overrides = emptyMap(),
            firstPlays = events.groupBy { it.songId }.mapValues { (_, list) -> list.minOf { it.timestamp } }
        )
    }

    /** The statistics of [period] as they stand on [today]. */
    fun stats(period: StatsPeriod, offset: Int = 0, today: LocalDate = LocalDate.of(2026, 9, 30)): ListeningStats =
        computeStats(input, statsWindow(period, offset, today, zone), zone, today)

    /** The same, for a person who listened to nothing. */
    fun empty(period: StatsPeriod, today: LocalDate = LocalDate.of(2026, 9, 30)): ListeningStats =
        computeStats(StatsInput(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()), statsWindow(period, 0, today, zone), zone, today)

    private const val EVENT_COUNT = 3_500
}
