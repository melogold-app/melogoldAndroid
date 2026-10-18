package app.melogold.android.ui.theme

import app.melogold.android.service.SPECTRUM_BANDS
import kotlin.math.max
import kotlin.math.min

/*
 * The living backdrop of "Now playing" (tasks/0029): the envelopes of the sound, the beat and the motion of variant 1
 * ("the drops are the bass, the flow is the highs"). Numbers are the same on every platform; the reference is Linux,
 * `examples/final_lab.rs` (`Analysis::step`, `Shared::advance`, `PRESETS[0]`). The math only — no Android API, so the
 * tests run on the JVM.
 */

/** The drops of the lava lamp. */
const val FLUID_BLOBS = 6

/** The bands the sound is split to: the bass is 0–9, the middle 10–29, the highs 30–47. */
private const val BASS_END = 10
private const val MID_END = 30

/** How fast a band follows its new value, per second. */
private const val BAND_RISE = 18f
private const val BAND_FALL = 4f

/** How fast the bass, the middle, the highs and the loudness follow their means, per second. */
private const val GROUP_FOLLOW = 12f

/** Paused: the targets fade out at this rate, per second. */
private const val PAUSE_FADE = 3f

/** The beat: the flux of the bass over `FLUX_RATIO · average + FLUX_FLOOR`, not oftener than every [BEAT_GAP] seconds. */
private const val FLUX_AVERAGE = 0.94f
private const val FLUX_RATIO = 2.2f
private const val FLUX_FLOOR = 0.035f
private const val BEAT_GAP = 0.2f
private const val BEAT_FADE = 4f

/**
 * The envelopes of the sound: 48 smoothed bands, the bass / middle / highs / loudness of them, and the beat.
 * [feed] takes each measurement (the raw targets), [step] is called every frame.
 */
class SpectrumEnvelopes {
    /** The smoothed bands, 0..1. */
    val bands = FloatArray(SPECTRUM_BANDS)
    private val targets = FloatArray(SPECTRUM_BANDS)

    var bass = 0f
        private set
    var mid = 0f
        private set
    var high = 0f
        private set
    var loud = 0f
        private set

    /** 1 on a beat, then fades: `× (1 − 4·dt)`. */
    var beat = 0f
        private set

    /** The beats since the start. */
    var beats = 0
        private set

    private var fluxAverage = 0f
    private var previousBass = 0f
    private var sinceBeat = 1f

    /** One measurement: 48 values 0..1 of the sound that plays now, low to high. */
    fun feed(values: FloatArray) {
        values.copyInto(targets, endIndex = SPECTRUM_BANDS)

        // The beat goes by the raw targets, before the smoothing
        val rawBass = mean(targets, 0, BASS_END)
        val flux = max(0f, rawBass - previousBass)
        previousBass = rawBass
        fluxAverage = fluxAverage * FLUX_AVERAGE + flux * (1f - FLUX_AVERAGE)
        if (flux > fluxAverage * FLUX_RATIO + FLUX_FLOOR && sinceBeat > BEAT_GAP) {
            beat = 1f
            beats++
            sinceBeat = 0f
        }
    }

    /** A frame, [dt] seconds after the last one. While not [playing] the targets fade out. */
    fun step(dt: Float, playing: Boolean) {
        for (band in 0 until SPECTRUM_BANDS) {
            if (!playing) targets[band] *= max(0f, 1f - dt * PAUSE_FADE)
            val speed = if (targets[band] > bands[band]) BAND_RISE else BAND_FALL
            bands[band] += (targets[band] - bands[band]) * min(speed * dt, 1f)
        }
        bass = follow(bass, mean(bands, 0, BASS_END), dt)
        mid = follow(mid, mean(bands, BASS_END, MID_END), dt)
        high = follow(high, mean(bands, MID_END, SPECTRUM_BANDS), dt)
        loud = follow(loud, mean(bands, 0, SPECTRUM_BANDS), dt)
        beat *= max(0f, 1f - dt * BEAT_FADE)
        sinceBeat += dt
    }

    /** True when nothing is left to fade: no frames are needed for it. */
    val quiet: Boolean
        get() = beat < QUIET && bass < QUIET && mid < QUIET && high < QUIET && loud < QUIET &&
            targets.all { it < QUIET } && bands.all { it < QUIET }

    /** Everything to silence at once (the backdrop stands still). */
    fun clear() {
        bands.fill(0f)
        targets.fill(0f)
        bass = 0f
        mid = 0f
        high = 0f
        loud = 0f
        beat = 0f
        previousBass = 0f
        fluxAverage = 0f
    }

    private fun follow(current: Float, target: Float, dt: Float) =
        current + (target - current) * min(GROUP_FOLLOW * dt, 1f)

    internal companion object {
        /** Below this a force is gone for the eye. */
        const val QUIET = 0.01f

        fun mean(values: FloatArray, from: Int, to: Int): Float {
            var sum = 0f
            for (i in from until to) sum += values[i]
            return sum / (to - from)
        }
    }
}

/** How fast the forces of the motion rise and fall, per second. */
private const val RISE = 5f
private const val FALL = 1.8f

/** A force is the sound over this, at most. */
private const val FORCE_CAP = 1.5f
private const val PULSE_CAP = 1.2f

/** The pulse: `beat · 1.0 + bass · 0.4`. */
private const val PULSE_BEAT = 1.0f
private const val PULSE_BASS = 0.4f

/** The time of the flow and of the drops runs only while the track plays: `base + gain · force` per second. */
private const val LIQUID_BASE_SPEED = 0.25f
private const val LIQUID_SPEED_GAIN = 1.4f
private const val LAVA_BASE_SPEED = 0.2f
private const val LAVA_SPEED_GAIN = 1.2f

/**
 * The motion of the backdrop, variant 1: the flow (`liquid`) follows the highs, the drops (`lava`, every `blobs[i]`)
 * the bass, the pulse the beat and the bass. The uniforms of the shader are these fields.
 */
class FluidMotion {
    val audio = SpectrumEnvelopes()

    var liquid = 0f
        private set
    var lava = 0f
        private set
    var pulse = 0f
        private set
    val blobs = FloatArray(FLUID_BLOBS)

    /** The time of the flow and of the drops, seconds-ish; it stands while nothing plays. */
    var liquidFlow = 0f
        private set
    var lavaFlow = 0f
        private set

    /** One measurement of the sound (48 values), see [SpectrumEnvelopes.feed]. */
    fun feed(values: FloatArray) = audio.feed(values)

    /**
     * A frame, [dt] seconds after the last one. [playing]: the track plays (the time runs); [listening]: the sound
     * is heard, else its targets fade out (paused, or no sound has come for a while).
     */
    fun step(dt: Float, playing: Boolean, listening: Boolean = playing) {
        audio.step(dt, listening)

        liquid = follow(liquid, min(audio.high, FORCE_CAP), dt)
        lava = follow(lava, min(audio.bass, FORCE_CAP), dt)
        val pulseTarget = min(audio.beat * PULSE_BEAT + audio.bass * PULSE_BASS, PULSE_CAP)
        // A beat is at once, then it lets go at its own pace
        pulse = if (pulseTarget > pulse) pulseTarget else follow(pulse, pulseTarget, dt)
        for (i in blobs.indices) blobs[i] = follow(blobs[i], min(lava, FORCE_CAP), dt)

        if (playing) {
            liquidFlow += dt * (LIQUID_BASE_SPEED + LIQUID_SPEED_GAIN * liquid)
            lavaFlow += dt * (LAVA_BASE_SPEED + LAVA_SPEED_GAIN * lava)
        }
    }

    /** True when nothing moves any more (paused, the forces faded): no frames are needed. */
    val settled: Boolean
        get() = audio.quiet &&
            liquid < SpectrumEnvelopes.QUIET &&
            lava < SpectrumEnvelopes.QUIET &&
            pulse < SpectrumEnvelopes.QUIET &&
            blobs.all { it < SpectrumEnvelopes.QUIET }

    /** A still frame: the forces to 0, the time where it stands. */
    fun freeze() {
        audio.clear()
        liquid = 0f
        lava = 0f
        pulse = 0f
        blobs.fill(0f)
    }

    private fun follow(current: Float, target: Float, dt: Float): Float {
        val rate = if (target > current) RISE else FALL
        return current + (target - current) * min(rate * dt, 1f)
    }
}
