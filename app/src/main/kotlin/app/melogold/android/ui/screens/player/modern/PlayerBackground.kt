package app.melogold.android.ui.screens.player.modern

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.melogold.android.utils.thumbnail
import app.melogold.core.ui.dynamicAccentColorOf
import app.melogold.core.ui.hsl
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

private const val SAMPLE_SIZE = 64
private const val FRAME_INTERVAL_MS = 50L
private val colorAnimationSpec = tween<Color>(durationMillis = 800, easing = LinearOutSlowInEasing)

@Immutable
private data class BackgroundColors(
    val base: Color,
    val blob1: Color,
    val blob2: Color,
    val blob3: Color,
    val scrim: Float
)

private fun hslColor(hue: Float, saturation: Float, lightness: Float) = Color.hsl(
    hue = ((hue % 360f) + 360f) % 360f,
    saturation = (saturation * 1.4f).coerceIn(0f, 0.85f),
    lightness = lightness.coerceIn(0f, 1f)
)

/** Colours already computed for recent artworks, so reopening the player does not flash. */
private val colorsCache = LruCache<String, BackgroundColors>(16)

private fun fallbackColors(accent: Color): BackgroundColors {
    val hue = accent.hsl.hue
    val saturation = accent.hsl.saturation
    return BackgroundColors(
        base = hslColor(hue, saturation, 0.16f),
        blob1 = hslColor(hue, saturation, 0.32f),
        blob2 = hslColor(hue + 25f, saturation, 0.28f),
        blob3 = hslColor(hue - 25f, saturation, 0.24f),
        scrim = 0.30f
    )
}

private fun Bitmap.meanLuminance(): Float {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    if (pixels.isEmpty()) return 0f
    return pixels.sumOf { ColorUtils.calculateLuminance(it) }.toFloat() / pixels.size
}

private fun backgroundColorsOf(bitmap: Bitmap?, accent: Color): BackgroundColors {
    if (bitmap == null) return fallbackColors(accent)

    val primary = runCatching { dynamicAccentColorOf(bitmap, isDark = true) }.getOrNull() ?: accent.hsl
    val palette = runCatching { Palette.from(bitmap).maximumColorCount(12).generate() }.getOrNull()

    fun FloatArray?.orRotated(degrees: Float) = this ?: floatArrayOf(
        primary.hue + degrees,
        primary.saturation,
        primary.lightness
    )

    val second = (palette?.vibrantSwatch ?: palette?.darkVibrantSwatch ?: palette?.dominantSwatch)
        ?.hsl.orRotated(25f)
    val third = (palette?.mutedSwatch ?: palette?.darkMutedSwatch)?.hsl.orRotated(-25f)

    val luminance = runCatching { bitmap.meanLuminance() }.getOrDefault(0f)
    val extraScrim = ((luminance - 0.55f) / 0.45f * 0.20f).coerceIn(0f, 0.20f)

    return BackgroundColors(
        base = hslColor(primary.hue, primary.saturation, primary.lightness.coerceIn(0.14f, 0.22f)),
        blob1 = hslColor(primary.hue, primary.saturation, primary.lightness.coerceIn(0.25f, 0.42f)),
        blob2 = hslColor(second[0], second[1], second[2].coerceIn(0.22f, 0.38f)),
        blob3 = hslColor(third[0], third[1], third[2].coerceIn(0.18f, 0.32f)),
        scrim = 0.30f + extraScrim
    )
}

/**
 * The Apple-Music-like background: a dark base colour and three large soft blobs taken from the
 * artwork, slowly drifting while [animate] is true.
 *
 * Everything moving is only read in the draw phase, so this never recomposes while animating; the
 * frame loop is capped at ~20 fps and stops entirely (after easing out) when [animate] is false.
 */
@Composable
fun PlayerBackground(
    artworkUri: Uri?,
    accent: Color,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var colors by remember {
        mutableStateOf(artworkUri?.let { colorsCache[it.toString()] } ?: fallbackColors(accent))
    }

    LaunchedEffect(artworkUri, accent) {
        artworkUri?.let { colorsCache[it.toString()] }?.let {
            colors = it
            return@LaunchedEffect
        }

        val bitmap = artworkUri?.let { uri ->
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(uri.thumbnail(SAMPLE_SIZE))
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()

                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                    ?.image
                    ?.toBitmap()
            }.getOrNull()
        }

        colors = withContext(Dispatchers.Default) { backgroundColorsOf(bitmap, accent) }
        if (bitmap != null) colorsCache.put(artworkUri.toString(), colors)
    }

    val base = animateColorAsState(colors.base, colorAnimationSpec, label = "")
    val blob1 = animateColorAsState(colors.blob1, colorAnimationSpec, label = "")
    val blob2 = animateColorAsState(colors.blob2, colorAnimationSpec, label = "")
    val blob3 = animateColorAsState(colors.blob3, colorAnimationSpec, label = "")
    val scrim = animateFloatAsState(colors.scrim, tween(800), label = "")

    val time = remember { mutableFloatStateOf(0f) }
    val speed = remember { Animatable(if (animate) 1f else 0f) }

    LaunchedEffect(animate) {
        launch { speed.animateTo(if (animate) 1f else 0f, tween(1000)) }

        var last = -1L
        var accumulated = 0L

        while (true) {
            val done = withFrameMillis { now ->
                if (last >= 0) accumulated += now - last
                last = now

                if (accumulated >= FRAME_INTERVAL_MS) {
                    time.floatValue += accumulated / 1000f * speed.value
                    accumulated = 0
                }

                !animate && speed.value == 0f
            }
            if (done) break
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = size.maxDimension * 0.9f

                val radius1 = radius
                val radius2 = radius * 0.85f
                val radius3 = radius * 0.75f

                // The gradient must reach transparency exactly at the circle's edge
                fun brush(color: Color, blobRadius: Float) = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = color.alpha * 0.5f), color.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = blobRadius
                )

                val brush1 = brush(blob1.value, radius1)
                val brush2 = brush(blob2.value, radius2)
                val brush3 = brush(blob3.value, radius3)
                val baseColor = base.value

                onDrawBehind {
                    val t = time.floatValue
                    val w = size.width
                    val h = size.height

                    drawRect(baseColor)

                    fun wave(periodSeconds: Float, phase: Float = 0f) =
                        sin(2f * PI.toFloat() * t / periodSeconds + phase)

                    translate(
                        left = w * (0.5f + 0.30f * wave(47f)),
                        top = h * (0.35f + 0.25f * wave(47f / 2f, 0.7f))
                    ) { drawCircle(brush1, radius1, Offset.Zero) }

                    translate(
                        left = w * (0.5f - 0.35f * wave(71f, 1.9f)),
                        top = h * (0.35f + 0.30f * wave(71f / 3f * 2f, 2.4f))
                    ) { drawCircle(brush2, radius2, Offset.Zero) }

                    translate(
                        left = w * (0.5f + 0.28f * wave(113f, 3.1f)),
                        top = h * (0.35f - 0.32f * wave(113f / 2f, 0.3f))
                    ) { drawCircle(brush3, radius3, Offset.Zero) }

                    drawRect(Color.Black, alpha = scrim.value.coerceIn(0f, 1f))
                }
            }
    )
}
