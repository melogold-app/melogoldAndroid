package app.melogold.android.ui.screens.settings.account

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.AppContainer
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.sync.AccountState
import app.melogold.android.sync.api.ClaimLinkRequest
import app.melogold.android.sync.api.DeviceInput
import app.melogold.android.sync.api.LinkPollResponse
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.PollLinkRequest
import app.melogold.android.sync.api.RegisterRequest
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.rememberAppSnackbar
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.compose.routing.RouteHandler
import app.melogold.core.ui.theme.MelogoldTheme
import app.melogold.domain.server.UserCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The owner's complaint was "I never saw it": this drives the real screens against a real Melogold server given by
 * `-Pmelogold.testServer=http://127.0.0.1:8787`, from the Settings card as a person would. The app is the new device:
 * Sign in › "Sign in with a code" shows a code; a signed-in MacBook (played through [MelogoldApi]) types it and the
 * number on screen is chosen there. Then the app is signed in: Account › Add device › "Show a code for a new device";
 * a new Pixel claims it and this device approves it on the same card as for a code typed here. With
 * `-Pmelogold.screenshots=<dir>` the screens are saved as they were drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class SignInByCodeScreenLiveTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = System.getProperty("melogold.testServer")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val random = SecureRandom()

    @Test
    fun `from the Settings card - sign in by a code shown here, then show a code for a new device`() {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val url = server!!
        val login = "link${hex(6)}"
        val password = "throwaway ${hex(12)}"
        val macbook = MelogoldApi(url)
        val pixel = MelogoldApi(url)
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        container.account.setServer(url)
        var token = ""

        try {
            token = runBlocking(Dispatchers.IO) {
                macbook.register(
                    RegisterRequest(login, password, DeviceInput(hex(32), "MacBook Air", "macos", osVersion = "26.0"))
                ).tokens.accessToken
            }

            compose.setContent {
                MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalAppSnackbar provides rememberAppSnackbar(),
                        LocalPlayerAwareWindowInsets provides WindowInsets(0)
                    ) {
                        // A stand-in for the Settings root: its account card and the routes of the real stack
                        RouteHandler {
                            AccountRoutes()
                            Content { AccountCard() }
                        }
                    }
                }
            }

            // Settings card › Sign in › "Sign in with a code"
            compose.onNodeWithTag("account_sign_in").performClick()
            compose.waitUntil(WAIT_MS) { has("account_sign_in_by_code") }
            shoot("ui-signin-password")
            compose.onNodeWithTag("account_sign_in_by_code").performClick()

            // The code is on the screen, big
            compose.waitUntil(WAIT_MS) { has("link_user_code") }
            val userCode = textOf("link_user_code").replace("​", "")
            assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(userCode), userCode)
            shoot("ui-signin-code")

            // A signed-in device types it (as a person would, in lower case) and gets the card
            val details = runBlocking(Dispatchers.IO) {
                macbook.resolveLink(token, assertNotNull(UserCode.normalize(userCode.lowercase().replace("-", " "))))
            }
            assertEquals("claimed", details.status)

            // The screen turns to the number to choose there
            compose.waitUntil(WAIT_MS) { has("link_verify_number") }
            val number = textOf("link_verify_number")
            assertTrue(number in details.verifyChoices, "$number in ${details.verifyChoices}")
            compose.onAllNodesWithText("Вход в аккаунт $login").assertCountEquals(1)
            shoot("ui-signin-verify")
            runBlocking(Dispatchers.IO) { macbook.approveLink(token, details.linkId, number) }

            // Signed in: both sign-in screens are gone, the card says who
            compose.waitUntil(WAIT_MS) { container.account.state.value is AccountState.SignedIn }
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Вы вошли как $login").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(login, container.account.session?.login)
            shoot("ui-signed-in-card")

            // Account › Add device › "Show a code for a new device"
            compose.onNodeWithTag("account_card").performClick()
            compose.waitUntil(WAIT_MS) { has("account_add_device") }
            compose.onNodeWithTag("account_add_device").performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { has("link_show_code") }
            compose.onNodeWithTag("link_show_code").performClick()
            compose.waitUntil(WAIT_MS) { has("link_user_code") }
            val inviteCode = textOf("link_user_code").replace("​", "")
            assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(inviteCode), inviteCode)
            shoot("ui-invite-code")

            // A new device types it and gets the number to show
            val claimed = runBlocking(Dispatchers.IO) {
                pixel.claimLink(
                    ClaimLinkRequest(
                        userCode = assertNotNull(UserCode.normalize(inviteCode.lowercase().replace("-", " "))),
                        device = DeviceInput(hex(32), "Google Pixel 8", "android", osVersion = "16", model = "Google Pixel 8")
                    )
                )
            }

            // The invitation is read every 3 s here (no live stream in a test): the card of the device, three numbers
            compose.waitUntil(WAIT_MS) { has("link_choice_${claimed.verifyCode}") }
            compose.onAllNodesWithText("Google Pixel 8").assertCountEquals(1)
            shoot("ui-invite-approve")
            compose.onNodeWithTag("link_choice_${claimed.verifyCode}").performClick()

            // Approved: the new device gets its session
            val done = runBlocking(Dispatchers.IO) { pixel.pollUntil(claimed.pollSecret, known = "claimed", until = "completed") }
            assertEquals(login, done.session?.user?.login)
            // ...and this screen is done: back on the account, where the device list is
            compose.waitUntil(WAIT_MS) { !has("link_choice_${claimed.verifyCode}") && has("account_add_device") }
        } finally {
            macbook.close()
            pixel.close()
            if (token.isNotEmpty()) runCatching { post(url, "/auth/me/delete", """{"password":"$password"}""", token) }
        }
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The text a node with [tag] shows. */
    private fun textOf(tag: String): String {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private suspend fun MelogoldApi.pollUntil(pollSecret: String, known: String, until: String): LinkPollResponse {
        repeat(POLL_TRIES) {
            val answer = pollLink(PollLinkRequest(pollSecret, waitSeconds = 5, knownStatus = known))
            if (answer.status == until) return answer
        }
        fail("the link never came to $until")
    }

    private fun post(url: String, path: String, body: String, token: String) {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Saves what is on the screen as [name].png when a folder is given. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val POLL_TRIES = 6
    }
}
