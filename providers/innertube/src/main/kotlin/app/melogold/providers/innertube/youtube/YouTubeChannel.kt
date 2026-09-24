package app.melogold.providers.innertube.youtube

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.WebBrowseBody
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val WEB_BROWSE = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

/** The "Videos" tab of a channel, newest first. */
private const val VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"

/**
 * A plain YouTube channel (REWRITE §3.7.2, §4.8.1): its header and the first page of its videos.
 * [playlistsParams] opens the "Playlists" tab, if the channel has one.
 */
data class YouTubeChannelPage(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val subscribersText: String?,
    val videosText: String?,
    val description: String?,
    val videos: YouTubeSearchPage,
    val playlistsParams: String?
)

/** A channel and the first page of its videos. */
suspend fun Innertube.youTubeChannel(channelId: String) = runCatchingCancellable {
    parseYouTubeChannel(channelId, webBrowse(WebBrowseBody(browseId = channelId, params = VIDEOS_TAB)))
}

/** The next page of a channel's videos. */
suspend fun Innertube.youTubeChannelVideos(continuation: String) = runCatchingCancellable {
    parseYouTubeChannelContinuation(webBrowse(WebBrowseBody(continuation = continuation)))
}

/** The playlists of a channel (its "Playlists" tab). */
suspend fun Innertube.youTubeChannelPlaylists(channelId: String, params: String) = runCatchingCancellable {
    selectedTab(webBrowse(WebBrowseBody(browseId = channelId, params = params)))
        ?.obj("content")
        ?.obj("sectionListRenderer")
        ?.arr("contents")
        .orEmpty()
        .flatMap { section -> section.obj("itemSectionRenderer")?.arr("contents").orEmpty() }
        .flatMap { content -> content.obj("gridRenderer")?.arr("items").orEmpty() }
        .mapNotNull { item -> item.obj("lockupViewModel")?.let(::parsePlaylist) }
        .distinctBy { it.key }
}

private suspend fun Innertube.webBrowse(body: WebBrowseBody) = baseClient.post(WEB_BROWSE) {
    body.context.apply()
    contentType(ContentType.Application.Json)
    setBody(body)
}.body<JsonObject>()

/** Reads the header and the videos of a channel's "Videos" tab. */
fun parseYouTubeChannel(channelId: String, response: JsonObject): YouTubeChannelPage {
    val pageHeader = response.obj("header")?.obj("pageHeaderRenderer")
    val header = pageHeader?.obj("content")?.obj("pageHeaderViewModel")
    // The older header some channels still get
    val c4Header = response.obj("header")?.obj("c4TabbedHeaderRenderer")

    val metadataParts = header?.obj("metadata")
        ?.obj("contentMetadataViewModel")
        ?.arr("metadataRows")
        .orEmpty()
        .flatMap { row -> row.arr("metadataParts").orEmpty() }
        .mapNotNull { part -> part.obj("text")?.str("content") }
        .filterNot { it.startsWith("@") }

    val name = pageHeader?.str("pageTitle")
        ?: header?.obj("title")?.obj("dynamicTextViewModel")?.obj("text")?.str("content")
        ?: c4Header?.str("title")
        ?: error("A channel page without a name")

    val tabs = response.obj("contents")?.obj("twoColumnBrowseResultsRenderer")?.arr("tabs").orEmpty()
    val playlistsTab = tabs
        .mapNotNull { it.obj("tabRenderer") }
        .firstOrNull { tab ->
            tab.obj("endpoint")
                ?.obj("commandMetadata")
                ?.obj("webCommandMetadata")
                ?.str("url")
                ?.endsWith("/playlists") == true
        }

    val videos = parseGrid(
        selectedTab(response)?.obj("content")?.obj("richGridRenderer")?.arr("contents").orEmpty()
    )

    return YouTubeChannelPage(
        channelId = channelId,
        name = name,
        avatarUrl = (
            header?.obj("image")
                ?.obj("decoratedAvatarViewModel")
                ?.obj("avatar")
                ?.obj("avatarViewModel")
                ?.obj("image")
                ?.arr("sources")
                ?: c4Header?.obj("avatar")?.arr("thumbnails")
            )
            ?.lastOrNull()
            ?.str("url")
            ?.absoluteUrl(),
        subscribersText = metadataParts.getOrNull(0) ?: c4Header?.text("subscriberCountText"),
        videosText = metadataParts.getOrNull(1) ?: c4Header?.text("videosCountText"),
        description = header?.obj("description")
            ?.obj("descriptionPreviewViewModel")
            ?.obj("description")
            ?.str("content"),
        videos = videos.copy(
            items = videos.items.map { item ->
                if (item is YouTubeItem.Video) item.copy(channelName = item.channelName ?: name, channelId = channelId)
                else item
            }
        ),
        playlistsParams = playlistsTab?.obj("endpoint")?.obj("browseEndpoint")?.str("params")
    )
}

/** Reads a continuation of a channel's videos. */
fun parseYouTubeChannelContinuation(response: JsonObject) = parseGrid(
    response.arr("onResponseReceivedActions")
        ?.flatMap { action ->
            (action.obj("appendContinuationItemsAction") ?: action.obj("reloadContinuationItemsCommand"))
                ?.arr("continuationItems")
                .orEmpty()
        }
        .orEmpty()
)

private fun selectedTab(response: JsonObject) = response.obj("contents")
    ?.obj("twoColumnBrowseResultsRenderer")
    ?.arr("tabs")
    ?.mapNotNull { it.obj("tabRenderer") }
    ?.firstOrNull { (it["selected"] as? JsonPrimitive)?.content == "true" }

/** The videos of a rich grid (first page or continuation) and the token of the next page. */
private fun parseGrid(items: List<JsonElement>) = YouTubeSearchPage(
    items = items
        .mapNotNull { item ->
            val content = item.obj("richItemRenderer")?.obj("content")
            content?.obj("lockupViewModel")?.let(::parseVideoLockup)
                ?: content?.obj("videoRenderer")?.let(::parseVideo)
        }
        .distinctBy { it.key },
    continuation = items.firstNotNullOfOrNull { item ->
        item.obj("continuationItemRenderer")
            ?.obj("continuationEndpoint")
            ?.obj("continuationCommand")
            ?.str("token")
    }
)

/** A video in the newer "lockup" form; members-only videos, which can't be played, are skipped. */
internal fun parseVideoLockup(lockup: JsonObject): YouTubeItem.Video? {
    if (lockup.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
    val videoId = lockup.str("contentId") ?: return null
    val metadata = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
    val rows = metadata?.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows").orEmpty()

    val membersOnly = rows.any { row ->
        row.arr("badges").orEmpty().any { it.obj("badgeViewModel")?.str("badgeStyle") == "BADGE_MEMBERS_ONLY" }
    }
    if (membersOnly) return null

    val parts = rows
        .flatMap { row -> row.arr("metadataParts").orEmpty() }
        .mapNotNull { part -> part.obj("text")?.str("content") }
    val thumbnail = lockup.obj("contentImage")?.obj("thumbnailViewModel")
    val badges = thumbnail?.arr("overlays").orEmpty()
        .flatMap { overlay ->
            overlay.obj("thumbnailBottomOverlayViewModel")?.arr("badges").orEmpty() +
                overlay.obj("thumbnailOverlayBadgeViewModel")?.arr("thumbnailBadges").orEmpty()
        }
        .mapNotNull { it.obj("thumbnailBadgeViewModel") }
    val liveLabel = badges.firstOrNull { it.str("badgeStyle")?.contains("LIVE") == true }?.str("text")

    return YouTubeItem.Video(
        videoId = videoId,
        title = metadata?.obj("title")?.str("content") ?: return null,
        channelName = null,
        channelId = null,
        durationText = badges.firstNotNullOfOrNull { badge -> badge.str("text")?.takeIf { ':' in it } },
        viewsText = parts.getOrNull(0),
        publishedText = parts.getOrNull(1),
        thumbnailUrl = thumbnail?.obj("image")?.arr("sources")?.lastOrNull()?.str("url")?.absoluteUrl(),
        liveLabel = liveLabel
    )
}
