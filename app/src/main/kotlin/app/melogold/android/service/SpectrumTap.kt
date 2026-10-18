package app.melogold.android.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** The queue holds this many measurements (about 5 s at 86 a second): the output buffer is far shorter. */
private const val QUEUE = 512

/** The heard position is guessed ahead of its last report by this long at most (µs). */
private const val MAX_EXTRAPOLATION_US = 250_000L

private const val NOT_SET = Long.MIN_VALUE

/**
 * The 48 bands of the sound that plays (tasks/0029), read from the decoded audio on its way to the output through a
 * [TeeAudioProcessor], as [AudioLevels]: no microphone permission, unlike `android.media.audiofx.Visualizer`. The
 * numbers are in [SpectrumAnalyzer].
 *
 * The audio thread cuts the audio into measurements, each stamped with the **audio time** of the middle of its window
 * (the time of the buffer it is in is given by [SpectrumAudioSink]). The screen takes ([drain]) those that sound **now**,
 * by the position the sink says is heard ([heardPositionUs]), and not the newest: the decoder is ahead of the ear by
 * the output buffer.
 *
 * It works only while [listening] (the backdrop is on the screen): else the audio thread does nothing for it.
 */
@OptIn(UnstableApi::class)
class SpectrumTap : TeeAudioProcessor.AudioBufferSink {
    private val lock = Any()

    // The queue of measurements: oldest at `head`
    private val times = LongArray(QUEUE)
    private val values = Array(QUEUE) { FloatArray(SPECTRUM_BANDS) }
    private var head = 0
    private var size = 0

    private val analyzer = SpectrumAnalyzer(::store)
    private val scratch = FloatArray(SPECTRUM_BANDS)

    // The audio thread only
    private var sampleRate = 44_100
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var wasListening = false
    private var lastInputUs = NOT_SET
    private var syncPending = false

    // The last report of the sink, with the time it came
    private var positionUs = NOT_SET
    private var positionAtNanos = 0L

    /** Set while the living backdrop is on the screen. */
    @Volatile
    var listening = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz.takeIf { it > 0 } ?: 44_100
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        lastInputUs = NOT_SET
        syncPending = false
        analyzer.reset(sampleRate)
        synchronized(lock) {
            head = 0
            size = 0
        }
    }

    /** The sink is given a buffer whose first sample is at [presentationTimeUs] of audio time. */
    internal fun onInputBuffer(presentationTimeUs: Long) {
        if (presentationTimeUs == lastInputUs) return
        lastInputUs = presentationTimeUs
        syncPending = true
    }

    /** The sink says [positionUs] of audio time is heard now. */
    internal fun onPosition(positionUs: Long) {
        if (positionUs == C.TIME_UNSET || positionUs == AudioSink.CURRENT_POSITION_NOT_SET) return
        synchronized(lock) {
            this.positionUs = positionUs
            positionAtNanos = System.nanoTime()
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val listens = listening
        if (!listens) {
            wasListening = false
            return
        }
        if (!wasListening) {
            // Just turned on: what the analyzer holds is old
            wasListening = true
            analyzer.reset(sampleRate)
            synchronized(lock) {
                head = 0
                size = 0
            }
        }

        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = data.remaining() / (bytesPerSample * channelCount)
        if (frames == 0) return

        if (syncPending) {
            analyzer.sync(lastInputUs)
            syncPending = false
        }
        repeat(frames) {
            // The mean of the channels is the mono signal
            var sum = 0.0
            repeat(channelCount) {
                sum += if (encoding == C.ENCODING_PCM_FLOAT) data.float.toDouble() else data.short / 32_768.0
            }
            analyzer.push(sum / channelCount)
        }
    }

    private fun store(timeUs: Long, bands: FloatArray) {
        synchronized(lock) {
            // The queue is full: the oldest goes
            if (size == QUEUE) {
                head = (head + 1) % QUEUE
                size--
            }
            val slot = (head + size) % QUEUE
            times[slot] = timeUs
            bands.copyInto(values[slot])
            size++
        }
    }

    /**
     * Gives [consume] the measurements (oldest first) whose audio time is up to [untilUs], and forgets them. The array
     * is reused: copy what is needed.
     */
    fun drain(untilUs: Long, consume: (FloatArray) -> Unit) {
        while (true) {
            val has = synchronized(lock) {
                if (size == 0 || times[head] > untilUs) {
                    false
                } else {
                    values[head].copyInto(scratch)
                    head = (head + 1) % QUEUE
                    size--
                    true
                }
            }
            if (!has) return
            consume(scratch)
        }
    }

    /**
     * The audio time that is heard now (µs): the last report of the sink, moved on by the time since it while
     * [playing] (it is asked every ten milliseconds or so). `null` before the first report.
     */
    fun heardPositionUs(playing: Boolean, nowNanos: Long = System.nanoTime()): Long? = synchronized(lock) {
        if (positionUs == NOT_SET) {
            null
        } else if (playing) {
            positionUs + min((nowNanos - positionAtNanos) / 1_000, MAX_EXTRAPOLATION_US)
        } else {
            positionUs
        }
    }
}

/**
 * The audio sink of the player with eyes for [SpectrumTap]: the time of each buffer that comes in, and the position
 * that is heard now. Everything else is the wrapped sink.
 */
@OptIn(UnstableApi::class)
class SpectrumAudioSink(sink: AudioSink, private val tap: SpectrumTap) : ForwardingAudioSink(sink) {
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        tap.onInputBuffer(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also { tap.onPosition(it) }
}
