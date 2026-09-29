package app.melogold.providers.songlink

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** The other music services whose links open in Melogold (tasks/0017). */
enum class MusicService(val displayName: String) {
    Spotify("Spotify"),
    AppleMusic("Apple Music"),
    YandexMusic("Yandex Music"),
    Deezer("Deezer"),
    Tidal("Tidal"),
    SoundCloud("SoundCloud")
}

/** What a link of such a service points at; [Unknown] for a short link that has to be followed to find out. */
enum class MusicLinkKind { Track, Album, Artist, Playlist, Unknown }

/** A link to a track, album, artist or playlist of another service: which service, what, and the link itself. */
data class MusicServiceLink(val service: MusicService, val kind: MusicLinkKind, val url: String)

/**
 * Tells the links of Spotify, Apple Music, Yandex Music, Deezer, Tidal and SoundCloud, wherever they are in a text, and
 * what each one is. Pure: it never looks at the network.
 */
object MusicServiceLinkParser {
    private val URL = Regex("""(?i)https?://\S+""")
    private const val TRAILING = ".,;:!?)]}>»\"'…"

    fun parse(input: String): MusicServiceLink? {
        val url = URL.find(input)?.value?.trimEnd { it in TRAILING } ?: return null
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryOf(uri.rawQuery)

        return when {
            host in SPOTIFY -> spotify(host, segments)?.let { MusicServiceLink(MusicService.Spotify, it, url) }
            host in APPLE -> MusicServiceLink(MusicService.AppleMusic, apple(segments, query), url)
            host.startsWith("music.yandex.") -> MusicServiceLink(MusicService.YandexMusic, yandex(segments), url)
            host in DEEZER -> MusicServiceLink(MusicService.Deezer, deezer(host, segments), url)
            host in TIDAL -> MusicServiceLink(MusicService.Tidal, tidal(host, segments), url)
            host in SOUNDCLOUD -> MusicServiceLink(MusicService.SoundCloud, soundcloud(host, segments), url)
            else -> null
        }
    }

    private val SPOTIFY = setOf("open.spotify.com", "play.spotify.com", "spotify.link", "spotify.app.link")
    private val APPLE = setOf("music.apple.com", "itunes.apple.com", "geo.music.apple.com")
    private val DEEZER = setOf("deezer.com", "link.deezer.com", "deezer.page.link", "dzr.page.link")
    private val TIDAL = setOf("tidal.com", "listen.tidal.com", "link.tidal.com")
    private val SOUNDCLOUD = setOf("soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")

    /** `open.spotify.com/intl-de/track/<id>` has a language in front. */
    private fun spotify(host: String, segments: List<String>): MusicLinkKind? {
        if (host != "open.spotify.com" && host != "play.spotify.com") return MusicLinkKind.Unknown
        val path = if (segments.firstOrNull()?.startsWith("intl-") == true) segments.drop(1) else segments
        return when (path.firstOrNull()) {
            "track" -> MusicLinkKind.Track
            "album" -> MusicLinkKind.Album
            "artist" -> MusicLinkKind.Artist
            "playlist" -> MusicLinkKind.Playlist
            else -> null
        }
    }

    /** `music.apple.com/<country>/album/<name>/<id>?i=<track>` is the track of the album. */
    private fun apple(segments: List<String>, query: Map<String, String>): MusicLinkKind {
        val kind = segments.firstOrNull { it in setOf("song", "album", "playlist", "artist", "music-video") } ?: return MusicLinkKind.Unknown
        return when (kind) {
            "song" -> MusicLinkKind.Track
            "album" -> if (query["i"] != null) MusicLinkKind.Track else MusicLinkKind.Album
            "playlist" -> MusicLinkKind.Playlist
            "artist" -> MusicLinkKind.Artist
            else -> MusicLinkKind.Unknown
        }
    }

    private fun yandex(segments: List<String>): MusicLinkKind = when {
        "playlists" in segments -> MusicLinkKind.Playlist
        segments.getOrNull(0) == "album" && segments.getOrNull(2) == "track" -> MusicLinkKind.Track
        segments.getOrNull(0) == "track" -> MusicLinkKind.Track
        segments.getOrNull(0) == "album" -> MusicLinkKind.Album
        segments.getOrNull(0) == "artist" -> MusicLinkKind.Artist
        else -> MusicLinkKind.Unknown
    }

    /** `deezer.com/<language>/track/<id>`: the language may be there or not. */
    private fun deezer(host: String, segments: List<String>): MusicLinkKind {
        if (host != "deezer.com") return MusicLinkKind.Unknown
        return when (segments.firstOrNull { it in setOf("track", "album", "artist", "playlist") }) {
            "track" -> MusicLinkKind.Track
            "album" -> MusicLinkKind.Album
            "artist" -> MusicLinkKind.Artist
            "playlist" -> MusicLinkKind.Playlist
            else -> MusicLinkKind.Unknown
        }
    }

    private fun tidal(host: String, segments: List<String>): MusicLinkKind {
        if (host == "link.tidal.com") return MusicLinkKind.Unknown
        return when (segments.firstOrNull { it in setOf("track", "album", "artist", "playlist", "mix") }) {
            "track" -> MusicLinkKind.Track
            "album" -> MusicLinkKind.Album
            "artist" -> MusicLinkKind.Artist
            "playlist", "mix" -> MusicLinkKind.Playlist
            else -> MusicLinkKind.Unknown
        }
    }

    /** `soundcloud.com/<user>/<track>`, `/<user>/sets/<set>`, `/<user>`. */
    private fun soundcloud(host: String, segments: List<String>): MusicLinkKind = when {
        host == "on.soundcloud.com" -> MusicLinkKind.Unknown
        segments.size >= 3 && segments[1] == "sets" -> MusicLinkKind.Playlist
        segments.size == 2 && segments[1] != "sets" -> MusicLinkKind.Track
        segments.size == 1 -> MusicLinkKind.Artist
        else -> MusicLinkKind.Unknown
    }

    private fun queryOf(raw: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val key = runCatching { URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) }.getOrDefault(pair.substringBefore('='))
            out.putIfAbsent(key, pair.substringAfter('=', ""))
        }
        return out
    }
}
