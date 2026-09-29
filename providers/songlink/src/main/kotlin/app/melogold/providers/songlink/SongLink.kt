package app.melogold.providers.songlink

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What song.link (Odesli) knows of a link of another service (tasks/0017): where the same track or album is on YouTube
 * Music, else on YouTube, and how it is called there.
 *
 * @param youtubeUrl the link on YouTube Music, else on YouTube; null when song.link has neither
 * @param title the name of the track or album, [artist] its artist, when the answer says
 */
data class SongLinkAnswer(val youtubeUrl: String?, val title: String?, val artist: String?, val isAlbum: Boolean) {
    /** The words to look for it by when there is no link to it: "Title Artist". */
    val searchText: String? get() = listOfNotNull(title, artist).joinToString(" ").trim().takeIf { title != null && it.isNotEmpty() }
}

/** Why song.link gave nothing. */
enum class SongLinkFailure {
    /** It does not know this link (400, 404). */
    NotFound,

    /** No network, an error of its own, or too many requests (429): try later. */
    Unavailable,

    /**
     * It asks for a key: the free access without one is over (`401 PUBLIC_API_ACCESS_DEPRECATED`, seen 2026-09).
     * Without a key nothing is asked again.
     */
    KeyRequired
}

/** An answer of song.link or the reason there is none. */
sealed interface SongLinkResult {
    data class Found(val answer: SongLinkAnswer) : SongLinkResult

    data class Failed(val reason: SongLinkFailure) : SongLinkResult
}

/** Somewhere to keep answers for a day, so that the same link is not asked twice (tasks/0017: about ten a minute). */
interface SongLinkCache {
    fun get(url: String): String?

    fun put(url: String, body: String)
}

/**
 * The client of `https://api.song.link/v1-alpha.1/links`. Requests go one at a time with a pause between them (the
 * service allows about ten a minute for one address), answers are kept for a day in [cache].
 *
 * The service used to answer anyone; today it asks for a key (`key=`), so without an [apiKey] no request is made and
 * [resolve] says [SongLinkFailure.KeyRequired] at once: the caller looks for the track by its name instead.
 */
class SongLinkClient(
    private val apiKey: String? = null,
    private val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT_MS
            requestTimeoutMillis = TIMEOUT_MS
        }
    },
    private val cache: SongLinkCache? = null,
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val pauseMs: Long = PAUSE_MS
) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    @Volatile
    private var keyRequired = false

    suspend fun resolve(link: String): SongLinkResult {
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key == null || keyRequired) return SongLinkResult.Failed(SongLinkFailure.KeyRequired)

        cache?.get(link)?.let { body -> parseSongLink(body)?.let { return SongLinkResult.Found(it) } }

        return mutex.withLock {
            val wait = lastRequestAt + pauseMs - now()
            if (wait > 0) delay(wait)
            lastRequestAt = now()

            val response = try {
                http.get("$baseUrl/links") {
                    parameter("url", link)
                    parameter("key", key)
                    parameter("songIfSingle", "true")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                return@withLock SongLinkResult.Failed(SongLinkFailure.Unavailable)
            }
            answer(link, response)
        }
    }

    private suspend fun answer(link: String, response: HttpResponse): SongLinkResult {
        val status = response.status.value
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return when {
            response.status.isSuccess() -> {
                val answer = parseSongLink(body) ?: return SongLinkResult.Failed(SongLinkFailure.NotFound)
                cache?.put(link, body)
                SongLinkResult.Found(answer)
            }

            status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> {
                keyRequired = true
                SongLinkResult.Failed(SongLinkFailure.KeyRequired)
            }

            status == HTTP_BAD_REQUEST || status == HTTP_NOT_FOUND -> SongLinkResult.Failed(SongLinkFailure.NotFound)
            else -> SongLinkResult.Failed(SongLinkFailure.Unavailable)
        }
    }

    companion object {
        const val BASE_URL = "https://api.song.link/v1-alpha.1"
        private const val TIMEOUT_MS = 12_000L
        private const val PAUSE_MS = 1_500L
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
    }
}

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Reads the answer of `GET /links`: `linksByPlatform.youtubeMusic.url`, else `youtube`, and the name and artist of the
 * entity the link is about (`entitiesByUniqueId[entityUniqueId]`) for a search when there is no such link. Null for a body
 * that is not an answer.
 */
fun parseSongLink(body: String): SongLinkAnswer? {
    val root = runCatching { lenient.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val links = root["linksByPlatform"] as? JsonObject
    val url = listOf("youtubeMusic", "youtube").firstNotNullOfOrNull { platform ->
        (links?.get(platform) as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
    }

    val entities = root["entitiesByUniqueId"] as? JsonObject
    val entity = root["entityUniqueId"]?.jsonPrimitive?.contentOrNull?.let { entities?.get(it) } as? JsonObject
        ?: entities?.values?.firstOrNull { (it as? JsonObject)?.get("title") != null } as? JsonObject
    val title = entity?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val artist = entity?.get("artistName")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val type = entity?.get("type")?.jsonPrimitive?.contentOrNull
    val album = type == "album" || root["entityUniqueId"]?.jsonPrimitive?.contentOrNull?.contains("_ALBUM::") == true

    if (url == null && title == null) return null
    return SongLinkAnswer(youtubeUrl = url, title = title, artist = artist, isAlbum = album)
}
