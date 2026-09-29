package app.melogold.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.ClaimLinkRequest
import app.melogold.android.sync.api.CreateLinkRequestRequest
import app.melogold.android.sync.api.DeviceInput
import app.melogold.android.sync.api.LinkPollResponse
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.PollLinkRequest
import app.melogold.android.sync.api.RegisterRequest
import app.melogold.domain.server.UserCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sign-in by code (tasks/0015, API §4.6), both modes, both sides, against a real Melogold server given by
 * `-Pmelogold.testServer=http://127.0.0.1:8787`. This app is the new device ([NewDeviceLinker] with the real
 * [Account]) or the signed-in one ([InviteLinker]); the other side speaks the same API calls of [MelogoldApi] with a
 * token, as another client would. Every test registers a throwaway account and deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class SignInByCodeLiveTest {
    private val server = System.getProperty("melogold.testServer")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun stop() {
        scope.cancel()
    }

    /** Another device of the account, signed in: a MacBook, with a token. */
    private inner class Elsewhere(val url: String) {
        val api = MelogoldApi(url)
        val login = "link${hex(6)}"
        val password = "throwaway ${hex(12)}"
        lateinit var token: String

        suspend fun register() = apply {
            token = api.register(
                RegisterRequest(login, password, DeviceInput(hex(32), "MacBook Air", "macos", osVersion = "26.0"))
            ).tokens.accessToken
        }

        fun delete() {
            runCatching { post(url, "/auth/me/delete", """{"password":"$password"}""", token) }
            api.close()
        }
    }

    /** The new device typing the code as a person would, and its own name. */
    private fun typed(userCode: String) = assertNotNull(UserCode.normalize(userCode.lowercase().replace("-", " ")))

    private suspend inline fun <reified T : NewDeviceLinkState> NewDeviceLinker.await(): T {
        val ended = withTimeout(TIMEOUT_MS) { state.first { it is T || it is NewDeviceLinkState.Failed } }
        return assertIs<T>(ended, "the sign-in ended as $ended")
    }

    private suspend inline fun <reified T : InviteState> InviteLinker.await(): T {
        val ended = withTimeout(TIMEOUT_MS) { state.first { it is T || it is InviteState.Failed } }
        return assertIs<T>(ended, "the invitation ended as $ended")
    }

    /** The new device's own long poll, for the tests where it is played by raw calls. */
    private suspend fun MelogoldApi.pollUntil(pollSecret: String, known: String, until: String): LinkPollResponse {
        repeat(POLL_TRIES) {
            val answer = pollLink(PollLinkRequest(pollSecret, waitSeconds = 5, knownStatus = known))
            if (answer.status == until) return answer
        }
        fail("the link never came to $until")
    }

    private suspend fun code(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: ApiException) {
        e.code
    }

    @Test
    fun `mode request - this new device shows a code, a signed-in device types it and picks the number`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val elsewhere = Elsewhere(server!!).register()
        val account = Account(context).also { it.setServer(server!!) }
        val linker = NewDeviceLinker(AccountLinkPort(account), scope)
        try {
            linker.showCode()
            val shown = linker.await<NewDeviceLinkState.ShowingCode>()
            assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(shown.userCode), shown.userCode)
            assertTrue(shown.expiresAt > System.currentTimeMillis(), "the code has time left")

            // The signed-in device: the code as it was typed, then the card and the three numbers
            val details = elsewhere.api.resolveLink(elsewhere.token, typed(shown.userCode))
            assertEquals("claimed", details.status)
            assertEquals("android", details.device?.platform)
            assertEquals(3, details.verifyChoices.size)

            // The new device shows the number, and for whom, and on which device to choose it
            val verify = linker.await<NewDeviceLinkState.Verify>()
            assertEquals(elsewhere.login, verify.login)
            assertEquals("MacBook Air", verify.approverName)
            assertEquals("macos", verify.approverPlatform)
            assertTrue(verify.verifyCode in details.verifyChoices, "${verify.verifyCode} in ${details.verifyChoices}")

            assertEquals("approved", elsewhere.api.approveLink(elsewhere.token, details.linkId, verify.verifyCode).status)
            linker.await<NewDeviceLinkState.SignedIn>()

            // The session is the account's, taken like after a password sign-in
            assertEquals(AccountState.SignedIn::class, account.state.value::class)
            assertEquals(elsewhere.login, account.session?.login)
            val devices = account.devices()
            assertTrue(devices.any { it.isCurrent && it.platform == "android" }, "this device is on the account: $devices")
            assertTrue(devices.any { it.name == "MacBook Air" && !it.isCurrent })
            // The code is used up
            val again = elsewhere.api.resolveLink(elsewhere.token, shown.userCode)
            assertTrue(again.status in setOf("approved", "completed"), again.status)
        } finally {
            linker.cancel()
            elsewhere.delete()
        }
    }

    @Test
    fun `mode request - Cancel, Deny and a wrong number end the sign-in with their reasons`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val elsewhere = Elsewhere(server!!).register()
        val account = Account(context).also { it.setServer(server!!) }
        val linker = NewDeviceLinker(AccountLinkPort(account), scope)
        try {
            // "Cancel" reaches the server: the code is dead for the signed-in device
            linker.showCode()
            val first = linker.await<NewDeviceLinkState.ShowingCode>()
            linker.cancel()
            assertEquals(NewDeviceLinkState.Idle, linker.state.value)
            // The cancel goes out on the app's scope; give it a moment. resolve answers 410 link_expired for a link that
            // is no longer pending, cancelled or not (link_cancelled comes from poll and approve)
            delay(CANCEL_SETTLE_MS)
            assertEquals("link_expired", code { elsewhere.api.resolveLink(elsewhere.token, first.userCode) })

            // "Deny" on the signed-in device
            linker.showCode()
            val second = linker.await<NewDeviceLinkState.ShowingCode>()
            val secondLink = elsewhere.api.resolveLink(elsewhere.token, second.userCode)
            linker.await<NewDeviceLinkState.Verify>()
            assertEquals("denied", elsewhere.api.denyLink(elsewhere.token, secondLink.linkId).status)
            val denied = linker.await<NewDeviceLinkState.Failed>()
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.Denied, started = true), denied)
            assertEquals(AccountState.SignedOut, account.state.value)

            // A number that is not the one on this screen: a refusal there, and here too
            linker.showCode()
            val third = linker.await<NewDeviceLinkState.ShowingCode>()
            val thirdLink = elsewhere.api.resolveLink(elsewhere.token, third.userCode)
            val shown = linker.await<NewDeviceLinkState.Verify>()
            val wrong = thirdLink.verifyChoices.first { it != shown.verifyCode }
            assertEquals("link_verify_mismatch", code { elsewhere.api.approveLink(elsewhere.token, thirdLink.linkId, wrong) })
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.Denied, started = true), linker.await<NewDeviceLinkState.Failed>())
            assertEquals(AccountState.SignedOut, account.state.value)
            assertEquals(null, account.session)
        } finally {
            linker.cancel()
            elsewhere.delete()
        }
    }

    @Test
    fun `mode invite - a signed-in device shows a code, this new device types it and shows the number`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val elsewhere = Elsewhere(server!!).register()
        val account = Account(context).also { it.setServer(server!!) }
        val linker = NewDeviceLinker(AccountLinkPort(account), scope)
        try {
            // A wrong code, and a code of the other mode: said, and nothing left open
            linker.claim("K7QX-M2PD")
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.NotFound, started = false), linker.await<NewDeviceLinkState.Failed>())

            val invite = elsewhere.api.createInvite(elsewhere.token)
            assertEquals("invite", invite.mode)
            linker.claim(typed(invite.userCode))
            val verify = linker.await<NewDeviceLinkState.Verify>()
            assertEquals(elsewhere.login, verify.login)
            assertEquals("MacBook Air", verify.approverName)

            // The signed-in device reads it and sees this device and the three numbers
            val details = elsewhere.api.link(elsewhere.token, invite.linkId)
            assertEquals("claimed", details.status)
            assertEquals("android", details.device?.platform)
            assertTrue(verify.verifyCode in details.verifyChoices, "${verify.verifyCode} in ${details.verifyChoices}")

            assertEquals("approved", elsewhere.api.approveLink(elsewhere.token, invite.linkId, verify.verifyCode).status)
            linker.await<NewDeviceLinkState.SignedIn>()
            assertEquals(elsewhere.login, account.session?.login)
            assertTrue(account.devices().any { it.isCurrent && it.platform == "android" })
        } finally {
            linker.cancel()
            elsewhere.delete()
        }
    }

    @Test
    fun `mode invite - the new device says why an invitation is refused or denied`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val elsewhere = Elsewhere(server!!).register()
        val account = Account(context).also { it.setServer(server!!) }
        val linker = NewDeviceLinker(AccountLinkPort(account), scope)
        try {
            // The code another new device shows is not an invitation
            val other = MelogoldApi(server!!)
            val request = other.createLinkRequest(
                CreateLinkRequestRequest(DeviceInput(hex(32), "Apple Watch", "watchos"))
            )
            linker.claim(typed(request.userCode))
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.WrongMode, started = false), linker.await<NewDeviceLinkState.Failed>())
            other.cancelLinkRequest(assertNotNull(request.pollSecret))
            other.close()

            // Cancelled by the signed-in device before it is typed: claim answers 410 link_expired
            val cancelled = elsewhere.api.createInvite(elsewhere.token)
            elsewhere.api.cancelInvite(elsewhere.token, cancelled.linkId)
            linker.claim(typed(cancelled.userCode))
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.Expired, started = false), linker.await<NewDeviceLinkState.Failed>())

            // Denied by the signed-in device after it is typed
            val invite = elsewhere.api.createInvite(elsewhere.token)
            linker.claim(typed(invite.userCode))
            linker.await<NewDeviceLinkState.Verify>()
            assertEquals("denied", elsewhere.api.denyLink(elsewhere.token, invite.linkId).status)
            assertEquals(NewDeviceLinkState.Failed(LinkFailure.Denied, started = true), linker.await<NewDeviceLinkState.Failed>())
            assertEquals(AccountState.SignedOut, account.state.value)

            // The new device cancels a typed invitation: the signed-in device sees it cancelled
            val third = elsewhere.api.createInvite(elsewhere.token)
            linker.claim(typed(third.userCode))
            linker.await<NewDeviceLinkState.Verify>()
            linker.cancel()
            var status = ""
            repeat(20) {
                if (status != "cancelled") {
                    status = elsewhere.api.link(elsewhere.token, third.linkId).status
                    if (status != "cancelled") delay(100)
                }
            }
            assertEquals("cancelled", status)
        } finally {
            linker.cancel()
            elsewhere.delete()
        }
    }

    @Test
    fun `mode invite - this signed-in device shows a code, a new device claims it, this device approves`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val url = server!!
        val account = Account(context).also { it.setServer(url) }
        val password = "throwaway ${hex(12)}"
        val login = "link${hex(6)}"
        account.register(login, password)
        // No live stream here: the invitation is read every 300 ms instead of every 3 s
        val invite = InviteLinker(AccountInvitePort(account), scope, emptyFlow(), pollMs = 300)
        val newDevice = MelogoldApi(url)
        try {
            invite.start()
            val waiting = invite.await<InviteState.Waiting>()
            assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(waiting.userCode), waiting.userCode)
            assertTrue(waiting.expiresAt > System.currentTimeMillis())

            // The new device types it and gets the number to show
            val claimed = newDevice.claimLink(
                ClaimLinkRequest(typed(waiting.userCode), DeviceInput(hex(32), "Google Pixel 8", "android", osVersion = "16"))
            )
            assertEquals("claimed", claimed.status)
            assertEquals(login, claimed.account.login)
            assertEquals("android", claimed.approverDevice.platform)

            // This device gets the same approval card as for a code typed here: the device, the three numbers
            val card = invite.await<InviteState.Claimed>().link
            assertEquals("Google Pixel 8", card.device?.name)
            assertEquals("android", card.device?.platform)
            assertTrue(claimed.verifyCode in card.verifyChoices, "${claimed.verifyCode} in ${card.verifyChoices}")

            assertEquals("approved", account.approveLink(card.linkId, claimed.verifyCode).status)
            invite.release()
            val done = newDevice.pollUntil(claimed.pollSecret, known = "claimed", until = "completed")
            assertEquals(login, done.session?.user?.login)
            assertTrue(account.devices().any { it.name == "Google Pixel 8" }, "the new device is on the account")
        } finally {
            invite.cancel()
            newDevice.close()
            account.session?.accessToken?.let { post(url, "/auth/me/delete", """{"password":"$password"}""", it) }
        }
    }

    @Test
    fun `mode invite - Cancel, Deny and a wrong number on this signed-in device`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val url = server!!
        val account = Account(context).also { it.setServer(url) }
        val password = "throwaway ${hex(12)}"
        account.register("link${hex(6)}", password)
        val invite = InviteLinker(AccountInvitePort(account), scope, emptyFlow(), pollMs = 300)
        val newDevice = MelogoldApi(url)
        fun device(name: String) = DeviceInput(hex(32), name, "android", osVersion = "16")
        try {
            // "Cancel": the code is dead for a new device
            invite.start()
            val open = invite.await<InviteState.Waiting>()
            invite.cancel()
            assertEquals(InviteState.Idle, invite.state.value)
            delay(CANCEL_SETTLE_MS)
            assertEquals("link_expired", code { newDevice.claimLink(ClaimLinkRequest(typed(open.userCode), device("Pixel"))) })

            // "Deny": the new device is told
            invite.start()
            val second = invite.await<InviteState.Waiting>()
            val claimed = newDevice.claimLink(ClaimLinkRequest(typed(second.userCode), device("Pixel 2")))
            val card = invite.await<InviteState.Claimed>().link
            assertEquals("denied", account.denyLink(card.linkId).status)
            invite.release()
            assertEquals("link_denied", code { newDevice.pollUntil(claimed.pollSecret, known = "claimed", until = "completed") })

            // A wrong number: a refusal, and the new device is told too
            invite.start()
            val third = invite.await<InviteState.Waiting>()
            val claimedThird = newDevice.claimLink(ClaimLinkRequest(typed(third.userCode), device("Pixel 3")))
            val thirdCard = invite.await<InviteState.Claimed>().link
            val wrong = thirdCard.verifyChoices.first { it != claimedThird.verifyCode }
            assertEquals("link_verify_mismatch", code { account.approveLink(thirdCard.linkId, wrong) })
            invite.release()
            assertEquals("link_denied", code { newDevice.pollUntil(claimedThird.pollSecret, known = "claimed", until = "completed") })
            assertTrue(account.devices().none { it.name.startsWith("Pixel") })
        } finally {
            invite.cancel()
            newDevice.close()
            account.session?.accessToken?.let { post(url, "/auth/me/delete", """{"password":"$password"}""", it) }
        }
    }

    private fun post(url: String, path: String, body: String, token: String? = null): String {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status < HTTP_ERROR) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(status < HTTP_ERROR) { "$path: HTTP $status $text" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 40_000L
        const val POLL_TRIES = 6
        const val CANCEL_SETTLE_MS = 700L
        const val HTTP_TIMEOUT_MS = 35_000
        const val HTTP_ERROR = 400
    }
}
