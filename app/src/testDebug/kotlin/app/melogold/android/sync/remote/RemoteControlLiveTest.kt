package app.melogold.android.sync.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.sync.Account
import app.melogold.android.sync.api.DeviceInput
import app.melogold.android.sync.api.LoginRequest
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.PlaybackCommandPayload
import app.melogold.android.sync.api.PlaybackHandoffInput
import app.melogold.android.sync.api.PlaybackPut
import app.melogold.android.sync.api.PlaybackState
import app.melogold.android.sync.api.PlaybackUpdatedPayload
import app.melogold.android.sync.api.TrackDto
import app.melogold.android.sync.api.TrackInput
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remote control between two devices of one account (tasks/0018), against a real Melogold server given by
 * `-Pmelogold.testServer=http://127.0.0.1:8787`. This app's [RemoteControl] with the real [Account] is the phone; the
 * computer is played by the same pieces the service uses (the reporter, the executor of commands) on a simulated
 * player, with its own session and live stream. Every test registers a throwaway account and deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteControlLiveTest {
    private val server = System.getProperty("melogold.testServer")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val password = "throwaway ${hex(12)}"
    private val json = Json { ignoreUnknownKeys = true }
    private var account: Account? = null
    private val streams = mutableListOf<Stream>()
    private val apis = mutableListOf<MelogoldApi>()

    @After
    fun cleanUp() {
        streams.forEach(Stream::close)
        val token = account?.session?.accessToken
        if (token != null) runCatching { post(server!!, "/auth/me/delete", """{"password":"$password"}""", token) }
        apis.forEach(MelogoldApi::close)
        scope.cancel()
    }

    // region The two devices

    private class LiveEvent(val type: String, val payload: JsonObject?)

    /** A live stream of a device (API §6), read as the app reads it. */
    private inner class Stream(val api: MelogoldApi, val token: String, val remote: Boolean) {
        val events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 256)
        val connected = CompletableDeferred<Unit>()
        private val job: Job = scope.launch {
            runCatching {
                api.streamClient.prepareGet(api.eventsUrl(remote)) {
                    bearerAuth(token)
                    header("Accept", "text/event-stream")
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val event = json.parseToJsonElement(line.removePrefix("data: ")) as? JsonObject ?: continue
                        val type = event["type"]?.jsonPrimitive?.content ?: continue
                        events.tryEmit(LiveEvent(type, event["payload"] as? JsonObject))
                        if (type == "system.connected") connected.complete(Unit)
                    }
                }
            }
        }

        fun close() = job.cancel()

        inline fun <reified T> of(type: String): Flow<T> = events.filter { it.type == type }
            .mapNotNull { it.payload?.let { payload -> runCatching { json.decodeFromJsonElement<T>(payload) }.getOrNull() } }
    }

    /** The player of the computer: what it plays, and what was done to it. */
    private class SimulatedPlayer(var queue: List<TrackInput>, var index: Int) : PlayerPort {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile
        var playing = true

        @Volatile
        var positionMs = 83_000L

        @Volatile
        var level = 55

        override val playWhenReady get() = playing

        override fun play() {
            playing = true
            calls += "play"
        }

        override fun pause() {
            playing = false
            calls += "pause"
        }

        override fun next() {
            index = (index + 1).coerceAtMost(queue.lastIndex)
            positionMs = 0
            calls += "next"
        }

        override fun previous() {
            index = (index - 1).coerceAtLeast(0)
            calls += "previous"
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs = positionMs
            calls += "seek $positionMs"
        }

        override fun setVolume(percent: Int) {
            level = percent
            calls += "volume $percent"
        }

        override fun playQueue(tracks: List<TrackDto>, index: Int, startMs: Long) {
            queue = tracks.map { TrackInput(it.videoId, it.title, it.artistsText) }
            this.index = index
            playing = true
            positionMs = startMs
            calls += "queue ${tracks.size} at $index"
        }

        override fun stop() {
            playing = false
            calls += "stop"
        }
    }

    /** The computer: its session, its stream, its player, and the pieces of the service between them. */
    private inner class Computer(val session: app.melogold.android.sync.api.AuthSession, remote: Boolean, val player: SimulatedPlayer) {
        val api = MelogoldApi(server!!).also(apis::add)
        val token = session.tokens.accessToken
        val deviceId = session.device.id
        var stream = open(remote)
        val commandsAt = CopyOnWriteArrayList<Long>()
        val gaveWay = CopyOnWriteArrayList<String>()

        val reporter: PlaybackReporter = PlaybackReporter(
            port = object : ReporterPort {
                override suspend fun snapshot() = PlayerSnapshot(
                    videoIds = player.queue.map { it.videoId },
                    index = player.index,
                    positionMs = player.positionMs,
                    durationMs = 200_000,
                    playing = player.playing,
                    volume = player.level,
                    trackAt = { player.queue[it] }
                )

                override suspend fun send(put: PlaybackPut) = putOutcome { api.putPlaybackState(token, put) }

                override fun monotonicMs() = System.nanoTime() / 1_000_000

                override fun serverNowMs() = ServerClock.now()
            },
            scope = scope
        )

        private val executor = RemoteCommandExecutor(player)

        init {
            listen()
        }

        private fun open(remote: Boolean) = Stream(api, token, remote).also(streams::add)

        private fun listen() {
            scope.launch {
                stream.of<PlaybackCommandPayload>("playback.command").collect { command ->
                    commandsAt += System.currentTimeMillis()
                    if (executor.execute(command)) reporter.changed()
                }
            }
            scope.launch {
                stream.of<PlaybackUpdatedPayload>("playback.updated").collect { payload ->
                    val state = payload.state ?: return@collect
                    if (shouldGiveWay(state.handoffFrom, deviceId, reporter.sessionId, ServerClock.now())) {
                        player.pause()
                        reporter.newSession()
                        gaveWay += state.deviceName.orEmpty()
                    }
                }
            }
        }

        /** The stream of the computer again, with or without `remote=1` (its owner switched the setting). */
        fun reconnect(remote: Boolean) {
            stream.close()
            stream = open(remote)
            listen()
        }
    }

    private suspend fun signedInPhone(): Account = Account(context).also {
        it.setServer(server!!)
        it.register("remote${hex(6)}", password)
        account = it
    }

    /** The second device of the same account, as a computer with its own hwid. */
    private suspend fun computer(remote: Boolean = true, queue: List<TrackInput> = tracks(3), index: Int = 1): Computer {
        val phone = account!!
        val api = MelogoldApi(server!!).also(apis::add)
        val login = phone.session!!.login
        val session = api.login(LoginRequest(login, password, DeviceInput(hex(32), "MacBook Air", "macos", osVersion = "26.0")))
        return Computer(session, remote, SimulatedPlayer(queue, index))
    }

    private fun tracks(count: Int, prefix: String = "vid") = (1..count).map {
        TrackInput(videoId = "$prefix%08d".format(it).take(11), title = "Трек $it", artistsText = "Кино", durationText = "3:20")
    }

    private fun remoteOf(phone: Account, updated: Flow<PlaybackUpdatedPayload>, connected: Flow<Unit>) = RemoteControl(
        account = phone,
        playbackUpdated = updated,
        liveConnected = connected,
        scope = scope
    )

    // endregion

    private suspend fun until(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("still waiting for $what after $timeoutMs ms")
        }
    }

    @Test
    fun `the phone sees the computer, controls it, and takes its playback over`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val phone = signedInPhone()
        val mac = computer()
        val phoneStream = Stream(MelogoldApi(server!!).also(apis::add), phone.session!!.accessToken, remote = false).also(streams::add)
        withTimeout(10_000) {
            phoneStream.connected.await()
            mac.stream.connected.await()
        }

        val remote = remoteOf(phone, phoneStream.of("playback.updated"), phoneStream.events.filter { it.type == "system.connected" }.map { })
        val notices = CopyOnWriteArrayList<RemoteNotice>()
        scope.launch { remote.notices.collect { notices += it } }
        assertTrue(remote.available(), "the server says features.remote")

        // The computer plays: it says so
        mac.reporter.soundPlayed()
        until("the computer in the list, playing") {
            runBlocking { remote.devices() }.any { it.name == "MacBook Air" && it.online && it.controllable && it.playing != null }
        }
        val listed = remote.devices().single { it.name == "MacBook Air" }
        assertEquals("macos", listed.platform)
        assertEquals(mac.player.queue[1].videoId, listed.playing?.track?.videoId)
        assertEquals(55, listed.volume, "the volume it reported")
        assertEquals(true, listed.playing?.playing)

        // The phone becomes its remote: what it plays is there at once
        remote.connect(listed)
        assertEquals("MacBook Air", remote.target.value?.name)
        assertEquals(mac.player.queue[1].videoId, remote.now.value?.track?.videoId)
        until("the state read from the server") { remote.now.value?.queueLength == 3 }

        // Pause: the computer gets it within a moment, the phone shows it at once and then what the computer says
        val sentAt = System.currentTimeMillis()
        remote.toggle()
        assertEquals(false, remote.now.value?.playing, "shown without waiting")
        until("the computer paused") { "pause" in mac.player.calls }
        val latency = mac.commandsAt.first() - sentAt
        System.err.println("remote control: a command reached the computer in $latency ms")
        assertTrue(latency < 2_000, "the command took $latency ms")
        until("the computer's own report of the pause") { remote.now.value?.playing == false && (remote.now.value?.rev ?: 0) > listed.playing!!.rev }

        // Seek, volume, next
        remote.seekTo(120_000)
        until("the seek") { "seek 120000" in mac.player.calls }
        remote.setVolume(30)
        until("the volume") { mac.player.level == 30 }
        until("the volume it reports") { remote.now.value?.volume == 30 }
        remote.next()
        until("next") { mac.player.index == 2 }
        until("the next track shows") { remote.now.value?.track?.videoId == mac.player.queue[2].videoId }
        remote.previous()
        until("previous") { "previous" in mac.player.calls }
        remote.play()
        until("play") { mac.player.playing }

        // A tap on a track of a list: play_queue with the queue of the list and the index
        val album = tracks(4, prefix = "alb")
        assertTrue(remote.playQueue(album, index = 2))
        until("the queue") { mac.player.queue.map { it.videoId } == album.map { it.videoId } && mac.player.index == 2 }
        until("its track shows") { remote.now.value?.track?.videoId == album[2].videoId }

        // Listen here: the phone takes the state (its queue) and the computer gives way
        var taken: PlaybackState? = null
        assertTrue(remote.listenHere { taken = it })
        assertNull(remote.target.value, "the phone is not a remote any more")
        val state = assertNotNull(taken)
        assertEquals(mac.deviceId, state.deviceId)
        assertEquals(album.map { it.videoId }, state.queue.map { it.videoId })
        assertEquals(2, state.index)

        val phoneReporter = PlaybackReporter(
            port = object : ReporterPort {
                override suspend fun snapshot() = PlayerSnapshot(
                    videoIds = state.queue.map { it.videoId },
                    index = state.index,
                    positionMs = 1_000,
                    durationMs = 200_000,
                    playing = true,
                    volume = 70,
                    trackAt = { index -> TrackInput(state.queue[index].videoId, state.queue[index].title) }
                )

                override suspend fun send(put: PlaybackPut) =
                    putOutcome { phone.authorized { api, token -> api.putPlaybackState(token, put) } }

                override fun monotonicMs() = System.nanoTime() / 1_000_000

                override fun serverNowMs() = ServerClock.now()
            },
            scope = scope
        )
        phoneReporter.takeOver(PlaybackHandoffInput(state.deviceId, state.sessionId))
        until("the computer gave way") { mac.gaveWay.isNotEmpty() }
        assertEquals(false, mac.player.playing)
        assertTrue(notices.isEmpty(), "nothing went wrong: $notices")
    }

    @Test
    fun `a command to a device that does not let itself be controlled, or is gone, says so and turns the remote off`() = runBlocking(Dispatchers.IO) {
        assumeTrue("no -Pmelogold.testServer", server != null)
        val phone = signedInPhone()
        val mac = computer(remote = false)
        val phoneStream = Stream(MelogoldApi(server!!).also(apis::add), phone.session!!.accessToken, remote = false).also(streams::add)
        withTimeout(10_000) {
            phoneStream.connected.await()
            mac.stream.connected.await()
        }
        val remote = remoteOf(phone, phoneStream.of("playback.updated"), phoneStream.events.filter { it.type == "system.connected" }.map { })
        val notices = CopyOnWriteArrayList<RemoteNotice>()
        scope.launch { remote.notices.collect { notices += it } }

        // Online, but control is off: the list says so, and a command is refused
        val listed = remote.devices().single { it.name == "MacBook Air" }
        assertTrue(listed.online)
        assertEquals(false, listed.controllable)
        remote.connect(listed)
        remote.toggle()
        until("the notice") { notices.isNotEmpty() }
        assertEquals(RemoteNotice.Disabled("MacBook Air"), notices.single())
        assertNull(remote.target.value, "the remote is off")
        assertTrue(mac.player.calls.isEmpty(), "nothing reached the computer")

        // The owner turns control on: the computer is controllable
        mac.reconnect(remote = true)
        withTimeout(10_000) { mac.stream.connected.await() }
        until("it is controllable") { runBlocking { remote.devices() }.single().controllable }
        notices.clear()

        // The computer goes off the network: a command finds it offline
        val again = remote.devices().single()
        remote.connect(again)
        mac.stream.close()
        until("the computer is offline") { !runBlocking { remote.devices() }.single().online }
        remote.pause()
        until("the notice") { notices.isNotEmpty() }
        assertEquals(RemoteNotice.Offline("MacBook Air"), notices.single())
        assertNull(remote.target.value)
    }

    @Test
    fun `the setting decides whether the stream carries remote=1`() {
        val api = MelogoldApi("http://127.0.0.1:8787")
        assertEquals("http://127.0.0.1:8787/auth/me/events?remote=1", api.eventsUrl(remote = true))
        assertEquals("http://127.0.0.1:8787/auth/me/events", api.eventsUrl(remote = false))
        api.close()
    }

    private fun post(url: String, path: String, body: String, token: String? = null): String {
        val connection = URI("$url$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status < HTTP_ERROR) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            check(status < HTTP_ERROR) { "$path: HTTP $status $text" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val HTTP_TIMEOUT_MS = 35_000
        const val HTTP_ERROR = 400
    }
}
