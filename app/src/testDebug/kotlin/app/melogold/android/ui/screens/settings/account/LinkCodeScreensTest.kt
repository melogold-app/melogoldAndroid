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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import app.melogold.android.sync.LinkFailure
import app.melogold.android.sync.NewDeviceLinkState
import app.melogold.android.sync.api.LinkDetails
import app.melogold.android.sync.api.LinkDeviceInfo
import app.melogold.android.sync.isoTime
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sign-in by code as it is drawn (tasks/0015), without a server: the new device's code and its number, the refusals,
 * the field for a code typed from another device, and the signed-in device's own code with the approval card. With
 * `-Pmelogold.screenshots=<dir>` the pictures are saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class LinkCodeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val verify = NewDeviceLinkState.Verify(
        verifyCode = "47",
        login = "maxim",
        approverName = "MacBook Air",
        approverPlatform = "macos",
        expiresAt = System.currentTimeMillis() + 4 * 60_000L + 32_000L
    )

    private fun remaining(minutes: Long, seconds: Long) = minutes * 60_000L + seconds * 1_000L

    @Test
    fun `the new device shows its code big, with the time left and one instruction`() {
        var cancelled = 0
        var haveCode = 0
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}, description = text(R.string.link_code_text)) {
                LinkCodePanel(
                    userCode = "K7QX-M2PD",
                    remainingMs = remaining(4, 32),
                    reconnecting = false,
                    onCancel = { cancelled++ },
                    onHaveCode = { haveCode++ }
                )
            }
        }

        compose.onNodeWithText("Вход по коду").assertExists()
        compose.onNodeWithText(text(R.string.link_code_text)).assertExists()
        compose.onNodeWithText("Действует 4:32").assertExists()
        // TalkBack reads the characters one by one, not "kayseven-qex"
        compose.onNodeWithTag("link_user_code").assertContentDescriptionEquals("Код для входа: K, 7, Q, X, M, 2, P, D")
        compose.onNodeWithContentDescription("Код для входа: K, 7, Q, X, M, 2, P, D").assertExists()
        assertEquals("K7QX-M2PD", codeOnScreen())
        compose.onNodeWithTag("link_reconnecting").assertDoesNotExist()
        shoot("link-code")

        compose.onNodeWithTag("link_cancel").performClick()
        compose.onNodeWithTag("link_have_code").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, haveCode)
    }

    @Test
    fun `without the server the code stays and says it is trying again`() {
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}, description = text(R.string.link_code_text)) {
                LinkCodePanel(
                    userCode = "K7QX-M2PD",
                    remainingMs = remaining(2, 5),
                    reconnecting = true,
                    onCancel = {},
                    onHaveCode = {}
                )
            }
        }
        compose.onNodeWithText("Нет связи с сервером. Пробуем снова…").assertExists()
        assertEquals("K7QX-M2PD", codeOnScreen())
        compose.onNodeWithText("Действует 2:05").assertExists()
        shoot("link-code-reconnecting")
    }

    @Test
    fun `the number is huge, with the account and the device that approves`() {
        var cancelled = 0
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}) {
                LinkVerifyPanel(link = verify, remainingMs = remaining(4, 32), onCancel = { cancelled++ })
            }
        }

        compose.onNodeWithText("Выберите это число на «MacBook Air»").assertExists()
        compose.onNodeWithText("47").assertExists()
        compose.onNodeWithTag("link_verify_number").assertContentDescriptionEquals("Число 47")
        compose.onNodeWithText("Вход в аккаунт maxim").assertExists()
        compose.onNodeWithText("Действует 4:32").assertExists()
        compose.onNodeWithContentDescription(text(R.string.device_kind_computer)).assertExists()
        shoot("link-verify")

        compose.onNodeWithTag("link_cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun `the same screens in English`() {
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}) {
                LinkVerifyPanel(link = verify.copy(approverName = "Pixel 8", approverPlatform = "android"), remainingMs = remaining(1, 1), onCancel = {})
            }
        }
        compose.onNodeWithText("Sign in with a code").assertExists()
        compose.onNodeWithText("Choose this number on “Pixel 8”").assertExists()
        compose.onNodeWithText("Signing in to maxim").assertExists()
        compose.onNodeWithText("Valid for 1:01").assertExists()
        compose.onNodeWithTag("link_verify_number").assertContentDescriptionEquals("Number 47")
        compose.onNodeWithContentDescription("Phone").assertExists()
        shoot("link-verify-en")
    }

    @Test
    fun `a code typed from another device has a field and a way back to this device's code`() {
        var code by mutableStateOf("")
        var shown = 0
        var continued = 0
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}, description = text(R.string.link_claim_text)) {
                LinkCodeStep(
                    code = code,
                    onCodeChange = { code = it },
                    busy = false,
                    error = null,
                    onContinue = { continued++ },
                    secondaryText = text(R.string.link_show_my_code),
                    secondaryTag = "link_show_my_code",
                    onSecondary = { shown++ }
                )
            }
        }

        compose.onNodeWithText(text(R.string.link_claim_text)).assertExists()
        compose.onNodeWithTag("link_code").performTextInput("k7qx m2pd")
        compose.onNodeWithTag("link_continue").assertIsEnabled().performClick()
        assertEquals(1, continued)
        compose.onNodeWithTag("link_show_my_code").performClick()
        assertEquals(1, shown)
        shoot("link-claim")
    }

    @Test
    fun `a typed code the server refuses is said in words`() {
        val words = mapOf(
            LinkFailure.NotFound to "Код не найден",
            LinkFailure.AlreadyClaimed to "Этот код уже используется",
            LinkFailure.Expired to "Код устарел. Покажите новый на устройстве, где вы уже вошли",
            LinkFailure.WrongMode to "Это код нового устройства. Введите его там, где вы уже вошли: Аккаунт › Добавить устройство",
            LinkFailure.Network to "Нет связи с сервером",
            LinkFailure.DeviceLimit to text(R.string.account_error_device_limit)
        )
        var failure by mutableStateOf(LinkFailure.NotFound)
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}, description = text(R.string.link_claim_text)) {
                LinkCodeStep(
                    code = "K7QX-M2PD",
                    onCodeChange = {},
                    busy = false,
                    error = newDeviceLinkError(failure, claiming = true),
                    onContinue = {}
                )
            }
        }
        words.forEach { (reason, sentence) ->
            failure = reason
            compose.waitForIdle()
            compose.onNodeWithText(sentence).assertExists("$reason: $sentence")
        }
        failure = LinkFailure.WrongMode
        compose.waitForIdle()
        shoot("link-claim-wrong-mode")
    }

    @Test
    fun `when the link is over the reason and the next step are said`() {
        var next = 0
        var out = 0
        render {
            AccountPage(title = text(R.string.link_code_title), onBack = {}) {
                LinkNotice(
                    text = text(newDeviceLinkError(LinkFailure.Denied, claiming = false)),
                    primary = text(R.string.link_new_code),
                    onPrimary = { next++ },
                    secondary = text(R.string.link_back_to_password),
                    onSecondary = { out++ }
                )
            }
        }
        compose.onNodeWithText("Вход отклонён на другом устройстве").assertExists()
        compose.onNodeWithTag("link_primary").performClick()
        compose.onNodeWithTag("link_secondary").performClick()
        assertEquals(1, next)
        assertEquals(1, out)
        shoot("link-denied")
    }

    @Test
    fun `every failure has a text on either mode and the words differ where the mode matters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LinkFailure.entries.forEach { failure ->
            listOf(true, false).forEach { claiming ->
                val id = newDeviceLinkError(failure, claiming)
                assertTrue(context.getString(id).isNotBlank(), "$failure $claiming")
            }
        }
        assertEquals(R.string.link_error_expired, newDeviceLinkError(LinkFailure.Expired, claiming = false))
        assertEquals(R.string.link_error_claim_expired, newDeviceLinkError(LinkFailure.Expired, claiming = true))
        assertEquals(R.string.link_error_denied, newDeviceLinkError(LinkFailure.Denied, claiming = false))
        assertEquals(R.string.link_error_cancelled, newDeviceLinkError(LinkFailure.Cancelled, claiming = true))
        assertEquals(R.string.account_error_device_limit, newDeviceLinkError(LinkFailure.DeviceLimit, claiming = false))
        assertEquals(R.string.account_error_network, newDeviceLinkError(LinkFailure.Network, claiming = true))
        assertNotEquals(R.string.link_error_expired, R.string.link_error_claim_expired)
        // The signed-in device says it its own way
        LinkFailure.entries.forEach { assertTrue(context.getString(inviteError(it)).isNotBlank(), "$it") }
        assertEquals(R.string.account_invite_expired, inviteError(LinkFailure.Expired))
        assertEquals(R.string.account_invite_cancelled, inviteError(LinkFailure.Cancelled))
    }

    @Test
    fun `this device shows its own code for a new device`() {
        var cancelled = 0
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}, description = text(R.string.account_invite_text)) {
                InviteWaiting(userCode = "K7QX-M2PD", remainingMs = remaining(4, 59), onCancel = { cancelled++ })
            }
        }
        compose.onNodeWithText(text(R.string.account_invite_text)).assertExists()
        compose.onNodeWithText("Действует 4:59").assertExists()
        compose.onNodeWithText("Ждём новое устройство…").assertExists()
        compose.onNodeWithTag("link_user_code").assertContentDescriptionEquals("Код для входа: K, 7, Q, X, M, 2, P, D")
        assertEquals("K7QX-M2PD", codeOnScreen())
        shoot("invite-waiting")
        compose.onNodeWithTag("link_cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `the code step of Add device offers the code for a new device`() {
        var invited = 0
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}, description = text(R.string.account_add_device_text)) {
                LinkCodeStep(
                    code = "",
                    onCodeChange = {},
                    busy = false,
                    error = R.string.account_invite_expired,
                    onContinue = {},
                    secondaryText = text(R.string.account_add_device_show_code),
                    secondaryTag = "link_show_code",
                    onSecondary = { invited++ }
                )
            }
        }
        compose.onNodeWithText("Показать код для нового устройства").assertExists()
        compose.onNodeWithText("Код устарел. Покажите новый").assertExists()
        shoot("add-device-with-invite")
        compose.onNodeWithTag("link_show_code").performClick()
        assertEquals(1, invited)
    }

    @Test
    fun `a new device that typed the invitation gets the same approval card as for its own code`() {
        val claimed = LinkDetails(
            linkId = "5b0e7c1a-2d3e-4f5a-8b6c-7d8e9f0a1b2c",
            mode = "invite",
            status = "claimed",
            createdAt = System.currentTimeMillis().isoTime(),
            expiresAt = (System.currentTimeMillis() + 4 * 60_000L).isoTime(),
            device = LinkDeviceInfo(name = "Google Pixel 8", platform = "android", osVersion = "16", model = "Google Pixel 8"),
            sameNetwork = true,
            verifyChoices = listOf("12", "47", "85")
        )
        var chosen: String? = null
        var denied = 0
        render {
            AccountPage(title = text(R.string.account_add_device), onBack = {}) {
                LinkApproval(link = claimed, minutesLeft = 4, busy = false, error = null, onChoose = { chosen = it }, onDeny = { denied++ })
            }
        }
        compose.onNodeWithText("Google Pixel 8").assertExists()
        compose.onNodeWithText("Google Pixel 8 · 16").assertExists()
        compose.onNodeWithText(text(R.string.account_add_device_same_network)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.device_kind_phone)).assertExists()
        shoot("invite-approve")
        compose.onNodeWithTag("link_choice_85").performClick()
        assertEquals("85", chosen)
        compose.onNodeWithTag("link_deny").performClick()
        assertEquals(1, denied)
    }

    /** The code as it is written on the screen, without the invisible break after the dash. */
    private fun codeOnScreen(): String {
        val node = compose.onNodeWithTag("link_user_code").fetchSemanticsNode()
        val text = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
        return text?.joinToString("") { it.text }.orEmpty().replace("​", "")
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
