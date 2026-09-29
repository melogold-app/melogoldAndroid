package app.melogold.android.ui.screens.settings.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.AccountInvitePort
import app.melogold.android.sync.InviteLinker
import app.melogold.android.sync.InviteState
import app.melogold.android.sync.LinkFailure
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.LinkDetails
import app.melogold.android.sync.epochMs
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.kit.deviceIcon
import app.melogold.android.ui.kit.deviceKindName
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.settings.SettingsEntryGroupText
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.compose.routing.Route0
import app.melogold.compose.routing.RouteHandler
import app.melogold.domain.server.UserCode
import kotlinx.coroutines.launch

val addDeviceRoute = Route0("addDeviceRoute")

/** Long enough for a code typed with spaces or dashes between every pair (`K7 QX M2 PD`). */
private const val CODE_INPUT_MAX = 16

/** How often "The code is valid for N more min" is counted again. */
private const val EXPIRY_TICK_MS = 10_000L

private const val MINUTE_MS = 60_000L

/** After these the link is over: back to the code, with the reason under the field. */
private val LINK_OVER = setOf("link_expired", "link_cancelled", "link_not_claimed", "link_verify_mismatch")

/** What went wrong with a code or a number (API §2.2), in the words of tasks/0004. */
@StringRes
internal fun linkError(error: Throwable): Int = when ((error as? ApiException)?.code) {
    "link_not_found" -> R.string.account_error_link_not_found
    "link_expired", "link_cancelled", "link_not_claimed" -> R.string.account_error_link_expired
    "link_verify_mismatch" -> R.string.account_error_link_mismatch
    "link_already_claimed" -> R.string.account_error_link_claimed
    "link_wrong_mode" -> R.string.account_error_link_wrong_mode
    "device_limit_reached" -> R.string.account_add_device_limit
    else -> accountError(error)
}

/**
 * Why a link that `resolve` gave cannot be approved; null while it waits at `claimed` with its three numbers. A code
 * typed again after its link is over still resolves (API §4.6): denied after a wrong number, expired, already used.
 */
@StringRes
internal fun resolvedLinkError(link: LinkDetails): Int? = when {
    link.status == "claimed" && link.verifyChoices.isNotEmpty() -> null
    link.status == "approved" || link.status == "completed" -> R.string.account_error_link_used
    else -> R.string.account_error_link_expired
}

/**
 * "Add device" (tasks/0004, API §4.6): a new device where a password is hard to type, like the watch, shows a code
 * `K7QX-M2PD` (mode `request`). Here it is typed in, the device that asks to sign in is shown, and the number on its
 * screen is chosen from three. A wrong number is a refusal on the server.
 *
 * Or this device shows the code (tasks/0015, mode `invite`): "Show a code for a new device". The new device types it
 * and shows a number; then the same card as above with the three numbers.
 */
@Route
@Composable
fun AddDeviceScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val container = LocalAppContainer.current
        val account = container.account
        val snackbar = LocalAppSnackbar.current
        val scope = rememberCoroutineScope()
        var input by rememberSaveable { mutableStateOf("") }
        var link by remember { mutableStateOf<LinkDetails?>(null) }
        // The invitation of this device (mode `invite`): followed by the live event, else every 3 s
        val invite = remember { InviteLinker(AccountInvitePort(account), container.appScope, container.sync.linkUpdated) }
        val inviteState by invite.state.collectAsState()
        DisposableEffect(invite) { onDispose { invite.cancel() } }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        val signedIn = stringResource(R.string.account_add_device_done)
        val denied = stringResource(R.string.account_add_device_denied)
        val someDevice = stringResource(R.string.device_kind_other)
        val deviceLimit = stringResource(R.string.account_add_device_limit)

        fun resolve() {
            val userCode = UserCode.normalize(input) ?: return
            if (busy) return
            invite.cancel()
            input = userCode
            busy = true
            error = null
            scope.launch {
                runCatching { account.resolveLink(userCode) }
                    .onSuccess { details ->
                        when (val over = resolvedLinkError(details)) {
                            null -> link = details
                            else -> {
                                input = ""
                                error = over
                            }
                        }
                    }
                    .onFailure { error = linkError(it) }
                busy = false
            }
        }

        /** [choice] null: "Deny". */
        fun decide(details: LinkDetails, choice: String?) {
            if (busy) return
            busy = true
            error = null
            scope.launch {
                runCatching {
                    if (choice == null) account.denyLink(details.linkId) else account.approveLink(details.linkId, choice)
                }
                    .onSuccess {
                        // Decided: the invitation is not cancelled behind the new device's back. The card stays on
                        // the screen until it closes, instead of the code field for a frame
                        link = details
                        invite.release()
                        snackbar.show(if (choice == null) denied else signedIn.format(details.device?.name ?: someDevice))
                        pop()
                    }
                    .onFailure {
                        when ((it as? ApiException)?.code) {
                            // The devices of the account are where one can be unlinked: back to them
                            "device_limit_reached" -> {
                                invite.release()
                                snackbar.show(deviceLimit)
                                pop()
                            }

                            in LINK_OVER -> {
                                invite.release()
                                link = null
                                input = ""
                                error = linkError(it)
                            }

                            else -> error = linkError(it)
                        }
                    }
                busy = false
            }
        }

        // The card is the same for a code typed here and for an invitation a new device has typed
        val approving = link ?: (inviteState as? InviteState.Claimed)?.link
        val waiting = inviteState as? InviteState.Waiting
        val inviting = inviteState is InviteState.Starting || waiting != null
        val inviteFailed = inviteState as? InviteState.Failed

        AccountPage(
            title = stringResource(R.string.account_add_device),
            onBack = pop,
            description = when {
                approving != null -> null
                waiting != null -> stringResource(R.string.account_invite_text)
                inviting -> null
                else -> stringResource(R.string.account_add_device_text)
            }
        ) {
            when {
                approving != null -> {
                    val now = rememberNow(EXPIRY_TICK_MS)
                    LinkApproval(
                        link = approving,
                        minutesLeft = runCatching { approving.expiresAt.epochMs() }.getOrNull()?.let { minutesLeft(it - now) },
                        busy = busy,
                        error = error,
                        onChoose = { decide(approving, it) },
                        onDeny = { decide(approving, null) }
                    )
                }

                waiting != null -> InviteWaiting(
                    userCode = waiting.userCode,
                    remainingMs = waiting.expiresAt - rememberNow(),
                    onCancel = { invite.cancel() }
                )

                inviting -> LinkWorking(text = stringResource(R.string.link_starting))

                else -> LinkCodeStep(
                    code = input,
                    onCodeChange = { input = it },
                    busy = busy,
                    error = error ?: inviteFailed?.let { inviteError(it.failure) },
                    onContinue = ::resolve,
                    secondaryText = stringResource(R.string.account_add_device_show_code),
                    secondaryTag = "link_show_code",
                    onSecondary = {
                        error = null
                        invite.start()
                    }
                )
            }
        }
    }
}

/** What went wrong with the invitation of this device, in words. */
@StringRes
internal fun inviteError(failure: LinkFailure): Int = when (failure) {
    LinkFailure.Expired -> R.string.account_invite_expired
    LinkFailure.Cancelled -> R.string.account_invite_cancelled
    LinkFailure.Denied -> R.string.account_add_device_denied
    LinkFailure.DeviceLimit -> R.string.account_add_device_limit
    LinkFailure.NotFound -> R.string.account_error_link_not_found
    LinkFailure.Throttled -> R.string.account_error_throttled
    LinkFailure.Network -> R.string.account_error_network
    LinkFailure.AlreadyClaimed, LinkFailure.WrongMode, LinkFailure.Unknown -> R.string.account_error_unknown
}

/** This device's code for a new device to type (mode `invite`): the code, how long it lives, and "Cancel". */
@Composable
internal fun InviteWaiting(userCode: String, remainingMs: Long, onCancel: () -> Unit) = Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.padding(horizontal = 4.dp)
) {
    LinkBigCode(userCode)
    LinkCountdown(remainingMs)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.account_invite_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("link_cancel")) {
        Text(text = stringResource(R.string.cancel))
    }
}

/** Whole minutes, rounded up, the way the code counts them: "1" in its last seconds, "0" once it is over. */
internal fun minutesLeft(millis: Long): Int = if (millis <= 0) 0 else ((millis + MINUTE_MS - 1) / MINUTE_MS).toInt()

/** The code the new device shows; "Continue" once it is one (API §1.6 `UserCode`). */
@Composable
internal fun LinkCodeStep(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    @StringRes error: Int?,
    onContinue: () -> Unit,
    secondaryText: String? = null,
    secondaryTag: String = "link_secondary",
    onSecondary: () -> Unit = {}
) {
    val valid = UserCode.normalize(code) != null
    // Eight characters typed but not a code: an `U`, a Cyrillic letter
    val wrong = !valid && code.count { it.isLetterOrDigit() } >= UserCode.LENGTH

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { onCodeChange(it.uppercase().take(CODE_INPUT_MAX)) },
            label = { Text(text = stringResource(R.string.account_add_device_code)) },
            placeholder = { Text(text = "XXXX-XXXX", fontFamily = FontFamily.Monospace) },
            supportingText = { Text(text = stringResource(R.string.account_add_device_code_hint)) },
            isError = wrong,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onContinue() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("link_code")
        )
        ErrorText(error)
        ProgressButton(
            text = stringResource(R.string.account_add_device_continue),
            busy = busy,
            enabled = valid,
            onClick = onContinue,
            modifier = Modifier.testTag("link_continue")
        )
        if (secondaryText != null) TextButton(
            onClick = onSecondary,
            enabled = !busy,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag(secondaryTag)
        ) {
            Text(text = secondaryText)
        }
    }
}

/**
 * Which device asks to sign in (its icon, name, model and system, whether it is on this network, how long the code
 * lives) and the three numbers, one of which is on its screen; "Deny" below.
 */
@Composable
internal fun LinkApproval(
    link: LinkDetails,
    minutesLeft: Int?,
    busy: Boolean,
    @StringRes error: Int?,
    onChoose: (String) -> Unit,
    onDeny: () -> Unit
) = Column {
    SettingsEntryGroupText(title = stringResource(R.string.account_add_device_new))
    LinkDeviceCard(link = link, minutesLeft = minutesLeft)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = 4.dp, top = 24.dp, end = 4.dp)) {
        Text(text = stringResource(R.string.account_add_device_pick), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            link.verifyChoices.forEach { choice ->
                FilledTonalButton(
                    onClick = { onChoose(choice) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(88.dp)
                        .testTag("link_choice_$choice")
                ) {
                    Text(text = choice, style = MaterialTheme.typography.displaySmall)
                }
            }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ErrorText(error)
        OutlinedButton(
            onClick = onDeny,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("link_deny")
        ) {
            Text(text = stringResource(R.string.account_add_device_deny))
        }
    }
}

@Composable
private fun LinkDeviceCard(link: LinkDetails, minutesLeft: Int?) = Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth()
) {
    val device = link.device
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(16.dp)
    ) {
        ShapeIcon(
            icon = deviceIcon(device?.platform),
            shape = IconShape.Circle,
            contentDescription = stringResource(deviceKindName(device?.platform)),
            size = 56.dp
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = device?.name ?: stringResource(R.string.device_kind_other),
                style = MaterialTheme.typography.titleMedium
            )
            listOfNotNull(device?.model, device?.osVersion)
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { Secondary(text = it.joinToString(" · ")) }
            link.sameNetwork?.let { same ->
                Secondary(
                    text = stringResource(
                        if (same) R.string.account_add_device_same_network else R.string.account_add_device_other_network
                    ),
                    // Another network is worth a look: someone else may be asking
                    warning = !same
                )
            }
            minutesLeft?.let {
                Secondary(
                    text = if (it > 0) stringResource(R.string.account_add_device_expires, it)
                    else stringResource(R.string.account_error_link_expired)
                )
            }
        }
    }
}

@Composable
private fun Secondary(text: String, warning: Boolean = false) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
)
