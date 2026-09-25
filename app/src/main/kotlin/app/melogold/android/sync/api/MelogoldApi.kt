package app.melogold.android.sync.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The protocol of `POST /sync` this client speaks (API §4.8, `X-Sync-Protocol`). */
const val SYNC_PROTOCOL = 1

/** The Melogold API versions this client understands (API §1.1). */
const val API_VERSION = 1

/** An error of the Melogold server: its HTTP status and the `code` of its envelope (API §2), or a transport failure. */
class ApiException(val status: Int, val code: String?, message: String?, cause: Throwable? = null) :
    Exception(message ?: code ?: "HTTP $status", cause) {
    /** The request never got an answer: no network, DNS, TLS, timeout. */
    val isNetwork get() = status == 0
}

@Serializable
private data class ErrorEnvelope(val statusCode: Int = 0, val error: String? = null, val message: String? = null, val code: String? = null)

// region Server (API §4.2)
@Serializable
data class ServerInfo(
    val software: String,
    val version: String,
    val apiVersion: Int,
    val minApiVersion: Int,
    val serverId: String,
    val instanceName: String,
    val publicUrl: String? = null,
    val secureTransport: Boolean = false,
    val registration: String,
    val features: ServerFeatures = ServerFeatures()
)

@Serializable
data class ServerFeatures(
    val sync: SyncFeature? = null,
    val playback: FeatureVersion? = null,
    val recoveryCode: FeatureVersion? = null,
    val registrationPow: FeatureVersion? = null,
    val lyrics: FeatureVersion? = null
)

@Serializable
data class SyncFeature(val protocol: Int, val minProtocol: Int, val kinds: List<String>, val streams: List<String>)

@Serializable
data class FeatureVersion(val version: Int)
// endregion

// region Accounts and devices (API §4.3, §4.4)
@Serializable
data class RegisterChallenge(val challenge: String, val bits: Int, val expiresAt: String)

@Serializable
data class DeviceInput(
    val hwid: String,
    val name: String,
    val platform: String,
    val osVersion: String? = null,
    val model: String? = null,
    val clientVersion: String? = null
)

@Serializable
data class PowSolution(val challenge: String, val nonce: String)

@Serializable
data class RegisterRequest(val login: String, val password: String, val device: DeviceInput, val pow: PowSolution? = null)

@Serializable
data class LoginRequest(val login: String, val password: String, val device: DeviceInput)

@Serializable
data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String
)

@Serializable
data class UserDto(val id: String, val login: String, val createdAt: String)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val osVersion: String? = null,
    val model: String? = null,
    val clientVersion: String? = null,
    val createdAt: String,
    val lastSeenAt: String? = null,
    val lastSyncAt: String? = null,
    val recentUntil: String? = null,
    val isCurrent: Boolean = false
)

@Serializable
data class AuthSession(
    val user: UserDto,
    val device: DeviceDto,
    val tokens: TokenPair,
    val serverId: String,
    val serverTime: String,
    val recoveryCode: String? = null
)

@Serializable
data class DevicePatch(val hwid: String, val clientVersion: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String, val device: DevicePatch)

@Serializable
data class RefreshResponse(val tokens: TokenPair, val device: DeviceDto, val serverId: String, val serverTime: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class MeResponse(val user: UserDto, val device: DeviceDto, val serverId: String, val serverTime: String)

@Serializable
data class DeviceListResponse(val devices: List<DeviceDto>, val maxDevices: Int? = null)

@Serializable
data class PasswordRequest(val password: String? = null)

@Serializable
data class RevokeOthersResponse(val revokedCount: Int)
// endregion

// region Linking a device by code (API §4.6, mode `request`)
@Serializable
data class ResolveLinkRequest(val userCode: String)

/** What the new device says about itself. */
@Serializable
data class LinkDeviceInfo(
    val name: String,
    val platform: String,
    val osVersion: String? = null,
    val model: String? = null,
    val clientVersion: String? = null,
    val alreadyLinked: Boolean = false
)

/** A link: at `claimed`, the three numbers to choose from, one of them shown on the new device. */
@Serializable
data class LinkDetails(
    val linkId: String,
    val mode: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val device: LinkDeviceInfo? = null,
    val sameNetwork: Boolean? = null,
    val verifyChoices: List<String> = emptyList()
)

@Serializable
data class ApproveLinkRequest(val verifyCode: String)

@Serializable
data class LinkDecision(val linkId: String, val status: String)
// endregion

// region Sync (API §4.7, §4.8)
@Serializable
data class SyncRequest(
    val cursor: String,
    val ops: List<JsonObject> = emptyList(),
    val streams: List<String>? = null,
    val limit: Int? = null
)

@Serializable
data class OpResult(
    val opId: String,
    val status: String,
    val code: String? = null,
    val playlistId: String? = null,
    val retryAfterSeconds: Int? = null,
    val replayed: Boolean = false
)

@Serializable
data class TrackDto(
    val videoId: String,
    val title: String,
    val artistsText: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    val durationText: String? = null,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false,
    val videoType: String? = null,
    val metadataStub: Boolean = false
)

@Serializable
data class PlaylistRow(
    val id: String,
    val name: String,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
    val createdAt: String,
    val deleted: Boolean
)

@Serializable
data class PlaylistItemRow(val playlistId: String, val videoId: String, val present: Boolean, val sortKey: String, val addedAt: String)

@Serializable
data class LikeRow(val videoId: String, val liked: Boolean, val likedAt: String? = null)

@Serializable
data class BookmarkRow(
    val type: String,
    val browseId: String,
    val bookmarked: Boolean,
    val bookmarkedAt: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val year: String? = null
)

@Serializable
data class SyncResponse(
    val results: List<OpResult> = emptyList(),
    val cursor: String,
    val hasMore: Boolean = false,
    val serverTime: String,
    val tracks: List<TrackDto> = emptyList(),
    val playlists: List<PlaylistRow> = emptyList(),
    val items: List<PlaylistItemRow> = emptyList(),
    val likes: List<LikeRow> = emptyList(),
    val bookmarks: List<BookmarkRow> = emptyList(),
    val plays: List<PlayRow> = emptyList(),
    val playStats: List<PlayStatRow> = emptyList(),
    val playForgets: List<PlayForgetRow> = emptyList()
)

@Serializable
data class PlayRow(val eventId: String, val videoId: String, val playedAt: String, val playTimeMs: Long, val deviceId: String? = null)

@Serializable
data class PlayStatRow(val videoId: String, val totalPlayTimeMs: Long, val lastPlayedAt: String? = null)

@Serializable
data class PlayForgetRow(val videoId: String, val eventsBefore: String, val totalBefore: String? = null)

@Serializable
data class MergePlanInput(val localKey: String, val syncId: String? = null, val name: String, val browseId: String? = null)

@Serializable
data class MergePlanRequest(val playlists: List<MergePlanInput>)

@Serializable
data class MergePlanEntry(val localKey: String, val action: String, val playlistId: String, val serverName: String? = null)

@Serializable
data class MergePlanResponse(val plan: List<MergePlanEntry>)
// endregion

// region Lyrics (API §4.10)
@Serializable
data class LyricsText(
    val plain: String? = null,
    val plainSource: String? = null,
    val synced: String? = null,
    val syncedFormat: String? = null,
    val syncedSource: String? = null,
    val startTimeMs: Long? = null,
    val language: String? = null
)

/** `PUT /lyrics/{videoId}`: absent fields are omitted (`explicitNulls = false`). */
@Serializable
data class LyricsPut(
    val plain: String? = null,
    val plainSource: String? = null,
    val synced: String? = null,
    val syncedFormat: String? = null,
    val syncedSource: String? = null,
    val startTimeMs: Long? = null,
    val language: String? = null
)

@Serializable
data class MyLyrics(
    val id: String,
    val videoId: String,
    val rev: Long,
    val deleted: Boolean,
    val text: LyricsText? = null,
    val updatedAt: String
)

@Serializable
data class SharedLyrics(val id: String, val videoId: String, val text: LyricsText, val updatedAt: String)

@Serializable
data class LyricsResponse(val mine: MyLyrics? = null, val shared: SharedLyrics? = null, val serverTime: String)

@Serializable
data class LyricsChangesRequest(val after: Long, val limit: Int? = null)

@Serializable
data class MyLyricsPage(val items: List<MyLyrics>, val rev: Long, val more: Boolean)
// endregion

/**
 * The calls of the Melogold server API that the app makes, against the server at [baseUrl] (API §1.1). Every call
 * turns an error envelope into an [ApiException] with its code; transport failures are [ApiException] with status 0.
 */
class MelogoldApi(private val baseUrl: String) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /** The client for long reads of the same server (SSE, API §6): no request timeout. */
    val streamClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    val eventsUrl get() = "$baseUrl/auth/me/events"

    suspend fun serverInfo(): ServerInfo = call { client.get("$baseUrl/server/info") }

    suspend fun registerChallenge(): RegisterChallenge = call { client.get("$baseUrl/auth/register/challenge") }

    suspend fun register(request: RegisterRequest): AuthSession = call { client.post("$baseUrl/auth/register") { jsonBody(request) } }

    suspend fun login(request: LoginRequest): AuthSession = call { client.post("$baseUrl/auth/login") { jsonBody(request) } }

    suspend fun refresh(request: RefreshRequest): RefreshResponse = call { client.post("$baseUrl/auth/refresh") { jsonBody(request) } }

    suspend fun logout(refreshToken: String) {
        callUnit { client.post("$baseUrl/auth/logout") { jsonBody(LogoutRequest(refreshToken)) } }
    }

    suspend fun me(token: String): MeResponse = call { client.get("$baseUrl/auth/me") { bearerAuth(token) } }

    suspend fun devices(token: String): DeviceListResponse = call { client.get("$baseUrl/auth/me/devices") { bearerAuth(token) } }

    suspend fun revokeDevice(token: String, deviceId: String, password: String?) {
        callUnit {
            client.post("$baseUrl/auth/me/devices/$deviceId/revoke") {
                bearerAuth(token)
                jsonBody(PasswordRequest(password))
            }
        }
    }

    suspend fun revokeOthers(token: String, password: String?): RevokeOthersResponse = call {
        client.post("$baseUrl/auth/me/devices/revoke-others") {
            bearerAuth(token)
            jsonBody(PasswordRequest(password))
        }
    }

    /** The code a new device shows (API §4.6): which device asks to sign in, and the three numbers. */
    suspend fun resolveLink(token: String, userCode: String): LinkDetails = call {
        client.post("$baseUrl/auth/me/links/resolve") {
            bearerAuth(token)
            jsonBody(ResolveLinkRequest(userCode))
        }
    }

    /** A wrong number denies the link: `409 link_verify_mismatch`. */
    suspend fun approveLink(token: String, linkId: String, verifyCode: String): LinkDecision = call {
        client.post("$baseUrl/auth/me/links/$linkId/approve") {
            bearerAuth(token)
            jsonBody(ApproveLinkRequest(verifyCode))
        }
    }

    suspend fun denyLink(token: String, linkId: String): LinkDecision = call {
        client.post("$baseUrl/auth/me/links/$linkId/deny") {
            bearerAuth(token)
            jsonBody(JsonObject(emptyMap()))
        }
    }

    suspend fun sync(token: String, request: SyncRequest): SyncResponse = call {
        client.post("$baseUrl/sync") {
            bearerAuth(token)
            header(SYNC_PROTOCOL_HEADER, SYNC_PROTOCOL.toString())
            jsonBody(request)
        }
    }

    /** `POST /sync` with a body built by hand (`include`, API §4.8). */
    suspend fun syncRaw(token: String, body: JsonObject): SyncResponse = call {
        client.post("$baseUrl/sync") {
            bearerAuth(token)
            header(SYNC_PROTOCOL_HEADER, SYNC_PROTOCOL.toString())
            jsonBody(body)
        }
    }

    suspend fun lyrics(token: String, videoId: String): LyricsResponse = call {
        client.get("$baseUrl/lyrics/$videoId") { bearerAuth(token) }
    }

    suspend fun putLyrics(token: String, videoId: String, body: LyricsPut): MyLyrics = call {
        client.put("$baseUrl/lyrics/$videoId") {
            bearerAuth(token)
            jsonBody(body)
        }
    }

    suspend fun deleteLyrics(token: String, videoId: String) = callUnit {
        client.delete("$baseUrl/lyrics/$videoId") { bearerAuth(token) }
    }

    suspend fun lyricsChanges(token: String, request: LyricsChangesRequest): MyLyricsPage = call {
        client.post("$baseUrl/auth/me/lyrics/changes") {
            bearerAuth(token)
            jsonBody(request)
        }
    }

    suspend fun mergePlan(token: String, request: MergePlanRequest): MergePlanResponse = call {
        client.post("$baseUrl/sync/merge-plan") {
            bearerAuth(token)
            header(SYNC_PROTOCOL_HEADER, SYNC_PROTOCOL.toString())
            jsonBody(request)
        }
    }

    fun close() {
        client.close()
        streamClient.close()
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = send(block)
        return runCatching { response.body<T>() }.getOrElse {
            throw ApiException(response.status.value, "invalid_response", "Unexpected answer of the server", it)
        }
    }

    private suspend inline fun callUnit(block: () -> HttpResponse) {
        send(block)
    }

    private suspend inline fun send(block: () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw ApiException(0, null, e.message, e)
        }
        if (!response.status.isSuccess()) throw errorOf(response)
        return response
    }

    /** The error of an answer that is not a success, with the code of its envelope (API §2). */
    suspend fun errorOf(response: HttpResponse): ApiException {
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(response.bodyAsText()) }.getOrNull()
        return ApiException(response.status.value, envelope?.code, envelope?.message)
    }

    private companion object {
        const val SYNC_PROTOCOL_HEADER = "X-Sync-Protocol"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
