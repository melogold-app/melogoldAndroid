package app.melogold.android.ui.kit

import app.melogold.android.R
import org.junit.Test
import kotlin.test.assertEquals

/** The icons of tasks/0004: every `platform` of API §1.6 and an unknown one, with what TalkBack says. */
class DeviceIconsTest {
    @Test
    fun `every platform has its icon and its name`() {
        listOf(
            Triple("android", R.drawable.ms_smartphone, R.string.device_kind_phone),
            Triple("ios", R.drawable.ms_smartphone, R.string.device_kind_phone),
            Triple("ipados", R.drawable.ms_tablet, R.string.device_kind_tablet),
            Triple("macos", R.drawable.ms_computer, R.string.device_kind_computer),
            Triple("windows", R.drawable.ms_computer, R.string.device_kind_computer),
            Triple("linux", R.drawable.ms_computer, R.string.device_kind_computer),
            Triple("watchos", R.drawable.ms_watch, R.string.device_kind_watch),
            Triple("visionos", R.drawable.ms_head_mounted_device, R.string.device_kind_headset)
        ).forEach { (platform, icon, name) ->
            assertEquals(icon, deviceIcon(platform), platform)
            assertEquals(name, deviceKindName(platform), platform)
        }
    }

    @Test
    fun `an unknown platform is a computer called device`() {
        listOf("other", "tvos", "", null).forEach { platform ->
            assertEquals(R.drawable.ms_computer, deviceIcon(platform), "$platform")
            assertEquals(R.string.device_kind_other, deviceKindName(platform), "$platform")
        }
    }
}
