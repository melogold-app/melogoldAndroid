package app.melogold.android.ui.screens.player.modern

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.melogold.android.ui.theme.BACKDROP_SIDE
import app.melogold.android.ui.theme.ColorShare
import app.melogold.android.ui.theme.FluidCover
import app.melogold.android.ui.theme.artworkShares
import app.melogold.android.ui.theme.backdropBrightness
import app.melogold.android.ui.theme.backdropColorMatrix
import app.melogold.android.ui.theme.darkBackdrop
import app.melogold.android.ui.theme.prepareFluidCover
import app.melogold.android.ui.theme.prepareBackdropCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The backdrop of "Now playing" (tasks/0028): the artwork, blurred, in two layers turning towards each other under a
 * color matrix, with a vignette. The numbers are the same on every platform; the reference is Linux
 * (`examples/backdrop_lab.rs`, `blurred_cover`). The pure part (the colors by shares, the small copy, the matrix) is in
 * `ui/theme/ArtworkBackdrop.kt`.
 */

/** Pixels of the artwork the backdrop is made from: the shares are taken from a grid of it, not from an average. */
private const val BACKDROP_SOURCE_SIDE = 448

/** The blur of the layers, dp. */
private val BackdropBlur = 32.dp

/** A new artwork replaces the old one in this time. */
private const val CROSSFADE_MS = 400

/** The clock runs at most this often: the layers turn a fraction of a pixel a frame, there is no use in 60 of them. */
private const val CLOCK_STEP_NANOS = 30_000_000L

/** The vignette: clear up to this part of the radius, then black up to [VIGNETTE_ALPHA] at the edge. */
private const val VIGNETTE_CLEAR = 0.55f
private const val VIGNETTE_ALPHA = 0.30f
private const val VIGNETTE_REACH = 0.75f

/**
 * The artwork prepared for the backdrop.
 *
 * @property image the small blurred copy, [BACKDROP_SIDE] × [BACKDROP_SIDE]
 * @property luma its mean brightness before the blur
 * @property base the color under the layers, for a corner they might not cover
 * @property shares the colors of the artwork by their shares, the dominant first (the drops of the living backdrop)
 * @property fluid the 256 × 256 copy and its mip levels for the shader of the living backdrop (tasks/0029)
 */
@Immutable
class ArtworkBackdrop(
    val image: ImageBitmap,
    val luma: Float,
    val base: Color,
    val shares: List<ColorShare> = emptyList(),
    val fluid: FluidCover? = null
)

/**
 * The backdrop of the artwork at [uri]; the previous one stays while the new one is prepared (it is replaced with a
 * crossfade), `null` without an artwork.
 *
 * @param key a stable id of the artwork (the media id)
 */
@Composable
fun rememberArtworkBackdrop(key: String?, uri: Uri?): ArtworkBackdrop? {
    val context = LocalContext.current
    var backdrop by remember { mutableStateOf<ArtworkBackdrop?>(null) }

    LaunchedEffect(key, uri) {
        if (uri == null) backdrop = null
        else loadArtworkBackdrop(context, uri)?.let { backdrop = it }
    }

    return backdrop
}

/** Loads the artwork at [uri] and prepares it; the work is done off the main thread. */
private suspend fun loadArtworkBackdrop(context: Context, uri: Uri): ArtworkBackdrop? {
    val bitmap = loadArtworkBitmap(context, uri, BACKDROP_SOURCE_SIDE) ?: return null

    return withContext(Dispatchers.Default) {
        runCatching { bitmap.toArtworkBackdrop() }.getOrNull()
    }
}

/** The backdrop of this artwork: the colors by shares and the small blurred copy. */
internal fun Bitmap.toArtworkBackdrop(): ArtworkBackdrop? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val cover = prepareBackdropCover(pixels, width, height) ?: return null
    val shares = artworkShares(pixels, width, height)
    val image = Bitmap.createBitmap(cover.pixels, BACKDROP_SIDE, BACKDROP_SIDE, Bitmap.Config.ARGB_8888)

    return ArtworkBackdrop(
        image = image.asImageBitmap(),
        luma = cover.luma,
        base = shares.firstOrNull()?.let { Color(0xFF000000.toInt() or darkBackdrop(it.rgb)) } ?: Color.Black,
        shares = shares,
        fluid = prepareFluidCover(pixels, width, height)
    )
}

/**
 * The clock of the layers, seconds. It runs only while [running], and only while the screen is visible (the lifecycle
 * is at least started); a pause does not reset it, the movement goes on from the same place. Nothing is written to the
 * clock while it stands, so nothing is redrawn.
 *
 * Read the clock in the draw phase only.
 */
@Composable
fun rememberBackdropClock(running: Boolean): () -> Float {
    val seconds = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(running, lifecycle) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last == 0L) last = now
                    else if (now - last >= CLOCK_STEP_NANOS) {
                        seconds.floatValue += (now - last) / NANOS_IN_SECOND
                        last = now
                    }
                }
            }
        }
    }

    return { seconds.floatValue }
}

private const val NANOS_IN_SECOND = 1_000_000_000f

/**
 * The backdrop: [backdrop] blurred, turning with [time]; [fallback] is the color while there is no artwork. A new
 * artwork fades in over the old one in 400 ms (the clock goes on).
 *
 * Below Android 12 there is no blur of the whole layer (`RenderEffect`): the small copy is simply stretched with a
 * linear filter, which is close to the blur at this size.
 */
@Composable
fun NowPlayingBackdrop(
    backdrop: ArtworkBackdrop?,
    fallback: Color,
    time: () -> Float,
    modifier: Modifier = Modifier
) {
    var shown by remember { mutableStateOf(backdrop) }
    var previous by remember { mutableStateOf<ArtworkBackdrop?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(backdrop) {
        if (backdrop === shown) return@LaunchedEffect
        previous = shown
        shown = backdrop
        fade.snapTo(0f)
        fade.animateTo(1f, tween(CROSSFADE_MS))
        previous = null
    }

    Box(modifier = modifier.fillMaxSize().background(fallback)) {
        // The old one stays whole under the new one that fades in: no dip to the fallback color in the middle
        previous?.let { CoverLayers(backdrop = it, time = time, alpha = { 1f }) }
        shown?.let { CoverLayers(backdrop = it, time = time, alpha = { fade.value }) }
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawVignette() })
    }
}

@Composable
private fun CoverLayers(backdrop: ArtworkBackdrop, time: () -> Float, alpha: () -> Float) {
    val filter = remember(backdrop) {
        ColorFilter.colorMatrix(ColorMatrix(backdropColorMatrix(backdropBrightness(backdrop.luma))))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .background(backdrop.base)
            // On Android 12+ the blur is `RenderEffect`, its edges clamped (the layers reach beyond the screen anyway); below it
            // does nothing
            .blur(BackdropBlur, BlurredEdgeTreatment.Rectangle)
            .drawBehind { drawCoverLayers(backdrop.image, filter, time()) }
    )
}

/**
 * Two layers of the same small copy: a square of 1.6 of the long side turning at 2.4° a second, and one of 1.15
 * turning towards it at 3.6° a second (from 90°) on a slow figure about the middle, at 70 % opacity.
 */
private fun DrawScope.drawCoverLayers(image: ImageBitmap, filter: ColorFilter, t: Float) {
    val w = size.width
    val h = size.height
    val longSide = max(w, h)

    fun layer(centerX: Float, centerY: Float, side: Float, degrees: Float, alpha: Float) {
        val half = (side / 2f).roundToInt()
        translate(centerX, centerY) {
            rotate(degrees, pivot = Offset.Zero) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(-half, -half),
                    dstSize = IntSize(half * 2, half * 2),
                    alpha = alpha,
                    colorFilter = filter,
                    // Linear: the small copy is stretched smoothly
                    filterQuality = FilterQuality.Low
                )
            }
        }
    }

    layer(w / 2f, h / 2f, longSide * 1.6f, t * 2.4f, 1f)
    layer(
        centerX = w / 2f + sin(t / 17f) * 0.18f * w,
        centerY = h / 2f + cos(t / 23f) * 0.15f * h,
        side = longSide * 1.15f,
        degrees = 90f - t * 3.6f,
        alpha = 0.7f
    )
}

/**
 * A radial gradient from the middle with the radii 0.75 of the width and of the height (an ellipse): clear up to 55 %,
 * black at 30 % opacity at the edge.
 */
private fun DrawScope.drawVignette() {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val center = Offset(w / 2f, h / 2f)
    val radius = VIGNETTE_REACH * w
    // The circle of the width stretched to the height: the ellipse
    scale(scaleX = 1f, scaleY = h / w, pivot = center) {
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Black.copy(alpha = 0f),
                    VIGNETTE_CLEAR to Color.Black.copy(alpha = 0f),
                    1f to Color.Black.copy(alpha = VIGNETTE_ALPHA)
                ),
                center = center,
                radius = radius
            ),
            topLeft = Offset(0f, center.y - w / 2f),
            size = Size(w, w)
        )
    }
}
