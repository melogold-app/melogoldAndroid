@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.theme.LocalMotionLevel

/**
 * A determinate linear progress with a wave on its active part (interludes in lyrics, the QR code
 * lifetime), following the motion level (REDESIGN-M3E §4.7):
 * - Expressive: full wave; its amplitude animates with `defaultEffectsSpec`;
 * - Restrained: a low wave;
 * - Minimal: a straight line.
 *
 * @param moving whether the wave travels; pass `false` while playback is paused so it stands still
 */
@Composable
fun WavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    moving: Boolean = true,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor
) {
    val level = LocalMotionLevel.current

    if (level == MotionLevel.Minimal) {
        LinearProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = color,
            trackColor = trackColor
        )
        return
    }

    val amplitude by animateFloatAsState(
        targetValue = if (level == MotionLevel.Expressive) 1f else RESTRAINED_AMPLITUDE,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "WavyProgressAmplitude"
    )
    val wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength

    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        amplitude = { value -> if (value <= 0f || value >= 1f) 0f else amplitude },
        wavelength = wavelength,
        waveSpeed = if (moving) wavelength else 0.dp
    )
}

private const val RESTRAINED_AMPLITUDE = 0.3f
