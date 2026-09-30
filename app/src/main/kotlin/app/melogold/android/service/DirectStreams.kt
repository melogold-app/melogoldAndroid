package app.melogold.android.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.PlayerResponse.StreamingData.AdaptiveFormat
import app.melogold.providers.innertube.requests.StreamClient
import app.melogold.providers.innertube.requests.StreamVisitor
import app.melogold.providers.innertube.requests.streamPlayer
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DirectStreams"

/**
 * The stream clients in use: the saved `stream-clients.json`, then the fresh one from `main` of this repository, so a
 * break on YouTube's side is fixed by one edit of the file, without a release. [StreamClient.builtIn] stays the
 * fallback; a file that does not parse is not applied.
 */
object StreamClients {
    const val URL = "https://raw.githubusercontent.com/melogold-app/melogoldAndroid/main/config/stream-clients.json"
    private const val FILE = "stream-clients.json"

    @Volatile
    var current: List<StreamClient> = StreamClient.builtIn
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val file = context.filesDir.resolve(FILE)
        scope.launch {
            runCatching { file.readText() }.getOrNull()?.let(StreamClient::parse)?.let { current = it }
            refresh(file)
        }
    }

    private suspend fun refresh(file: File) {
        val text = try {
            Innertube.baseClient.get(URL).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.i(TAG, "The stream clients were not refreshed: ${e.message}")
            return
        }
        val clients = StreamClient.parse(text)
        if (clients == null) {
            Log.w(TAG, "The stream clients from GitHub do not parse; keeping ${current.joinToString { it.name }}")
            return
        }
        runCatching { file.writeText(text) }
        current = clients
        Log.i(TAG, "Stream clients: ${clients.joinToString { it.name }}")
    }
}

/**
 * A resolved stream and what the player keeps of it ([app.melogold.core.data.models.Format]).
 *
 * @property userAgent the User-Agent googlevideo wants for [uri] (ANDROID_VR); `null` — the player's own
 */
data class ResolvedStream(
    val uri: Uri,
    val contentLength: Long?,
    val itag: Int?,
    val mimeType: String?,
    val bitrate: Long?,
    val loudnessDb: Float?,
    val lastModified: Long?,
    val durationMs: Long?,
    val userAgent: String?,
    val source: String
)

/**
 * YouTube closed the address this device goes out from (its bot check): remembered for 10 minutes, so background work
 * (downloads) does not ask YouTube again and deepen the block. A user's action still tries one request; a stream that
 * comes clears it.
 */
object BlockedAddress {
    private const val REMEMBER_MS = 10 * 60_000L

    @Volatile
    private var until = 0L

    val isBlocked: Boolean get() = System.currentTimeMillis() < until

    fun mark() {
        until = System.currentTimeMillis() + REMEMBER_MS
    }

    fun clear() {
        until = 0L
    }
}

/** Opus 160k, AAC 128k, then the smaller ones; the rest by bitrate. Only audio with a direct address. */
private val PREFERRED_ITAGS = listOf(251, 140, 250, 249, 139)

internal fun pickAudio(formats: List<AdaptiveFormat>): AdaptiveFormat? {
    val direct = formats.filter { it.url != null && it.mimeType.startsWith("audio/") }
    return PREFERRED_ITAGS.firstNotNullOfOrNull { itag -> direct.firstOrNull { it.itag == itag } }
        ?: direct.maxByOrNull { it.bitrate ?: 0L }
}

/**
 * The stream of [videoId] from [clients] in order: one request when the first answers. What the clients said on the
 * way goes to [reasons], for yt-dlp's turn and the diagnosis.
 *
 * - YouTube's bot check (or 429): the next client is still asked once (a bot check can hit one client and not another:
 *   a German VPN exit on 2026-09-30 blocked VISIONOS and ANDROID_VR but not IOS); when every client got it,
 *   [BotCheckException] — no yt-dlp, no diagnosis, the address is remembered as closed ([BlockedAddress]);
 * - no client answered at all — the network error of the last one;
 * - otherwise `null`: yt-dlp tries next.
 *
 * [background] (downloads): while the address is remembered as closed ([BlockedAddress]), no request at all.
 */
suspend fun resolveDirect(
    videoId: String,
    clients: List<StreamClient>,
    reasons: MutableList<String>,
    background: Boolean = false
): ResolvedStream? {
    if (background && BlockedAddress.isBlocked) throw BotCheckException(IOException("the address is closed, not asking"))
    var networkError: IOException? = null
    var onlyNetworkErrors = true
    var botCheck: BotCheckException? = null
    for (client in clients) {
        val result = Innertube.streamPlayer(videoId, client) ?: throw CancellationException()
        val response = result.getOrElse { error ->
            if (error is ResponseException && error.response.status.value == HTTP_TOO_MANY_REQUESTS) {
                botCheck = BotCheckException(error)
                reasons += "${client.name}: HTTP 429"
                onlyNetworkErrors = false
                return@getOrElse null
            }
            if (error is IOException) networkError = error else onlyNetworkErrors = false
            reasons += "${client.name}: ${error.message}"
            null
        } ?: continue
        onlyNetworkErrors = false

        val status = response.playabilityStatus?.status
        val reason = response.playabilityStatus?.reason
        if (status != "OK") {
            if (isBotCheck(status, reason)) {
                botCheck = BotCheckException(IOException("${client.name}: $status $reason"))
                reasons += "${client.name}: $status ${reason.orEmpty()}".trim()
                continue
            }
            reasons += "${client.name}: $status ${reason.orEmpty()}".trim()
            continue
        }
        val returned = response.videoDetails?.videoId
        if (returned != null && returned != videoId) {
            reasons += "${client.name}: answered for $returned"
            continue
        }
        val format = pickAudio(response.streamingData?.adaptiveFormats.orEmpty())
        val uri = format?.url?.let { runCatching { it.toUri() }.getOrNull() }
        if (format == null || uri == null) {
            reasons += "${client.name}: no audio with a direct address"
            continue
        }
        BlockedAddress.clear()
        return ResolvedStream(
            uri = uri,
            contentLength = format.contentLength,
            itag = format.itag,
            mimeType = format.mimeType,
            bitrate = format.bitrate,
            loudnessDb = response.playerConfig?.audioConfig?.normalizedLoudnessDb
                ?: format.loudnessDb?.plus(LOUDNESS_OFFSET)?.toFloat(),
            lastModified = format.lastModified,
            durationMs = format.approxDurationMs,
            userAgent = client.mediaUserAgent,
            source = client.name
        )
    }
    botCheck?.let {
        BlockedAddress.mark()
        // The kept visitorData may be the flagged one: the next try (a user's action) starts with a fresh one
        StreamVisitor.forget()
        throw it
    }
    if (onlyNetworkErrors) networkError?.let { throw it }
    return null
}

private const val HTTP_TOO_MANY_REQUESTS = 429

/** `normalizedLoudnessDb` of the player answer: YouTube's `loudnessDb` + 7. */
private const val LOUDNESS_OFFSET = 7.0
