package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.json
import app.melogold.providers.innertube.models.BrowseResponse
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.models.SearchResponse
import app.melogold.providers.innertube.utils.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Клипы в списке альбома: треки берутся из плейлиста альбома, а если он их не дал — из строк самого
 * альбома, и песню для каждого клипа ищет поиск «Песни» (fixtures/album-clips, задание 0030).
 */
class AlbumClipsTest {
    /**
     * 20.10.2026: плейлист переиздания «CALL ME IF YOU GET LOST: The Estate Sale» приходит без
     * `contents`, и его пустой список заменял треки альбома — экран альбома показывал «YouTube
     * изменил страницу». Теперь остаются 16 строк альбома, а LEMONHEAD и CORSO находят свои песни.
     */
    @Test
    fun `the estate sale keeps its tracks when its playlist is empty and finds songs for the clips`() {
        val rows = page("estate-sale.album.en").songsPage?.items.orEmpty()
        // Ещё 8 строк альбома серые (GREY_OUT, без watchEndpoint): Android их не показывал и раньше
        assertEquals(16, rows.size)
        assertTrue(rows.all { it.isClip }, "все 16 строк — клипы")
        assertNull(page("estate-sale.playlist.en").songsPage, "плейлист альбома пуст")

        val queries = mutableListOf<String>()
        val album = estateSale { query ->
            queries += query
            estateSaleSearch(query)
        }
        val tracks = album.songsPage?.items.orEmpty()

        assertEquals(rows.map { it.info?.name }, tracks.map { it.info?.name }, "строки альбома, в том же порядке")
        assertEquals(16, queries.size, "поиск — по каждому клипу")
        assertTrue(LEMONHEAD_QUERY in queries && CORSO_QUERY in queries, queries.toString())

        for ((title, clip, song, duration) in listOf(
            listOf("LEMONHEAD (feat. 42 Dugg)", "a5JCltuobqQ", "QjckqsLmb1o", "2:11"),
            listOf("CORSO", "Lqvwm3zAILI", "qz-k3DoMxkw", "2:27")
        )) {
            assertEquals(clip, page("estate-sale.album.en").track(title).id, "в строке альбома — клип")
            val track = album.track(title)
            assertEquals(song, track.id, title)
            assertEquals("MUSIC_VIDEO_TYPE_ATV", track.type, title)
            assertEquals(duration, track.durationText, "длительность строки остаётся")
            assertEquals("CALL ME IF YOU GET LOST: The Estate Sale", track.album?.name, "альбом остаётся")
            assertEquals(ESTATE_SALE, track.album?.endpoint?.browseId)
        }
        val others = tracks.filter { it.info?.name !in setOf("LEMONHEAD (feat. 42 Dugg)", "CORSO") }
        val otherRows = rows.filter { it.info?.name !in setOf("LEMONHEAD (feat. 42 Dugg)", "CORSO") }
        assertEquals(otherRows.map { it.id }, others.map { it.id })
        assertTrue(others.all { it.isClip }, "без пары строка остаётся клипом")
    }

    @Test
    fun `a failed search leaves the clips and keeps the tracks`() {
        val tracks = estateSale { null }.songsPage?.items.orEmpty()

        assertEquals(16, tracks.size)
        assertTrue(tracks.all { it.isClip })
    }

    /** Альбом 2021 года: плейлист дал песни — как раньше, треки из плейлиста и ни одного поиска. */
    @Test
    fun `an album whose playlist gives the songs is read from the playlist without searching`() {
        val album = runBlocking {
            page("call-me-if-you-get-lost.album.en").withSongs(
                browseId = "MPREb_rMqWqQe5N3L",
                params = null,
                playlist = { listId ->
                    assertEquals("OLAK5uy_np90iV2eD-YiScFePYgA7-i3c9kOYXMpc", listId)
                    page("call-me-if-you-get-lost.playlist.en")
                },
                searchSongs = { fail("лишний поиск: $it") }
            )
        }
        val tracks = album.songsPage?.items.orEmpty()

        assertEquals(16, tracks.size)
        assertTrue(tracks.all { it.type == "MUSIC_VIDEO_TYPE_ATV" })
        assertEquals("QjckqsLmb1o", album.track("LEMONHEAD (feat. 42 Dugg)").id)
        assertEquals("qz-k3DoMxkw", album.track("CORSO").id)
        assertEquals("aktRqdBmrf4", album.track("SIR BAUDELAIRE (feat. DJ Drama)").id)
        assertTrue(tracks.all { it.album?.endpoint?.browseId == "MPREb_rMqWqQe5N3L" })
    }

    /** Пара строгая: длительность клипа, строка без длительности, чужой исполнитель или клип в выдаче — не пара. */
    @Test
    fun `a song from search must match the title, an artist and the duration of the row`() {
        val lemonhead = estateSale { emptyList() }.track("LEMONHEAD (feat. 42 Dugg)")
        val found = songs("estate-sale.songs-lemonhead.en")

        assertEquals(131_000L, lemonhead.durationMs, "в строке — длительность песни")
        assertEquals("QjckqsLmb1o", songForClip(lemonhead, found)?.id)
        assertNull(songForClip(lemonhead, found, durationMs = 86_000), "длительность клипа, а не песни")
        assertNull(songForClip(lemonhead, found, durationMs = null))
        assertNull(songForClip(lemonhead.copy(durationText = null), found), "строка без длительности")
        assertEquals("QjckqsLmb1o", songForClip(lemonhead, found, durationMs = 132_500)?.id, "округление в 1,5 с")

        val stranger = found.map {
            it.copy(authors = listOf(Innertube.Info("Other", NavigationEndpoint.Endpoint.Browse(browseId = "UCother"))))
        }
        assertNull(songForClip(lemonhead, stranger), "чужой исполнитель")

        val byName = found.map { it.copy(authors = listOf(Innertube.Info("tyler,  the creator", null))) }
        assertEquals("QjckqsLmb1o", songForClip(lemonhead, byName)?.id, "исполнитель по имени без учёта регистра")

        val clipType = lemonhead.info?.endpoint?.watchEndpointMusicSupportedConfigs
        val clips = found.map { song -> song.watch { it.copy(watchEndpointMusicSupportedConfigs = clipType) } }
        assertNull(songForClip(lemonhead, clips), "клип из выдачи — не песня")

        val renamed = lemonhead.copy(info = lemonhead.info?.copy(name = "  lemonhead   (FEAT. 42 dugg) "))
        assertEquals("QjckqsLmb1o", songForClip(renamed, found)?.id, "название без учёта регистра и пробелов")
    }

    @Test
    fun `one song is not taken twice`() {
        val lemonhead = estateSale { emptyList() }.track("LEMONHEAD (feat. 42 Dugg)")
        val twin = lemonhead.watch { it.copy(videoId = "twinClip") }
        val tracks = runBlocking { songsForClips(listOf(lemonhead, twin)) { songs("estate-sale.songs-lemonhead.en") } }

        assertEquals(listOf("QjckqsLmb1o", "twinClip"), tracks.map { it.id })
        assertTrue(tracks[1].isClip)
    }

    @Test
    fun `searches go at most eight at once`() {
        var now = 0
        var most = 0
        estateSale {
            most = maxOf(most, ++now)
            delay(10)
            now--
            emptyList()
        }

        assertEquals(8, most)
    }

    @Test
    fun `durations are read in milliseconds`() {
        val row = estateSale { emptyList() }.track("CORSO")

        assertEquals(147_000L, row.durationMs)
        assertEquals(3_723_000L, row.copy(durationText = "1:02:03").durationMs)
        assertNotNull(row.copy(durationText = "0:58").durationMs)
        assertNull(row.copy(durationText = "1 min").durationMs)
        assertNull(row.copy(durationText = null).durationMs)
    }
}

private const val ESTATE_SALE = "MPREb_VpXYEs5HeNs"
private const val LEMONHEAD_QUERY = "Tyler, The Creator LEMONHEAD (feat. 42 Dugg)"
private const val CORSO_QUERY = "Tyler, The Creator CORSO"

private fun text(name: String): String {
    val resource = AlbumClipsTest::class.java.getResource("/fixtures/album-clips/$name.json")
    return checkNotNull(resource) { "нет фикстуры $name" }.readText()
}

private fun page(name: String) = json.decodeFromString<BrowseResponse>(text(name)).toPlaylistOrAlbumPage()

private fun songs(name: String) = json.decodeFromString<SearchResponse>(text(name))
    .toSearchPage(Innertube.SongItem.Companion::from)
    ?.items
    .orEmpty()

private val Innertube.SongItem.id get() = info?.endpoint?.videoId

private val Innertube.SongItem.type
    get() = info?.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType

private fun Innertube.SongItem.watch(
    change: (NavigationEndpoint.Endpoint.Watch) -> NavigationEndpoint.Endpoint.Watch
) = copy(info = info?.copy(endpoint = info.endpoint?.let(change)))

private fun Innertube.PlaylistOrAlbumPage.track(title: String) =
    songsPage?.items.orEmpty().single { it.info?.name == title }

/** Поиск «Песни» по фикстурам: LEMONHEAD и CORSO записаны, по остальным клипам — пусто. */
private fun estateSaleSearch(query: String) = when (query) {
    LEMONHEAD_QUERY -> songs("estate-sale.songs-lemonhead.en")
    CORSO_QUERY -> songs("estate-sale.songs-corso.en")
    else -> emptyList()
}

/** Estate Sale, каким его видит экран альбома: плейлист пуст, поиск — по [search]. */
private fun estateSale(search: suspend (String) -> List<Innertube.SongItem>?) = runBlocking {
    page("estate-sale.album.en").withSongs(
        browseId = ESTATE_SALE,
        params = null,
        playlist = { listId ->
            assertEquals("OLAK5uy_kSIbgi-WxAiBFs28kWN1cMy7bewO3-LCs", listId)
            page("estate-sale.playlist.en")
        },
        searchSongs = search
    )
}
