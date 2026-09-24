package app.melogold.android.sync

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import app.melogold.android.BuildConfig
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.AuthSession
import app.melogold.android.sync.api.DeviceDto
import app.melogold.android.sync.api.DeviceInput
import app.melogold.android.sync.api.DevicePatch
import app.melogold.android.sync.api.LoginRequest
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.PowSolution
import app.melogold.android.sync.api.RefreshRequest
import app.melogold.android.sync.api.RegisterRequest
import app.melogold.android.sync.api.ServerInfo
import app.melogold.android.sync.api.TokenPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Refreshed this long before it expires (API §1.7: proactively, 60 s early). */
private const val REFRESH_EARLY_MS = 60_000L

/** The session on the Melogold server, as kept on the device (never backed up: `melogold_account` is excluded). */
@Serializable
data class StoredSession(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val login: String,
    val deviceId: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String
)

/** Where the account stands. */
sealed interface AccountState {
    /** No account: the app works like ViTune, alone on the device. */
    data object SignedOut : AccountState

    data class SignedIn(val login: String, val deviceId: String, val serverUrl: String) : AccountState

    /**
     * The server ended the session (the device was revoked, the password changed): sign in again. The library and the
     * binding stay (API §1.7).
     */
    data class AuthRequired(val login: String, val serverUrl: String) : AccountState
}

/**
 * The account on the Melogold server (API §4.3, §4.4): sign-up with proof of work, sign-in, the tokens and their
 * refresh (single flight, one retry of the call), sign-out, the devices. Screens and the sync engine reach the server
 * only through [authorized].
 */
class Account(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private var cachedApi: Pair<String, MelogoldApi>? = null

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    /** The server this app talks to (Settings › Server). */
    val serverUrl: String get() = prefs.getString(KEY_SERVER, null) ?: BuildConfig.DEFAULT_SERVER_URL

    /** This install's device id for the server (API §4.1 `Hwid`): 32 random bytes, made once. */
    val hwid: String
        get() = prefs.getString(KEY_HWID, null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit { putString(KEY_HWID, it) } }

    val session: StoredSession?
        get() = prefs.getString(KEY_SESSION, null)?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() }

    fun api(url: String = serverUrl): MelogoldApi = cachedApi?.takeIf { it.first == url }?.second
        ?: MelogoldApi(url).also { api ->
            cachedApi?.second?.close()
            cachedApi = url to api
        }

    /** Checks a server before switching to it (REWRITE §3.5.12): only a Melogold server this app can talk to. */
    suspend fun check(url: String): ServerInfo {
        val info = MelogoldApi(url).let { api -> try { api.serverInfo() } finally { api.close() } }
        if (info.software != "melogold-server") throw ApiException(0, "not_melogold", "Not a Melogold server")
        return info
    }

    /** Switches to another server: the session of the old one ends on this device. */
    fun setServer(url: String) {
        prefs.edit {
            putString(KEY_SERVER, url)
            remove(KEY_SESSION)
        }
        mutableState.value = AccountState.SignedOut
    }

    /** Creates an account; the answer is the recovery code, shown once (API §4.3). */
    suspend fun register(login: String, password: String): String {
        val api = api()
        val challenge = api.registerChallenge()
        val pow = if (challenge.bits > 0) {
            PowSolution(challenge.challenge, withContext(Dispatchers.Default) { solvePow(challenge.challenge, challenge.bits) })
        } else null
        val session = api.register(RegisterRequest(login = login, password = password, device = deviceInput(), pow = pow))
        save(session)
        return session.recoveryCode.orEmpty()
    }

    suspend fun signIn(login: String, password: String) {
        save(api().login(LoginRequest(login = login, password = password, device = deviceInput())))
    }

    /** Ends the session on the server too; the library stays on the device. */
    suspend fun signOut() {
        val current = session
        prefs.edit { remove(KEY_SESSION) }
        mutableState.value = AccountState.SignedOut
        if (current != null) runCatching { api(current.serverUrl).logout(current.refreshToken) }
    }

    /**
     * Runs [block] with a valid access token: refreshed first when it is about to expire, refreshed and retried once
     * when the server calls it expired. A session the server ended moves to [AccountState.AuthRequired].
     */
    suspend fun <T> authorized(block: suspend (api: MelogoldApi, token: String) -> T): T {
        val api = api(session?.serverUrl ?: serverUrl)
        val token = freshToken()
        return try {
            block(api, token)
        } catch (e: ApiException) {
            when (e.code) {
                "access_token_expired", "access_token_invalid" -> block(api, freshToken(force = true))
                "session_revoked" -> {
                    endSession()
                    throw e
                }

                else -> throw e
            }
        }
    }

    suspend fun devices(): List<DeviceDto> = authorized { api, token -> api.devices(token).devices }

    suspend fun revoke(deviceId: String, password: String?) = authorized { api, token -> api.revokeDevice(token, deviceId, password) }

    suspend fun revokeOthers(password: String?): Int = authorized { api, token -> api.revokeOthers(token, password).revokedCount }

    /** The server ended the session: sign in again, the data stays. */
    fun endSession() {
        val current = session ?: return
        prefs.edit { remove(KEY_SESSION) }
        mutableState.value = AccountState.AuthRequired(current.login, current.serverUrl)
    }

    private suspend fun freshToken(force: Boolean = false): String = refreshMutex.withLock {
        val current = session ?: throw ApiException(401, "unauthorized", "Not signed in")
        if (!force && current.accessTokenExpiresAt - System.currentTimeMillis() > REFRESH_EARLY_MS) return current.accessToken

        val refreshed = try {
            api(current.serverUrl).refresh(
                RefreshRequest(current.refreshToken, DevicePatch(hwid = hwid, clientVersion = BuildConfig.VERSION_NAME))
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Any 401 of /auth/refresh: the session is over (API §1.7); network errors keep it
            if (e.status == 401) endSession()
            throw e
        }
        val updated = current.withTokens(refreshed.tokens)
        prefs.edit { putString(KEY_SESSION, json.encodeToString(StoredSession.serializer(), updated)) }
        updated.accessToken
    }

    private fun save(session: AuthSession) {
        val stored = StoredSession(
            serverUrl = serverUrl,
            serverId = session.serverId,
            userId = session.user.id,
            login = session.user.login,
            deviceId = session.device.id,
            accessToken = session.tokens.accessToken,
            accessTokenExpiresAt = session.tokens.accessTokenExpiresAt.epochMs(),
            refreshToken = session.tokens.refreshToken
        )
        prefs.edit { putString(KEY_SESSION, json.encodeToString(StoredSession.serializer(), stored)) }
        mutableState.value = AccountState.SignedIn(stored.login, stored.deviceId, stored.serverUrl)
    }

    private fun initialState(): AccountState = session?.let { AccountState.SignedIn(it.login, it.deviceId, it.serverUrl) }
        ?: AccountState.SignedOut

    private fun deviceInput() = DeviceInput(
        hwid = hwid,
        name = Build.MODEL.orEmpty().ifBlank { "Android" }.take(DEVICE_FIELD_MAX),
        platform = "android",
        osVersion = Build.VERSION.RELEASE?.take(DEVICE_FIELD_MAX),
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(DEVICE_FIELD_MAX),
        clientVersion = BuildConfig.VERSION_NAME.take(DEVICE_FIELD_MAX)
    )

    private companion object {
        const val PREFS = "melogold_account"
        const val KEY_SERVER = "server_url"
        const val KEY_HWID = "hwid"
        const val KEY_SESSION = "session"
        const val DEVICE_FIELD_MAX = 64
    }
}

private fun StoredSession.withTokens(tokens: TokenPair) = copy(
    accessToken = tokens.accessToken,
    accessTokenExpiresAt = tokens.accessTokenExpiresAt.epochMs(),
    refreshToken = tokens.refreshToken
)

/** An `Iso` time of the API (API §1.5) in epoch milliseconds. */
fun String.epochMs(): Long = Instant.parse(this).toEpochMilli()

/** An epoch time as the API writes it: `2026-09-23T10:00:00.000Z`. */
fun Long.isoTime(): String = Instant.ofEpochMilli(this).toString().let { text ->
    // Instant drops zero millis ("…:00Z"); the API reads both, this keeps one form
    if (text.length == 20) text.dropLast(1) + ".000Z" else text
}

/**
 * The proof of work of API §4.3: the first `nonce` ("0", "1", …) for which `sha256(challenge + ":" + nonce)` starts with
 * at least [bits] zero bits.
 */
fun solvePow(challenge: String, bits: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var nonce = 0L
    while (true) {
        val hash = digest.digest("$challenge:$nonce".toByteArray(Charsets.UTF_8))
        if (hash.leadingZeroBits() >= bits) return nonce.toString()
        nonce++
    }
}

private fun ByteArray.leadingZeroBits(): Int {
    var count = 0
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        if (value == 0) count += 8 else return count + Integer.numberOfLeadingZeros(value) - 24
    }
    return count
}
