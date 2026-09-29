package app.melogold.android.sync.remote

import app.melogold.android.sync.api.PlaybackCommandPayload
import app.melogold.android.sync.api.TrackDto
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** tasks/0018: what a command from another device does to the player, one action at a time. */
class RemoteCommandsTest {
    private class FakePlayer : PlayerPort {
        val calls = mutableListOf<String>()
        override var playWhenReady = false

        override fun play() {
            playWhenReady = true
            calls += "play"
        }

        override fun pause() {
            playWhenReady = false
            calls += "pause"
        }

        override fun next() {
            calls += "next"
        }

        override fun previous() {
            calls += "previous"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
        }

        override fun setVolume(percent: Int) {
            calls += "volume $percent"
        }

        override fun playQueue(tracks: List<TrackDto>, index: Int) {
            calls += "queue ${tracks.map { it.videoId }} at $index"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private fun command(action: String, positionMs: Long? = null, volume: Int? = null, queue: List<TrackDto>? = null, index: Int? = null) =
        PlaybackCommandPayload("c1", "mac-id", "MacBook Air", action, positionMs, volume, queue, index)

    private fun track(id: String) = TrackDto(videoId = id, title = id)

    private val player = FakePlayer()
    private val executor = RemoteCommandExecutor(player)

    @Test
    fun `play, pause, next, previous and stop go to the player`() {
        assertTrue(executor.execute(command("play")))
        assertTrue(executor.execute(command("pause")))
        assertTrue(executor.execute(command("next")))
        assertTrue(executor.execute(command("previous")))
        assertTrue(executor.execute(command("stop")))

        assertEquals(listOf("play", "pause", "next", "previous", "stop"), player.calls)
    }

    @Test
    fun `toggle plays what is paused and pauses what plays`() {
        player.playWhenReady = false
        executor.execute(command("toggle"))
        executor.execute(command("toggle"))

        assertEquals(listOf("play", "pause"), player.calls)
    }

    @Test
    fun `seek needs a position and goes to it`() {
        assertTrue(executor.execute(command("seek", positionMs = 83_000)))
        assertFalse(executor.execute(command("seek")))
        assertTrue(executor.execute(command("seek", positionMs = -5)))

        assertEquals(listOf("seek 83000", "seek 0"), player.calls)
    }

    @Test
    fun `volume needs a level, between 0 and 100`() {
        assertTrue(executor.execute(command("volume", volume = 40)))
        assertFalse(executor.execute(command("volume")))
        assertTrue(executor.execute(command("volume", volume = 250)))
        assertTrue(executor.execute(command("volume", volume = -3)))

        assertEquals(listOf("volume 40", "volume 100", "volume 0"), player.calls)
    }

    @Test
    fun `play_queue plays the queue from the index`() {
        val queue = listOf(track("aaaaaaaaaa1"), track("aaaaaaaaaa2"), track("aaaaaaaaaa3"))

        assertTrue(executor.execute(command("play_queue", queue = queue, index = 2)))

        assertEquals(listOf("queue [aaaaaaaaaa1, aaaaaaaaaa2, aaaaaaaaaa3] at 2"), player.calls)
    }

    @Test
    fun `play_queue without a queue, an index, or with an index outside is not carried out`() {
        val queue = listOf(track("aaaaaaaaaa1"))

        assertFalse(executor.execute(command("play_queue")))
        assertFalse(executor.execute(command("play_queue", queue = emptyList(), index = 0)))
        assertFalse(executor.execute(command("play_queue", queue = queue)))
        assertFalse(executor.execute(command("play_queue", queue = queue, index = 1)))
        assertFalse(executor.execute(command("play_queue", queue = queue, index = -1)))

        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun `an action of a newer version is left alone`() {
        assertFalse(executor.execute(command("cast")))
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun `the notice comes at most every thirty seconds`() {
        val throttle = NoticeThrottle()

        assertTrue(throttle.allow(1_000))
        assertFalse(throttle.allow(1_000 + 29_999))
        assertTrue(throttle.allow(1_000 + 30_000))
        assertFalse(throttle.allow(1_000 + 30_001))
    }

    @Test
    fun `the position counts on from the state while it plays`() {
        // Playing: from 83 s at t, 5 s later it is 88 s
        assertEquals(88_000L, extrapolatePosition(83_000, atMs = 10_000, playing = true, nowMs = 15_000, durationMs = 200_000))
        // Paused: it stays
        assertEquals(83_000L, extrapolatePosition(83_000, atMs = 10_000, playing = false, nowMs = 15_000, durationMs = 200_000))
        // Not past the end (the last second is the player's)
        assertEquals(199_000L, extrapolatePosition(190_000, atMs = 0, playing = true, nowMs = 60_000, durationMs = 200_000))
        // A state from the future (clocks differ) does not go back
        assertEquals(83_000L, extrapolatePosition(83_000, atMs = 20_000, playing = true, nowMs = 15_000, durationMs = 200_000))
        // Never more than three minutes on: a state that old is not a promise
        assertEquals(83_000L + 180_000L, extrapolatePosition(83_000, atMs = 0, playing = true, nowMs = 900_000, durationMs = 3_600_000))
        // Without a duration
        assertEquals(93_000L, extrapolatePosition(83_000, atMs = 0, playing = true, nowMs = 10_000, durationMs = null))
    }

    @Test
    fun `the clock of the server moves the times this device tells`() {
        ServerClock.reset()
        assertEquals(1_000L, ServerClock.now(local = 1_000))

        ServerClock.update("2026-09-30T10:00:05.000Z", local = java.time.Instant.parse("2026-09-30T10:00:00.000Z").toEpochMilli())
        assertEquals(java.time.Instant.parse("2026-09-30T10:00:15.000Z").toEpochMilli(), ServerClock.now(local = java.time.Instant.parse("2026-09-30T10:00:10.000Z").toEpochMilli()))

        ServerClock.update("not a time")
        ServerClock.reset()
    }
}
