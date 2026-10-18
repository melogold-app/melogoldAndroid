package app.melogold.android.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/*
 * The sound of "Now playing" as 48 bands (tasks/0029): the same numbers on every platform; the reference is Linux, where
 * GStreamer's `spectrum` gives the bins and `examples/final_lab.rs` (`Analysis::feed`) turns them into bands. This file
 * is the math only — no Android API, so the tests run on the JVM.
 */

/** The bands of the spectrum, low to high. */
const val SPECTRUM_BANDS = 48

/** The FFT window, samples (N). */
const val SPECTRUM_WINDOW = 2_048

/** A measurement is made every this many new samples. */
const val SPECTRUM_HOP = 512

private const val FIRST_BAND_HZ = 30.0
private const val LAST_BAND_HZ = 16_000.0

/** The band `b` starts here: `30·(16000/30)^(b/48)` Hz; `b = 48` is the end of the last band. */
internal fun bandEdgeHz(b: Int): Double =
    FIRST_BAND_HZ * (LAST_BAND_HZ / FIRST_BAND_HZ).pow(b / SPECTRUM_BANDS.toDouble())

/** The dB range the value of a band is spread over: `-70 dB` is 0, `-15 dB` is 1 (after the tilt). */
private const val FLOOR_DB = -70.0
private const val RANGE_DB = 55.0

/** +3 dB per octave, so the highs do not sleep. */
private const val TILT_DB_PER_OCTAVE = 3.0

/**
 * Cuts the mono signal into windows of [SPECTRUM_WINDOW] samples every [SPECTRUM_HOP] and gives 48 band values (0..1)
 * of each window, stamped with the **audio time** of the middle of the window.
 *
 * 1. a Hamming window, an FFT; the power of a bin is `P = (re² + im²) / N²` (the samples are -1..1);
 * 2. 48 bands from 30 Hz to 16 kHz by the logarithm: band `b` is `[30·(16000/30)^(b/48), 30·(16000/30)^((b+1)/48))`;
 *    its power is the **mean** of the linear power of the bins in it (at least one bin);
 * 3. the value is `clamp((10·log10(P) + 3·log2(f_low/30) + 70) / 55, 0, 1)`; silence is 0.
 *
 * Not thread safe: one thread feeds it.
 *
 * @param onMeasure called for each window with the audio time of its middle (µs) and the band values; the array is
 * reused for the next window, copy what is needed
 */
class SpectrumAnalyzer(private val onMeasure: (timeUs: Long, bands: FloatArray) -> Unit) {
    private val ring = DoubleArray(SPECTRUM_WINDOW)
    private val re = DoubleArray(SPECTRUM_WINDOW)
    private val im = DoubleArray(SPECTRUM_WINDOW)
    private val bands = FloatArray(SPECTRUM_BANDS)

    // Per band: the bins [from, to) and the tilt, for the sample rate
    private val from = IntArray(SPECTRUM_BANDS)
    private val to = IntArray(SPECTRUM_BANDS)
    private val tilt = DoubleArray(SPECTRUM_BANDS)

    private var sampleRate = DEFAULT_RATE

    /** Samples since [reset]. */
    private var seen = 0L

    /** The sample number [anchorSample] is at [anchorUs] of audio time. */
    private var anchorSample = 0L
    private var anchorUs = 0L

    init {
        reset(DEFAULT_RATE)
    }

    /** Starts anew at [sampleRate] Hz: the old samples are forgotten (a seek, a new track, a new format). */
    fun reset(sampleRate: Int) {
        this.sampleRate = sampleRate.takeIf { it > 0 } ?: DEFAULT_RATE
        seen = 0L
        anchorSample = 0L
        anchorUs = 0L
        ring.fill(0.0)

        // The bin `k` is at `k · rate / N` Hz
        val binHz = this.sampleRate.toDouble() / SPECTRUM_WINDOW
        val bins = SPECTRUM_WINDOW / 2
        for (band in 0 until SPECTRUM_BANDS) {
            val low = bandEdgeHz(band)
            val high = bandEdgeHz(band + 1)
            val start = floor(low / binHz).toInt().coerceAtMost(bins - 1)
            from[band] = start
            to[band] = floor(high / binHz).toInt().coerceAtLeast(start + 1).coerceAtMost(bins)
            tilt[band] = TILT_DB_PER_OCTAVE * ln(low / FIRST_BAND_HZ) / LN_2
        }
    }

    /** The next sample that comes is at [timeUs] of audio time (the time of the buffer it is in). */
    fun sync(timeUs: Long) {
        anchorSample = seen
        anchorUs = timeUs
    }

    /** Takes [count] mono samples (-1..1) of [samples]. */
    fun feed(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) push(samples[i].toDouble())
    }

    /** Takes one mono sample (-1..1). */
    fun push(sample: Double) {
        ring[(seen % SPECTRUM_WINDOW).toInt()] = sample
        seen++
        if (seen >= SPECTRUM_WINDOW && (seen - SPECTRUM_WINDOW) % SPECTRUM_HOP == 0L) measure()
    }

    private fun measure() {
        // The oldest sample of the window is where `seen` points to in the ring
        val start = (seen % SPECTRUM_WINDOW).toInt()
        for (i in 0 until SPECTRUM_WINDOW) {
            re[i] = ring[(start + i) % SPECTRUM_WINDOW] * HAMMING[i]
            im[i] = 0.0
        }
        fft(re, im)

        val scale = 1.0 / (SPECTRUM_WINDOW.toDouble() * SPECTRUM_WINDOW)
        for (band in 0 until SPECTRUM_BANDS) {
            var power = 0.0
            for (bin in from[band] until to[band]) power += (re[bin] * re[bin] + im[bin] * im[bin]) * scale
            power /= to[band] - from[band]
            bands[band] = if (power <= 0.0) {
                0f
            } else {
                ((10.0 * log10(power) + tilt[band] - FLOOR_DB) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
            }
        }

        // The middle of the window is the sample N/2 before the newest one
        val middle = seen - SPECTRUM_WINDOW / 2
        val timeUs = anchorUs + ((middle - anchorSample) * 1_000_000.0 / sampleRate).toLong()
        onMeasure(timeUs, bands)
    }

    private companion object {
        const val DEFAULT_RATE = 44_100
        val LN_2 = ln(2.0)

        /** `0.54 − 0.46·cos(2πi/(N−1))` */
        val HAMMING = DoubleArray(SPECTRUM_WINDOW) { 0.54 - 0.46 * cos(2.0 * PI * it / (SPECTRUM_WINDOW - 1)) }

        val COS = DoubleArray(SPECTRUM_WINDOW / 2) { cos(2.0 * PI * it / SPECTRUM_WINDOW) }
        val SIN = DoubleArray(SPECTRUM_WINDOW / 2) { -sin(2.0 * PI * it / SPECTRUM_WINDOW) }

        val REVERSED = IntArray(SPECTRUM_WINDOW).also { table ->
            val bits = Integer.numberOfTrailingZeros(SPECTRUM_WINDOW)
            for (i in table.indices) table[i] = Integer.reverse(i) ushr (32 - bits)
        }

        /** In-place radix-2 FFT of [SPECTRUM_WINDOW] points. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until SPECTRUM_WINDOW) {
                val j = REVERSED[i]
                if (j > i) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var size = 2
            while (size <= SPECTRUM_WINDOW) {
                val half = size / 2
                val step = SPECTRUM_WINDOW / size
                var block = 0
                while (block < SPECTRUM_WINDOW) {
                    for (k in 0 until half) {
                        val wr = COS[k * step]
                        val wi = SIN[k * step]
                        val a = block + k
                        val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                    block += size
                }
                size *= 2
            }
        }
    }
}
