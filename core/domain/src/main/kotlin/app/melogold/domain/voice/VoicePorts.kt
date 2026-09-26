package app.melogold.domain.voice

/**
 * Where voice commands look: the library of the app and YouTube Music / YouTube. The online calls throw when the
 * network or YouTube fails; [VoiceCommands] turns that into the library or a [VoiceException].
 */
interface VoiceCatalog {
    /** Whether the device has a network now: without one only the library answers. */
    val isOnline: Boolean

    /**
     * Tracks of the library whose title or artists contain [query] (lowercase, «ё» as «е», so both spellings of a
     * name match), liked and most played first.
     */
    suspend fun libraryTracks(query: String, limit: Int): List<VoiceTrack>

    /** The track with [videoId] if the library has it. */
    suspend fun libraryTrack(videoId: String): VoiceTrack?

    /** The liked tracks, the last liked first, without hidden ones. */
    suspend fun favorites(): List<VoiceTrack>

    /** Own playlists, saved artists or saved albums. */
    suspend fun library(kind: CollectionKind): List<VoiceCollection>

    /** The tracks the library has of [collection], in their order, without hidden ones. */
    suspend fun libraryTracks(collection: VoiceCollection): List<VoiceTrack>

    /** Songs of YouTube Music, the best first. */
    suspend fun searchSongs(query: String): List<VoiceTrack>

    /** Videos of plain YouTube, the best first. */
    suspend fun searchVideos(query: String): List<VoiceTrack>

    /** Artists, albums or community playlists of YouTube Music, the best first. */
    suspend fun search(kind: CollectionKind, query: String): List<VoiceCollection>

    /** The track with [videoId] from YouTube Music; null if there is none. */
    suspend fun track(videoId: String): VoiceTrack?

    /** The tracks of an album or a playlist of YouTube Music, the top songs of an artist. */
    suspend fun tracks(collection: VoiceCollection): List<VoiceTrack>

    /** YouTube Music's mix of the songs of [artist]; null if it has none. */
    suspend fun artistMix(artist: VoiceCollection): VoiceRadio?
}

/** The player the voice commands drive; every play replaces the queue. */
interface VoicePlayer {
    /** Plays [track] alone and then similar tracks, as a search result or a link does. */
    suspend fun playWithSimilar(track: VoiceTrack)

    /** Plays [tracks] from the first. */
    suspend fun playTracks(tracks: List<VoiceTrack>)

    /**
     * Plays the queue YouTube Music makes from [radio] and keeps it going. Returns once its first tracks are in the
     * queue: the first one, or null when YouTube Music has none (the queue stays as it was). Throws when YouTube
     * fails.
     */
    suspend fun playRadio(radio: VoiceRadio): VoiceTrack?

    /** Pauses; what was playing, or null when the queue is empty. */
    suspend fun pause(): VoiceTrack?

    /** Plays the queue on (the last one after a restart); what plays, or null when there is nothing to play. */
    suspend fun resume(): VoiceTrack?

    /** Skips to the next track of the queue; what plays then, or null when the queue is empty. */
    suspend fun next(): VoiceTrack?

    /** Whether the system refused to play while the app is in the background: the person has to open it. */
    suspend fun needsApp(): Boolean
}
