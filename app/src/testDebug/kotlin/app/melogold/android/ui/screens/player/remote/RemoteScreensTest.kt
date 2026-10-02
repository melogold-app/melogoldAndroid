package app.melogold.android.ui.screens.player.remote

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.MainApplication
import app.melogold.android.sync.api.PlaybackSummary
import app.melogold.android.sync.api.RemoteDevice
import app.melogold.android.sync.api.TrackDto
import app.melogold.android.sync.remote.RemoteNow
import app.melogold.android.sync.remote.RemoteTarget
import app.melogold.android.sync.remote.ServerClock
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.theme.MelogoldTheme
import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The remote as it is drawn (tasks/0018), without a server: the sheet of devices, the expanded remote with its banner,
 * seek bar, transport and volume, and the mini player with the icon of the device. With `-Pmelogold.screenshots=<dir>`
 * the pictures are saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class RemoteScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val mac = RemoteTarget("mac-id", "MacBook Air", "macos")

    private val track = TrackDto(
        videoId = "aaaaaaaaaa1",
        title = "Группа крови",
        artistsText = "Кино",
        durationText = "4:45",
        durationMs = 285_000,
        thumbnailUrl = "https://i.example/a.jpg"
    )

    private fun now(playing: Boolean = true, volume: Int? = 60, position: Long = 83_000) = RemoteNow(
        deviceId = "mac-id",
        track = track,
        index = 1,
        queueLength = 12,
        positionMs = position,
        durationMs = 285_000,
        playing = playing,
        atMs = ServerClock.now(),
        volume = volume,
        rev = 1
    )

    private fun summary(playing: Boolean = true) = PlaybackSummary(
        rev = 1, deviceId = "mac-id", deviceName = "MacBook Air", sessionId = "s", queueVersion = 0, index = 1, queueLength = 12,
        track = track, positionMs = 83_000, durationMs = 285_000, playing = playing, at = "2026-09-30T10:00:00.000Z",
        updatedAt = "2026-09-30T10:00:00.000Z", volume = 60
    )

    private val devices = listOf(
        RemoteDevice("mac-id", "MacBook Air", "macos", online = true, controllable = true, playing = summary(), volume = 60),
        RemoteDevice("ipad-id", "iPad Pro", "ipados", online = false, controllable = true),
        RemoteDevice("win-id", "Рабочий компьютер", "windows", online = true, controllable = false)
    )

    // region The sheet

    private class SheetCalls {
        var thisDevice = 0
        var output = 0
        val selected = mutableListOf<String>()
    }

    private fun sheet(state: DevicesState, controlling: RemoteTarget? = null, calls: SheetCalls = SheetCalls()) = render {
        DeviceSheetContent(
            state = state,
            controlling = controlling,
            onOutput = { calls.output++ },
            onThisDevice = { calls.thisDevice++ },
            onSelect = { calls.selected += it.deviceId }
        )
    }

    @Test
    fun `the sheet has this device and the output, then the other devices with what they play`() {
        val calls = SheetCalls()
        sheet(DevicesState.Loaded(devices), calls = calls)

        compose.onNodeWithText("Устройство").assertExists()
        compose.onNodeWithText("Это устройство").assertExists()
        compose.onNodeWithText("Выбор вывода звука…").assertExists()
        compose.onNodeWithText("Другие устройства").assertExists()
        compose.onNodeWithText("MacBook Air").assertExists()
        compose.onNodeWithText("В сети · Кино — Группа крови · Громкость 60\u00A0%").assertExists()
        compose.onNodeWithText("iPad Pro").assertExists()
        compose.onNodeWithText("Не в сети").assertExists()
        compose.onNodeWithText("В сети · Управление выключено").assertExists()
        shoot("remote-devices")

        compose.onNodeWithTag("device_output").performClick()
        assertEquals(1, calls.output)
        compose.onNodeWithTag("device_this").performClick()
        assertEquals(1, calls.thisDevice)
        compose.onNodeWithTag("device_mac-id").performClick()
        assertEquals(listOf("mac-id"), calls.selected)
    }

    @Test
    fun `an offline device, and one that does not let itself be controlled, cannot be chosen`() {
        val calls = SheetCalls()
        sheet(DevicesState.Loaded(devices), calls = calls)

        compose.onNodeWithTag("device_ipad-id").assertIsNotEnabled()
        compose.onNodeWithTag("device_win-id").assertIsNotEnabled()
        compose.onNodeWithTag("device_mac-id").assertIsEnabled()
        compose.onNodeWithTag("device_ipad-id").performClick()
        compose.onNodeWithTag("device_win-id").performClick()
        assertEquals(emptyList(), calls.selected)
    }

    @Test
    fun `the device that is controlled has a check, this device has it otherwise`() {
        sheet(DevicesState.Loaded(devices), controlling = mac)

        compose.onNodeWithTag("device_mac-id").assertExists()
        shoot("remote-devices-connected")
    }

    @Test
    fun `no other devices, a list that could not be loaded, and loading`() {
        sheet(DevicesState.Loaded(emptyList()))
        compose.onNodeWithTag("devices_empty").assertExists()
        compose.onNodeWithText("Других устройств нет. Войдите на них в тот же аккаунт").assertExists()
        shoot("remote-devices-empty")
    }

    @Test
    fun `a failed list says so`() {
        sheet(DevicesState.Failed)
        compose.onNodeWithTag("devices_failed").assertExists()
        compose.onNodeWithText("Это устройство").assertExists()
    }

    @Test
    fun `while the list loads this device and the output are there`() {
        sheet(DevicesState.Loading)
        compose.onNodeWithText("Это устройство").assertExists()
        compose.onNodeWithText("Выбор вывода звука…").assertExists()
        compose.onAllNodesWithTag("devices_empty").assertCountEquals(0)
    }

    @Test
    fun `what a device says of itself`() {
        val said = mutableListOf<String>()
        render {
            said += deviceStatus(devices[1])
            said += deviceStatus(devices[2])
            said += deviceStatus(devices[0])
            said += deviceStatus(devices[0].copy(playing = null, volume = null))
        }
        compose.waitForIdle()

        assertEquals(
            listOf("Не в сети", "В сети · Управление выключено", "В сети · Кино — Группа крови · Громкость 60\u00A0%", "В сети"),
            said
        )
    }

    // endregion

    // region The remote

    private class RemoteCalls {
        var playPause = 0
        var previous = 0
        var next = 0
        var listenHere = 0
        var disconnect = 0
        var devices = 0
        var collapse = 0
        val seeks = mutableListOf<Long>()
        val volumes = mutableListOf<Int>()
    }

    private fun player(now: RemoteNow?, calls: RemoteCalls = RemoteCalls()) = render {
        RemotePlayer(
            target = mac,
            now = now,
            artworkScheme = null,
            onCollapse = { calls.collapse++ },
            onDevices = { calls.devices++ },
            onListenHere = { calls.listenHere++ },
            onDisconnect = { calls.disconnect++ },
            onPlayPause = { calls.playPause++ },
            onPrevious = { calls.previous++ },
            onNext = { calls.next++ },
            onSeek = { calls.seeks += it },
            onVolume = { calls.volumes += it }
        )
    }

    @Test
    fun `the remote says where it plays, and shows the track, its progress and volume`() {
        player(now())

        compose.onNodeWithText("Играет на «MacBook Air»").assertExists()
        compose.onNodeWithText("Слушать здесь").assertExists()
        compose.onNodeWithText("Отключиться").assertExists()
        compose.onNodeWithText("Группа крови").assertExists()
        compose.onNodeWithText("Кино").assertExists()
        compose.onNodeWithTag("remote_volume_value").assertExists()
        compose.onNodeWithText("60\u00A0%").assertExists()
        compose.onNodeWithTag("remote_scrubber").assertExists()
        shoot("remote-player")
    }

    @Test
    fun `the buttons of the remote are commands`() {
        val calls = RemoteCalls()
        player(now(), calls)

        compose.onNodeWithTag("remote_play_pause").performClick()
        compose.onNodeWithTag("remote_prev").performClick()
        compose.onNodeWithTag("remote_next").performClick()
        compose.onNodeWithTag("remote_listen_here").performClick()
        compose.onNodeWithTag("remote_disconnect").performClick()
        compose.onNodeWithTag("remote_devices_button").performClick()
        compose.onNodeWithTag("remote_collapse").performClick()

        assertEquals(1, calls.playPause)
        assertEquals(1, calls.previous)
        assertEquals(1, calls.next)
        assertEquals(1, calls.listenHere)
        assertEquals(1, calls.disconnect)
        assertEquals(1, calls.devices)
        assertEquals(1, calls.collapse)
    }

    @Test
    fun `nothing playing there says so and the buttons wait`() {
        player(null)

        compose.onNodeWithText("На «MacBook Air» ничего не играет").assertExists()
        compose.onNodeWithTag("remote_play_pause").assertIsNotEnabled()
        compose.onNodeWithTag("remote_next").assertIsNotEnabled()
        shoot("remote-player-nothing")
    }

    @Test
    fun `a paused device shows play`() {
        player(now(playing = false))

        compose.onNodeWithTag("remote_play_pause").assertExists()
        shoot("remote-player-paused")
    }

    @Test
    fun `the volume goes 150 ms after the finger stops, and a drag is one command`() {
        val calls = RemoteCalls()
        player(now(volume = 60), calls)
        compose.mainClock.autoAdvance = false

        // A drag: 61, 62, 63 … 70 within a few milliseconds
        (61..70).forEach { level ->
            compose.onNodeWithTag("remote_volume").performSemanticsAction(SemanticsActions.SetProgress) { it(level.toFloat()) }
            compose.mainClock.advanceTimeBy(10)
        }
        assertEquals(emptyList(), calls.volumes, "the finger has only just stopped")
        compose.mainClock.advanceTimeBy(100)
        assertEquals(emptyList(), calls.volumes, "150 ms have not passed")
        compose.mainClock.advanceTimeBy(60)
        assertEquals(listOf(70), calls.volumes)
        compose.onNodeWithText("70\u00A0%").assertExists()

        // Nothing more without another change
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(70), calls.volumes)
    }

    @Test
    fun `the seek is a command when the finger lifts`() {
        val calls = RemoteCalls()
        player(now(), calls)

        compose.onNodeWithTag("remote_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithTag("remote_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()

        // The slider reports the end of a drag: the position it was moved to is asked of the device
        assertEquals(true, calls.seeks.all { it in 0..285_000 })
    }

    @Test
    fun `the mini player shows the track and the icon of the device, and plays and skips on it`() {
        var expanded = 0
        var playPause = 0
        var next = 0
        render {
            RemoteMiniPlayer(
                target = mac,
                now = now(),
                onExpand = { expanded++ },
                onPlayPause = { playPause++ },
                onNext = { next++ }
            )
        }

        compose.onNodeWithText("Группа крови").assertExists()
        compose.onNodeWithText("MacBook Air").assertExists()
        compose.onNodeWithTag("remote_mini_device", useUnmergedTree = true).assertExists()
        shoot("remote-mini-player")

        compose.onNodeWithTag("remote_mini_play").performClick()
        compose.onNodeWithTag("remote_mini_next").performClick()
        compose.onNodeWithTag("remote_mini_player").performClick()
        assertEquals(1, playPause)
        assertEquals(1, next)
        assertEquals(1, expanded)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun `the same in English`() {
        player(now())

        compose.onNodeWithText("Playing on “MacBook Air”").assertExists()
        compose.onNodeWithText("Listen here").assertExists()
        compose.onNodeWithText("Disconnect").assertExists()
        shoot("remote-player-en")
    }

    // endregion

    private fun render(content: @Composable () -> Unit) = compose.setContent {
        // Minimal motion: the living background of the player stands still, so Compose gets idle and shots are stable
        MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true, motionLevel = MotionLevel.Minimal) {
            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides WindowInsets(0),
                LocalAppContainer provides ApplicationProvider.getApplicationContext<MainApplication>().container
            ) { content() }
        }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
