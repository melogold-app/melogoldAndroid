package app.melogold.android.sync.remote

import app.melogold.android.sync.api.RemoteDevice
import org.junit.Test
import kotlin.test.assertEquals

/** tasks/0023: a watch is never offered as a device to play music on. */
class PlayableDevicesTest {
    @Test
    fun watchIsNotOffered() {
        val devices = listOf(
            RemoteDevice("mac-id", "MacBook Air", "macos", online = true, controllable = true),
            RemoteDevice("watch-id", "Apple Watch", "watchos", online = false, controllable = true),
            RemoteDevice("ipad-id", "iPad", "ipados", online = true, controllable = true)
        )
        assertEquals(listOf("mac-id", "ipad-id"), playable(devices).map { it.deviceId })
    }
}
