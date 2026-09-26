package app.melogold.domain.voice

/**
 * A track a voice command found or plays (tasks/0006-gemini-app-functions.md).
 *
 * @property id the video id of YouTube, or the key of a local file
 * @property inLibrary liked or played before: the person knows it
 */
data class VoiceTrack(
    val id: String,
    val title: String,
    val artists: String? = null,
    val durationText: String? = null,
    val inLibrary: Boolean = false
)

/** What a name in a voice command may point at. */
enum class CollectionKind { Artist, Album, Playlist }

/**
 * An artist, an album or a playlist, of the library or of YouTube Music.
 *
 * @property id the browse id of YouTube Music; for an own playlist of the library, its row id
 * @property authors who made it (the artists of an album, the channel of a playlist), for matching and answers
 */
data class VoiceCollection(
    val kind: CollectionKind,
    val id: String,
    val name: String,
    val authors: String? = null
)

/** A queue YouTube Music makes from a seed: the mix of an artist, the radio of a track. */
data class VoiceRadio(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    val playlistSetVideoId: String? = null
)

/** Where what plays was found; [code] is what an agent sees. */
enum class VoiceSource(val code: String) {
    Library("library"),
    YouTubeMusic("youtube_music"),
    YouTube("youtube"),

    /** The command acted on what is already in the queue: pause, resume, next. */
    Queue("queue")
}

/**
 * The answer of a voice command.
 *
 * @property title what plays now
 * @property collection the artist, album or playlist that plays, if any
 * @property needsApp the system did not let the app play in the background: the person has to open it
 */
data class VoiceResult(
    val title: String,
    val artists: String? = null,
    val collection: String? = null,
    val source: VoiceSource,
    val needsApp: Boolean = false
)

/** Why a voice command could not be done; the message is for the person, in Russian and English. */
class VoiceException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** Nothing matches, or nothing plays. */
        NotFound,

        /** The request itself is wrong: no query, a malformed video id. */
        InvalidArgument,

        /** No network, or YouTube did not answer in time, and the library has nothing. */
        Unavailable
    }
}

/** What «включи X» from the system asks for (REWRITE §3.14.3). */
sealed interface VoiceRequest {
    /** An empty request: continue the last queue. */
    data object Resume : VoiceRequest

    data class Song(val query: String) : VoiceRequest
    data class Artist(val name: String) : VoiceRequest
    data class Album(val name: String) : VoiceRequest
    data class Playlist(val name: String) : VoiceRequest
}
