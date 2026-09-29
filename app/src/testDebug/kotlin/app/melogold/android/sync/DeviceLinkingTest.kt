package app.melogold.android.sync

import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.LinkAccount
import app.melogold.android.sync.api.LinkApprover
import app.melogold.android.sync.api.LinkClaimed
import app.melogold.android.sync.api.LinkCreated
import app.melogold.android.sync.api.LinkDetails
import app.melogold.android.sync.api.LinkDeviceInfo
import app.melogold.android.sync.api.LinkPollResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The sign-in by code as logic (tasks/0015): both sides, every ending, without a server. A fake port answers what the
 * test hands it, so a long poll is a channel the test fills.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkingTest {
    private val expires = (System.currentTimeMillis() + 5 * 60_000L).isoTime()

    private fun created(mode: String, pollSecret: String? = "mgps_secret") = LinkCreated(
        linkId = "link-1",
        mode = mode,
        serverId = "server",
        linkToken = "token",
        userCode = "K7QX-M2PD",
        pollSecret = pollSecret,
        expiresAt = expires
    )

    private fun poll(status: String, verifyCode: String? = null) = LinkPollResponse(
        linkId = "link-1",
        status = status,
        expiresAt = expires,
        account = verifyCode?.let { LinkAccount("maxim") },
        approverDevice = verifyCode?.let { LinkApprover("MacBook Air", "macos") },
        verifyCode = verifyCode
    )

    private fun api(status: Int, code: String?) = ApiException(status, code, null)

    private class FakeNewDevicePort(
        var request: () -> LinkCreated,
        var claim: (String) -> LinkClaimed = { error("no claim") }
    ) : NewDeviceLinkPort {
        val answers = Channel<Result<LinkPollResponse>>(Channel.UNLIMITED)
        val polls = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        var requests = 0

        override suspend fun request(): LinkCreated {
            requests++
            return request.invoke()
        }

        override suspend fun claim(userCode: String) = claim.invoke(userCode)

        override suspend fun poll(pollSecret: String, knownStatus: String): LinkPollResponse {
            polls += pollSecret to knownStatus
            return answers.receive().getOrThrow()
        }

        override suspend fun cancel(pollSecret: String) {
            cancelled += pollSecret
        }
    }

    @Test
    fun `the code is shown, the number comes, the session is taken`() = runTest {
        val port = FakeNewDevicePort(request = { created("request") })
        val linker = NewDeviceLinker(port, backgroundScope)

        assertEquals(NewDeviceLinkState.Idle, linker.state.value)
        linker.showCode()
        assertEquals(NewDeviceLinkState.Starting, linker.state.value)
        runCurrent()
        val code = assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value)
        assertEquals("K7QX-M2PD", code.userCode)
        assertTrue(code.expiresAt > System.currentTimeMillis())

        // 25 s without news: the server answers "pending" and the poll goes on with the same status
        port.answers.trySend(Result.success(poll("pending")))
        runCurrent()
        assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value)

        port.answers.trySend(Result.success(poll("claimed", verifyCode = "47")))
        runCurrent()
        val verify = assertIs<NewDeviceLinkState.Verify>(linker.state.value)
        assertEquals("47", verify.verifyCode)
        assertEquals("maxim", verify.login)
        assertEquals("MacBook Air", verify.approverName)
        assertEquals("macos", verify.approverPlatform)

        port.answers.trySend(Result.success(poll("completed", verifyCode = "47")))
        runCurrent()
        assertEquals(NewDeviceLinkState.SignedIn, linker.state.value)
        assertEquals(listOf("pending", "pending", "claimed"), port.polls.map { it.second })
        assertTrue(port.polls.all { it.first == "mgps_secret" })

        // Leaving after the sign-in gives nothing up
        linker.cancel()
        runCurrent()
        assertEquals(NewDeviceLinkState.SignedIn, linker.state.value)
        assertTrue(port.cancelled.isEmpty())
    }

    @Test
    fun `no code without a network can be asked again`() = runTest {
        val port = FakeNewDevicePort(request = { throw api(0, null) })
        val linker = NewDeviceLinker(port, backgroundScope)

        linker.showCode()
        runCurrent()
        assertEquals(NewDeviceLinkState.Failed(LinkFailure.Network, started = false), linker.state.value)

        port.request = { created("request") }
        linker.showCode()
        runCurrent()
        assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value)
        assertEquals(2, port.requests)
    }

    @Test
    fun `a poll that loses the network is repeated and the code stays on screen`() = runTest {
        val port = FakeNewDevicePort(request = { created("request") })
        val linker = NewDeviceLinker(port, backgroundScope, retryDelayMs = 3_000)
        linker.showCode()
        runCurrent()

        port.answers.trySend(Result.failure(api(0, null)))
        runCurrent()
        assertEquals(true, assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value).reconnecting)
        assertEquals(1, port.polls.size)

        // A busy server and a rate limit are waited out too
        advanceTimeBy(3_001)
        port.answers.trySend(Result.failure(api(503, "server_busy")))
        runCurrent()
        assertEquals(2, port.polls.size)
        assertEquals(true, assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value).reconnecting)

        advanceTimeBy(3_001)
        port.answers.trySend(Result.success(poll("claimed", verifyCode = "12")))
        runCurrent()
        assertEquals(false, assertIs<NewDeviceLinkState.Verify>(linker.state.value).reconnecting)
        // Still asking with the last known status, not starting over; then on with "claimed"
        assertEquals(listOf("pending", "pending", "pending", "claimed"), port.polls.map { it.second })
        assertTrue(port.cancelled.isEmpty())
    }

    @Test
    fun `every ending of the link has its failure`() = runTest {
        val endings = mapOf(
            api(403, "link_denied") to LinkFailure.Denied,
            api(410, "link_expired") to LinkFailure.Expired,
            api(410, "link_cancelled") to LinkFailure.Cancelled,
            api(409, "device_limit_reached") to LinkFailure.DeviceLimit,
            api(404, "link_not_found") to LinkFailure.NotFound
        )
        endings.forEach { (error, failure) ->
            val port = FakeNewDevicePort(request = { created("request") })
            val linker = NewDeviceLinker(port, backgroundScope)
            linker.showCode()
            runCurrent()
            port.answers.trySend(Result.failure(error))
            runCurrent()
            assertEquals(NewDeviceLinkState.Failed(failure, started = true), linker.state.value, error.code)
            // The link is over: nothing left to cancel
            linker.cancel()
            runCurrent()
            assertTrue(port.cancelled.isEmpty(), error.code)
        }
    }

    @Test
    fun `Cancel and leaving give the code up on the server`() = runTest {
        val port = FakeNewDevicePort(request = { created("request") })
        val linker = NewDeviceLinker(port, backgroundScope)
        linker.showCode()
        runCurrent()

        linker.cancel()
        runCurrent()
        assertEquals(NewDeviceLinkState.Idle, linker.state.value)
        assertEquals(listOf("mgps_secret"), port.cancelled)

        // A second cancel says nothing more to the server
        linker.cancel()
        runCurrent()
        assertEquals(1, port.cancelled.size)
    }

    @Test
    fun `asking for a new code cancels the old one`() = runTest {
        var count = 0
        val port = FakeNewDevicePort(request = { created("request", pollSecret = "mgps_${++count}") })
        val linker = NewDeviceLinker(port, backgroundScope)
        linker.showCode()
        runCurrent()
        linker.showCode()
        runCurrent()
        assertEquals(listOf("mgps_1"), port.cancelled)
        assertIs<NewDeviceLinkState.ShowingCode>(linker.state.value)
        assertEquals("mgps_2", port.polls.last().first)
    }

    @Test
    fun `an invitation typed here shows the number at once and waits for the session`() = runTest {
        val claimed = LinkClaimed(
            linkId = "link-1",
            status = "claimed",
            pollSecret = "mgps_claim",
            account = LinkAccount("maxim"),
            approverDevice = LinkApprover("MacBook Air", "macos"),
            verifyCode = "47",
            expiresAt = expires
        )
        var typed: String? = null
        val port = FakeNewDevicePort(request = { error("not in this mode") }, claim = { typed = it; claimed })
        val linker = NewDeviceLinker(port, backgroundScope)

        linker.claim("K7QX-M2PD")
        runCurrent()
        assertEquals("K7QX-M2PD", typed)
        assertEquals("47", assertIs<NewDeviceLinkState.Verify>(linker.state.value).verifyCode)
        assertEquals(listOf("mgps_claim" to "claimed"), port.polls)

        port.answers.trySend(Result.success(poll("completed", verifyCode = "47")))
        runCurrent()
        assertEquals(NewDeviceLinkState.SignedIn, linker.state.value)
    }

    @Test
    fun `a typed code the server refuses comes back with its reason`() = runTest {
        val refusals = mapOf(
            api(404, "link_not_found") to LinkFailure.NotFound,
            api(409, "link_already_claimed") to LinkFailure.AlreadyClaimed,
            api(409, "link_wrong_mode") to LinkFailure.WrongMode,
            api(410, "link_expired") to LinkFailure.Expired,
            api(0, null) to LinkFailure.Network
        )
        refusals.forEach { (error, failure) ->
            val port = FakeNewDevicePort(request = { created("request") }, claim = { throw error })
            val linker = NewDeviceLinker(port, backgroundScope)
            linker.claim("K7QX-M2PD")
            runCurrent()
            assertEquals(NewDeviceLinkState.Failed(failure, started = false), linker.state.value, error.code)
            assertTrue(port.polls.isEmpty())
        }
    }

    // region Invitation of this device

    private class FakeInvitePort(var created: LinkCreated, var details: () -> LinkDetails) : InvitePort {
        val gets = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun create() = created

        override suspend fun get(linkId: String): LinkDetails {
            gets += linkId
            return details()
        }

        override suspend fun cancel(linkId: String) {
            cancelled += linkId
        }
    }

    private fun details(status: String, choices: List<String> = emptyList()) = LinkDetails(
        linkId = "link-1",
        mode = "invite",
        status = status,
        createdAt = System.currentTimeMillis().isoTime(),
        expiresAt = expires,
        device = if (status == "claimed") LinkDeviceInfo(name = "Pixel 8", platform = "android") else null,
        sameNetwork = true,
        verifyChoices = choices
    )

    private fun TestScope.invite(port: FakeInvitePort, updates: MutableSharedFlow<String> = MutableSharedFlow()) =
        InviteLinker(port, backgroundScope, updates, pollMs = 3_000)

    @Test
    fun `an invitation waits, is read every 3 s and shows the card when a device claims it`() = runTest {
        var current = details("pending")
        val port = FakeInvitePort(created("invite", pollSecret = null)) { current }
        val linker = invite(port)

        linker.start()
        runCurrent()
        val waiting = assertIs<InviteState.Waiting>(linker.state.value)
        assertEquals("K7QX-M2PD", waiting.userCode)
        assertTrue(port.gets.isEmpty(), "nothing to read the moment it is made")

        advanceTimeBy(3_001)
        assertEquals(1, port.gets.size)
        assertIs<InviteState.Waiting>(linker.state.value)

        current = details("claimed", listOf("12", "47", "85"))
        advanceTimeBy(3_000)
        val claimed = assertIs<InviteState.Claimed>(linker.state.value)
        assertEquals("Pixel 8", claimed.link.device?.name)
        assertEquals(listOf("12", "47", "85"), claimed.link.verifyChoices)
        assertEquals(listOf("link-1", "link-1"), port.gets)
    }

    @Test
    fun `the live event reads the invitation at once`() = runTest {
        var current = details("pending")
        val port = FakeInvitePort(created("invite", pollSecret = null)) { current }
        val updates = MutableSharedFlow<String>()
        val linker = invite(port, updates)
        linker.start()
        runCurrent()

        current = details("claimed", listOf("12", "47", "85"))
        // Another invitation of the account is none of ours
        updates.emit("link-other")
        runCurrent()
        assertTrue(port.gets.isEmpty())
        updates.emit("link-1")
        runCurrent()
        assertIs<InviteState.Claimed>(linker.state.value)
        assertEquals(1, port.gets.size)
    }

    @Test
    fun `a claimed invitation that the new device drops or that runs out says so`() = runTest {
        var current = details("claimed", listOf("12", "47", "85"))
        val port = FakeInvitePort(created("invite", pollSecret = null)) { current }
        val linker = invite(port)
        linker.start()
        runCurrent()
        advanceTimeBy(3_001)
        assertIs<InviteState.Claimed>(linker.state.value)

        current = details("cancelled")
        advanceTimeBy(3_000)
        assertEquals(InviteState.Failed(LinkFailure.Cancelled, started = true), linker.state.value)
        // Over: the reads stop and nothing is cancelled behind it
        val reads = port.gets.size
        advanceTimeBy(10_000)
        assertEquals(reads, port.gets.size)
        linker.cancel()
        runCurrent()
        assertTrue(port.cancelled.isEmpty())

        current = details("expired")
        linker.start()
        runCurrent()
        advanceTimeBy(3_001)
        assertEquals(InviteState.Failed(LinkFailure.Expired, started = true), linker.state.value)
    }

    @Test
    fun `an invitation survives a lost network and a busy server`() = runTest {
        var answer: () -> LinkDetails = { throw api(0, null) }
        val port = FakeInvitePort(created("invite", pollSecret = null)) { answer() }
        val linker = invite(port)
        linker.start()
        runCurrent()

        advanceTimeBy(3_001)
        assertIs<InviteState.Waiting>(linker.state.value)
        answer = { throw api(503, "server_busy") }
        advanceTimeBy(3_000)
        assertIs<InviteState.Waiting>(linker.state.value)
        answer = { throw api(404, "link_not_found") }
        advanceTimeBy(3_000)
        assertEquals(InviteState.Failed(LinkFailure.NotFound, started = true), linker.state.value)
    }

    @Test
    fun `leaving cancels an open invitation and a decision does not`() = runTest {
        val port = FakeInvitePort(created("invite", pollSecret = null)) { details("pending") }
        val linker = invite(port)
        linker.start()
        runCurrent()

        linker.cancel()
        runCurrent()
        assertEquals(InviteState.Idle, linker.state.value)
        assertEquals(listOf("link-1"), port.cancelled)

        // After approve or deny the screen releases it: the new device is finishing, not cancelled
        linker.start()
        runCurrent()
        linker.release()
        linker.cancel()
        runCurrent()
        assertEquals(1, port.cancelled.size)
    }

    @Test
    fun `an invitation that could not be made says why`() = runTest {
        val port = object : InvitePort {
            override suspend fun create(): LinkCreated = throw api(429, "rate_limited")

            override suspend fun get(linkId: String): LinkDetails = error("never")

            override suspend fun cancel(linkId: String) = Unit
        }
        val linker = InviteLinker(port, backgroundScope, MutableSharedFlow())
        linker.start()
        runCurrent()
        assertEquals(InviteState.Failed(LinkFailure.Throttled, started = false), linker.state.value)
    }

    // endregion

    @Test
    fun `codes and numbers read aloud and in a countdown`() {
        assertEquals("K, 7, Q, X, M, 2, P, D", spokenCode("K7QX-M2PD"))
        assertEquals("4:32", countdownText(4 * 60_000L + 32_000L))
        assertEquals("4:32", countdownText(4 * 60_000L + 31_001L), "rounded up, like the code counts it")
        assertEquals("0:01", countdownText(1))
        assertEquals("0:00", countdownText(0))
        assertEquals("0:00", countdownText(-5_000))
        assertEquals("15:00", countdownText(15 * 60_000L))
    }

    @Test
    fun `codes of the API map to failures`() {
        fun failure(status: Int, code: String?) = linkFailureOf(api(status, code))
        assertEquals(LinkFailure.Denied, failure(403, "link_denied"))
        assertEquals(LinkFailure.Denied, failure(409, "link_verify_mismatch"))
        assertEquals(LinkFailure.Expired, failure(410, "link_expired"))
        assertEquals(LinkFailure.Cancelled, failure(410, "link_cancelled"))
        assertEquals(LinkFailure.NotFound, failure(404, "link_not_found"))
        assertEquals(LinkFailure.AlreadyClaimed, failure(409, "link_already_claimed"))
        assertEquals(LinkFailure.WrongMode, failure(409, "link_wrong_mode"))
        assertEquals(LinkFailure.DeviceLimit, failure(409, "device_limit_reached"))
        assertEquals(LinkFailure.Throttled, failure(429, "rate_limited"))
        assertEquals(LinkFailure.Network, failure(0, null))
        assertEquals(LinkFailure.Unknown, failure(500, "internal_error"))
        assertEquals(LinkFailure.Unknown, linkFailureOf(IllegalStateException()))

        assertEquals(LinkFailure.Denied, linkStatusFailure("denied"))
        assertEquals(LinkFailure.Expired, linkStatusFailure("expired"))
        assertEquals(LinkFailure.Cancelled, linkStatusFailure("cancelled"))
        assertEquals(null, linkStatusFailure("pending"))
        assertEquals(null, linkStatusFailure("claimed"))
        assertEquals(null, linkStatusFailure("approved"))

        assertTrue(api(0, null).isTransient)
        assertTrue(api(429, "rate_limited").isTransient)
        assertTrue(api(503, "server_busy").isTransient)
        assertTrue(!api(410, "link_expired").isTransient)
        assertTrue(!api(403, "link_denied").isTransient)
    }

    @Test
    fun `the live event of a link names its link and nothing else does`() {
        val event = """{"id":"1f2e3d4c-5b6a-4978-8a7b-6c5d4e3f2a1b","type":"link.updated","at":"2026-09-30T08:00:00.200Z","payload":{"linkId":"5b0e7c1a-2d3e-4f5a-8b6c-7d8e9f0a1b2c","status":"claimed"}}"""
        assertEquals("5b0e7c1a-2d3e-4f5a-8b6c-7d8e9f0a1b2c", linkUpdatedId(event))
        assertEquals(null, linkUpdatedId(event.replace("link.updated", "devices.updated")))
        assertEquals(null, linkUpdatedId("""{"id":"x","type":"link.updated","at":"2026-09-30T08:00:00.200Z","payload":null}"""))
        assertEquals(null, linkUpdatedId("not json"))
    }
}
