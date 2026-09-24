package app.melogold.providers.innertube.youtube

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.WebSearchBody
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val WEB_SEARCH = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"

/**
 * The segments of a plain YouTube search (REWRITE §3.1.3, §4.8.1).
 */
enum class YouTubeSearchFilter(val params: String) {
    Videos("EgIQAQ%3D%3D"),
    Channels("EgIQAg%3D%3D"),
    Live("EgJAAQ%3D%3D"),
    Playlists("EgIQAw%3D%3D")
}

/**
 * One result of a plain YouTube search. Texts come localized from YouTube and are only shown,
 * never parsed into numbers (REWRITE §4.8.2).
 */
sealed interface YouTubeItem {
    val key: String

    data class Video(
        val videoId: String,
        val title: String,
        val channelName: String?,
        val channelId: String?,
        val durationText: String?,
        val viewsText: String?,
        val publishedText: String?,
        val thumbnailUrl: String?,
        val liveLabel: String?
    ) : YouTubeItem {
        override val key get() = videoId
        val isLive get() = liveLabel != null
    }

    data class Channel(
        val channelId: String,
        val name: String,
        val thumbnailUrl: String?,
        val subtitle: String?
    ) : YouTubeItem {
        override val key get() = channelId
    }

    data class Playlist(
        val playlistId: String,
        val title: String,
        val channelName: String?,
        val videoCountText: String?,
        val thumbnailUrl: String?
    ) : YouTubeItem {
        override val key get() = playlistId
    }
}

data class YouTubeSearchPage(
    val items: List<YouTubeItem>,
    val continuation: String?
)

/**
 * Searches plain YouTube: re-uploads, covers, concerts — what the music catalog doesn't have.
 */
suspend fun Innertube.youTubeSearch(query: String, filter: YouTubeSearchFilter?) =
    webSearch(WebSearchBody(query = query, params = filter?.params))

/**
 * The next page of a plain YouTube search.
 */
suspend fun Innertube.youTubeSearchContinuation(continuation: String) =
    webSearch(WebSearchBody(continuation = continuation))

private suspend fun Innertube.webSearch(body: WebSearchBody) = runCatchingCancellable {
    val response = baseClient.post(WEB_SEARCH) {
        body.context.apply()
        contentType(ContentType.Application.Json)
        setBody(body)
    }.body<JsonObject>()

    parseYouTubeSearch(response)
}

/**
 * Reads a WEB `search` response, first page or continuation. Unknown renderers (shelves, shorts,
 * ads) are skipped.
 */
fun parseYouTubeSearch(response: JsonObject): YouTubeSearchPage {
    val sections = response.obj("contents")
        ?.obj("twoColumnSearchResultsRenderer")
        ?.obj("primaryContents")
        ?.obj("sectionListRenderer")
        ?.arr("contents")
        ?: response.arr("onResponseReceivedCommands")
            ?.flatMap { command ->
                (command.obj("appendContinuationItemsAction") ?: command.obj("reloadContinuationItemsCommand"))
                    ?.arr("continuationItems")
                    .orEmpty()
            }
        ?: emptyList()

    val items = sections
        .flatMap { section -> section.obj("itemSectionRenderer")?.arr("contents").orEmpty() }
        .mapNotNull(::parseItem)
        .distinctBy { it.key }

    val continuation = sections.firstNotNullOfOrNull { section ->
        section.obj("continuationItemRenderer")
            ?.obj("continuationEndpoint")
            ?.obj("continuationCommand")
            ?.str("token")
    }

    return YouTubeSearchPage(items = items, continuation = continuation)
}

private fun parseItem(item: JsonElement): YouTubeItem? {
    item.obj("videoRenderer")?.let { return parseVideo(it) }
    item.obj("channelRenderer")?.let { return parseChannel(it) }
    item.obj("lockupViewModel")?.let { return parsePlaylist(it) }
    return null
}

private fun parseVideo(video: JsonObject): YouTubeItem.Video? {
    val videoId = video.str("videoId") ?: return null
    val byline = (video.obj("longBylineText") ?: video.obj("ownerText"))?.arr("runs")?.firstOrNull()
    val liveLabel = video.arr("badges")
        ?.mapNotNull { it.obj("metadataBadgeRenderer") }
        ?.firstOrNull { it.str("style") == "BADGE_STYLE_TYPE_LIVE_NOW" }
        ?.let { it.str("label") ?: "LIVE" }

    return YouTubeItem.Video(
        videoId = videoId,
        title = video.text("title") ?: return null,
        channelName = byline?.str("text"),
        channelId = byline?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId"),
        durationText = video.text("lengthText"),
        viewsText = if (liveLabel != null) video.text("viewCountText")
        else video.text("shortViewCountText") ?: video.text("viewCountText"),
        publishedText = video.text("publishedTimeText"),
        thumbnailUrl = video.obj("thumbnail")?.arr("thumbnails")?.lastOrNull()?.str("url")?.absoluteUrl(),
        liveLabel = liveLabel
    )
}

private fun parseChannel(channel: JsonObject): YouTubeItem.Channel? {
    val channelId = channel.str("channelId") ?: return null
    // YouTube puts the @handle in subscriberCountText and the subscribers in videoCountText
    val texts = listOfNotNull(channel.text("videoCountText"), channel.text("subscriberCountText"))

    return YouTubeItem.Channel(
        channelId = channelId,
        name = channel.text("title") ?: return null,
        thumbnailUrl = channel.obj("thumbnail")?.arr("thumbnails")?.lastOrNull()?.str("url")?.absoluteUrl(),
        subtitle = texts.firstOrNull { text -> !text.startsWith("@") && text.any(Char::isDigit) }
            ?: texts.firstOrNull()
    )
}

private val playlistLockups = setOf(
    "LOCKUP_CONTENT_TYPE_PLAYLIST",
    "LOCKUP_CONTENT_TYPE_ALBUM",
    "LOCKUP_CONTENT_TYPE_PODCAST"
)

private fun parsePlaylist(lockup: JsonObject): YouTubeItem.Playlist? {
    if (lockup.str("contentType") !in playlistLockups) return null
    val playlistId = lockup.str("contentId") ?: return null
    val metadata = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
    val thumbnail = lockup.obj("contentImage")
        ?.obj("collectionThumbnailViewModel")
        ?.obj("primaryThumbnail")
        ?.obj("thumbnailViewModel")

    return YouTubeItem.Playlist(
        playlistId = playlistId,
        title = metadata?.obj("title")?.str("content") ?: return null,
        channelName = metadata.obj("metadata")
            ?.obj("contentMetadataViewModel")
            ?.arr("metadataRows")
            ?.firstOrNull()
            ?.arr("metadataParts")
            ?.firstOrNull()
            ?.obj("text")
            ?.str("content"),
        videoCountText = thumbnail?.arr("overlays")
            ?.firstNotNullOfOrNull { overlay ->
                overlay.obj("thumbnailOverlayBadgeViewModel")
                    ?.arr("thumbnailBadges")
                    ?.firstNotNullOfOrNull { it.obj("thumbnailBadgeViewModel")?.str("text") }
            },
        thumbnailUrl = thumbnail?.obj("image")?.arr("sources")?.lastOrNull()?.str("url")?.absoluteUrl()
    )
}

private fun String.absoluteUrl() = if (startsWith("//")) "https:$this" else this

private fun JsonElement?.obj(key: String) = (this as? JsonObject)?.get(key) as? JsonObject
private fun JsonElement?.arr(key: String) = (this as? JsonObject)?.get(key) as? JsonArray
private fun JsonElement?.str(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

/**
 * A YouTube text: `simpleText`, or the `runs` joined.
 */
private fun JsonObject.text(key: String): String? {
    val text = obj(key) ?: return null
    return (text.str("simpleText") ?: text.arr("runs")?.joinToString("") { it.str("text").orEmpty() })
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
