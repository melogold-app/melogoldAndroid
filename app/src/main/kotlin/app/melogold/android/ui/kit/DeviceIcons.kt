package app.melogold.android.ui.kit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.melogold.android.R
import app.melogold.domain.server.DeviceKind

/**
 * The icon of a device of the account by its `platform` (tasks/0004): the devices of the account, the device filter of
 * History and the approval of a sign-in by code. An unknown platform is drawn as a computer.
 */
@DrawableRes
fun deviceIcon(platform: String?): Int = when (DeviceKind.of(platform)) {
    DeviceKind.Phone -> R.drawable.ms_smartphone
    DeviceKind.Tablet -> R.drawable.ms_tablet
    DeviceKind.Watch -> R.drawable.ms_watch
    DeviceKind.Headset -> R.drawable.ms_head_mounted_device
    DeviceKind.Computer, DeviceKind.Other -> R.drawable.ms_computer
}

/** What the icon of [deviceIcon] says to TalkBack: "Phone", "Watch", … or "Device". */
@StringRes
fun deviceKindName(platform: String?): Int = when (DeviceKind.of(platform)) {
    DeviceKind.Phone -> R.string.device_kind_phone
    DeviceKind.Tablet -> R.string.device_kind_tablet
    DeviceKind.Computer -> R.string.device_kind_computer
    DeviceKind.Watch -> R.string.device_kind_watch
    DeviceKind.Headset -> R.string.device_kind_headset
    DeviceKind.Other -> R.string.device_kind_other
}
