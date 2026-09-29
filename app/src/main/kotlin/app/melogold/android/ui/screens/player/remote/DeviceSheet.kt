@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player.remote

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.api.RemoteDevice
import app.melogold.android.sync.remote.RemoteTarget
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.ui.components.menu.MenuDivider
import app.melogold.android.ui.components.menu.MenuEntry
import app.melogold.android.ui.components.menu.MenuSectionTitle
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.deviceIcon
import kotlinx.coroutines.delay

/** The other devices of the account as the sheet has them. */
sealed interface DevicesState {
    data object Loading : DevicesState

    data class Loaded(val devices: List<RemoteDevice>) : DevicesState

    data object Failed : DevicesState
}

/** How often the list is read again while the sheet is open: a device comes online, or starts playing. */
private const val REFRESH_MS = 5_000L

/**
 * The sheet of the "Device" button of the player (tasks/0018): this device, and with it the system's choice of the
 * output (Bluetooth, speakers); the other devices of the account with whether they are online and what they play. A tap
 * on one makes the player its remote; a tap on "This device" gives the control back.
 */
@Composable
fun DeviceSheet(
    onDismiss: () -> Unit,
    onOutput: () -> Unit,
    onThisDevice: () -> Unit,
    onSelect: (RemoteDevice) -> Unit
) {
    val remote = LocalAppContainer.current.remote
    val controlling by remote.target.collectAsState()
    var state by remember { mutableStateOf<DevicesState>(DevicesState.Loading) }

    LaunchedEffect(Unit) {
        while (true) {
            state = runCatching { DevicesState.Loaded(remote.devices()) }
                // A failed refresh keeps what is on screen
                .getOrElse { state as? DevicesState.Loaded ?: DevicesState.Failed }
            delay(REFRESH_MS)
        }
    }

    DeviceSheetContent(
        state = state,
        controlling = controlling,
        onOutput = {
            onDismiss()
            onOutput()
        },
        onThisDevice = {
            onDismiss()
            onThisDevice()
        },
        onSelect = { device ->
            onDismiss()
            onSelect(device)
        }
    )
}

/** The sheet without its sources. */
@Composable
fun DeviceSheetContent(
    state: DevicesState,
    controlling: RemoteTarget?,
    onOutput: () -> Unit,
    onThisDevice: () -> Unit,
    onSelect: (RemoteDevice) -> Unit
) = Menu(modifier = Modifier.testTag("device_sheet")) {
    MenuSectionTitle(text = stringResource(R.string.remote_device))

    MenuEntry(
        icon = R.drawable.ms_smartphone,
        text = stringResource(R.string.remote_this_device),
        onClick = onThisDevice,
        trailingContent = if (controlling == null) {
            { Icon(painter = painterResource(R.drawable.ms_check), contentDescription = null) }
        } else null,
        modifier = Modifier.testTag("device_this")
    )
    MenuEntry(
        icon = R.drawable.ms_media_output,
        text = stringResource(R.string.remote_output),
        onClick = onOutput,
        modifier = Modifier.testTag("device_output")
    )

    MenuDivider()
    MenuSectionTitle(text = stringResource(R.string.remote_other_devices))

    when (state) {
        DevicesState.Loading -> DelayedLoadingIndicator(modifier = Modifier.padding(24.dp))

        DevicesState.Failed -> Text(
            text = stringResource(R.string.remote_devices_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("devices_failed")
        )

        is DevicesState.Loaded -> if (state.devices.isEmpty()) Text(
            text = stringResource(R.string.remote_no_other_devices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("devices_empty")
        ) else state.devices.forEach { device ->
            MenuEntry(
                icon = deviceIcon(device.platform),
                text = device.name,
                secondaryText = deviceStatus(device),
                enabled = device.online && device.controllable,
                onClick = { onSelect(device) },
                trailingContent = if (controlling?.deviceId == device.deviceId) {
                    { Icon(painter = painterResource(R.drawable.ms_check), contentDescription = null) }
                } else null,
                modifier = Modifier.testTag("device_${device.deviceId}")
            )
        }
    }
}

/** "Online · Kino — Group of blood · Volume 60 %", or "Offline", or "Online · Control is off". */
@Composable
fun deviceStatus(device: RemoteDevice): String {
    val parts = mutableListOf<String>()
    parts += stringResource(if (device.online) R.string.remote_online else R.string.remote_offline)
    if (device.online && !device.controllable) parts += stringResource(R.string.remote_control_off)
    if (device.online) {
        device.playing?.track?.let { track ->
            parts += track.artistsText?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.remote_now_playing_line, it, track.title) } ?: track.title
        }
        (device.volume ?: device.playing?.volume)?.let { parts += stringResource(R.string.remote_device_volume, it) }
    }
    return parts.joinToString(" · ")
}
