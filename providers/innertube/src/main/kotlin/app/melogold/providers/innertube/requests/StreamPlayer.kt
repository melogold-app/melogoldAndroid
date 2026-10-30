package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.json
import app.melogold.providers.innertube.models.PlayerResponse
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.userAgent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A client of InnerTube `player` that answers with direct stream addresses, nothing to decipher: one request per
 * track instead of the four or five of yt-dlp (page, data, player, m3u8). The same list and file format as the Apple
 * client (`melogoldiOSmacOS/Config/stream-clients.json`), so a fix on YouTube's side is one edit of that file.
 *
 * @property mediaUserAgent the User-Agent googlevideo wants for this client's addresses (ANDROID_VR); `null` — any
 */
@Serializable
data class StreamClient(
    val name: String,
    val id: Int,
    val version: String,
    val host: String,
    val userAgent: String,
    val referer: String? = null,
    val platform: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val mediaUserAgent: String? = null
) {
    val isValid: Boolean
        get() = name.isNotBlank() && id > 0 && version.isNotBlank() && host.isNotBlank() && userAgent.isNotBlank()

    companion object {
        const val SCHEMA = 1

        val VisionOS = StreamClient(
            name = "VISIONOS",
            id = 101,
            version = "1.02",
            host = "www.youtube.com",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/26.0 Safari/605.1.15",
            referer = "https://www.youtube.com/",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471"
        )

        /**
         * VISIONOS only. ANDROID_VR, the second until 30.10.2026, got from googlevideo only the start of a file without a
         * PO token (403 on the second range: the song broke off at ~30 s); IOS and ANDROID alike, the rest want a
         * sign-in. Without a second client a bot check of VISIONOS is said as it is, and other failures go to yt-dlp.
         */
        val builtIn = listOf(VisionOS)

        @Serializable
        private data class Config(val schema: Int? = null, val clients: List<StreamClient>? = null)

        /** The clients of `stream-clients.json`; `null` — another schema, no clients or a client without its fields. */
        fun parse(text: String): List<StreamClient>? = runCatching {
            val config = json.decodeFromString<Config>(text)
            config.clients?.takeIf { config.schema == SCHEMA && it.isNotEmpty() && it.all(StreamClient::isValid) }
        }.getOrNull()
    }
}

/**
 * The `visitorData` of this install, sent with every stream request: YouTube answers a request without one, or with
 * the one every copy of the app shares, with its bot check sooner. Kept by the app between launches
 * ([onVisitorData]); the first comes from the lightest request of YouTube Music.
 */
object StreamVisitor {
    @Volatile
    var visitorData: String? = null

    /** Called with a new `visitorData`, for the app to keep it. */
    @Volatile
    var onVisitorData: ((String) -> Unit)? = null

    internal fun remember(value: String?) {
        if (value.isNullOrBlank() || visitorData != null) return
        visitorData = value
        onVisitorData?.invoke(value)
    }

    /** YouTube's bot check: the next request gets a fresh `visitorData` (the kept one may be the flagged one). */
    fun forget() {
        visitorData = null
        onVisitorData?.invoke("")
    }
}

private const val WEB_REMIX_VERSION = "1.20250922.03.00"
private const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/** A `visitorData` of this install's own, if there is none yet: `music/get_search_suggestions` with an empty input. */
private suspend fun Innertube.ensureStreamVisitor() {
    if (StreamVisitor.visitorData != null) return
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", WEB_REMIX_VERSION)
                put("hl", "en")
                put("gl", "US")
            }
        }
        put("input", "")
    }
    runCatchingCancellable {
        baseClient.post("https://music.youtube.com/youtubei/v1/music/get_search_suggestions") {
            parameter("prettyPrint", "false")
            contentType(ContentType.Application.Json)
            userAgent(DESKTOP_UA)
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            setBody(body)
        }.body<JsonObject>()
    }?.getOrNull()
        ?.get("responseContext")?.jsonObject?.get("visitorData")?.jsonPrimitive?.content
        ?.let(StreamVisitor::remember)
}

/**
 * One `player` request of [client] for [videoId]. The answer is as YouTube gave it: the caller reads the
 * playability, picks the format and decides what the failure means.
 */
suspend fun Innertube.streamPlayer(videoId: String, client: StreamClient): Result<PlayerResponse>? =
    runCatchingCancellable {
        ensureStreamVisitor()
        val visitor = StreamVisitor.visitorData
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "US")
                    put("timeZone", "UTC")
                    put("utcOffsetMinutes", 0)
                    client.platform?.let { put("platform", it) }
                    client.deviceMake?.let { put("deviceMake", it) }
                    client.deviceModel?.let { put("deviceModel", it) }
                    client.osName?.let { put("osName", it) }
                    client.osVersion?.let { put("osVersion", it) }
                    client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    visitor?.let { put("visitorData", it) }
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        baseClient.post("https://${client.host}/youtubei/v1/player") {
            parameter("prettyPrint", "false")
            contentType(ContentType.Application.Json)
            userAgent(client.userAgent)
            header("X-YouTube-Client-Name", client.id.toString())
            header("X-YouTube-Client-Version", client.version)
            client.referer?.let { header("Referer", it) }
            visitor?.let { header("X-Goog-Visitor-Id", it) }
            setBody(body)
        }.body<PlayerResponse>().also { StreamVisitor.remember(it.responseContext?.visitorData) }
    }
