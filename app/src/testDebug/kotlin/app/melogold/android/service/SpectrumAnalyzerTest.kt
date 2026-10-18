package app.melogold.android.service

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** tasks/0029 §2: the FFT of 2048 with a step of 512, a Hamming window, 48 bands from 30 Hz to 16 kHz. */
class SpectrumAnalyzerTest {
    private class Measure(val timeUs: Long, val bands: FloatArray)

    private fun analyze(rate: Int = 44_100, samples: Int, startUs: Long = 0L, sample: (Int) -> Double): List<Measure> {
        val out = mutableListOf<Measure>()
        val analyzer = SpectrumAnalyzer { time, bands -> out += Measure(time, bands.copyOf()) }
        analyzer.reset(rate)
        analyzer.sync(startUs)
        repeat(samples) { analyzer.push(sample(it)) }
        return out
    }

    private fun assertNear(expected: Long, actual: Long) = assertTrue(kotlin.math.abs(expected - actual) <= 1L, "expected $expected, was $actual")

    private fun bandOf(hz: Double): Int = (0 until SPECTRUM_BANDS).first { hz >= bandEdgeHz(it) && hz < bandEdgeHz(it + 1) }

    @Test
    fun `the bands run from 30 Hz to 16 kHz by the logarithm`() {
        assertEquals(30.0, bandEdgeHz(0), 1e-9)
        assertEquals(16_000.0, bandEdgeHz(SPECTRUM_BANDS), 1e-6)
        // Equal ratio between the edges
        val ratio = bandEdgeHz(1) / bandEdgeHz(0)
        for (b in 1 until SPECTRUM_BANDS) assertEquals(ratio, bandEdgeHz(b + 1) / bandEdgeHz(b), 1e-9)
    }

    @Test
    fun `a sine of 1 kHz is the loudest in the band that holds 1 kHz`() {
        for (rate in listOf(44_100, 48_000)) {
            val measures = analyze(rate, samples = 8_192) { 0.5 * sin(2.0 * PI * 1_000.0 * it / rate) }
            val bands = measures.last().bands
            val loudest = bands.indices.maxBy { bands[it] }

            assertEquals(bandOf(1_000.0), loudest, "rate $rate: ${bands.toList()}")
            assertTrue(bands[loudest] > 0.5, "a half-scale sine is loud: ${bands[loudest]}")
            // Far from the sine it is quiet: two octaves above and below
            assertTrue(bands[bandOf(250.0)] < bands[loudest] - 0.3, "250 Hz: ${bands[bandOf(250.0)]}")
            assertTrue(bands[bandOf(4_000.0)] < bands[loudest] - 0.3, "4 kHz: ${bands[bandOf(4_000.0)]}")
        }
    }

    @Test
    fun `sines of other pitches are loudest in their own bands`() {
        for (hz in listOf(440.0, 2_500.0, 8_000.0, 14_000.0)) {
            val bands = analyze(samples = 6_144) { 0.5 * sin(2.0 * PI * hz * it / 44_100) }.last().bands
            assertEquals(bandOf(hz), bands.indices.maxBy { bands[it] }, "$hz Hz: ${bands.toList()}")
        }
    }

    /**
     * As on Linux, the bin `k` stands for `[k, k+1)` bin widths (21.5 Hz at 44.1 kHz), the bins of a band are
     * `[floor(low/bin), floor(high/bin))`: at 200 Hz the bands are as narrow as a bin, and the sine lands in the band
     * of its bin, at most one band above.
     */
    @Test
    fun `at the bass the sine is in its band or the next one, the bands being as narrow as a bin`() {
        val bands = analyze(samples = 6_144) { 0.5 * sin(2.0 * PI * 200.0 * it / 44_100) }.last().bands
        val loudest = bands.indices.maxBy { bands[it] }

        assertTrue(loudest in bandOf(200.0)..bandOf(200.0) + 1, "$loudest: ${bands.toList()}")
    }

    @Test
    fun `silence is all zeros`() {
        val measures = analyze(samples = 4_096) { 0.0 }

        assertTrue(measures.isNotEmpty())
        measures.forEach { m -> assertTrue(m.bands.all { it == 0f }, m.bands.toList().toString()) }
    }

    @Test
    fun `the values stay within 0 to 1 even for a full-scale click`() {
        val bands = analyze(samples = 4_096) { if (it % 700 == 0) 1.0 else 0.0 }.last().bands
        assertTrue(bands.all { it in 0f..1f })
    }

    @Test
    fun `a measurement every 512 samples once 2048 are in`() {
        assertEquals(0, analyze(samples = 2_047) { 0.1 }.size)
        assertEquals(1, analyze(samples = 2_048) { 0.1 }.size)
        assertEquals(1, analyze(samples = 2_048 + 511) { 0.1 }.size)
        assertEquals(2, analyze(samples = 2_048 + 512) { 0.1 }.size)
        assertEquals(1 + (10_000 - 2_048) / 512, analyze(samples = 10_000) { 0.1 }.size)
    }

    @Test
    fun `a measurement is stamped with the audio time of the middle of its window`() {
        val rate = 48_000
        // The first sample is at 5 s of audio
        val measures = analyze(rate, samples = 2_048 + 1_024, startUs = 5_000_000L) { 0.1 }

        // The window 0..2047: the middle is the sample 1024, then 512 later
        assertNear(5_000_000L + 1_024L * 1_000_000 / rate, measures[0].timeUs)
        assertNear(5_000_000L + 1_536L * 1_000_000 / rate, measures[1].timeUs)
        assertNear(512L * 1_000_000 / rate, measures[1].timeUs - measures[0].timeUs)
    }

    @Test
    fun `a new buffer time re-anchors the stamps`() {
        val out = mutableListOf<Long>()
        val analyzer = SpectrumAnalyzer { time, _ -> out += time }
        analyzer.reset(48_000)
        analyzer.sync(0L)
        repeat(2_048) { analyzer.push(0.1) }
        // The next buffer says its first sample is at 10 s (a seek the sink did not flush for, a gap)
        analyzer.sync(10_000_000L)
        repeat(512) { analyzer.push(0.1) }

        // The window ends 512 samples after the anchor (the sample 2048 is at 10 s); its middle is 512 samples before it
        assertNear(10_000_000L - 512L * 1_000_000 / 48_000, out.last())
    }

    @Test
    fun `a louder sound is a higher value`() {
        val quiet = analyze(samples = 4_096) { 0.01 * sin(2.0 * PI * 1_000.0 * it / 44_100) }.last().bands
        val loud = analyze(samples = 4_096) { 0.5 * sin(2.0 * PI * 1_000.0 * it / 44_100) }.last().bands

        assertTrue(loud[bandOf(1_000.0)] > quiet[bandOf(1_000.0)] + 0.2)
    }
}
