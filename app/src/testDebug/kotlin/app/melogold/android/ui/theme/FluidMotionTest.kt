package app.melogold.android.ui.theme

import app.melogold.android.service.SPECTRUM_BANDS
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** tasks/0029 §3 – §4: the envelopes, the beat and the motion of variant 1. */
class FluidMotionTest {
    private val frame = 1f / 60f

    /** A measurement: [level] in the bands [from] until [to], 0 elsewhere. */
    private fun measure(from: Int, to: Int, level: Float) = FloatArray(SPECTRUM_BANDS) { if (it in from until to) level else 0f }

    private fun bass(level: Float) = measure(0, 10, level)
    private fun highs(level: Float) = measure(30, SPECTRUM_BANDS, level)

    private fun SpectrumEnvelopes.run(seconds: Float, playing: Boolean = true) =
        repeat((seconds / frame).toInt()) { step(frame, playing) }

    @Test
    fun `a step of the bass is a beat, and a quarter second later it is under 0_4`() {
        val audio = SpectrumEnvelopes()
        audio.feed(FloatArray(SPECTRUM_BANDS))
        audio.run(0.3f)
        assertEquals(0f, audio.beat, 1e-6f)

        audio.feed(bass(0.8f))
        assertEquals(1f, audio.beat)
        assertEquals(1, audio.beats)

        // 15 frames of 1/60 s: 0.933^15 = 0.355
        audio.run(0.25f)
        assertTrue(audio.beat < 0.4f, "beat ${audio.beat}")
    }

    @Test
    fun `a steady bass is not a beat again and again`() {
        val audio = SpectrumEnvelopes()
        audio.feed(bass(0.8f))
        audio.run(0.5f)
        repeat(100) {
            audio.feed(bass(0.8f))
            audio.run(0.01f)
        }
        assertEquals(1, audio.beats)
    }

    @Test
    fun `two beats are at least 0_2 s apart`() {
        val audio = SpectrumEnvelopes()
        audio.feed(FloatArray(SPECTRUM_BANDS))
        audio.feed(bass(0.9f))
        assertEquals(1, audio.beats)

        // 0.1 s later another step: too soon
        audio.run(0.1f)
        audio.feed(FloatArray(SPECTRUM_BANDS))
        audio.feed(bass(0.9f))
        assertEquals(1, audio.beats)

        audio.run(0.2f)
        audio.feed(FloatArray(SPECTRUM_BANDS))
        audio.feed(bass(0.9f))
        assertEquals(2, audio.beats)
    }

    @Test
    fun `the highs do not make a beat`() {
        val audio = SpectrumEnvelopes()
        audio.feed(FloatArray(SPECTRUM_BANDS))
        audio.feed(highs(1f))
        assertEquals(0, audio.beats)
    }

    @Test
    fun `the bands rise at 18 a second and fall at 4`() {
        val audio = SpectrumEnvelopes()
        audio.feed(bass(1f))
        audio.step(frame, true)
        // v += (1 − 0) · 18/60
        assertEquals(0.3f, audio.bands[0], 1e-5f)
        assertEquals(0f, audio.bands[20], 1e-6f)

        audio.run(1f)
        assertTrue(audio.bands[0] > 0.99f)

        audio.feed(FloatArray(SPECTRUM_BANDS))
        val before = audio.bands[0]
        audio.step(frame, true)
        assertEquals(before - before * 4f / 60f, audio.bands[0], 1e-5f)
    }

    @Test
    fun `the groups are the means of their bands`() {
        val audio = SpectrumEnvelopes()
        audio.feed(measure(0, SPECTRUM_BANDS, 0.5f))
        audio.run(2f)

        assertEquals(0.5f, audio.bass, 0.01f)
        assertEquals(0.5f, audio.mid, 0.01f)
        assertEquals(0.5f, audio.high, 0.01f)
        assertEquals(0.5f, audio.loud, 0.01f)

        val bassOnly = SpectrumEnvelopes()
        bassOnly.feed(bass(1f))
        bassOnly.run(2f)
        assertEquals(1f, bassOnly.bass, 0.01f)
        assertEquals(0f, bassOnly.high, 1e-3f)
        assertEquals(10f / SPECTRUM_BANDS, bassOnly.loud, 0.01f)
    }

    @Test
    fun `paused, the targets fade and everything goes quiet`() {
        val audio = SpectrumEnvelopes()
        audio.feed(measure(0, SPECTRUM_BANDS, 1f))
        audio.run(1f)
        assertFalse(audio.quiet)

        audio.run(8f, playing = false)
        assertTrue(audio.quiet, "bass ${audio.bass} loud ${audio.loud} beat ${audio.beat}")
    }

    @Test
    fun `the drops follow the bass and the flow follows the highs`() {
        val motion = FluidMotion()
        motion.feed(bass(1f))
        repeat(120) { motion.step(frame, playing = true) }

        assertTrue(motion.lava > 0.9f, "lava ${motion.lava}")
        assertTrue(motion.blobs.all { it > 0.9f })
        assertEquals(0f, motion.liquid, 1e-3f)

        val other = FluidMotion()
        other.feed(highs(1f))
        repeat(120) { other.step(frame, playing = true) }
        assertTrue(other.liquid > 0.9f, "liquid ${other.liquid}")
        assertEquals(0f, other.lava, 1e-3f)
    }

    @Test
    fun `the forces rise at 5 a second and fall at 1_8`() {
        val motion = FluidMotion()
        motion.feed(highs(1f))
        // Let the highs come up first
        repeat(240) { motion.step(frame, playing = true) }
        val high = motion.audio.high

        val fresh = FluidMotion()
        fresh.feed(highs(1f))
        fresh.step(frame, playing = true)
        // The band is 0.3 after the first frame, the group follows at 12/s: 0.3 · 0.2 · (18 highs of 18)
        val groupAfterOne = 0.3f * 12f / 60f
        assertEquals(groupAfterOne * 5f / 60f, fresh.liquid, 1e-5f)
        assertTrue(high > 0.99f)

        // Falling: no more highs
        motion.feed(FloatArray(SPECTRUM_BANDS))
        val liquid = motion.liquid
        motion.step(frame, playing = true)
        assertTrue(motion.liquid < liquid)
    }

    @Test
    fun `the pulse jumps to the beat at once and lets go slowly`() {
        val motion = FluidMotion()
        motion.feed(FloatArray(SPECTRUM_BANDS))
        motion.step(frame, playing = true)
        motion.feed(bass(0.9f))
        motion.step(frame, playing = true)

        // beat · 1 + bass · 0.4 at once
        assertTrue(motion.pulse > 0.9f, "pulse ${motion.pulse}")
        assertTrue(motion.pulse <= 1.2f)

        val peak = motion.pulse
        var last = peak
        repeat(30) {
            motion.step(frame, playing = true)
            assertTrue(motion.pulse <= last + 1e-6f, "falls")
            last = motion.pulse
        }
        assertTrue(last < peak)
    }

    @Test
    fun `the time runs only while it plays, at 0_25 and 0_2 a second in the silence`() {
        val motion = FluidMotion()
        repeat(60) { motion.step(frame, playing = true) }
        assertEquals(0.25f, motion.liquidFlow, 1e-3f)
        assertEquals(0.2f, motion.lavaFlow, 1e-3f)

        val stood = motion.liquidFlow
        repeat(120) { motion.step(frame, playing = false) }
        assertEquals(stood, motion.liquidFlow)
    }

    @Test
    fun `the time runs faster with the music`() {
        val silent = FluidMotion()
        val loud = FluidMotion()
        loud.feed(measure(0, SPECTRUM_BANDS, 1f))
        repeat(180) {
            silent.step(frame, playing = true)
            loud.step(frame, playing = true)
        }
        assertTrue(loud.liquidFlow > silent.liquidFlow * 2f, "${loud.liquidFlow} vs ${silent.liquidFlow}")
        assertTrue(loud.lavaFlow > silent.lavaFlow * 2f, "${loud.lavaFlow} vs ${silent.lavaFlow}")
    }

    @Test
    fun `paused, the forces fade out and the motion settles, the time stands`() {
        val motion = FluidMotion()
        motion.feed(measure(0, SPECTRUM_BANDS, 1f))
        repeat(120) { motion.step(frame, playing = true) }
        assertFalse(motion.settled)
        val flow = motion.liquidFlow

        var frames = 0
        while (!motion.settled && frames < 60 * 20) {
            motion.step(frame, playing = false)
            frames++
        }
        assertTrue(motion.settled, "settles")
        assertTrue(frames < 60 * 10, "in under 10 s: $frames frames")
        assertEquals(flow, motion.liquidFlow)
    }

    @Test
    fun `a still frame has no forces and keeps the time`() {
        val motion = FluidMotion()
        motion.feed(measure(0, SPECTRUM_BANDS, 1f))
        repeat(120) { motion.step(frame, playing = true) }
        val flow = motion.lavaFlow

        motion.freeze()

        assertEquals(0f, motion.liquid)
        assertEquals(0f, motion.lava)
        assertEquals(0f, motion.pulse)
        assertEquals(0f, motion.audio.loud)
        assertTrue(motion.blobs.all { it == 0f })
        assertEquals(flow, motion.lavaFlow)
        assertTrue(motion.settled)
    }

    @Test
    fun `without the sound heard the targets fade though the track plays`() {
        val motion = FluidMotion()
        motion.feed(measure(0, SPECTRUM_BANDS, 1f))
        repeat(60) { motion.step(frame, playing = true) }
        val flow = motion.lavaFlow

        repeat(600) { motion.step(frame, playing = true, listening = false) }
        assertTrue(motion.settled)
        assertTrue(motion.lavaFlow > flow, "the time runs")
    }
}
