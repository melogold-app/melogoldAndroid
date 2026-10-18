package app.melogold.android.service

import androidx.media3.common.C
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0029 §2 item 5: a measurement is picked by the audio time that is heard, not by when it came. */
class SpectrumTapTest {
    private val rate = 48_000

    private fun stereo16(frames: Int, from: Int = 0, hz: Double = 1_000.0): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            val value = (0.5 * sin(2.0 * PI * hz * (from + it) / rate) * 32_767).toInt().toShort()
            buffer.putShort(value).putShort(value)
        }
        buffer.flip()
        return buffer
    }

    private fun tap() = SpectrumTap().apply {
        listening = true
        flush(rate, 2, C.ENCODING_PCM_16BIT)
    }

    /** Feeds [seconds] of a sine as buffers of 1024 frames; the first one is at [startUs]. */
    private fun SpectrumTap.play(seconds: Double, startUs: Long = 0L) {
        var frame = 0
        val total = (seconds * rate).toInt()
        while (frame < total) {
            val n = minOf(1_024, total - frame)
            onInputBuffer(startUs + frame * 1_000_000L / rate)
            handleBuffer(stereo16(n, frame))
            frame += n
        }
    }

    private fun SpectrumTap.drained(untilUs: Long): Int {
        var n = 0
        drain(untilUs) { n++ }
        return n
    }

    @Test
    fun `only the measurements up to the heard time come out, oldest first`() {
        val tap = tap()
        tap.play(1.0)

        // The first window is 2048 samples: the middle at 1024 / 48000 s = 21 ms, then every 10.7 ms
        assertEquals(0, tap.drained(untilUs = 20_000L))
        assertEquals(1, tap.drained(untilUs = 22_000L))
        // 500 ms: (500 ms − 21.3 ms) / 10.67 ms = 44.9 → 44 more
        assertEquals(44, tap.drained(untilUs = 500_000L))
        // And the rest: 1 s of audio is 48000 samples → (48000 − 2048) / 512 + 1 = 90 in all
        assertEquals(90 - 45, tap.drained(untilUs = Long.MAX_VALUE))
        assertEquals(0, tap.drained(untilUs = Long.MAX_VALUE))
    }

    @Test
    fun `the time of a measurement follows the time of the buffers, not the count`() {
        val tap = tap()
        // Audio that starts at 100 s (a track in the middle of the queue, or after a seek)
        tap.play(0.5, startUs = 100_000_000L)

        assertEquals(0, tap.drained(untilUs = 99_000_000L))
        assertTrue(tap.drained(untilUs = 100_030_000L) >= 1)
    }

    @Test
    fun `a flush forgets the measurements`() {
        val tap = tap()
        tap.play(0.5)
        tap.flush(rate, 2, C.ENCODING_PCM_16BIT)

        assertEquals(0, tap.drained(untilUs = Long.MAX_VALUE))
    }

    @Test
    fun `while nobody listens nothing is measured`() {
        val tap = tap()
        tap.listening = false
        tap.play(0.5)

        assertEquals(0, tap.drained(untilUs = Long.MAX_VALUE))
    }

    @Test
    fun `the heard position moves on from the last report while it plays`() {
        val tap = tap()
        assertNull(tap.heardPositionUs(playing = true))

        tap.onPosition(2_000_000L)
        val now = System.nanoTime()
        assertEquals(2_000_000L, tap.heardPositionUs(playing = false, nowNanos = now))
        // 100 ms later: the report is 100 ms old
        val later = tap.heardPositionUs(playing = true, nowNanos = now + 100_000_000L)!!
        assertTrue(later in 2_090_000L..2_110_000L + 1_000L, "$later")
        // A report that is long old is not believed for more than a quarter second
        assertEquals(2_250_000L, tap.heardPositionUs(playing = true, nowNanos = now + 10_000_000_000L)!!, 5_000.0)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) =
        assertTrue(kotlin.math.abs(expected - actual) <= delta, "expected $expected, was $actual")
}
