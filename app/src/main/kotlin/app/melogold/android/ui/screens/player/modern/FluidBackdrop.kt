package app.melogold.android.ui.screens.player.modern

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.service.SpectrumTap
import app.melogold.android.ui.theme.FluidMotion

/*
 * The living backdrop of "Now playing" (tasks/0029): the blurred artwork that flows, a lava lamp of the colors of the
 * artwork, a pulse on the bass — a shader ([FluidShader]) driven by the sound ([SpectrumTap] → [FluidMotion]). The
 * frames come from the display (`withFrameNanos`: 60, 90, 120 Hz — the refresh rate of the screen), not from a timer.
 *
 * Without the shader (Android below 13, or it does not compile) the backdrop of tasks/0028 stays as it was.
 */

/** A new artwork replaces the old one in this time. */
private const val FLUID_CROSSFADE_MS = 400

/** No sound for this long while playing (a seek, a stall): the sound is taken as gone and fades out. */
private const val SOUND_LOST_SECONDS = 0.5f

/** The longest frame the motion believes in, seconds (a pause of the app is not a leap of the drops). */
private const val LONGEST_FRAME = 0.1f

private const val DEFAULT_FRAME = 1f / 60f
private const val NANOS_IN_SECOND = 1_000_000_000f

/**
 * The backdrop of "Now playing": the living one where the shader runs, else the blurred artwork of tasks/0028.
 *
 * @param playing the track plays: the time of the flow runs and the sound is listened to
 * @param visible the sheet is open and the screen is shown: only then frames are drawn
 * @param live the backdrop may move: the setting "Live background" is on, the animations of the system are not turned
 * off and the contrast is not high. Else it is a still frame (the forces 0, the time stands); the same shader
 */
@Composable
fun NowPlayingLiveBackdrop(
    backdrop: ArtworkBackdrop?,
    fallback: Color,
    playing: Boolean,
    visible: Boolean,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    var failed by remember { mutableStateOf(false) }
    val fluid = !failed && (backdrop == null || backdrop.fluid != null)

    // AGSL is Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && fluid) {
        FluidBackdrop(
            backdrop = backdrop,
            fallback = fallback,
            playing = playing,
            visible = visible,
            live = live,
            onFail = { failed = true },
            modifier = modifier
        )
    } else {
        val clock = rememberBackdropClock(running = live && playing && visible)
        NowPlayingBackdrop(backdrop = backdrop, fallback = fallback, time = clock, modifier = modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FluidBackdrop(
    backdrop: ArtworkBackdrop?,
    fallback: Color,
    playing: Boolean,
    visible: Boolean,
    live: Boolean,
    onFail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = remember { FluidMotion() }
    // Written every frame, read in the draw phase only: a frame is a redraw, not a recomposition
    val frame = remember { mutableLongStateOf(0L) }
    val tap = LocalPlayerServiceBinder.current?.spectrumTap
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(live, visible, playing, tap, lifecycle) {
        when {
            // A still frame: the forces 0, the time where it stands
            !live -> {
                motion.freeze()
                frame.longValue++
            }

            // Under the mini player nothing is drawn, the state is kept
            !visible -> Unit

            else -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runFrames(motion, frame, tap, playing)
            }
        }
    }

    var shown by remember { mutableStateOf(backdrop) }
    var previous by remember { mutableStateOf<ArtworkBackdrop?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(backdrop) {
        if (backdrop === shown) return@LaunchedEffect
        previous = shown
        shown = backdrop
        fade.snapTo(0f)
        fade.animateTo(1f, tween(FLUID_CROSSFADE_MS))
        previous = null
    }

    Box(modifier = modifier.fillMaxSize().background(fallback)) {
        // The old one stays whole under the new one that fades in: no dip to the fallback color in the middle
        previous?.let {
            FluidLayer(backdrop = it, motion = motion, frame = { frame.longValue }, alpha = { 1f }, onFail = onFail)
        }
        shown?.let {
            FluidLayer(
                backdrop = it,
                motion = motion,
                frame = { frame.longValue },
                alpha = { fade.value },
                onFail = onFail
            )
        }
    }
}

/**
 * The frames of the backdrop, one for each of the display, until [playing] stops and everything fades out (then no
 * more frames: nothing is drawn for a pause). Each frame takes the measurements of the sound that is heard now, steps
 * the motion and asks for a redraw.
 */
private suspend fun runFrames(
    motion: FluidMotion,
    frame: MutableLongState,
    tap: SpectrumTap?,
    playing: Boolean
) {
    // The audio thread works for the backdrop only while it plays
    tap?.listening = playing
    try {
        var last = 0L
        var silentFor = 0f
        var settled = false
        while (!settled) {
            withFrameNanos { now ->
                val dt = if (last == 0L) DEFAULT_FRAME else ((now - last) / NANOS_IN_SECOND).coerceIn(0f, LONGEST_FRAME)
                last = now

                var fed = false
                if (playing && tap != null) {
                    // What sounds now, by the position the player says is heard — not the newest, which is ahead
                    val until = tap.heardPositionUs(playing = true) ?: Long.MAX_VALUE
                    tap.drain(until) {
                        motion.feed(it)
                        fed = true
                    }
                }
                silentFor = if (fed) 0f else silentFor + dt

                motion.step(dt, playing, listening = playing && silentFor < SOUND_LOST_SECONDS)
                frame.longValue = now
                settled = !playing && motion.settled
            }
        }
    } finally {
        tap?.listening = false
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FluidLayer(
    backdrop: ArtworkBackdrop,
    motion: FluidMotion,
    frame: () -> Long,
    alpha: () -> Float,
    onFail: () -> Unit
) {
    val currentOnFail by rememberUpdatedState(onFail)
    val shader = remember(backdrop) {
        val cover = backdrop.fluid ?: return@remember null
        runCatching { FluidShader(cover, backdrop.shares.map { it.rgb to it.share.toFloat() }) }.getOrNull()
    }
    if (shader == null) {
        // The shader does not compile here: the backdrop of tasks/0028 takes over
        LaunchedEffect(backdrop) { currentOnFail() }
        return
    }

    val brush = remember(shader) { ShaderBrush(shader.shader) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .drawBehind {
                // The redraw is asked for by the frame
                frame()
                shader.update(size.width, size.height, motion, backdrop.luma)
                drawRect(brush)
            }
    )
}
