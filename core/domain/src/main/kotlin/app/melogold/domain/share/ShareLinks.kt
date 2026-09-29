package app.melogold.domain.share

import app.melogold.domain.server.ServerAddress
import app.melogold.domain.server.ServerAddressPolicy
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The links the app makes when something is shared (tasks/0017): a track, an album, an artist or a playlist of YouTube
 * (which everyone can open), and the text that goes with them.
 */
object ShareLinks {
    private const val MUSIC = "https://music.youtube.com"
    private const val YOUTUBE = "https://www.youtube.com"

    /** The first tracks a `watch_videos` link opens: YouTube plays 50 of them. */
    const val WATCH_VIDEOS_LIMIT = 50

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** A track of the catalog ([isMusic]) is on YouTube Music, a plain video on YouTube. */
    fun track(videoId: String, isMusic: Boolean) = "${if (isMusic) MUSIC else YOUTUBE}/watch?v=$videoId"

    fun album(browseId: String) = "$MUSIC/browse/$browseId"

    /** An artist of the catalog is on YouTube Music, a [channel] of YouTube on YouTube. */
    fun artist(browseId: String, channel: Boolean) = "${if (channel) YOUTUBE else MUSIC}/channel/$browseId"

    /** A playlist of YouTube; [playlistId] may still carry the `VL` of a browse id. */
    fun playlist(playlistId: String) = "$MUSIC/playlist?list=${playlistId.removePrefix("VL")}"

    /**
     * An own playlist for someone without Melogold: the first [WATCH_VIDEOS_LIMIT] of its YouTube videos as one list.
     * Files on the device and anything that is not a video id are left out; null when nothing is left.
     */
    fun watchVideos(videoIds: List<String>): String? {
        val ids = videoIds.filter { VIDEO_ID.matches(it) }.take(WATCH_VIDEOS_LIMIT)
        return ids.takeIf { it.isNotEmpty() }?.let { "$YOUTUBE/watch_videos?video_ids=${it.joinToString(",")}" }
    }

    /** "Title — Artist" and the link under it; without [subtitle] the title alone. */
    fun message(title: String, subtitle: String?, url: String): String {
        val head = subtitle?.trim()?.takeIf { it.isNotEmpty() }?.let { "${title.trim()} — $it" } ?: title.trim()
        return if (head.isEmpty()) url else "$head\n$url"
    }

    /** The link of a shared playlist that opens in the app (API §7.2): `melogold://share?v=1&url=…&id=…`. */
    fun melogoldShare(serverUrl: String, shareId: String) =
        "melogold://share?v=1&url=${URLEncoder.encode(serverUrl, Charsets.UTF_8)}&id=$shareId"
}

/** A playlist snapshot on a Melogold server: the base URL of the server (API §7.1) and the id of the snapshot. */
data class ShareRef(val serverUrl: String, val shareId: String)

/**
 * Reads what points at a shared playlist (API §7.2): the link of the app `melogold://share?v=1&url=<base>&id=<id>` and
 * the link of the page `https://<server>/s/<id>`, wherever they are in a text. The server is checked by the address
 * rules every client shares ([ServerAddressPolicy]); a link is only a pointer, nothing is fetched here.
 */
object ShareLinkParser {
    private val LINK = Regex("""(?i)(?:melogold|https?)://\S+""")
    private val SHARE_ID = Regex("^[0-9A-Za-z]{10}$")
    private const val TRAILING = ".,;:!?)]}>»\"'…"

    fun parse(text: String): ShareRef? {
        val link = LINK.find(text)?.value?.trimEnd { it in TRAILING } ?: return null
        val uri = try {
            URI(link)
        } catch (_: URISyntaxException) {
            return null
        }
        return when (uri.scheme?.lowercase()) {
            "melogold" -> fromDeepLink(uri)
            "http", "https" -> fromPage(uri)
            else -> null
        }
    }

    private fun fromDeepLink(uri: URI): ShareRef? {
        if (uri.host?.lowercase() != "share") return null
        val query = queryOf(uri.rawQuery)
        if (query["v"] != "1") return null
        val server = (ServerAddressPolicy.normalize(query["url"].orEmpty()) as? ServerAddress.Valid)?.url ?: return null
        val id = query["id"]?.takeIf { SHARE_ID.matches(it) } ?: return null
        return ShareRef(server, id)
    }

    private fun fromPage(uri: URI): ShareRef? {
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[segments.lastIndex - 1] != "s") return null
        val id = segments.last().takeIf { SHARE_ID.matches(it) } ?: return null
        val prefix = segments.dropLast(2).joinToString("/") { it }
        val base = "${uri.scheme}://${uri.rawAuthority}" + if (prefix.isEmpty()) "" else "/$prefix"
        val server = (ServerAddressPolicy.normalize(base) as? ServerAddress.Valid)?.url ?: return null
        return ShareRef(server, id)
    }

    private fun queryOf(raw: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val key = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            out.putIfAbsent(decode(key), decode(value))
        }
        return out
    }

    private fun decode(value: String) = try {
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
