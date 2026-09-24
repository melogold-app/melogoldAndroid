package app.melogold.providers.innertube.links

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * What a link or shared text points at (REWRITE §4.9).
 */
sealed interface LinkTarget {
    data class Video(
        val videoId: String,
        val playlistId: String? = null,
        val index: Int? = null,
        val startMs: Long? = null
    ) : LinkTarget

    /** PL…, RDCLAK5uy_…, RD…, UU…, OLAK5uy_… (an album: the resolver finds it by the first track). */
    data class Playlist(val playlistId: String) : LinkTarget
    data class Album(val browseId: String) : LinkTarget
    data class Channel(val channelId: String) : LinkTarget

    /** `/@name`: needs `resolve_url`. */
    data class Handle(val handle: String) : LinkTarget

    /** `/c/…`, `/user/…`: needs `resolve_url`. */
    data class LegacyChannel(val url: String) : LinkTarget
    data class Search(val query: String) : LinkTarget

    /** Apple Music, Yandex Music or Spotify: importing is a later feature. */
    data class External(val service: String, val url: String) : LinkTarget
    data class Unsupported(val reason: String) : LinkTarget

    companion object {
        const val REASON_EMPTY = "empty"
        const val REASON_INVALID_VIDEO_ID = "invalid_video_id"
        const val REASON_MISSING_PARAMETER = "missing_parameter"
        const val REASON_PRIVATE_PLAYLIST = "private_playlist"
        const val REASON_CLIP = "clip"
        const val REASON_POST = "post"
        const val REASON_UNKNOWN_PATH = "unknown_path"
        const val REASON_UNKNOWN_HOST = "unknown_host"
        const val REASON_UNSUPPORTED_EXTERNAL = "unsupported_external"
    }
}

/**
 * Parses YouTube and YouTube Music links, and text shared from other apps, by the rules and
 * vectors of `docs/spec/youtube-links.vectors.json` (REWRITE §4.9). Pure JVM, no network: the
 * steps that need one (`resolve_url`, the album of an `OLAK5uy_` playlist) belong to the resolver.
 */
object YouTubeLinkParser {
    private const val MAX_QUERY_LENGTH = 200
    private const val MAX_UNWRAP_DEPTH = 3
    private const val VND_PREFIX = "vnd.youtube:"

    private val urlRegex = Regex("""(?i)https?://\S+""")
    private val whitespaceRegex = Regex("""\s+""")
    private val videoIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val timeRegex = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$""")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>»\"'…"

    private val youTubeHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com"
    )
    private val googleHosts = setOf("google.com", "www.google.com")

    // The account's own lists: meaningless outside the account
    private val privatePlaylists = setOf("LL", "WL", "LM")

    fun parse(input: String): LinkTarget {
        val text = input.trim()
        if (text.isEmpty()) return LinkTarget.Unsupported(LinkTarget.REASON_EMPTY)

        if (text.startsWith(VND_PREFIX, ignoreCase = true)) return video(
            videoId = text.substring(VND_PREFIX.length).takeWhile { it !in "?&#" },
            query = emptyMap()
        )

        val url = urlRegex.find(text)?.value?.trimEnd { it in TRAILING_PUNCTUATION }
            ?: return LinkTarget.Search(searchText(text))

        return parseUrl(url, depth = 0)
    }

    private fun searchText(text: String): String {
        val collapsed = text.replace(whitespaceRegex, " ").trim()
        if (collapsed.length <= MAX_QUERY_LENGTH) return collapsed

        // Never cut a surrogate pair in half
        val end = if (Character.isHighSurrogate(collapsed[MAX_QUERY_LENGTH - 1])) MAX_QUERY_LENGTH - 1
        else MAX_QUERY_LENGTH
        return collapsed.substring(0, end)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun parseUrl(url: String, depth: Int): LinkTarget {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
        }
        val host = uri.host?.lowercase() ?: return LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_HOST)
        val query = queryParameters(uri.rawQuery)
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val rawSegments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }

        // Wrappers: unwrap and parse again
        fun unwrap(target: String?): LinkTarget = when {
            target.isNullOrBlank() -> LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)
            depth >= MAX_UNWRAP_DEPTH -> LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
            else -> parseUrl(target, depth + 1)
        }
        if (host == "consent.youtube.com") return unwrap(query["continue"])
        if (host in googleHosts && segments.firstOrNull() == "url") return unwrap(query["q"] ?: query["url"])

        external(host, segments, url)?.let { return it }
        if (host !in youTubeHosts) return LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_HOST)

        if (host == "youtu.be") return segments.firstOrNull()
            ?.let { video(videoId = it, query = query) }
            ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

        val first = segments.firstOrNull() ?: return LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
        val second = segments.getOrNull(1)

        return when {
            first == "attribution_link" -> unwrap(
                query["u"]?.let { if (it.startsWith("/")) "https://www.youtube.com$it" else it }
            )

            first == "watch" -> {
                val videoId = query["v"]
                val list = query["list"]
                when {
                    videoId != null -> video(videoId = videoId, query = query)
                    list.isNullOrEmpty() -> LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)
                    list in privatePlaylists -> LinkTarget.Unsupported(LinkTarget.REASON_PRIVATE_PLAYLIST)
                    else -> LinkTarget.Playlist(list)
                }
            }

            first in setOf("shorts", "live", "embed", "v", "e") ->
                second?.let { video(videoId = it, query = query) }
                    ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

            first == "playlist" -> {
                val list = query["list"]
                when {
                    list.isNullOrEmpty() -> LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)
                    list in privatePlaylists -> LinkTarget.Unsupported(LinkTarget.REASON_PRIVATE_PLAYLIST)
                    else -> LinkTarget.Playlist(list)
                }
            }

            first == "browse" -> when {
                second == null -> LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)
                second.startsWith("VL") -> LinkTarget.Playlist(second.removePrefix("VL"))
                second.startsWith("MPREb_") -> LinkTarget.Album(second)
                second.startsWith("UC") -> LinkTarget.Channel(second)
                else -> LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
            }

            first == "channel" -> when {
                second == null -> LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)
                second.startsWith("UC") -> LinkTarget.Channel(second)
                else -> LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
            }

            first.startsWith("@") && first.length > 1 -> LinkTarget.Handle(first.drop(1))

            first == "c" || first == "user" -> rawSegments.getOrNull(1)
                ?.let { LinkTarget.LegacyChannel("https://www.youtube.com/$first/$it") }
                ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

            first == "search" -> query["q"]?.takeIf { it.isNotBlank() }?.let { LinkTarget.Search(it) }
                ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

            first == "results" -> query["search_query"]?.takeIf { it.isNotBlank() }?.let { LinkTarget.Search(it) }
                ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

            first == "hashtag" -> second?.let { LinkTarget.Search("#$it") }
                ?: LinkTarget.Unsupported(LinkTarget.REASON_MISSING_PARAMETER)

            first == "clip" -> LinkTarget.Unsupported(LinkTarget.REASON_CLIP)
            first == "post" -> LinkTarget.Unsupported(LinkTarget.REASON_POST)
            else -> LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH)
        }
    }

    private fun external(host: String, segments: List<String>, url: String): LinkTarget? {
        val service = when (host) {
            "music.apple.com" -> "apple".takeIf { "playlist" in segments }
            "music.yandex.ru", "music.yandex.com" ->
                "yandex".takeIf { "playlists" in segments || segments.firstOrNull() == "album" }

            "open.spotify.com" -> "spotify".takeIf { segments.firstOrNull() == "playlist" }
            else -> return null
        }
        return service?.let { LinkTarget.External(service = it, url = url) }
            ?: LinkTarget.Unsupported(LinkTarget.REASON_UNSUPPORTED_EXTERNAL)
    }

    private fun video(videoId: String, query: Map<String, String>): LinkTarget {
        if (!videoIdRegex.matches(videoId)) return LinkTarget.Unsupported(LinkTarget.REASON_INVALID_VIDEO_ID)

        return LinkTarget.Video(
            videoId = videoId,
            playlistId = query["list"]?.takeIf { it.isNotEmpty() && it !in privatePlaylists },
            index = query["index"]?.toIntOrNull(),
            startMs = (query["t"] ?: query["start"])?.let(::timeMs)
        )
    }

    /**
     * "90", "90s", "1m30s", "1h2m3s" → milliseconds; anything else → null.
     */
    private fun timeMs(value: String): Long? {
        if (value.isEmpty()) return null
        val match = timeRegex.matchEntire(value) ?: return null
        val (hours, minutes, seconds) = match.destructured
        if (hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null

        val total = (hours.toLongOrNull() ?: 0) * 3600 + (minutes.toLongOrNull() ?: 0) * 60 + (seconds.toLongOrNull() ?: 0)
        return total * 1000
    }

    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()

        val parameters = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = decode(pair.substringBefore('='))
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
            parameters.putIfAbsent(key, value)
        }
        return parameters
    }

    private fun decode(value: String) = try {
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
