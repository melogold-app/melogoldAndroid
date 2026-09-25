package app.melogold.android.service

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Bands of the "now playing" bars: low · mid · high. */
const val AUDIO_BANDS = 3

/** How long decoded audio waits in the output buffer before it is heard (the bars follow the ear). */
private const val OUTPUT_DELAY_MS = 200L

/** Without new audio for this long (paused, stopped) the bars rest. */
private const val SILENT_AFTER_MS = 400L

private const val HISTORY = 128
private const val LOW_CUTOFF_HZ = 250.0
private const val HIGH_CUTOFF_HZ = 2_500.0

/** The loudest level a band had lately falls this much per second: the bars keep the track's range. */
private const val PEAK_FALL_PER_SECOND = 0.35f

/**
 * How loud the three bands of what plays now are (0..1, low · mid · high), read from the decoded
 * audio on its way to the output through a [TeeAudioProcessor]: the "now playing" bars move with
 * the music. No microphone permission, unlike `android.media.audiofx.Visualizer`.
 *
 * The audio thread writes a short history stamped with the time; [read] takes the levels of about
 * [OUTPUT_DELAY_MS] ago, when that audio is heard.
 */
@OptIn(UnstableApi::class)
class AudioLevels : TeeAudioProcessor.AudioBufferSink {
    private val times = LongArray(HISTORY)
    private val values = FloatArray(HISTORY * AUDIO_BANDS)

    @Volatile
    private var head = -1

    private var sampleRate = 44_100
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var lowAlpha = 0f
    private var highAlpha = 0f
    private var low = 0f
    private var lowAndMid = 0f
    private val peaks = FloatArray(AUDIO_BANDS) { 1e-4f }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz.takeIf { it > 0 } ?: 44_100
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        lowAlpha = onePole(LOW_CUTOFF_HZ)
        highAlpha = onePole(HIGH_CUTOFF_HZ)
        low = 0f
        lowAndMid = 0f
    }

    @Suppress("CyclomaticComplexMethod")
    override fun handleBuffer(buffer: ByteBuffer) {
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frames = data.remaining() / (bytesPerSample * channelCount)
        if (frames == 0) return

        val energy = DoubleArray(AUDIO_BANDS)
        repeat(frames) {
            // One channel is enough for a level, and cheaper than a mix
            val sample = if (encoding == C.ENCODING_PCM_FLOAT) data.float else data.short / 32_768f
            data.position(data.position() + (channelCount - 1) * bytesPerSample)

            low += lowAlpha * (sample - low)
            lowAndMid += highAlpha * (sample - lowAndMid)
            energy[0] += (low * low).toDouble()
            (lowAndMid - low).let { energy[1] += (it * it).toDouble() }
            (sample - lowAndMid).let { energy[2] += (it * it).toDouble() }
        }

        val seconds = frames.toFloat() / sampleRate
        val fall = (1f - PEAK_FALL_PER_SECOND).pow(seconds)
        val next = (head + 1) % HISTORY
        repeat(AUDIO_BANDS) { band ->
            val rms = sqrt(energy[band] / frames).toFloat()
            peaks[band] = max(rms, peaks[band] * fall).coerceAtLeast(1e-4f)
            // Relative to the band's recent peak, a little compressed: quiet parts still move
            values[next * AUDIO_BANDS + band] = (rms / peaks[band]).coerceIn(0f, 1f).pow(0.7f)
        }
        times[next] = SystemClock.uptimeMillis()
        head = next
    }

    /** Writes the levels heard now into [into] (size [AUDIO_BANDS]); zeros while nothing plays. */
    fun read(into: FloatArray, now: Long = SystemClock.uptimeMillis()) {
        val newest = head
        if (newest < 0 || now - times[newest] > OUTPUT_DELAY_MS + SILENT_AFTER_MS) {
            into.fill(0f)
            return
        }
        // The newest entry old enough to be heard now
        var index = newest
        var steps = 0
        while (times[index] > now - OUTPUT_DELAY_MS && steps < HISTORY - 1) {
            val previous = (index - 1 + HISTORY) % HISTORY
            if (times[previous] == 0L || times[previous] > times[index]) break
            index = previous
            steps++
        }
        repeat(AUDIO_BANDS) { band -> into[band] = values[index * AUDIO_BANDS + band] }
    }

    private fun onePole(cutoffHz: Double) = (1 - exp(-2 * PI * cutoffHz / sampleRate)).toFloat()
}
