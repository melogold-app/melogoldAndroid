package app.melogold.domain.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The voice commands on a catalog and a player in memory (tasks/0006-gemini-app-functions.md): the same rule for
 * «включи X» and for every AppFunction Gemini calls.
 */
class VoiceCommandsTest {
    private val catalog = FakeCatalog()
    private val player = FakePlayer()
    private val commands = VoiceCommands(catalog, player, random = Random(7))

    // region playSong
    @Test
    fun `a song is the best result of YouTube Music, then similar ones`() = runTest {
        catalog.songs[STAR_QUERY] = listOf(STAR, STAR_COVER)
        catalog.videos[STAR_QUERY] = listOf(STAR_LIVE)

        val result = commands.playSong(STAR_QUERY, videoId = null)

        assertEquals(listOf("similar ${STAR.id}"), player.started)
        assertEquals(VoiceResult(title = STAR.title, artists = "Кино", source = VoiceSource.YouTubeMusic), result)
        assertEquals(listOf("songs $STAR_QUERY"), catalog.calls, "YouTube is not asked when YouTube Music has it")
    }

    @Test
    fun `without a song on YouTube Music the first video of YouTube plays`() = runTest {
        catalog.videos[STAR_QUERY] = listOf(STAR_LIVE, STAR_COVER)

        val result = commands.playSong(STAR_QUERY, videoId = null)

        assertEquals(listOf("similar ${STAR_LIVE.id}"), player.started)
        assertEquals(VoiceSource.YouTube, result.source)
    }

    @Test
    fun `a video id chosen from a search plays without a search`() = runTest {
        catalog.librarySongs = listOf(STAR)
        catalog.tracksById[STAR_COVER.id] = STAR_COVER

        assertEquals(VoiceSource.Library, commands.playSong(query = "что угодно", videoId = STAR.id).source)
        assertEquals(VoiceSource.YouTubeMusic, commands.playSong(query = null, videoId = " ${STAR_COVER.id} ").source)

        assertEquals(listOf("similar ${STAR.id}", "similar ${STAR_COVER.id}"), player.started)
        assertEquals(listOf("track ${STAR_COVER.id}"), catalog.calls)
    }

    @Test
    fun `a local file of the library plays by its key, a malformed id is refused`() = runTest {
        val local = VoiceTrack(id = "local:42", title = "Запись с репетиции")
        catalog.librarySongs = listOf(local)

        commands.playSong(query = null, videoId = local.id)
        assertEquals(listOf("similar local:42"), player.started)

        val error = assertFailsWith<VoiceException> { commands.playSong(query = null, videoId = "not a video") }
        assertEquals(VoiceException.Kind.InvalidArgument, error.kind)
    }

    @Test
    fun `without a query there is nothing to play`() = runTest {
        listOf(null, "", "   ").forEach { query ->
            val error = assertFailsWith<VoiceException> { commands.playSong(query, videoId = null) }
            assertEquals(VoiceException.Kind.InvalidArgument, error.kind)
        }
        assertTrue(player.started.isEmpty())
    }

    @Test
    fun `without a network the library plays what it has, whatever the order of the words`() = runTest {
        catalog.isOnline = false
        catalog.librarySongs = listOf(PEREMEN, STAR)

        val result = commands.playSong("кино звезда по имени солнце", videoId = null)

        assertEquals(listOf("similar ${STAR.id}"), player.started)
        assertEquals(VoiceSource.Library, result.source)
        assertTrue(catalog.calls.isEmpty(), "no network, no calls")
    }

    @Test
    fun `a library track with «ё» is found said with «е», and the other way round`() = runTest {
        catalog.isOnline = false
        catalog.librarySongs = listOf(PEREMEN, ONCE_MORE, STARS)

        commands.playSong("еще раз", videoId = null)
        commands.playSong("кино звезды", videoId = null)
        assertEquals(listOf("similar ${ONCE_MORE.id}", "similar ${STARS.id}"), player.started)
        assertEquals(listOf(ONCE_MORE.id), commands.search("Ещё раз").map { it.id })
    }

    @Test
    fun `YouTube failing falls back to the library, and says so when the library has nothing`() = runTest {
        catalog.failing = true
        catalog.librarySongs = listOf(STAR)

        assertEquals(VoiceSource.Library, commands.playSong(STAR_QUERY, videoId = null).source)

        val error = assertFailsWith<VoiceException> { commands.playSong("Группа крови", videoId = null) }
        assertEquals(VoiceException.Kind.Unavailable, error.kind)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a search gives up after 8 seconds`() = runTest {
        catalog.hangs = true

        val error = assertFailsWith<VoiceException> { commands.playSong(STAR_QUERY, videoId = null) }

        assertEquals(VoiceException.Kind.Unavailable, error.kind)
        assertEquals(VoiceCommands.SEARCH_TIMEOUT.inWholeMilliseconds, currentTime, "songs time out; videos are not asked")
        assertTrue(player.started.isEmpty())
    }

    @Test
    fun `nothing anywhere is not found`() = runTest {
        val error = assertFailsWith<VoiceException> { commands.playSong("абырвалг", videoId = null) }

        assertEquals(VoiceException.Kind.NotFound, error.kind)
        assertTrue("абырвалг" in error.message.orEmpty())
        assertEquals(listOf("songs абырвалг", "videos абырвалг"), catalog.calls)
    }
    // endregion

    // region searchSongs
    @Test
    fun `a search lists the library first, then YouTube Music, without repeats`() = runTest {
        catalog.librarySongs = listOf(STAR.copy(inLibrary = true), PEREMEN)
        catalog.songs[STAR_QUERY] = listOf(STAR, STAR_COVER)

        val found = commands.search(STAR_QUERY)

        assertEquals(listOf(STAR.id, STAR_COVER.id), found.map { it.id })
        assertTrue(found.first().inLibrary)
    }

    @Test
    fun `a search falls back to videos and keeps to the limit`() = runTest {
        catalog.videos[STAR_QUERY] = List(15) { STAR_LIVE.copy(id = "video%07d".format(it)) }

        assertEquals(VoiceCommands.SEARCH_LIMIT, commands.search(STAR_QUERY).size)
        assertEquals(3, commands.search(STAR_QUERY, limit = 3).size)
    }

    @Test
    fun `a search that finds nothing fails, and so does an empty one`() = runTest {
        assertEquals(VoiceException.Kind.NotFound, assertFailsWith<VoiceException> { commands.search("абырвалг") }.kind)
        assertEquals(VoiceException.Kind.InvalidArgument, assertFailsWith<VoiceException> { commands.search(" ") }.kind)

        catalog.isOnline = false
        assertEquals(VoiceException.Kind.Unavailable, assertFailsWith<VoiceException> { commands.search("абырвалг") }.kind)
    }
    // endregion

    // region playPlaylist
    @Test
    fun `an own playlist comes first, even by a part of its name`() = runTest {
        catalog.library[CollectionKind.Playlist] = listOf(ROAD)
        catalog.libraryContents[ROAD.id] = listOf(STAR, PEREMEN)
        catalog.found[CollectionKind.Playlist to "дорога"] = listOf(ROAD_ONLINE)

        val result = commands.playPlaylist("дорога")

        assertEquals(listOf("tracks ${STAR.id},${PEREMEN.id}"), player.started)
        assertEquals(VoiceResult(STAR.title, "Кино", collection = ROAD.name, source = VoiceSource.Library), result)
        assertTrue(catalog.calls.isEmpty(), "YouTube Music is not asked")
    }

    @Test
    fun `a playlist the library does not have is searched on YouTube Music`() = runTest {
        catalog.found[CollectionKind.Playlist to "русский рок"] = listOf(ROAD_ONLINE, ROCK_ONLINE)
        catalog.onlineContents[ROCK_ONLINE.id] = listOf(PEREMEN, STAR)

        val result = commands.playPlaylist("русский рок")

        assertEquals(listOf("tracks ${PEREMEN.id},${STAR.id}"), player.started, "the one that matches, not the first")
        assertEquals(VoiceSource.YouTubeMusic, result.source)
        assertEquals(ROCK_ONLINE.name, result.collection)
    }

    @Test
    fun `an empty own playlist is said to be empty`() = runTest {
        catalog.library[CollectionKind.Playlist] = listOf(ROAD)

        val error = assertFailsWith<VoiceException> { commands.playPlaylist("Дорога") }

        assertEquals(VoiceException.Kind.NotFound, error.kind)
        assertTrue("пуст" in error.message.orEmpty())
    }

    @Test
    fun `a playlist called favorites is the favorites, unless the person has one so named`() = runTest {
        catalog.favorites = listOf(PEREMEN, STAR)

        commands.playPlaylist("Избранное")
        assertEquals(listOf("tracks ${PEREMEN.id},${STAR.id}"), player.started)

        val own = VoiceCollection(CollectionKind.Playlist, id = "9", name = "Избранное 2020")
        catalog.library[CollectionKind.Playlist] = listOf(own)
        catalog.libraryContents[own.id] = listOf(STAR)
        commands.playPlaylist("избранное")
        assertEquals("tracks ${STAR.id}", player.started.last())
    }
    // endregion

    // region playArtist, playAlbum
    @Test
    fun `an artist plays as YouTube Music's mix of their songs`() = runTest {
        catalog.found[CollectionKind.Artist to "Кино"] = listOf(KINOLOG, KINO)
        catalog.mixes[KINO.id] = KINO_MIX
        player.radios[KINO_MIX.playlistId] = listOf(PEREMEN, STAR)

        val result = commands.playArtist("Кино")

        assertEquals(listOf("radio ${KINO_MIX.playlistId}"), player.started, "the exact name, not the first result")
        assertEquals(
            VoiceResult(PEREMEN.title, "Кино", collection = "Кино", source = VoiceSource.YouTubeMusic),
            result,
            "what plays first"
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the answer for an artist waits until the mix is in the queue`() = runTest {
        catalog.found[CollectionKind.Artist to "Кино"] = listOf(KINO)
        catalog.mixes[KINO.id] = KINO_MIX
        player.radios[KINO_MIX.playlistId] = listOf(STAR)
        player.radioDelay = 3.seconds
        player.refusesBackground = true

        val result = commands.playArtist("Кино")

        assertEquals(3_000, currentTime, "YouTube Music took 3 s to give the mix")
        assertEquals(listOf("radio ${KINO_MIX.playlistId}"), player.startedWhenAskedForApp, "asked once it plays")
        assertTrue(result.needsApp)
    }

    @Test
    fun `a mix YouTube Music gives nothing for is not found, a failing or slow one is unavailable`() = runTest {
        catalog.found[CollectionKind.Artist to "Кино"] = listOf(KINO)
        catalog.mixes[KINO.id] = KINO_MIX

        assertEquals(VoiceException.Kind.NotFound, assertFailsWith<VoiceException> { commands.playArtist("Кино") }.kind)

        player.radios[KINO_MIX.playlistId] = listOf(STAR)
        player.radioFails = true
        assertEquals(VoiceException.Kind.Unavailable, assertFailsWith<VoiceException> { commands.playArtist("Кино") }.kind)

        player.radioFails = false
        player.radioDelay = VoiceCommands.SEARCH_TIMEOUT + 1.seconds
        assertEquals(VoiceException.Kind.Unavailable, assertFailsWith<VoiceException> { commands.playArtist("Кино") }.kind)

        assertTrue(player.started.isEmpty(), "nothing was said to play")
        assertEquals(null, player.startedWhenAskedForApp)
    }

    @Test
    fun `a saved artist plays offline as their tracks shuffled`() = runTest {
        catalog.isOnline = false
        catalog.library[CollectionKind.Artist] = listOf(KINO)
        catalog.libraryContents[KINO.id] = listOf(STAR, PEREMEN, BLOOD)

        val result = commands.playArtist("кино")

        val started = player.started.single()
        assertTrue(started.startsWith("tracks "))
        assertEquals(setOf(STAR.id, PEREMEN.id, BLOOD.id), started.removePrefix("tracks ").split(',').toSet())
        assertEquals(VoiceSource.Library, result.source)
    }

    @Test
    fun `a saved artist that only contains the name loses to YouTube Music`() = runTest {
        catalog.library[CollectionKind.Artist] = listOf(KINOLOG)
        catalog.found[CollectionKind.Artist to "кино"] = listOf(KINO)
        catalog.mixes[KINO.id] = KINO_MIX
        catalog.mixes[KINOLOG.id] = VoiceRadio(playlistId = "RDkinolog")
        player.radios[KINO_MIX.playlistId] = listOf(STAR)
        player.radios["RDkinolog"] = listOf(STAR_COVER)

        commands.playArtist("кино")

        assertEquals(listOf("radio ${KINO_MIX.playlistId}"), player.started)
    }

    @Test
    fun `an album is found by its title and artist`() = runTest {
        val remaster = VoiceCollection(CollectionKind.Album, "MPREb_remaster", "Группа крови (Remastered)", "Кино")
        val album = VoiceCollection(CollectionKind.Album, "MPREb_blood", "Группа крови", "Кино")
        catalog.found[CollectionKind.Album to "Группа крови Кино"] = listOf(remaster, album)
        catalog.onlineContents[album.id] = listOf(BLOOD, STAR)

        val result = commands.playAlbum("Группа крови Кино")

        assertEquals(listOf("tracks ${BLOOD.id},${STAR.id}"), player.started)
        assertEquals(VoiceResult(BLOOD.title, "Кино", collection = album.name, source = VoiceSource.YouTubeMusic), result)
    }

    @Test
    fun `a saved album plays from the library, or from YouTube Music when the library lacks its tracks`() = runTest {
        val album = VoiceCollection(CollectionKind.Album, "MPREb_blood", "Группа крови", "Кино")
        catalog.library[CollectionKind.Album] = listOf(album)
        catalog.onlineContents[album.id] = listOf(BLOOD, STAR)

        assertEquals(VoiceSource.YouTubeMusic, commands.playAlbum("группа крови").source)
        assertEquals(listOf("tracks ${BLOOD.id},${STAR.id}"), player.started)

        catalog.libraryContents[album.id] = listOf(BLOOD)
        assertEquals(VoiceSource.Library, commands.playAlbum("группа крови").source)
        assertEquals("tracks ${BLOOD.id}", player.started.last())
    }

    @Test
    fun `an unknown artist is not found`() = runTest {
        val error = assertFailsWith<VoiceException> { commands.playArtist("Несуществующие") }
        assertEquals(VoiceException.Kind.NotFound, error.kind)
    }
    // endregion

    // region favorites and the queue
    @Test
    fun `favorites play from the last liked, or shuffled`() = runTest {
        catalog.favorites = listOf(STAR, PEREMEN, BLOOD, STAR_COVER) + List(6) { STAR.copy(id = "liked%06d".format(it)) }
        val liked = catalog.favorites.map { it.id }

        val result = commands.playFavorites(shuffled = false)
        assertEquals("tracks " + liked.joinToString(","), player.started.last())
        assertEquals(VoiceCommands.FAVORITES, result.collection)

        commands.playFavorites(shuffled = true)
        val shuffled = player.started.last().removePrefix("tracks ").split(',')
        assertEquals(liked.toSet(), shuffled.toSet())
        assertTrue(shuffled != liked, "Random(7) shuffles ten tracks")
    }

    @Test
    fun `empty favorites are said to be empty`() = runTest {
        assertEquals(VoiceException.Kind.NotFound, assertFailsWith<VoiceException> { commands.playFavorites(shuffled = true) }.kind)
    }

    @Test
    fun `pause, resume and next act on the queue`() = runTest {
        listOf(commands::pause, commands::resume, commands::next).forEach { command ->
            assertEquals(VoiceException.Kind.NotFound, assertFailsWith<VoiceException> { command() }.kind)
        }

        player.queue = listOf(STAR, PEREMEN)
        assertEquals(VoiceResult(STAR.title, "Кино", source = VoiceSource.Queue), commands.resume())
        assertTrue(player.isPlaying)
        assertEquals(PEREMEN.title, commands.next().title)
        assertEquals(PEREMEN.title, commands.pause().title)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `an empty request continues the last queue, the others go to their commands`() = runTest {
        player.queue = listOf(PEREMEN)
        assertEquals(PEREMEN.title, commands.play(VoiceRequest.Resume).title)

        catalog.songs[STAR_QUERY] = listOf(STAR)
        commands.play(VoiceRequest.Song(STAR_QUERY))
        assertEquals("similar ${STAR.id}", player.started.last())
    }

    @Test
    fun `the app is asked for when the system refuses to play in the background`() = runTest {
        catalog.songs[STAR_QUERY] = listOf(STAR)
        player.refusesBackground = true

        assertTrue(commands.playSong(STAR_QUERY, videoId = null).needsApp)
        assertFalse(commands.pause().needsApp, "a pause needs nothing")
    }
    // endregion

    private companion object {
        const val STAR_QUERY = "Звезда по имени Солнце"

        val STAR = VoiceTrack("nt3edWLgIg4", "Звезда по имени Солнце", "Кино", "3:45")
        val STAR_COVER = VoiceTrack("coverAAAAAA", "Звезда по имени Солнце (кавер)", "Кто-то", "3:50")
        val STAR_LIVE = VoiceTrack("liveAAAAAAA", "Кино — Звезда по имени Солнце (live 1990)", "Kino Live", "4:10")
        val PEREMEN = VoiceTrack("peremenAAAA", "Хочу перемен", "Кино", "4:50")
        val BLOOD = VoiceTrack("bloodAAAAAA", "Группа крови", "Кино", "4:46")
        val ONCE_MORE = VoiceTrack("onceMoreAAA", "Ещё раз", "Кто-то", "3:10")
        val STARS = VoiceTrack("starsAAAAAA", "Звёзды", "Кино", "3:20")

        val ROAD = VoiceCollection(CollectionKind.Playlist, id = "3", name = "Дорога на дачу")
        val ROAD_ONLINE = VoiceCollection(CollectionKind.Playlist, "VLroad", "Дорога", "Кто-то")
        val ROCK_ONLINE = VoiceCollection(CollectionKind.Playlist, "VLrock", "Русский рок", "YouTube Music")

        val KINO = VoiceCollection(CollectionKind.Artist, "UCkino", "Кино")
        val KINOLOG = VoiceCollection(CollectionKind.Artist, "UCkinolog", "Кинолог")
        val KINO_MIX = VoiceRadio(playlistId = "RDkino", params = "shuffle")
    }
}
