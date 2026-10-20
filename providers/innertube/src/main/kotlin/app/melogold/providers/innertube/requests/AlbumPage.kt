package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.models.bodies.SearchBody
import app.melogold.providers.innertube.utils.from
import io.ktor.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

suspend fun Innertube.albumPage(body: BrowseBody) =
    playlistPage(body)
        ?.map { album ->
            album.withSongs(
                browseId = body.browseId,
                params = body.params,
                playlist = { listId -> playlistPage(BrowseBody(browseId = "VL$listId"))?.getOrNull() },
                searchSongs = { query ->
                    searchPage(
                        body = SearchBody(query = query, params = Innertube.SearchFilter.Song.value),
                        fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                    )?.getOrNull()?.items
                }
            )
        }

/**
 * Треки альбома. В списке альбома YouTube Music отдаёт клип (`MUSIC_VIDEO_TYPE_OMV`) везде, где у
 * песни есть клип, а длительность в строке — песни; плейлист альбома (`OLAK5uy_…`) несёт сами песни
 * (`MUSIC_VIDEO_TYPE_ATV`), поэтому треки берутся оттуда. Плейлист пуст, не загрузился или сам из
 * клипов — остаются строки самого альбома, а песню для каждого оставшегося клипа ищет поиск
 * «Песни» ([songsForClips]).
 *
 * [playlist] и [searchSongs] — запросы плейлиста по его id (без `VL`) и поиска «Песни»: снаружи,
 * чтобы проверять на фикстурах.
 */
internal suspend fun Innertube.PlaylistOrAlbumPage.withSongs(
    browseId: String,
    params: String?,
    playlist: suspend (listId: String) -> Innertube.PlaylistOrAlbumPage?,
    searchSongs: suspend (query: String) -> List<Innertube.SongItem>?
): Innertube.PlaylistOrAlbumPage {
    // Переиздание «CALL ME IF YOU GET LOST: The Estate Sale» (20.10.2026): плейлист приходит без
    // `contents`, и раньше его пустой список заменял треки альбома — альбом не открывался
    val audio = url
        ?.let { Url(it).parameters["list"] }
        ?.let { playlist(it) }
        ?.songsPage
        ?.takeIf { page -> page.items.orEmpty().any { !it.isClip } }
    val album = if (audio == null) this else copy(songsPage = audio)

    val songs = album.songsPage?.items?.map { song ->
        song.copy(
            authors = song.authors ?: album.authors,
            album = Innertube.Info(
                name = album.title,
                endpoint = NavigationEndpoint.Endpoint.Browse(
                    browseId = browseId,
                    params = params
                )
            ),
            thumbnail = album.thumbnail
        )
    }

    return album.copy(songsPage = album.songsPage?.copy(items = songs?.let { songsForClips(it, searchSongs) }))
}

/** Сколько поисков песен для клипов альбома идёт сразу. */
private const val SONG_SEARCHES_AT_ONCE = 8

/** Длительность песни из поиска может разойтись со строкой альбома на округление. */
private const val SONG_DURATION_SLACK_MS = 2_000L

private const val SONG = "MUSIC_VIDEO_TYPE_ATV"

/** Не клипы: песня, обычное видео пользователя, выпуск подкаста. */
private val NOT_CLIPS = setOf(SONG, "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")

/**
 * [tracks] с песнями вместо клипов, которым не нашлось пары в плейлисте альбома: поиск «Песни» по
 * «исполнители название», пара — по [songForClip]. Поиски идут по [SONG_SEARCHES_AT_ONCE] сразу,
 * чтобы альбом не открывался секундами. Клип без пары (или если поиск не прошёл) остаётся клипом —
 * лучше сыграть клип, чем чужую песню. Один трек дважды не берётся.
 */
internal suspend fun songsForClips(
    tracks: List<Innertube.SongItem>,
    searchSongs: suspend (query: String) -> List<Innertube.SongItem>?
): List<Innertube.SongItem> {
    val clips = tracks.withIndex().filter { it.value.isClip }
    if (clips.isEmpty()) return tracks

    val permits = Semaphore(SONG_SEARCHES_AT_ONCE)
    val found = coroutineScope {
        clips.map { (index, clip) ->
            async { index to permits.withPermit { searchSongs(clip.searchQuery) }.orEmpty() }
        }.awaitAll()
    }

    val taken = tracks.mapNotNullTo(mutableSetOf()) { it.videoId }
    val result = tracks.toMutableList()
    for ((index, songs) in found) {
        val song = songForClip(result[index], songs.filter { it.videoId !in taken }) ?: continue
        song.videoId?.let(taken::add)
        result[index] = result[index].playing(song)
    }
    return result
}

/**
 * Песня для клипа из выдачи поиска «Песни», когда плейлист альбома её не дал: у переиздания
 * Tyler, The Creator «CALL ME IF YOU GET LOST: The Estate Sale» (2023) плейлист альбома пуст, а
 * песни есть только у альбома 2021 года (20.10.2026).
 *
 * Пара — песня (`MUSIC_VIDEO_TYPE_ATV`) с тем же названием (без учёта регистра и пробелов), хоть
 * одним общим исполнителем (по id или имени) и длительностью строки альбома [durationMs] ±2 с: в
 * строке стоит длительность песни, а у клипа она своя (LEMONHEAD — 2:11 у песни и 1:26 у клипа,
 * CORSO — 2:27 и 5:32). Без длительности строки пары нет.
 */
internal fun songForClip(
    clip: Innertube.SongItem,
    found: List<Innertube.SongItem>,
    durationMs: Long? = clip.durationMs
): Innertube.SongItem? {
    durationMs ?: return null
    val wanted = clip.info?.name?.let(::titleKey) ?: return null

    return found.firstOrNull { song ->
        song.videoType == SONG &&
            song.info?.name?.let(::titleKey) == wanted &&
            song.durationMs?.let { abs(it - durationMs) <= SONG_DURATION_SLACK_MS } == true &&
            sharesArtist(clip, song)
    }
}

private val Innertube.SongItem.videoId get() = info?.endpoint?.videoId

private val Innertube.SongItem.videoType
    get() = info?.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType

/** Клип — трек с типом, и это не песня, не видео пользователя и не подкаст (как `is_clip` у Linux). */
internal val Innertube.SongItem.isClip get() = videoType.let { it != null && it !in NOT_CLIPS }

/** «Исполнитель» строки, как его пишет YouTube Music: имена вместе с « & » и запятыми между ними. */
private val Innertube.SongItem.artistsText
    get() = authors?.joinToString("") { it.name.orEmpty() }?.takeIf { it.isNotBlank() }

private val Innertube.SongItem.searchQuery get() = "${artistsText.orEmpty()} ${info?.name.orEmpty()}".trim()

/** «2:11» и «1:02:03» — в миллисекундах; другое — null. */
internal val Innertube.SongItem.durationMs: Long?
    get() {
        val parts = durationText?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        return numbers.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

private fun titleKey(text: String) = text.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

private fun sharesArtist(a: Innertube.SongItem, b: Innertube.SongItem): Boolean {
    fun Innertube.SongItem.ids() = authors.orEmpty().mapNotNull { it.endpoint?.browseId }.toSet()

    // Разделители (« & », «, ») — тоже элементы списка, без букв: за исполнителя не считаются
    fun Innertube.SongItem.names() = (
        authors.orEmpty().mapNotNull { author -> author.name?.takeIf { name -> name.any(Char::isLetterOrDigit) } } +
            listOfNotNull(artistsText)
        ).map(::titleKey).toSet()

    return (a.ids() intersect b.ids()).isNotEmpty() || (a.names() intersect b.names()).isNotEmpty()
}

/** Строка альбома играет [song]: меняются только videoId и тип, название, альбом и длительность — строки. */
private fun Innertube.SongItem.playing(song: Innertube.SongItem) = copy(
    info = info?.copy(
        endpoint = info.endpoint?.copy(
            videoId = song.videoId,
            watchEndpointMusicSupportedConfigs = song.info?.endpoint?.watchEndpointMusicSupportedConfigs
        )
    )
)
