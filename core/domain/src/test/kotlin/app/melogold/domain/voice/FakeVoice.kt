package app.melogold.domain.voice

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration

/** A catalog in memory: the library and what YouTube Music and YouTube answer, without a network. */
class FakeCatalog : VoiceCatalog {
    override var isOnline = true

    /** YouTube throws, as with a network that is up but does not reach it. */
    var failing = false

    /** YouTube never answers. */
    var hangs = false

    var librarySongs = listOf<VoiceTrack>()
    var favorites = listOf<VoiceTrack>()
    val library = mutableMapOf<CollectionKind, List<VoiceCollection>>()
    val libraryContents = mutableMapOf<String, List<VoiceTrack>>()

    val songs = mutableMapOf<String, List<VoiceTrack>>()
    val videos = mutableMapOf<String, List<VoiceTrack>>()
    val found = mutableMapOf<Pair<CollectionKind, String>, List<VoiceCollection>>()
    val onlineContents = mutableMapOf<String, List<VoiceTrack>>()
    val mixes = mutableMapOf<String, VoiceRadio>()
    val tracksById = mutableMapOf<String, VoiceTrack>()

    /** The online calls made, in order. */
    val calls = mutableListOf<String>()

    private suspend fun network(call: String) {
        calls += call
        if (hangs) awaitCancellation()
        if (failing) throw IOException("YouTube is down")
    }

    /** As the library query: any case, «ё» as «е». */
    override suspend fun libraryTracks(query: String, limit: Int) = librarySongs
        .filter { query.folded() in "${it.title} ${it.artists.orEmpty()}".folded() }
        .take(limit)

    private fun String.folded() = lowercase().replace('ё', 'е')

    override suspend fun libraryTrack(videoId: String) = librarySongs.firstOrNull { it.id == videoId }
    override suspend fun favorites() = favorites
    override suspend fun library(kind: CollectionKind) = library[kind].orEmpty()
    override suspend fun libraryTracks(collection: VoiceCollection) = libraryContents[collection.id].orEmpty()

    override suspend fun searchSongs(query: String): List<VoiceTrack> {
        network("songs $query")
        return songs[query].orEmpty()
    }

    override suspend fun searchVideos(query: String): List<VoiceTrack> {
        network("videos $query")
        return videos[query].orEmpty()
    }

    override suspend fun search(kind: CollectionKind, query: String): List<VoiceCollection> {
        network("$kind $query")
        return found[kind to query].orEmpty()
    }

    override suspend fun track(videoId: String): VoiceTrack? {
        network("track $videoId")
        return tracksById[videoId]
    }

    override suspend fun tracks(collection: VoiceCollection): List<VoiceTrack> {
        network("tracks ${collection.id}")
        return onlineContents[collection.id].orEmpty()
    }

    override suspend fun artistMix(artist: VoiceCollection): VoiceRadio? {
        network("mix ${artist.id}")
        return mixes[artist.id]
    }
}

/** A player that remembers what it was told. */
class FakePlayer : VoicePlayer {
    /** What was started, in order: `similar <id>`, `tracks <id>,<id>`, `radio <playlistId>`. */
    val started = mutableListOf<String>()
    var queue = listOf<VoiceTrack>()
    var index = 0
    var isPlaying = false

    /** The system does not let the app play in the background. */
    var refusesBackground = false

    /** The first tracks YouTube Music gives for a radio, by its playlist id; none when missing. */
    val radios = mutableMapOf<String?, List<VoiceTrack>>()

    /** How long YouTube Music takes to give the first tracks of a radio. */
    var radioDelay = Duration.ZERO

    /** YouTube fails when asked for a radio. */
    var radioFails = false

    /** What had been started when [needsApp] was asked. */
    var startedWhenAskedForApp: List<String>? = null

    override suspend fun playWithSimilar(track: VoiceTrack) {
        started += "similar ${track.id}"
        queue = listOf(track)
        index = 0
        isPlaying = true
    }

    override suspend fun playTracks(tracks: List<VoiceTrack>) {
        started += "tracks " + tracks.joinToString(",") { it.id }
        queue = tracks
        index = 0
        isPlaying = true
    }

    override suspend fun playRadio(radio: VoiceRadio): VoiceTrack? {
        delay(radioDelay)
        if (radioFails) throw IOException("YouTube is down")
        val tracks = radios[radio.playlistId].orEmpty().ifEmpty { return null }
        started += "radio ${radio.playlistId}"
        queue = tracks
        index = 0
        isPlaying = true
        return tracks.first()
    }

    override suspend fun pause() = queue.getOrNull(index)?.also { isPlaying = false }
    override suspend fun resume() = queue.getOrNull(index)?.also { isPlaying = true }

    override suspend fun next(): VoiceTrack? {
        if (queue.isEmpty()) return null
        index = (index + 1) % queue.size
        isPlaying = true
        return queue[index]
    }

    override suspend fun needsApp(): Boolean {
        startedWhenAskedForApp = started.toList()
        return refusesBackground
    }
}
