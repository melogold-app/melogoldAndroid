package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.Context
import app.melogold.providers.innertube.models.BrowseResponse
import app.melogold.providers.innertube.models.NextResponse
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The browse id of the "Lyrics" tab of the watch page of [body]'s video; null when it has none. */
private suspend fun Innertube.lyricsBrowseId(body: NextBody): String? = client.post(NEXT) {
    setBody(body)
    @Suppress("all")
    mask(
        "contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.tabRenderer(endpoint,title)"
    )
}.body<NextResponse>()
    .contents
    ?.singleColumnMusicWatchNextResultsRenderer
    ?.tabbedRenderer
    ?.watchNextTabbedResultsRenderer
    ?.tabs
    ?.getOrNull(1)
    ?.tabRenderer
    ?.endpoint
    ?.browseEndpoint
    ?.browseId

/** The plain lyrics YouTube Music shows for [body]'s video. */
suspend fun Innertube.lyrics(body: NextBody) = runCatchingCancellable {
    val browseId = lyricsBrowseId(body) ?: return@runCatchingCancellable null

    val response = client.post(BROWSE) {
        setBody(BrowseBody(browseId = browseId))
        mask("contents.sectionListRenderer.contents.musicDescriptionShelfRenderer.description")
    }.body<BrowseResponse>()

    response.contents
        ?.sectionListRenderer
        ?.contents
        ?.firstOrNull()
        ?.musicDescriptionShelfRenderer
        ?.description
        ?.text
}

/**
 * The time-synced lyrics YouTube Music shows for [body]'s video, as LRC (`[mm:ss.xx]line`); null
 * when it has none. The same "Lyrics" tab, but only the Android client gets its lines with times
 * (`timedLyricsModel`), so they are exactly the lines of this track, no search by title.
 */
suspend fun Innertube.timedLyrics(body: NextBody) = runCatchingCancellable {
    val browseId = lyricsBrowseId(body) ?: return@runCatchingCancellable null
    val context = Context.DefaultAndroidMusic

    val response = client.post(BROWSE) {
        setBody(BrowseBody(context = context, browseId = browseId))
        context.apply()
    }.body<JsonObject>()

    val lines = listOf("contents", "elementRenderer", "newElement", "type", "componentType", "model", "timedLyricsModel", "lyricsData")
        .fold<String, JsonElement?>(response) { element, key -> (element as? JsonObject)?.get(key) }
        ?.let { it as? JsonObject }
        ?.get("timedLyricsData")
        ?.let { it as? JsonArray }
        ?.mapNotNull { element ->
            val line = element as? JsonObject ?: return@mapNotNull null
            val start = (line["cueRange"] as? JsonObject)?.get("startTimeMilliseconds")
                ?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            start to line["lyricLine"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        ?.takeIf { it.isNotEmpty() }
        ?: return@runCatchingCancellable null

    lines.joinToString("\n") { (start, text) ->
        val centis = start / 10
        "[%02d:%02d.%02d]%s".format(centis / 6000, centis / 100 % 60, centis % 100, text)
    }
}
