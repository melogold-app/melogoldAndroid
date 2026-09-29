package app.melogold.android.sync.remote

import app.melogold.android.sync.api.PlaybackPut
import app.melogold.android.sync.api.PlaybackState
import app.melogold.android.sync.api.PlaybackHandoffInput
import app.melogold.android.sync.api.TrackInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0018: what this device tells the server about its player, and how often. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    private fun id(index: Int) = "video%06d".format(index).padEnd(11, 'x').take(11)

    private fun ids(count: Int) = List(count) { id(it) }

    private fun snap(
        queue: List<String> = ids(5),
        index: Int = 0,
        position: Long = 0,
        playing: Boolean = true,
        volume: Int? = 60
    ) = PlayerSnapshot(
        videoIds = queue,
        index = index,
        positionMs = position,
        durationMs = 200_000,
        playing = playing,
        volume = volume,
        trackAt = { TrackInput(videoId = queue[it], title = "Track ${queue[it]}") }
    )

    /** The server and the player, in virtual time: every request with the time it was made at. */
    private class Fakes(private val scope: TestScope) : ReporterPort {
        var snapshot: PlayerSnapshot? = null
        val puts = mutableListOf<Pair<Long, PlaybackPut>>()
        var outcome: (PlaybackPut) -> PutOutcome = { PutOutcome.Applied }

        override suspend fun snapshot() = snapshot

        override suspend fun send(put: PlaybackPut): PutOutcome {
            puts += scope.currentTime to put
            return outcome(put)
        }

        override fun monotonicMs() = scope.currentTime

        override fun serverNowMs() = 1_790_000_000_000L + scope.currentTime
    }

    private fun TestScope.reporter(
        fakes: Fakes,
        sizeOf: (PlaybackPut) -> Int = { 0 },
        onHandedOff: (PlaybackState?) -> Unit = {}
    ): PlaybackReporter {
        var session = 0
        return PlaybackReporter(
            port = fakes,
            scope = backgroundScope,
            newSessionId = { "session-${++session}" },
            onHandedOff = onHandedOff,
            sizeOf = sizeOf
        )
    }

    @Test
    fun `nothing is told until a sound has played`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap() }
        val reporter = reporter(fakes)

        reporter.changed()
        advanceTimeBy(5_000)

        assertTrue(fakes.puts.isEmpty())

        reporter.soundPlayed()
        advanceTimeBy(1)
        assertEquals(1, fakes.puts.size)
    }

    @Test
    fun `the first state has the session, the queue, the place, the volume and the time on the server clock`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(3), index = 1, position = 83_000) }
        val reporter = reporter(fakes)

        reporter.soundPlayed()
        runCurrent()

        val put = fakes.puts.single().second
        assertEquals("session-1", put.sessionId)
        assertEquals(0L, put.queueVersion)
        assertEquals(1, put.index)
        assertEquals(83_000L, put.positionMs)
        assertEquals(200_000L, put.durationMs)
        assertEquals(true, put.playing)
        assertEquals(60, put.volume)
        assertEquals(ids(3), put.queue?.map { it.videoId })
        assertNull(put.handoffFrom)
        assertTrue(put.at.startsWith("2026-"), put.at)
        assertTrue(put.at.endsWith("Z"))
    }

    @Test
    fun `a burst of changes is one request, and never more often than once a second`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap() }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        repeat(20) {
            reporter.changed()
            advanceTimeBy(50)
        }
        advanceTimeBy(5_000)

        assertTrue(fakes.puts.size in 2..3, "the first, then the burst gathered: ${fakes.puts.map { it.first }}")
        fakes.puts.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 1_000, "${a.first} then ${b.first}") }
    }

    @Test
    fun `one change on its own goes out at once`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap() }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        advanceTimeBy(10_000)
        val before = fakes.puts.size

        reporter.changed()
        runCurrent()

        assertEquals(before + 1, fakes.puts.size)
        assertEquals(currentTime, fakes.puts.last().first)
    }

    @Test
    fun `the queue goes only when it is new, and its version grows with each change`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(4), index = 0) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        // Only the place in it moves: no queue
        fakes.snapshot = snap(queue = ids(4), index = 1, position = 5_000)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        assertNull(fakes.puts.last().second.queue)
        assertEquals(1, fakes.puts.last().second.index)
        assertEquals(0L, fakes.puts.last().second.queueVersion)

        // A track added to it: a new version, and the queue again
        fakes.snapshot = snap(queue = ids(5), index = 1)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        assertEquals(1L, fakes.puts.last().second.queueVersion)
        assertEquals(ids(5), fakes.puts.last().second.queue?.map { it.videoId })
    }

    @Test
    fun `a long queue is a window of two hundred from fifty before the current track`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(500), index = 300) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        val put = fakes.puts.single().second
        assertEquals(200, put.queue?.size)
        assertEquals(id(250), put.queue?.first()?.videoId)
        assertEquals(id(449), put.queue?.last()?.videoId)
        assertEquals(50, put.index, "the index is in the window")
    }

    @Test
    fun `the window of a short tail and of the start`() {
        assertEquals(250 until 450, queueWindowOf(500, 300))
        assertEquals(0 until 200, queueWindowOf(500, 10))
        assertEquals(430 until 500, queueWindowOf(500, 480))
        assertEquals(0 until 5, queueWindowOf(5, 2))
        assertEquals(300 until 301, queueWindowOf(500, 300, maxItems = 1))
        assertEquals(275 until 325, queueWindowOf(500, 300, maxItems = 50))
        assertEquals(IntRange.EMPTY, queueWindowOf(0, 0))
        assertEquals(IntRange.EMPTY, queueWindowOf(10, 11))
    }

    @Test
    fun `moving on within the window sends the new place, out of it the queue again`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(500), index = 300) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        fakes.snapshot = snap(queue = ids(500), index = 310)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        assertNull(fakes.puts.last().second.queue)
        assertEquals(60, fakes.puts.last().second.index)

        fakes.snapshot = snap(queue = ids(500), index = 460)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        val put = fakes.puts.last().second
        assertEquals(id(410), put.queue?.first()?.videoId)
        assertEquals(50, put.index)
    }

    @Test
    fun `files of the device are left out, and a file playing tells nothing`() = runTest {
        val queue = listOf(id(1), "local:42", id(2), id(3))
        val fakes = Fakes(this).apply { snapshot = snap(queue = queue, index = 2) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        val put = fakes.puts.single().second
        assertEquals(listOf(id(1), id(2), id(3)), put.queue?.map { it.videoId })
        assertEquals(1, put.index, "the file before it does not count")

        fakes.snapshot = snap(queue = queue, index = 1)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        assertEquals(1, fakes.puts.size, "the current track is a file: nothing is sent")
    }

    @Test
    fun `nothing to tell is not told`() = runTest {
        val fakes = Fakes(this).apply { snapshot = null }
        val reporter = reporter(fakes)

        reporter.soundPlayed()
        advanceTimeBy(5_000)

        assertTrue(fakes.puts.isEmpty())
    }

    @Test
    fun `the server that has no queue of this session asks for it and gets it`() = runTest {
        val fakes = Fakes(this).apply {
            snapshot = snap(queue = ids(3), index = 0)
            outcome = { put -> if (put.queue == null) PutOutcome.QueueRequired else PutOutcome.Applied }
        }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        // The first went with its queue; now the queue is lost on the server's side
        fakes.snapshot = snap(queue = ids(3), index = 1)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()

        val last = fakes.puts.last().second
        assertTrue(last.queue != null, "it is sent again")
        assertEquals(2, fakes.puts.size - 1, "the one without it and the one with it, after the first")
    }

    @Test
    fun `a body that is too large is sent again with half the window`() = runTest {
        val fakes = Fakes(this).apply {
            snapshot = snap(queue = ids(500), index = 300)
            outcome = { put -> if ((put.queue?.size ?: 0) > 100) PutOutcome.TooLarge else PutOutcome.Applied }
        }
        val reporter = reporter(fakes)

        reporter.soundPlayed()
        runCurrent()

        assertEquals(listOf(200, 100), fakes.puts.map { it.second.queue?.size })
        assertEquals(id(300), fakes.puts.last().second.queue?.get(fakes.puts.last().second.index)?.videoId)
    }

    @Test
    fun `a body over the limit is narrowed before it goes`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(500), index = 300) }
        val reporter = reporter(fakes, sizeOf = { put -> (put.queue?.size ?: 0) * 1_000 })

        reporter.soundPlayed()
        runCurrent()

        val put = fakes.puts.single().second
        assertTrue(put.queue!!.size * 1_000 <= 120 * 1024, "${put.queue!!.size} tracks")
        assertEquals(id(300), put.queue!![put.index].videoId)
    }

    @Test
    fun `when another device took the session over this one gives way and starts a new session`() = runTest {
        val fakes = Fakes(this).apply {
            snapshot = snap()
            outcome = { PutOutcome.HandedOff(null) }
        }
        var handedOff = 0
        val reporter = reporter(fakes, onHandedOff = { handedOff++ })

        reporter.soundPlayed()
        runCurrent()

        assertEquals(1, handedOff)
        assertEquals("session-2", reporter.sessionId)
    }

    @Test
    fun `while it plays the state is repeated every minute, paused it is not`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(playing = true) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()

        advanceTimeBy(3 * 60_000 + 500)
        assertEquals(listOf(0L, 60_000L, 120_000L, 180_000L), fakes.puts.map { it.first })

        fakes.snapshot = snap(playing = false)
        reporter.changed()
        advanceTimeBy(1_500)
        assertEquals(false, fakes.puts.last().second.playing, "the pause itself is told")
        val count = fakes.puts.size
        advanceTimeBy(5 * 60_000)
        assertEquals(count, fakes.puts.size, "a paused player is not heard from every minute")
    }

    @Test
    fun `without a network the last state is tried again in a while`() = runTest {
        var reachable = false
        val fakes = Fakes(this).apply {
            snapshot = snap()
            outcome = { if (reachable) PutOutcome.Applied else PutOutcome.Unreachable }
        }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        assertEquals(1, fakes.puts.size)

        reachable = true
        advanceTimeBy(15_500)

        assertEquals(2, fakes.puts.size)
        assertEquals(15_000L, fakes.puts.last().first)
    }

    @Test
    fun `while this device controls another it tells nothing, and tells at once when it stops`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap() }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        val before = fakes.puts.size

        reporter.paused = true
        reporter.changed()
        advanceTimeBy(10_000)
        assertEquals(before, fakes.puts.size)

        reporter.paused = false
        runCurrent()
        assertEquals(before + 1, fakes.puts.size)
    }

    @Test
    fun `listening here names the device and the session it takes over, in a session of its own, with the queue`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(queue = ids(3), index = 1) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        val first = reporter.sessionId

        reporter.takeOver(PlaybackHandoffInput(deviceId = "mac-id", sessionId = "mac-session"))
        advanceTimeBy(1_500)

        val put = fakes.puts.last().second
        assertNotEquals(first, put.sessionId)
        assertEquals(PlaybackHandoffInput("mac-id", "mac-session"), put.handoffFrom)
        assertEquals(ids(3), put.queue?.map { it.videoId })

        // Only that one: the next state is an ordinary one
        fakes.snapshot = snap(queue = ids(3), index = 2)
        reporter.changed()
        advanceTimeBy(1_500)
        assertNull(fakes.puts.last().second.handoffFrom)
    }

    @Test
    fun `the volume is between 0 and 100 or left out`() = runTest {
        val fakes = Fakes(this).apply { snapshot = snap(volume = 140) }
        val reporter = reporter(fakes)
        reporter.soundPlayed()
        runCurrent()
        assertEquals(100, fakes.puts.last().second.volume)

        fakes.snapshot = snap(volume = null)
        advanceTimeBy(1_500)
        reporter.changed()
        runCurrent()
        assertNull(fakes.puts.last().second.volume)
    }
}
