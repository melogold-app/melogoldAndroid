package app.melogold.domain.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The voice commands of Melogold (REWRITE §3.14.3, tasks/0006-gemini-app-functions.md): «включи X» from Google
 * Assistant or Android Auto, and the AppFunctions Gemini calls. Every entry point comes here, so they all follow
 * one rule:
 *
 * - a song is the best result of YouTube Music, else the first video of YouTube, and similar tracks follow it;
 *   without a network, the library;
 * - an artist, an album or a playlist is looked for in the library first (an own playlist by any match, a saved
 *   artist or album by whole words), then on YouTube Music;
 * - an empty request continues the last queue;
 * - a search takes at most [searchTimeout], then the command fails.
 */
class VoiceCommands(
    private val catalog: VoiceCatalog,
    private val player: VoicePlayer,
    private val searchTimeout: Duration = SEARCH_TIMEOUT,
    private val random: Random = Random.Default
) {
    /** «включи X» from the system. */
    suspend fun play(request: VoiceRequest): VoiceResult = when (request) {
        VoiceRequest.Resume -> resume()
        is VoiceRequest.Song -> playSong(query = request.query, videoId = null)
        is VoiceRequest.Artist -> playArtist(request.name)
        is VoiceRequest.Album -> playAlbum(request.name)
        is VoiceRequest.Playlist -> playPlaylist(request.name)
    }

    /**
     * Tracks for [query], for the person to choose from: up to [LIBRARY_IN_SEARCH] of the library, then YouTube
     * Music's songs (YouTube's videos when it has none).
     */
    suspend fun search(query: String?, limit: Int = SEARCH_LIMIT): List<VoiceTrack> {
        val wanted = query.required("query")
        val library = libraryTracks(wanted).take(LIBRARY_IN_SEARCH)
        val online = online {
            catalog.searchSongs(wanted).ifEmpty { catalog.searchVideos(wanted) }
        }

        val found = (library + online.value.orEmpty()).distinctBy { it.id }.take(limit.coerceAtLeast(1))
        if (found.isEmpty()) throw notFound(online, wanted)
        return found
    }

    /** Plays [videoId] when given (a choice from [search]), else the best song for [query], then similar ones. */
    suspend fun playSong(query: String?, videoId: String?): VoiceResult {
        videoId?.trim()?.takeIf { it.isNotEmpty() }?.let { return playTrack(it) }

        val wanted = query.required("query")
        val songs = online { catalog.searchSongs(wanted) }
        // YouTube only when YouTube Music answered without songs: a failure or a timeout would only repeat there
        val videos = if (songs.value?.isEmpty() == true) online { catalog.searchVideos(wanted) } else null

        val (track, source) = songs.value?.firstOrNull()?.let { it to VoiceSource.YouTubeMusic }
            ?: videos?.value?.firstOrNull()?.let { it to VoiceSource.YouTube }
            // Without a network (or for a local file YouTube does not know) the library plays what it has
            ?: libraryTracks(wanted).firstOrNull()?.let { it to VoiceSource.Library }
            ?: throw notFound(songs.takeIf { it.failed } ?: videos ?: songs, wanted)

        player.playWithSimilar(track)
        return track.result(source)
    }

    suspend fun playArtist(name: String?): VoiceResult = playCollection(CollectionKind.Artist, name)
    suspend fun playAlbum(name: String?): VoiceResult = playCollection(CollectionKind.Album, name)
    suspend fun playPlaylist(name: String?): VoiceResult = playCollection(CollectionKind.Playlist, name)

    /** The liked tracks, the last liked first, or [shuffled]. */
    suspend fun playFavorites(shuffled: Boolean): VoiceResult {
        val tracks = catalog.favorites()
        if (tracks.isEmpty()) {
            throw VoiceException(VoiceException.Kind.NotFound, "В Избранном пусто / Favorites are empty")
        }

        val queue = if (shuffled) tracks.shuffled(random) else tracks
        player.playTracks(queue)
        return queue.first().result(VoiceSource.Library, collection = FAVORITES)
    }

    suspend fun pause(): VoiceResult = player.pause()?.result(VoiceSource.Queue, askApp = false)
        ?: throw VoiceException(VoiceException.Kind.NotFound, "Ничего не играет / Nothing is playing")

    suspend fun resume(): VoiceResult = player.resume()?.result(VoiceSource.Queue)
        ?: throw VoiceException(
            VoiceException.Kind.NotFound,
            "Нечего продолжить: очередь пуста / Nothing to resume: the queue is empty"
        )

    suspend fun next(): VoiceResult = player.next()?.result(VoiceSource.Queue)
        ?: throw VoiceException(VoiceException.Kind.NotFound, "Очередь пуста / The queue is empty")

    private suspend fun playTrack(videoId: String): VoiceResult {
        // The library first: a local file has no video id, and a known track needs no network
        catalog.libraryTrack(videoId)?.let { track ->
            player.playWithSimilar(track)
            return track.result(VoiceSource.Library)
        }
        if (!VIDEO_ID.matches(videoId)) {
            throw VoiceException(VoiceException.Kind.InvalidArgument, "Неверный videoId / Malformed videoId: $videoId")
        }

        val online = online { catalog.track(videoId) }
        val track = online.value ?: throw notFound(online, videoId)
        player.playWithSimilar(track)
        return track.result(VoiceSource.YouTubeMusic)
    }

    private suspend fun playCollection(kind: CollectionKind, name: String?): VoiceResult {
        val wanted = name.required("name")

        // What the person keeps comes first: an own playlist by any match, a saved artist or album by whole words
        val own = NameMatch.best(catalog.library(kind), wanted)
        if (own != null && (kind == CollectionKind.Playlist || own.second >= MatchLevel.Prefix)) {
            return start(ownPlan(own.first))
        }
        if (kind == CollectionKind.Playlist && NameMatch.normalize(wanted) in FAVORITES_NAMES) {
            return playFavorites(shuffled = false)
        }

        val online = online {
            val found = catalog.search(kind, wanted)
            val pick = NameMatch.best(found, wanted)?.first ?: found.firstOrNull()
            pick?.let { onlinePlan(it) }
        }
        online.value?.let { return start(it) }
        own?.let { return start(ownPlan(it.first)) }
        throw notFound(online, wanted)
    }

    /** How to play a collection of the library: its tracks, an artist's mix when there is a network. */
    private suspend fun ownPlan(collection: VoiceCollection): Plan = when (collection.kind) {
        CollectionKind.Playlist -> catalog.libraryTracks(collection).takeIf { it.isNotEmpty() }
            ?.let { Plan.Tracks(collection, it, VoiceSource.Library) }
            ?: throw VoiceException(
                VoiceException.Kind.NotFound,
                "Плейлист «${collection.name}» пуст / The playlist «${collection.name}» is empty"
            )

        CollectionKind.Album -> catalog.libraryTracks(collection).takeIf { it.isNotEmpty() }
            ?.let { Plan.Tracks(collection, it, VoiceSource.Library) }
            ?: online { onlinePlan(collection) }.let { it.value ?: throw notFound(it, collection.name) }

        CollectionKind.Artist -> online { catalog.artistMix(collection) }.value
            ?.let { Plan.Mix(collection, it, VoiceSource.Library) }
            ?: catalog.libraryTracks(collection).takeIf { it.isNotEmpty() }
                ?.let { Plan.Tracks(collection, it.shuffled(random), VoiceSource.Library) }
            ?: throw notFound(Online(null, failed = !catalog.isOnline), collection.name)
    }

    /** How to play a collection of YouTube Music; null when it has nothing to play. */
    private suspend fun onlinePlan(collection: VoiceCollection): Plan? = when (collection.kind) {
        CollectionKind.Artist -> catalog.artistMix(collection)
            ?.let { Plan.Mix(collection, it, VoiceSource.YouTubeMusic) }
            ?: catalog.tracks(collection).takeIf { it.isNotEmpty() }
                ?.let { Plan.Tracks(collection, it, VoiceSource.YouTubeMusic) }

        CollectionKind.Album, CollectionKind.Playlist -> catalog.tracks(collection).takeIf { it.isNotEmpty() }
            ?.let { Plan.Tracks(collection, it, VoiceSource.YouTubeMusic) }
    }

    private suspend fun start(plan: Plan): VoiceResult = when (plan) {
        is Plan.Tracks -> {
            player.playTracks(plan.tracks)
            plan.tracks.first().result(plan.source, collection = plan.collection.name)
        }

        // The answer waits for the mix to be in the queue: a mix that fails or comes empty is not reported as
        // playing, and whether the app has to be opened is known only once the queue is there
        is Plan.Mix -> {
            val first = online { player.playRadio(plan.radio) }
            val track = first.value ?: throw notFound(first, plan.collection.name)
            track.result(plan.source, collection = plan.collection.name)
        }
    }

    /** Tracks of the library for [wanted], the best matches first. */
    private suspend fun libraryTracks(wanted: String): List<VoiceTrack> {
        val word = NameMatch.keyWord(wanted) ?: return emptyList()
        return catalog.libraryTracks(word, LIBRARY_CANDIDATES)
            .mapNotNull { track -> NameMatch.level(track.title, track.artists, wanted)?.let { track to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private suspend fun VoiceTrack.result(
        source: VoiceSource,
        collection: String? = null,
        askApp: Boolean = true
    ) = VoiceResult(
        title = title,
        artists = artists,
        collection = collection,
        source = source,
        needsApp = askApp && player.needsApp()
    )

    /** An online call within [searchTimeout]; [Online.failed] when there is no network, it threw or took too long. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> online(block: suspend () -> T): Online<T> {
        if (!catalog.isOnline) return Online(null, failed = true)
        return try {
            withTimeoutOrNull(searchTimeout) { Online(block(), failed = false) } ?: Online(null, failed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Online(null, failed = true)
        }
    }

    private fun notFound(online: Online<*>, what: String) = if (online.failed) VoiceException(
        VoiceException.Kind.Unavailable,
        "Нет сети или YouTube не ответил, а в библиотеке ничего нет: «$what» / " +
            "No network or YouTube did not answer, and the library has nothing: «$what»"
    ) else VoiceException(VoiceException.Kind.NotFound, "Не найдено / Not found: «$what»")

    private fun String?.required(name: String): String = this?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw VoiceException(VoiceException.Kind.InvalidArgument, "Пустой параметр $name / Empty parameter $name")

    private class Online<T>(val value: T?, val failed: Boolean)

    private sealed interface Plan {
        data class Tracks(val collection: VoiceCollection, val tracks: List<VoiceTrack>, val source: VoiceSource) : Plan
        data class Mix(val collection: VoiceCollection, val radio: VoiceRadio, val source: VoiceSource) : Plan
    }

    companion object {
        /** REWRITE §3.14.3: a search that takes longer fails with «Не удалось найти». */
        val SEARCH_TIMEOUT = 8.seconds

        const val SEARCH_LIMIT = 10
        const val LIBRARY_IN_SEARCH = 3
        private const val LIBRARY_CANDIDATES = 50

        /** The name of the favorites in answers. */
        const val FAVORITES = "Избранное / Favorites"

        /** A playlist asked for by these names is the favorites, unless the person has a playlist so named. */
        val FAVORITES_NAMES = setOf(
            "избранное", "мое избранное", "любимое", "любимые", "любимые песни", "понравившиеся", "мои лайки", "лайки",
            "favorites", "favourites", "my favorites", "liked", "liked songs", "liked music"
        )

        /** A video id of YouTube. */
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
