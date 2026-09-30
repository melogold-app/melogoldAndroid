package app.melogold.android.ui.screens.settings.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.AccountLinkPort
import app.melogold.android.sync.LinkFailure
import app.melogold.android.sync.NewDeviceLinkState
import app.melogold.android.sync.NewDeviceLinker
import app.melogold.android.sync.countdownText
import app.melogold.android.sync.spokenCode
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.MelogoldLoadingIndicator
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.kit.deviceIcon
import app.melogold.android.ui.kit.deviceKindName
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.compose.routing.Route0
import app.melogold.compose.routing.RouteHandler
import app.melogold.domain.server.UserCode
import kotlinx.coroutines.delay

val signInByCodeRoute = Route0("signInByCodeRoute")

/** How often the countdown of a code is counted again. */
private const val COUNTDOWN_TICK_MS = 1_000L

/** The current time, read again every [intervalMs]: the countdown of a code. */
@Composable
internal fun rememberNow(intervalMs: Long = COUNTDOWN_TICK_MS): Long {
    val now by produceState(System.currentTimeMillis(), intervalMs) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * What went wrong on the new device, in words. [claiming]: the code was typed here (an invitation of a signed-in
 * device), otherwise this device showed its own.
 */
@StringRes
internal fun newDeviceLinkError(failure: LinkFailure, claiming: Boolean): Int = when (failure) {
    LinkFailure.Denied -> R.string.link_error_denied
    LinkFailure.Expired -> if (claiming) R.string.link_error_claim_expired else R.string.link_error_expired
    LinkFailure.Cancelled -> R.string.link_error_cancelled
    LinkFailure.DeviceLimit -> R.string.account_error_device_limit
    LinkFailure.NotFound -> R.string.account_error_link_not_found
    LinkFailure.AlreadyClaimed -> R.string.account_error_link_claimed
    LinkFailure.WrongMode -> R.string.link_error_wrong_mode_claim
    LinkFailure.Throttled -> R.string.account_error_throttled
    LinkFailure.Network -> R.string.account_error_network
    LinkFailure.Unknown -> R.string.account_error_unknown
}

/**
 * "Sign in with a code" on the new device (tasks/0015, API §4.6): this device shows a code `K7QX-M2PD` to enter on a
 * device that is signed in (mode `request`), then the number to choose there; or the code a signed-in device shows is
 * typed here (mode `invite`). At the end the session is taken as after a password sign-in and both sign-in screens close.
 * Leaving the screen gives the link up on the server.
 */
@Route
@Composable
fun SignInByCodeScreen(startClaiming: Boolean = false) = RouteHandler {
    GlobalRoutes()

    Content {
        val container = LocalAppContainer.current
        val linker = remember { NewDeviceLinker(AccountLinkPort(container.account), container.appScope) }
        val state by linker.state.collectAsState()
        // Typing the code of another device instead of showing this one's
        var claiming by rememberSaveable { mutableStateOf(startClaiming) }
        var input by rememberSaveable { mutableStateOf("") }

        DisposableEffect(linker) { onDispose { linker.cancel() } }
        LaunchedEffect(linker) { if (!claiming) linker.showCode() }
        // The session is taken: this screen and the sign-in under it are done (that one closes itself)
        LaunchedEffect(state) { if (state is NewDeviceLinkState.SignedIn) pop() }

        fun showCode() {
            claiming = false
            linker.showCode()
        }

        fun typeCode() {
            claiming = true
            linker.cancel()
        }

        fun submitCode() {
            val userCode = UserCode.normalize(input) ?: return
            input = userCode
            linker.claim(userCode)
        }

        val current = state
        val failed = current as? NewDeviceLinkState.Failed
        // A code typed here: the field, until the server takes it; a refused one comes back to it with the reason
        val fieldStep = claiming && (
            current is NewDeviceLinkState.Idle || current is NewDeviceLinkState.Starting || (failed != null && !failed.started)
            )

        AccountPage(
            title = stringResource(R.string.link_code_title),
            onBack = pop,
            description = when {
                current is NewDeviceLinkState.ShowingCode -> stringResource(R.string.link_code_text)
                fieldStep -> stringResource(R.string.link_claim_text)
                else -> null
            }
        ) {
            when {
                current is NewDeviceLinkState.Verify -> LinkVerifyPanel(
                    link = current,
                    remainingMs = current.expiresAt - rememberNow(),
                    onCancel = pop
                )

                current is NewDeviceLinkState.SignedIn -> LinkWorking(text = stringResource(R.string.link_signing_in))

                fieldStep -> LinkCodeStep(
                    code = input,
                    onCodeChange = { input = it },
                    busy = current is NewDeviceLinkState.Starting,
                    error = failed?.let { newDeviceLinkError(it.failure, claiming = true) },
                    onContinue = ::submitCode,
                    secondaryText = stringResource(R.string.link_show_my_code),
                    secondaryTag = "link_show_my_code",
                    onSecondary = ::showCode
                )

                current is NewDeviceLinkState.ShowingCode -> LinkCodePanel(
                    userCode = current.userCode,
                    remainingMs = current.expiresAt - rememberNow(),
                    reconnecting = current.reconnecting,
                    onCancel = pop,
                    onHaveCode = ::typeCode
                )

                failed != null -> LinkNotice(
                    text = stringResource(newDeviceLinkError(failed.failure, claiming)),
                    primary = if (claiming) stringResource(R.string.link_enter_other_code)
                    else stringResource(if (failed.started) R.string.link_new_code else R.string.link_retry),
                    onPrimary = if (claiming) ::typeCode else ::showCode,
                    secondary = stringResource(R.string.link_back_to_password),
                    onSecondary = pop
                )

                else -> LinkWorking(text = stringResource(R.string.link_starting))
            }
        }
    }
}

/**
 * The code to type on the other device, big and readable (`K7QX-M2PD`), with how long it lives. Shared by the new
 * device (mode `request`) and the signed-in one that invites (mode `invite`).
 */
@Composable
internal fun LinkBigCode(userCode: String, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier = modifier.fillMaxWidth()
) {
    val spoken = stringResource(R.string.link_code_description, spokenCode(userCode))
    Text(
        // A line breaks only at the dash
        text = userCode.replace("-", "-​"),
        style = MaterialTheme.typography.displayMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 32.dp)
            .semantics { contentDescription = spoken }
            .testTag("link_user_code")
    )
}

/** "Valid for 4:32". */
@Composable
internal fun LinkCountdown(remainingMs: Long, modifier: Modifier = Modifier) = Text(
    text = stringResource(R.string.link_valid_for, countdownText(remainingMs)),
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = modifier
        .fillMaxWidth()
        .testTag("link_countdown")
)

/** Shown while the server cannot be reached and the code is still asked for. */
@Composable
internal fun LinkReconnecting() = Text(
    text = stringResource(R.string.link_reconnecting),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.error,
    textAlign = TextAlign.Center,
    modifier = Modifier
        .fillMaxWidth()
        .testTag("link_reconnecting")
)

/** The new device's code (mode `request`): what to do with it is the description of the page. */
@Composable
internal fun LinkCodePanel(
    userCode: String,
    remainingMs: Long,
    reconnecting: Boolean,
    onCancel: () -> Unit,
    onHaveCode: () -> Unit
) = Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
    LinkBigCode(userCode)
    LinkCountdown(remainingMs)
    if (reconnecting) LinkReconnecting() else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("link_cancel")) {
        Text(text = stringResource(R.string.cancel))
    }
    TextButton(onClick = onHaveCode, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("link_have_code")) {
        Text(text = stringResource(R.string.link_have_code))
    }
}

/**
 * The number to choose on the approving device, huge, with its name, and for which account (both modes reach it once
 * the other side has the code).
 */
@Composable
internal fun LinkVerifyPanel(link: NewDeviceLinkState.Verify, remainingMs: Long, onCancel: () -> Unit) = Column(
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier.padding(horizontal = 4.dp)
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(
            icon = deviceIcon(link.approverPlatform),
            shape = IconShape.Circle,
            contentDescription = stringResource(deviceKindName(link.approverPlatform)),
            size = 56.dp
        )
        Text(
            text = stringResource(R.string.link_verify_pick, link.approverName),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("link_verify_pick")
        )
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        val spoken = stringResource(R.string.link_verify_number_description, link.verifyCode)
        Text(
            text = link.verifyCode,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(vertical = 32.dp)
                .semantics { contentDescription = spoken }
                .testTag("link_verify_number")
        )
    }
    Text(
        text = stringResource(R.string.link_verify_account, link.login),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("link_verify_account")
    )
    LinkCountdown(remainingMs)
    if (link.reconnecting) LinkReconnecting() else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("link_cancel")) {
        Text(text = stringResource(R.string.cancel))
    }
}

/** The link is over: the reason, what to do next ([primary]) and the way out ([secondary]). */
@Composable
internal fun LinkNotice(text: String, primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit) = Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.padding(horizontal = 4.dp)
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("link_notice")
    )
    ProgressButton(text = primary, busy = false, enabled = true, onClick = onPrimary, modifier = Modifier.testTag("link_primary"))
    if (secondary != null) TextButton(onClick = onSecondary, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("link_secondary")) {
        Text(text = secondary)
    }
}

/** A code is being asked for, or the session taken. */
@Composable
internal fun LinkWorking(text: String) = Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 48.dp)
) {
    Box(contentAlignment = Alignment.Center) { MelogoldLoadingIndicator() }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
