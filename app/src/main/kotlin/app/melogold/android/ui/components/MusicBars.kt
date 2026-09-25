package app.melogold.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.service.AUDIO_BANDS
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.theme.LocalMotionLevel
import kotlin.math.pow

/** The shortest a bar gets: it never vanishes, so the track still reads as the playing one. */
private const val MIN_HEIGHT = 0.18f

/** Reduced motion: still bars of different heights. */
private val StillLevels = floatArrayOf(0.55f, 0.85f, 0.4f)

/** How much of the way to the new level a bar goes per 16 ms: up quickly, down slower, like a meter. */
private const val RISE = 0.55f
private const val FALL = 0.18f

/**
 * The "now playing" bars: low, mid and high of what plays now ([app.melogold.android.service.AudioLevels]),
 * so they move with the music instead of a loop. They rest while nothing plays, and stand still
 * with reduced motion.
 */
@Composable
fun MusicBars(
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    cornerRadius: Dp = 16.dp,
    space: Dp = 4.dp
) {
    val levels = LocalPlayerServiceBinder.current?.audioLevels
    val still = LocalMotionLevel.current == MotionLevel.Minimal
    val bars = remember { List(AUDIO_BANDS) { mutableFloatStateOf(MIN_HEIGHT) } }

    LaunchedEffect(levels, still) {
        if (still || levels == null) {
            bars.forEachIndexed { index, bar -> bar.floatValue = StillLevels[index] }
            return@LaunchedEffect
        }
        val target = FloatArray(AUDIO_BANDS)
        var last = 0L
        while (true) withFrameMillis { now ->
            levels.read(target)
            val frames = if (last == 0L) 1f else ((now - last) / 16f).coerceIn(0.25f, 4f)
            last = now
            bars.forEachIndexed { index, bar ->
                val goal = MIN_HEIGHT + (1 - MIN_HEIGHT) * target[index]
                val rate = if (goal > bar.floatValue) RISE else FALL
                bar.floatValue += (goal - bar.floatValue) * (1 - (1 - rate).pow(frames))
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .width(barWidth * AUDIO_BANDS + space * (AUDIO_BANDS - 1))
    ) {
        val radius = CornerRadius(cornerRadius.toPx())
        val barWidthPx = barWidth.toPx()
        val stride = barWidthPx + space.toPx()

        bars.forEachIndexed { index, bar ->
            val height = size.height * bar.floatValue
            drawRoundRect(
                color = color,
                topLeft = Offset(x = index * stride, y = size.height - height),
                size = Size(width = barWidthPx, height = height),
                cornerRadius = radius
            )
        }
    }
}
