package app.melogold.domain.server

import org.junit.Test
import kotlin.test.assertEquals

/** The table of tasks/0004: every `platform` of API §1.6 and an unknown one. */
class DeviceKindTest {
    @Test
    fun `every known platform has its kind`() {
        mapOf(
            "android" to DeviceKind.Phone,
            "ios" to DeviceKind.Phone,
            "ipados" to DeviceKind.Tablet,
            "macos" to DeviceKind.Computer,
            "windows" to DeviceKind.Computer,
            "linux" to DeviceKind.Computer,
            "watchos" to DeviceKind.Watch,
            "visionos" to DeviceKind.Headset
        ).forEach { (platform, kind) -> assertEquals(kind, DeviceKind.of(platform), platform) }
    }

    @Test
    fun `an unknown platform is some other device`() {
        listOf("other", "tvos", "", null).forEach { assertEquals(DeviceKind.Other, DeviceKind.of(it), "$it") }
    }

    @Test
    fun `the case doesn't matter`() {
        assertEquals(DeviceKind.Watch, DeviceKind.of("watchOS"))
        assertEquals(DeviceKind.Tablet, DeviceKind.of("IPADOS"))
    }
}
