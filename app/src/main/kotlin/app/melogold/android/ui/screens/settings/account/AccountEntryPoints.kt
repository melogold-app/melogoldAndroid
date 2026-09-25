package app.melogold.android.ui.screens.settings.account

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.AccountState
import app.melogold.android.sync.SyncStatus
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.SegmentedGroup
import app.melogold.android.ui.components.m3e.SegmentedRow
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.compose.routing.RouteHandlerScope

/*
 * Entry points of the account and server screens for the settings root (REDESIGN-M3E §3.1, §3.5; REWRITE §3.5.12,
 * §3.5.13): the card at the top, the server row, and the routes of the Settings section's stack.
 */

/**
 * The card at the top of the settings: signed out, "Melogold works without an account" with Sign in and Create
 * account; signed in, who and how the sync goes (a tap opens the account); after the server ended the session, a
 * note to sign in again.
 */
@Composable
fun RouteHandlerScope.AccountCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val state by container.account.state.collectAsState()
    val status by container.sync.status.collectAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { if (state is AccountState.SignedIn) it.clickable { accountRoute() } else it }
            .padding(20.dp)
            .testTag("account_card")
    ) {
        when (val current = state) {
            is AccountState.SignedIn -> CardHeader(
                icon = R.drawable.ms_person_fill,
                title = stringResource(R.string.account_signed_in_as, current.login),
                text = syncStatusText(status),
                trailing = true
            )

            is AccountState.AuthRequired -> {
                CardHeader(
                    icon = R.drawable.ms_sync_problem,
                    title = stringResource(R.string.account_auth_required_title),
                    text = stringResource(R.string.account_auth_required_text)
                )
                Button(onClick = { signInRoute() }) { Text(text = stringResource(R.string.account_sign_in)) }
            }

            AccountState.SignedOut -> {
                CardHeader(
                    icon = R.drawable.ms_person,
                    title = stringResource(R.string.account_stub_no_account_title),
                    text = stringResource(R.string.account_stub_no_account_text)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { signInRoute() }, modifier = Modifier.testTag("account_sign_in")) {
                        Text(text = stringResource(R.string.account_stub_sign_in))
                    }
                    OutlinedButton(onClick = { registerRoute() }, modifier = Modifier.testTag("account_register")) {
                        Text(text = stringResource(R.string.account_stub_create_account))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(icon: Int, title: String, text: String, trailing: Boolean = false) = Row(
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    ShapeIcon(icon = icon, shape = IconShape.Cookie9Sided, contentDescription = null, size = 48.dp)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (trailing) Icon(
        painter = painterResource(R.drawable.ms_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** "Synced · 2 min ago", "Syncing…", "Can't reach the server"… */
@Composable
fun syncStatusText(status: SyncStatus): String = when (status) {
    SyncStatus.Syncing -> stringResource(R.string.sync_status_syncing)
    is SyncStatus.Failed -> stringResource(if (status.offline) R.string.sync_status_offline else R.string.sync_status_failed)
    is SyncStatus.Idle -> status.lastSyncAt?.let { stringResource(R.string.sync_status_done, relativeTime(it)) }
        ?: stringResource(R.string.sync_status_never)

    SyncStatus.Off -> stringResource(R.string.sync_status_never)
}

@Composable
fun relativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    return if (now - epochMs < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.sync_just_now)
    else DateUtils.getRelativeTimeSpanString(epochMs, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/**
 * The "Melogold server" row with the server's host as its summary.
 */
@Composable
fun RouteHandlerScope.ServerSettingsRow(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    // Read again when the account changes: switching servers signs out
    val state by container.account.state.collectAsState()
    val host = remember(state) { Uri.parse(container.account.serverUrl).host.orEmpty() }

    SegmentedGroup(modifier = modifier) {
        row { shapes ->
            SegmentedRow(
                headline = stringResource(R.string.account_stub_server),
                supporting = host,
                icon = R.drawable.ms_dns,
                shapes = shapes,
                onClick = { serverRoute() },
                trailing = {
                    Icon(
                        painter = painterResource(R.drawable.ms_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }
}

/** The account routes of the Settings section's stack. */
@Composable
fun RouteHandlerScope.AccountRoutes() {
    signInRoute { SignInScreen() }
    registerRoute { RegisterScreen() }
    accountRoute { AccountScreen() }
    serverRoute { ServerScreen() }
}
