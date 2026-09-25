package app.melogold.android.ui.screens.settings.account

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.DeviceDto
import app.melogold.android.sync.api.LinkDetails
import app.melogold.android.sync.api.LinkDeviceInfo
import app.melogold.android.sync.isoTime
import app.melogold.android.ui.screens.settings.SettingsEntryGroupText
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.core.ui.theme.MelogoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Add device" (tasks/0004) as it is drawn, without an account: the code step, the approval card with the three
 * numbers and "Deny", and the devices of the account with the icon of each kind. With `-Pmelogold.screenshots=<dir>`
 * the pictures are saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class AddDeviceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val watch = LinkDetails(
        linkId = "5b0e7c1a-2d3e-4f5a-8b6c-7d8e9f0a1b2c",
        mode = "request",
        status = "claimed",
        createdAt = System.currentTimeMillis().isoTime(),
        expiresAt = (System.currentTimeMillis() + 4 * 60_000L).isoTime(),
        device = LinkDeviceInfo(name = "Apple Watch Максима", platform = "watchos", osVersion = "watchOS 26.0", model = "Apple Watch Series 11"),
        sameNetwork = false,
        verifyChoices = listOf("12", "47", "85")
    )

    @Test
    fun `the code step lets a code through and nothing else`() {
        var code by mutableStateOf("")
        var continued = 0
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}, description = text(R.string.account_add_device_text)) {
                LinkCodeStep(code = code, onCodeChange = { code = it }, busy = false, error = null, onContinue = { continued++ })
            }
        }

        compose.onNodeWithTag("link_continue").assertIsNotEnabled()
        compose.onNodeWithTag("link_code").performTextInput("k7qx m2pd")
        assertEquals("K7QX M2PD", code, "typed in upper case")
        compose.onNodeWithTag("link_continue").assertIsEnabled().performClick()
        assertEquals(1, continued)
        shoot("add-device-code")

        compose.onNodeWithTag("link_code").performTextReplacement("K7QU-M2PD")
        compose.onNodeWithTag("link_continue").assertIsNotEnabled()
    }

    @Test
    fun `the approval card shows the device and sends the chosen number`() {
        var chosen: String? = null
        var denied = false
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}) {
                LinkApproval(
                    link = watch,
                    minutesLeft = 4,
                    busy = false,
                    error = null,
                    onChoose = { chosen = it },
                    onDeny = { denied = true }
                )
            }
        }

        compose.onNodeWithText("Apple Watch Максима").assertExists()
        compose.onNodeWithText("Apple Watch Series 11 · watchOS 26.0").assertExists()
        compose.onNodeWithText(text(R.string.account_add_device_other_network)).assertExists()
        compose.onNodeWithText(text(R.string.account_add_device_expires, 4)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.device_kind_watch)).assertExists()

        shoot("add-device-approve")

        compose.onNodeWithTag("link_choice_47").performClick()
        assertEquals("47", chosen)
        compose.onNodeWithTag("link_deny").performClick()
        assertTrue(denied)
    }

    @Test
    fun `a wrong number is said in words`() {
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}, description = text(R.string.account_add_device_text)) {
                LinkCodeStep(code = "", onCodeChange = {}, busy = false, error = R.string.account_error_link_mismatch, onContinue = {})
            }
        }
        compose.onNodeWithText("Число не совпало — вход отклонён").assertExists()
        shoot("add-device-mismatch")
    }

    @Test
    fun `every device of the account has the icon of its kind`() {
        val now = System.currentTimeMillis()
        fun device(name: String, platform: String, minutesAgo: Long, current: Boolean = false) = DeviceDto(
            id = name,
            name = name,
            platform = platform,
            createdAt = (now - 86_400_000L).isoTime(),
            lastSeenAt = (now - minutesAgo * 60_000L).isoTime(),
            isCurrent = current
        )
        val devices = listOf(
            device("Pixel 8", "android", 0, current = true),
            device("iPhone 17", "ios", 5),
            device("iPad Air", "ipados", 60),
            device("Apple Watch", "watchos", 2),
            device("Vision Pro", "visionos", 600),
            device("MacBook Air", "macos", 30),
            device("DESKTOP-7Q2", "windows", 1_440),
            device("Телевизор", "other", 4_000)
        )
        render {
            AccountPage(title = "maxim", onBack = {}) {
                SettingsEntryGroupText(title = text(R.string.account_devices_group))
                DevicesGroup(devices = devices, onRevoke = {}, onRevokeOthers = {}, onAddDevice = {})
            }
        }

        mapOf(
            R.string.device_kind_phone to 2,
            R.string.device_kind_tablet to 1,
            R.string.device_kind_watch to 1,
            R.string.device_kind_headset to 1,
            R.string.device_kind_computer to 2,
            R.string.device_kind_other to 1
        ).forEach { (kind, count) ->
            compose.onAllNodesWithContentDescription(text(kind), useUnmergedTree = true).assertCountEquals(count)
        }
        compose.onNodeWithTag("account_add_device").assertExists()
        shoot("account-devices")
    }

    @Test
    fun `link errors are the words of the task`() {
        fun error(code: String) = linkError(ApiException(409, code, null))
        assertEquals(R.string.account_error_link_not_found, error("link_not_found"))
        assertEquals(R.string.account_error_link_expired, error("link_expired"))
        assertEquals(R.string.account_error_link_expired, error("link_cancelled"))
        assertEquals(R.string.account_error_link_mismatch, error("link_verify_mismatch"))
        assertEquals(R.string.account_error_link_claimed, error("link_already_claimed"))
        assertEquals(R.string.account_error_link_wrong_mode, error("link_wrong_mode"))
        assertEquals(R.string.account_add_device_limit, error("device_limit_reached"))
        assertEquals(R.string.account_error_network, linkError(ApiException(0, null, "timeout")))
        assertEquals(0, minutesLeft(-1))
        assertEquals(1, minutesLeft(1))
        assertEquals(5, minutesLeft(4 * 60_000L + 1))
    }

    @Test
    fun `a code typed again after its link is over stays on the code step`() {
        assertEquals(null, resolvedLinkError(watch))
        // What resolve gives for a link that is over: no device, no network, no numbers
        fun over(status: String) =
            resolvedLinkError(watch.copy(status = status, device = null, sameNetwork = null, verifyChoices = emptyList()))
        listOf("denied", "expired", "cancelled").forEach { assertEquals(R.string.account_error_link_expired, over(it), it) }
        listOf("approved", "completed").forEach { assertEquals(R.string.account_error_link_used, over(it), it) }
        assertEquals(R.string.account_error_link_expired, resolvedLinkError(watch.copy(verifyChoices = emptyList())))
        assertEquals("Этот код уже использован", text(R.string.account_error_link_used))
    }

    private fun text(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    /** Renders [content] in the Melogold theme. */
    private fun render(content: @Composable () -> Unit) = compose.setContent {
        MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0)) { content() }
        }
    }

    /** Saves what is on the screen as [name].png when a folder is given. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
