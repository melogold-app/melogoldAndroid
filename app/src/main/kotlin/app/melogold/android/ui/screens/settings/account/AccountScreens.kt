package app.melogold.android.ui.screens.settings.account

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import app.melogold.android.BuildConfig
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.AccountState
import app.melogold.android.sync.SyncStatus
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.DeviceDto
import app.melogold.android.sync.api.ServerInfo
import app.melogold.android.sync.epochMs
import app.melogold.android.ui.components.m3e.SegmentedGroup
import app.melogold.android.ui.components.m3e.SegmentedRow
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.settings.LocalSettingsPadding
import app.melogold.android.ui.screens.settings.SettingsCategoryScreen
import app.melogold.android.ui.screens.settings.SettingsEntryGroupText
import app.melogold.android.ui.screens.settings.SettingsGroupSpacer
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.domain.server.ServerAddress
import app.melogold.domain.server.ServerAddressPolicy
import app.melogold.compose.routing.Route0
import app.melogold.compose.routing.RouteHandler
import kotlinx.coroutines.launch

val signInRoute = Route0("signInRoute")
val registerRoute = Route0("registerRoute")
val accountRoute = Route0("accountRoute")
val serverRoute = Route0("serverRoute")

/** Platforms of the API (API §4.1) shown with a phone. */
private val MOBILE_PLATFORMS = setOf("android", "ios")

/** What went wrong, in words (API §2 codes). */
@StringRes
private fun accountError(error: Throwable): Int = when ((error as? ApiException)?.code) {
    "invalid_credentials" -> R.string.account_error_credentials
    "login_throttled", "rate_limited", "reauth_throttled" -> R.string.account_error_throttled
    "device_limit_reached" -> R.string.account_error_device_limit
    "login_taken" -> R.string.account_error_login_taken
    "invalid_login_format" -> R.string.account_error_login_format
    "password_too_short" -> R.string.account_error_password_short
    "password_too_common", "password_too_weak", "password_contains_login", "password_too_long" ->
        R.string.account_error_password_weak

    "registration_closed" -> R.string.account_error_registration_closed
    "recent_device_restricted" -> R.string.account_error_password_needed
    "invalid_password" -> R.string.account_error_invalid_password
    else -> if ((error as? ApiException)?.isNetwork == true) R.string.account_error_network else R.string.account_error_unknown
}

/** A page of the account screens: the top bar with "back", then the body; [description] lines up with the fields. */
@Composable
private fun AccountPage(title: String, onBack: () -> Unit, description: String? = null, content: @Composable () -> Unit) =
    CollectionScaffold(title = title, subtitle = null, onBack = onBack) { padding ->
        CompositionLocalProvider(LocalSettingsPadding provides padding) {
            SettingsCategoryScreen(title = title) {
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp)
                    )
                }
                content()
            }
        }
    }

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        supportingText = supporting?.let { { Text(text = it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            // Not a stop of "Next" on the keyboard: it goes from field to field
            IconButton(onClick = { visible = !visible }, modifier = Modifier.focusProperties { canFocus = false }) {
                Icon(
                    painter = painterResource(if (visible) R.drawable.ms_visibility_off else R.drawable.ms_visibility),
                    contentDescription = stringResource(if (visible) R.string.account_password_hide else R.string.account_password_show)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun LoginField(value: String, onValueChange: (String) -> Unit, supporting: String? = null) = OutlinedTextField(
    value = value,
    onValueChange = { onValueChange(it.trim()) },
    label = { Text(text = stringResource(R.string.account_login)) },
    supportingText = supporting?.let { { Text(text = it) } },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    modifier = Modifier
        .fillMaxWidth()
        .testTag("account_login")
)

@Composable
private fun ErrorText(@StringRes error: Int?) {
    if (error != null) Text(
        text = stringResource(error),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ProgressButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(text = text)
    }

/** Sign-in with a login and a password (API §4.3). */
@Route
@Composable
fun SignInScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val account = LocalAppContainer.current.account
        val scope = rememberCoroutineScope()
        // Signing in again after the server ended the session: the login is known
        var login by rememberSaveable {
            mutableStateOf((account.state.value as? AccountState.AuthRequired)?.login ?: account.session?.login.orEmpty())
        }
        var password by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        val host = remember { Uri.parse(account.serverUrl).host.orEmpty() }

        fun submit() {
            if (busy || login.isBlank() || password.isEmpty()) return
            busy = true
            error = null
            scope.launch {
                runCatching { account.signIn(login, password) }
                    .onSuccess { pop() }
                    .onFailure { error = accountError(it) }
                busy = false
            }
        }

        AccountPage(
            title = stringResource(R.string.account_sign_in_title),
            onBack = pop,
            description = stringResource(R.string.account_sign_in_text)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                LoginField(value = login, onValueChange = { login = it })
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.account_password),
                    onDone = ::submit,
                    modifier = Modifier.testTag("account_password")
                )
                ErrorText(error)
                ProgressButton(
                    text = stringResource(R.string.account_sign_in),
                    busy = busy,
                    enabled = login.isNotBlank() && password.isNotEmpty(),
                    onClick = ::submit,
                    modifier = Modifier.testTag("account_submit")
                )
                TextButton(onClick = { registerRoute() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(text = stringResource(R.string.account_no_account))
                }
                Text(
                    text = stringResource(R.string.account_on_server, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Sign-up with a login and a password; then the recovery code, shown once (REWRITE §3.5.13). */
@Route
@Composable
fun RegisterScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val account = LocalAppContainer.current.account
        val scope = rememberCoroutineScope()
        var login by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var repeat by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        var recoveryCode by rememberSaveable { mutableStateOf<String?>(null) }

        fun submit() {
            if (busy) return
            if (password != repeat) {
                error = R.string.account_error_password_mismatch
                return
            }
            busy = true
            error = null
            scope.launch {
                runCatching { account.register(login, password) }
                    .onSuccess { recoveryCode = it }
                    .onFailure { error = accountError(it) }
                busy = false
            }
        }

        recoveryCode?.let { code ->
            RecoveryCodePage(code = code, onDone = pop)
            return@Content
        }

        AccountPage(
            title = stringResource(R.string.account_register_title),
            onBack = pop,
            description = stringResource(R.string.account_register_text)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                LoginField(value = login, onValueChange = { login = it }, supporting = stringResource(R.string.account_login_hint))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.account_password),
                    supporting = stringResource(R.string.account_password_hint),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.testTag("account_password")
                )
                PasswordField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = stringResource(R.string.account_password_repeat),
                    onDone = ::submit,
                    modifier = Modifier.testTag("account_password_repeat")
                )
                ErrorText(error)
                ProgressButton(
                    text = stringResource(R.string.account_register),
                    busy = busy,
                    enabled = login.length >= 3 && password.length >= 8 && repeat.isNotEmpty(),
                    onClick = ::submit,
                    modifier = Modifier.testTag("account_submit")
                )
            }
        }
    }
}

@Composable
private fun RecoveryCodePage(code: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val copied = stringResource(R.string.account_recovery_copied)
    var saved by rememberSaveable { mutableStateOf(false) }

    AccountPage(
        title = stringResource(R.string.account_recovery_title),
        onBack = onDone,
        description = stringResource(R.string.account_recovery_text)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    // A line breaks only between the groups of the code
                    text = code.replace("-", "-\u200B"),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(24.dp)
                        .testTag("recovery_code")
                )
            }
            FilledTonalButton(
                onClick = {
                    context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Melogold", code))
                    snackbar.show(copied)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painter = painterResource(R.drawable.ms_content_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.account_recovery_copy))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it })
            ) {
                Checkbox(checked = saved, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(text = stringResource(R.string.account_recovery_saved), style = MaterialTheme.typography.bodyLarge)
            }
            Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.account_recovery_done))
            }
        }
    }
}

/**
 * The account (REWRITE §3.5.13): how the sync goes with "Sync now", the devices with "Unlink" and "Sign out on
 * other devices", and "Sign out".
 */
@Route
@Composable
fun AccountScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val container = LocalAppContainer.current
        val account = container.account
        val state by account.state.collectAsState()
        val status by container.sync.status.collectAsState()
        val snackbar = LocalAppSnackbar.current
        val scope = rememberCoroutineScope()
        var devices by remember { mutableStateOf<List<DeviceDto>?>(null) }
        var reload by remember { mutableStateOf(0) }
        var revoking by remember { mutableStateOf<DeviceDto?>(null) }
        var revokingOthers by remember { mutableStateOf(false) }
        var signingOut by remember { mutableStateOf(false) }
        val revokedOthers = stringResource(R.string.account_revoked_others)

        // Signed out here or elsewhere: nothing to show
        LaunchedEffect(state) { if (state !is AccountState.SignedIn) pop() }
        LaunchedEffect(reload) { devices = runCatching { account.devices() }.getOrNull() ?: devices }
        LaunchedEffect(Unit) { container.sync.devicesChanged.collect { reload++ } }

        val signedIn = state as? AccountState.SignedIn ?: return@Content

        AccountPage(title = signedIn.login, onBack = pop) {
            SettingsEntryGroupText(title = stringResource(R.string.account_sync_group))
            SegmentedGroup {
                row { shapes ->
                    SegmentedRow(
                        headline = stringResource(R.string.account_sync_now),
                        supporting = syncStatusText(status),
                        icon = if (status is SyncStatus.Failed) R.drawable.ms_sync_problem else R.drawable.ms_sync,
                        shapes = shapes,
                        onClick = { scope.launch { container.sync.sync(force = true) } }
                    )
                }
            }
            Text(
                text = stringResource(R.string.account_sync_what),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
            SettingsGroupSpacer()

            SettingsEntryGroupText(title = stringResource(R.string.account_devices_group))
            SegmentedGroup {
                devices.orEmpty().forEach { device ->
                    row { shapes ->
                        SegmentedRow(
                            headline = device.name,
                            supporting = if (device.isCurrent) stringResource(R.string.account_device_current)
                            else device.lastSeenAt?.let { stringResource(R.string.account_device_seen, relativeTime(it.epochMs())) },
                            icon = if (device.platform in MOBILE_PLATFORMS) R.drawable.ms_smartphone else R.drawable.ms_computer,
                            shapes = shapes,
                            onClick = if (device.isCurrent) null else ({ revoking = device }),
                            trailing = if (device.isCurrent) null else {
                                { TextButton(onClick = { revoking = device }) { Text(text = stringResource(R.string.account_device_revoke)) } }
                            }
                        )
                    }
                }
                if (devices.orEmpty().size > 1) row { shapes ->
                    SegmentedRow(
                        headline = stringResource(R.string.account_revoke_others),
                        icon = R.drawable.ms_link_off,
                        shapes = shapes,
                        onClick = { revokingOthers = true }
                    )
                }
            }
            SettingsGroupSpacer()

            OutlinedButton(
                onClick = { signingOut = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_sign_out")
            ) {
                Icon(painter = painterResource(R.drawable.ms_logout), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(R.string.account_sign_out))
            }
        }

        revoking?.let { device ->
            PasswordConfirmDialog(
                title = stringResource(R.string.account_device_revoke_title, device.name),
                text = stringResource(R.string.account_device_revoke_text),
                confirm = stringResource(R.string.account_device_revoke),
                onDismiss = { revoking = null },
                action = { password -> account.revoke(device.id, password) },
                onDone = {
                    revoking = null
                    reload++
                }
            )
        }

        if (revokingOthers) PasswordConfirmDialog(
            title = stringResource(R.string.account_revoke_others_title),
            text = stringResource(R.string.account_revoke_others_text),
            confirm = stringResource(R.string.account_revoke_others),
            onDismiss = { revokingOthers = false },
            action = { password ->
                val count = account.revokeOthers(password)
                snackbar.show(revokedOthers.format(count))
            },
            onDone = {
                revokingOthers = false
                reload++
            }
        )

        if (signingOut) AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text(text = stringResource(R.string.account_sign_out_title)) },
            text = { Text(text = stringResource(R.string.account_sign_out_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        signingOut = false
                        scope.launch { account.signOut() }
                    }
                ) { Text(text = stringResource(R.string.account_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text(text = stringResource(R.string.cancel)) } }
        )
    }
}

/**
 * A destructive action on devices: first without a password; when the server asks for one (a device new on the
 * account, DESIGN §4.8), the dialog asks for it and tries again.
 */
@Composable
private fun PasswordConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    action: suspend (password: String?) -> Unit,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var needsPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = text)
                if (needsPassword) PasswordField(value = password, onValueChange = { password = it }, label = stringResource(R.string.account_password))
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (!needsPassword || password.isNotEmpty()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { action(password.takeIf { needsPassword }) }
                            .onSuccess { onDone() }
                            .onFailure {
                                val code = (it as? ApiException)?.code
                                if (code == "recent_device_restricted" && !needsPassword) needsPassword = true
                                error = accountError(it)
                            }
                        busy = false
                    }
                }
            ) { Text(text = if (needsPassword) stringResource(R.string.account_password_confirm) else confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) } }
    )
}

@StringRes
private fun addressError(reason: ServerAddress.Reason): Int = when (reason) {
    ServerAddress.Reason.HttpsRequired -> R.string.server_https_needed
    ServerAddress.Reason.CredentialsOrParams -> R.string.server_error_params
    ServerAddress.Reason.UnsupportedScheme -> R.string.server_error_scheme
    ServerAddress.Reason.Empty, ServerAddress.Reason.Malformed -> R.string.server_error_malformed
}

/**
 * The server (REWRITE §3.5.12): its address, checked before use (`/server/info`: a Melogold server this app can talk
 * to), and "Connect", which signs out of the old one.
 */
@Route
@Composable
fun ServerScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val account = LocalAppContainer.current.account
        val scope = rememberCoroutineScope()
        var address by rememberSaveable { mutableStateOf(account.serverUrl) }
        var info by remember { mutableStateOf<ServerInfo?>(null) }
        var checked by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<Int?>(null) }
        val parsed = ServerAddressPolicy.normalize(address)
        val normalized = (parsed as? ServerAddress.Valid)?.url
        val current = normalized == account.serverUrl

        fun check(then: (() -> Unit)? = null) {
            val url = normalized ?: return
            busy = true
            error = null
            scope.launch {
                runCatching { account.check(url) }
                    .onSuccess {
                        info = it
                        checked = url
                        then?.invoke()
                    }
                    .onFailure {
                        info = null
                        error = if ((it as? ApiException)?.code == "not_melogold") R.string.server_not_melogold else R.string.server_unreachable
                    }
                busy = false
            }
        }

        LaunchedEffect(Unit) { check() }

        AccountPage(title = stringResource(R.string.server_title), onBack = pop, description = stringResource(R.string.server_text)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it.trim()
                        info = null
                        error = null
                    },
                    label = { Text(text = stringResource(R.string.server_address)) },
                    isError = parsed is ServerAddress.Invalid && parsed.reason != ServerAddress.Reason.Empty,
                    supportingText = when {
                        parsed is ServerAddress.Invalid && parsed.reason != ServerAddress.Reason.Empty -> {
                            { Text(text = stringResource(addressError(parsed.reason))) }
                        }

                        (parsed as? ServerAddress.Valid)?.insecure == true -> {
                            { Text(text = stringResource(R.string.server_insecure)) }
                        }

                        else -> null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { check() }),
                    modifier = Modifier.fillMaxWidth()
                )

                info?.takeIf { checked == normalized }?.let { server ->
                    Text(
                        text = stringResource(R.string.server_info, server.instanceName, server.version) + "\n" + stringResource(
                            when (server.registration) {
                                "open" -> R.string.server_registration_open
                                "first" -> R.string.server_registration_first
                                else -> R.string.server_registration_closed
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ErrorText(error)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { check() }, enabled = !busy && normalized != null, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.server_check))
                    }
                    Button(
                        onClick = {
                            check {
                                account.setServer(checked ?: return@check)
                                pop()
                            }
                        },
                        enabled = !busy && normalized != null && !current,
                        modifier = Modifier.weight(1f)
                    ) { Text(text = stringResource(if (current) R.string.server_current else R.string.server_connect)) }
                }
                if (!current && account.session != null) Text(
                    text = stringResource(R.string.server_switch_signs_out),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (address != BuildConfig.DEFAULT_SERVER_URL) TextButton(onClick = { address = BuildConfig.DEFAULT_SERVER_URL }) {
                    Text(text = stringResource(R.string.server_default))
                }
            }
        }
    }
}
