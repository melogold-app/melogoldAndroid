package app.melogold.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.sync.api.ApiException
import app.melogold.domain.server.UserCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * "Add device" against a real Melogold server given by `-Pmelogold.testServer=http://127.0.0.1:8787` (tasks/0004,
 * API §4.6 mode `request`). A throwaway account is registered through [Account] (with its proof of work); a watch
 * asks to sign in with raw HTTP, as the Apple client does; this app resolves its code, chooses the number the watch
 * shows, and the watch gets its session. A wrong number and "Deny" refuse. The account is deleted at the end.
 */
@RunWith(RobolectricTestRunner::class)
class LinkDeviceLiveTest {
    private val server = System.getProperty("melogold.testServer")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Test
    fun `a watch signs in by the code it shows`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val url = server!!
        val account = Account(ApplicationProvider.getApplicationContext<Context>())
        account.setServer(url)
        val password = "throwaway ${hex(12)}"
        account.register(login = "link${hex(6)}", password = password)

        try {
            // The watch shows its code; typed here as a person would
            val watch = watchAsks(url, "Apple Watch")
            val typed = watch.userCode.lowercase().replace("-", " ")
            val link = account.resolveLink(assertNotNull(UserCode.normalize(typed)))
            assertEquals("claimed", link.status)
            assertEquals("watchos", link.device?.platform)
            assertEquals("Apple Watch", link.device?.name)
            assertEquals(3, link.verifyChoices.size)

            // The watch shows the number; the same one is among the three here
            val shown = poll(url, watch.pollSecret, knownStatus = "pending", until = "claimed")
            val verifyCode = shown.string("verifyCode")
            assertTrue(verifyCode in link.verifyChoices, "$verifyCode in ${link.verifyChoices}")

            assertEquals("approved", account.approveLink(link.linkId, verifyCode).status)
            val completed = poll(url, watch.pollSecret, knownStatus = "claimed", until = "completed")
            assertNotNull(completed["session"], "the watch has its session")
            assertTrue(account.devices().any { it.platform == "watchos" && it.name == "Apple Watch" }, "the watch is a device of the account")

            // Another number than the one on the screen: a refusal
            val stranger = watchAsks(url, "Apple Watch 2")
            val strangerLink = account.resolveLink(stranger.userCode)
            val strangerCode = poll(url, stranger.pollSecret, knownStatus = "pending", until = "claimed").string("verifyCode")
            val wrong = strangerLink.verifyChoices.first { it != strangerCode }
            val mismatch = runCatching { account.approveLink(strangerLink.linkId, wrong) }.exceptionOrNull()
            assertEquals("link_verify_mismatch", (mismatch as? ApiException)?.code, "$mismatch")

            // "Deny"
            val denied = watchAsks(url, "Apple Watch 3")
            assertEquals("denied", account.denyLink(account.resolveLink(denied.userCode).linkId).status)
            assertTrue(account.devices().none { it.name == "Apple Watch 2" || it.name == "Apple Watch 3" })
        } finally {
            val token = account.session?.accessToken
            if (token != null) request(url, "/auth/me/delete", JsonObject(mapOf("password" to JsonPrimitive(password))).toString(), token)
        }
    }

    private class Asked(val userCode: String, val pollSecret: String)

    /** The watch's side: `POST /auth/link/requests` (API §4.6 step 1). */
    private fun watchAsks(url: String, name: String): Asked {
        val body = """{"device":{"hwid":"${hex(32)}","name":"$name","platform":"watchos","osVersion":"26.0","model":"Watch7,1","clientVersion":"0.1.0"}}"""
        val created = request(url, "/auth/link/requests", body)
        return Asked(userCode = created.string("userCode"), pollSecret = created.string("pollSecret"))
    }

    /** The watch waits for [until] (API §4.6 steps 3 and 5): the server answers at once when the status moved. */
    private fun poll(url: String, pollSecret: String, knownStatus: String, until: String): JsonObject {
        repeat(POLL_TRIES) {
            val answer = request(url, "/auth/link/poll", """{"pollSecret":"$pollSecret","knownStatus":"$knownStatus","waitSeconds":5}""")
            if (answer.string("status") == until) return answer
        }
        fail("the link never came to $until")
    }

    private fun request(url: String, path: String, body: String, token: String? = null): JsonObject {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(status < 400) { "$path: HTTP $status $text" }
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val POLL_TRIES = 6
        const val TIMEOUT_MS = 35_000
    }
}
