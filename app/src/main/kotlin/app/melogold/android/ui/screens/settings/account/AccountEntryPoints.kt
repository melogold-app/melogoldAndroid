package app.melogold.android.ui.screens.settings.account

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.BuildConfig
import app.melogold.android.R
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.SegmentedGroup
import app.melogold.android.ui.components.m3e.SegmentedRow
import app.melogold.android.ui.components.m3e.SegmentedRowValue
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.compose.routing.RouteHandlerScope

/*
 * Entry points of the account and server screens for the settings root (REDESIGN-M3E §3.1, §3.5).
 *
 * **Stubs of Phase 1.** Task T2.5 implements them (sign-in, QR, devices, server check) behind the
 * same signatures; task T2.4 places them on the new settings root. Until the Melogold server exists
 * every action answers honestly that it is not available yet.
 *
 * Release builds (`BuildConfig.ACCOUNT_UI == false`) show only "Melogold works without an account"
 * and the "Server" row.
 */

/**
 * The card at the top of the settings: the account, or the note that none is needed.
 */
@Composable
fun AccountCard(modifier: Modifier = Modifier) {
    val snackbar = LocalAppSnackbar.current
    val notAvailable = stringResource(R.string.account_stub_not_available)
    val showNotAvailable: () -> Unit = { snackbar.show(notAvailable) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShapeIcon(
                icon = R.drawable.ms_person,
                shape = IconShape.Cookie9Sided,
                contentDescription = null,
                size = 48.dp
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.account_stub_no_account_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.account_stub_no_account_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (BuildConfig.ACCOUNT_UI) FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = showNotAvailable) {
                Text(text = stringResource(R.string.account_stub_sign_in))
            }
            OutlinedButton(onClick = showNotAvailable) {
                Text(text = stringResource(R.string.account_stub_create_account))
            }
            TextButton(onClick = showNotAvailable) {
                Icon(
                    painter = painterResource(R.drawable.ms_qr_code_2),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(R.string.account_stub_qr))
            }
        }
    }
}

/**
 * The "Melogold server" row with the current server as its summary.
 */
@Composable
fun ServerSettingsRow(modifier: Modifier = Modifier) {
    val snackbar = LocalAppSnackbar.current
    val notAvailable = stringResource(R.string.account_stub_not_available)

    SegmentedGroup(modifier = modifier) {
        row { shapes ->
            SegmentedRow(
                headline = stringResource(R.string.account_stub_server),
                supporting = stringResource(R.string.account_stub_server_official),
                icon = R.drawable.ms_dns,
                shapes = shapes,
                onClick = { snackbar.show(notAvailable) },
                trailing = { SegmentedRowValue(value = null) }
            )
        }
    }
}

/**
 * The "Account and sync" group, shown only while signed in. Nobody can sign in yet, so it is
 * empty.
 */
@Suppress("UnusedParameter")
@Composable
fun AccountSettingsGroup(modifier: Modifier = Modifier) = Unit

/**
 * The account routes (sign-in, QR, devices, sync, server) of the Settings section's stack. None
 * yet.
 */
@Suppress("UnusedReceiverParameter")
@Composable
fun RouteHandlerScope.AccountRoutes() = Unit
