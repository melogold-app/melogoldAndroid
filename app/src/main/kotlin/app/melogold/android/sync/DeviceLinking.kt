package app.melogold.android.sync

import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.LinkClaimed
import app.melogold.android.sync.api.LinkCreated
import app.melogold.android.sync.api.LinkDetails
import app.melogold.android.sync.api.LinkPollResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/*
 * Linking a device by a code (API §4.6, tasks/0015). Two sides, two modes:
 *
 *   mode `request`: the NEW device shows a code; a signed-in device types it (AddDeviceScreen), picks the number.
 *   mode `invite`:  the SIGNED-IN device shows a code; the new device types it and shows the number.
 *
 * [NewDeviceLinker] is the new device (both modes end with the same long poll, which brings the session);
 * [InviteLinker] is the signed-in device that shows a code and waits for a new device to claim it.
 */

/** Why a link did not lead anywhere, in the terms of the screens (API §2.2). */
enum class LinkFailure {
    /** The approving device refused, or a wrong number was chosen there. */
    Denied,
    Expired,
    Cancelled,
    DeviceLimit,
    NotFound,
    AlreadyClaimed,
    WrongMode,
    Throttled,
    Network,
    Unknown
}

fun linkFailureOf(error: Throwable): LinkFailure {
    val api = error as? ApiException
    return when (api?.code) {
        "link_denied", "link_verify_mismatch" -> LinkFailure.Denied
        "link_expired" -> LinkFailure.Expired
        "link_cancelled" -> LinkFailure.Cancelled
        "link_not_found" -> LinkFailure.NotFound
        "link_already_claimed" -> LinkFailure.AlreadyClaimed
        "link_wrong_mode" -> LinkFailure.WrongMode
        "device_limit_reached" -> LinkFailure.DeviceLimit
        "rate_limited", "login_throttled" -> LinkFailure.Throttled
        else -> if (api?.isNetwork == true) LinkFailure.Network else LinkFailure.Unknown
    }
}

/** The status of a [LinkDetails] that is over, as a failure; null while it is alive or done well. */
fun linkStatusFailure(status: String): LinkFailure? = when (status) {
    "denied" -> LinkFailure.Denied
    "expired" -> LinkFailure.Expired
    "cancelled" -> LinkFailure.Cancelled
    else -> null
}

/** A failure worth trying again in a moment without bothering anyone: no network, a busy server, a rate limit. */
internal val ApiException.isTransient: Boolean get() = isNetwork || status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

/** How long the code lives, `4:32`; never below `0:00`. */
fun countdownText(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + MILLIS_IN_SECOND - 1) / MILLIS_IN_SECOND
    return "%d:%02d".format(seconds / SECONDS_IN_MINUTE, seconds % SECONDS_IN_MINUTE)
}

/** `K7QX-M2PD` as separate characters for TalkBack: `K, 7, Q, X, M, 2, P, D`. */
fun spokenCode(code: String): String = code.filter { it != '-' }.toList().joinToString(", ")

private const val MILLIS_IN_SECOND = 1_000L
private const val SECONDS_IN_MINUTE = 60L
private const val RETRY_DELAY_MS = 3_000L
private const val INVITE_POLL_MS = 3_000L
private const val DEFAULT_TTL_MS = 5 * 60_000L

/** The expiry of the API in epoch milliseconds; [DEFAULT_TTL_MS] from now when the server's time is unreadable. */
private fun expiryOf(iso: String): Long = runCatching { iso.epochMs() }.getOrDefault(System.currentTimeMillis() + DEFAULT_TTL_MS)

// region The new device

/** Where the sign-in by code of this (new) device stands. */
sealed interface NewDeviceLinkState {
    /** Nothing asked yet, or given up. */
    data object Idle : NewDeviceLinkState

    /** The server is asked for a code (mode `request`) or for the typed one (mode `invite`). */
    data object Starting : NewDeviceLinkState

    /** Mode `request`: this device's code, waiting for a signed-in device to type it. [reconnecting]: the server is out of reach. */
    data class ShowingCode(val userCode: String, val expiresAt: Long, val reconnecting: Boolean = false) : NewDeviceLinkState

    /**
     * The other side has the code: the number to choose there ([verifyCode]), for whom, and on which device.
     */
    data class Verify(
        val verifyCode: String,
        val login: String,
        val approverName: String,
        val approverPlatform: String,
        val expiresAt: Long,
        val reconnecting: Boolean = false
    ) : NewDeviceLinkState

    /** The session is taken: the same as after a password sign-in. */
    data object SignedIn : NewDeviceLinkState

    /** The link is over. [started] false: it never got going (no code yet, the typed code was refused). */
    data class Failed(val failure: LinkFailure, val started: Boolean) : NewDeviceLinkState
}

/** What [NewDeviceLinker] needs from the server: [AccountLinkPort] in the app, a fake in tests. */
interface NewDeviceLinkPort {
    suspend fun request(): LinkCreated

    suspend fun claim(userCode: String): LinkClaimed

    /** At `completed` the session has been taken already. */
    suspend fun poll(pollSecret: String, knownStatus: String): LinkPollResponse

    suspend fun cancel(pollSecret: String)
}

class AccountLinkPort(private val account: Account) : NewDeviceLinkPort {
    override suspend fun request() = account.requestLink()

    override suspend fun claim(userCode: String) = account.claimLink(userCode)

    override suspend fun poll(pollSecret: String, knownStatus: String) = account.pollLink(pollSecret, knownStatus)

    override suspend fun cancel(pollSecret: String) = account.cancelLinkRequest(pollSecret)
}

/**
 * The sign-in by code of the new device: [showCode] asks for a code and waits for a signed-in device to approve it
 * (mode `request`); [claim] types the code a signed-in device shows (mode `invite`). Either way the state goes on to
 * [NewDeviceLinkState.Verify] (the number to choose on the other device) and ends in [NewDeviceLinkState.SignedIn],
 * with the session already taken. [cancel] gives the link up on the server too.
 *
 * The long poll runs in [scope] (the app's, so a "Cancel" still reaches the server when the screen is gone).
 */
class NewDeviceLinker(
    private val port: NewDeviceLinkPort,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = RETRY_DELAY_MS
) {
    private val mutableState = MutableStateFlow<NewDeviceLinkState>(NewDeviceLinkState.Idle)
    val state: StateFlow<NewDeviceLinkState> = mutableState.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var pollSecret: String? = null

    /** Mode `request`: this device's code. */
    fun showCode() = start {
        val created = try {
            port.request()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            mutableState.value = NewDeviceLinkState.Failed(linkFailureOf(e), started = false)
            return@start
        }
        val secret = created.pollSecret
        if (secret == null) {
            mutableState.value = NewDeviceLinkState.Failed(LinkFailure.Unknown, started = false)
            return@start
        }
        pollSecret = secret
        mutableState.value = NewDeviceLinkState.ShowingCode(created.userCode, expiryOf(created.expiresAt))
        follow(secret, knownStatus = "pending")
    }

    /** Mode `invite`: [userCode] (normalized, `K7QX-M2PD`) is what a signed-in device shows. */
    fun claim(userCode: String) = start {
        val claimed = try {
            port.claim(userCode)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            mutableState.value = NewDeviceLinkState.Failed(linkFailureOf(e), started = false)
            return@start
        }
        pollSecret = claimed.pollSecret
        mutableState.value = NewDeviceLinkState.Verify(
            verifyCode = claimed.verifyCode,
            login = claimed.account.login,
            approverName = claimed.approverDevice.name,
            approverPlatform = claimed.approverDevice.platform,
            expiresAt = expiryOf(claimed.expiresAt)
        )
        follow(claimed.pollSecret, knownStatus = "claimed")
    }

    /** Gives the link up, here and on the server; back to [NewDeviceLinkState.Idle] unless the session is taken. */
    fun cancel() {
        job?.cancel()
        job = null
        val secret = pollSecret
        pollSecret = null
        if (mutableState.value != NewDeviceLinkState.SignedIn) mutableState.value = NewDeviceLinkState.Idle
        if (secret != null) scope.launch { runCatching { port.cancel(secret) } }
    }

    private fun start(block: suspend () -> Unit) {
        cancel()
        mutableState.value = NewDeviceLinkState.Starting
        job = scope.launch { block() }
    }

    /** The long poll (API §4.6): answers at once when the status moved, else after 25 s. */
    private suspend fun follow(secret: String, knownStatus: String) {
        var known = knownStatus
        while (true) {
            val answer = try {
                port.poll(secret, known)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if ((e as? ApiException)?.isTransient == true) {
                    reconnecting(true)
                    delay(retryDelayMs)
                    continue
                }
                // Denied, expired, cancelled, the device limit: the link is over, nothing left to cancel
                pollSecret = null
                mutableState.value = NewDeviceLinkState.Failed(linkFailureOf(e), started = true)
                return
            }
            when (answer.status) {
                "completed" -> {
                    pollSecret = null
                    mutableState.value = NewDeviceLinkState.SignedIn
                    return
                }

                "claimed" -> {
                    known = "claimed"
                    mutableState.value = verifyOf(answer) ?: run {
                        pollSecret = null
                        NewDeviceLinkState.Failed(LinkFailure.Unknown, started = true)
                    }
                }

                else -> reconnecting(false)
            }
        }
    }

    private fun verifyOf(answer: LinkPollResponse): NewDeviceLinkState.Verify? {
        val code = answer.verifyCode ?: return null
        val login = answer.account?.login ?: return null
        val approver = answer.approverDevice ?: return null
        return NewDeviceLinkState.Verify(code, login, approver.name, approver.platform, expiryOf(answer.expiresAt))
    }

    /** Marks the code (or the number) on screen as "the server is out of reach" or as fresh again. */
    private fun reconnecting(value: Boolean) {
        mutableState.value = when (val current = mutableState.value) {
            is NewDeviceLinkState.ShowingCode -> current.copy(reconnecting = value)
            is NewDeviceLinkState.Verify -> current.copy(reconnecting = value)
            else -> current
        }
    }
}

// endregion

// region The signed-in device that shows a code

sealed interface InviteState {
    data object Idle : InviteState

    data object Starting : InviteState

    /** The code to type on the new device. */
    data class Waiting(val userCode: String, val expiresAt: Long) : InviteState

    /** A new device typed the code: its card and the three numbers (the approval screen). */
    data class Claimed(val link: LinkDetails) : InviteState

    /** The invitation is over. [started] false: no code was made. */
    data class Failed(val failure: LinkFailure, val started: Boolean) : InviteState
}

interface InvitePort {
    suspend fun create(): LinkCreated

    suspend fun get(linkId: String): LinkDetails

    suspend fun cancel(linkId: String)
}

class AccountInvitePort(private val account: Account) : InvitePort {
    override suspend fun create() = account.createInvite()

    override suspend fun get(linkId: String) = account.link(linkId)

    override suspend fun cancel(linkId: String) = account.cancelInvite(linkId)
}

/**
 * "Show a code for a new device" (mode `invite`): makes the invitation and follows it until a new device claims it
 * (the live event `link.updated` in [updates] reads it at once, [pollMs] is the fallback of API §4.6 without SSE).
 * The state keeps following a claimed invitation too: if the new device gives up, or the code runs out, it says so.
 * [release] stops without cancelling (after the decision), [cancel] cancels the invitation on the server.
 */
class InviteLinker(
    private val port: InvitePort,
    private val scope: CoroutineScope,
    private val updates: Flow<String>,
    private val pollMs: Long = INVITE_POLL_MS
) {
    private val mutableState = MutableStateFlow<InviteState>(InviteState.Idle)
    val state: StateFlow<InviteState> = mutableState.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var linkId: String? = null

    fun start() {
        cancel()
        mutableState.value = InviteState.Starting
        job = scope.launch {
            val created = try {
                port.create()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                mutableState.value = InviteState.Failed(linkFailureOf(e), started = false)
                return@launch
            }
            val id = created.linkId
            linkId = id
            mutableState.value = InviteState.Waiting(created.userCode, expiryOf(created.expiresAt))
            val nudges = merge(
                flow {
                    while (true) {
                        delay(pollMs)
                        emit(Unit)
                    }
                },
                updates.filter { it == id }.map { }
            )
            nudges.first { refresh(id) }
        }
    }

    /** Reads the invitation once; true when there is nothing more to follow. */
    private suspend fun refresh(id: String): Boolean {
        val details = try {
            port.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if ((e as? ApiException)?.isTransient == true) return false
            linkId = null
            mutableState.value = InviteState.Failed(linkFailureOf(e), started = true)
            return true
        }
        linkStatusFailure(details.status)?.let { failure ->
            linkId = null
            mutableState.value = InviteState.Failed(failure, started = true)
            return true
        }
        when (details.status) {
            "claimed" -> if (details.verifyChoices.isNotEmpty()) mutableState.value = InviteState.Claimed(details)
            // Decided (here) and finished (there): the screen has said what it had to
            "approved", "completed" -> return true
        }
        return false
    }

    /** Stops following without cancelling: the link is decided (approved or denied). */
    fun release() {
        job?.cancel()
        job = null
        linkId = null
        mutableState.value = InviteState.Idle
    }

    /** Cancels the invitation, here and on the server; back to [InviteState.Idle]. */
    fun cancel() {
        val id = linkId
        release()
        if (id != null) scope.launch { runCatching { port.cancel(id) } }
    }
}

// endregion
