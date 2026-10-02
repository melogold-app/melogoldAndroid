package app.melogold.android.ui.screens.player.modern

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.service.AUDIO_BANDS
import app.melogold.android.utils.centerSquare
import app.melogold.android.utils.squareThumbnail
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.theme.LocalMotionLevel
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlin.math.cos
import kotlin.math.sin

private const val SAMPLE_SIZE = 112

/**
 * A small software copy of the artwork at [uri], for picking its colors; null while loading or
 * when there is no artwork. A video frame gives its middle square, as everywhere it is square.
 */
@Composable
fun rememberArtworkBitmap(uri: Uri?): Bitmap? {
    val context = LocalContext.current
    // A new artwork starts empty: the old one must not be taken for it (its seed is cached per track)
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = uri?.let {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(it.toString().squareThumbnail(SAMPLE_SIZE))
                    .size(SAMPLE_SIZE)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                result?.image?.toBitmap()?.centerSquare()
            }.getOrNull()
        }
    }

    return bitmap
}

/**
 * The living background of the player (the user, 2026-10-02: «как Apple Music, но живой… градиент, живой на звук»; «это
 * база» on every platform, Apple does the same with a mesh gradient). Soft blobs of the artwork's color scheme — primary,
 * tertiary and secondary containers over its surface — drift slowly, faster when the music is louder; the bass briefly
 * swells and brightens the middle one. Calmer at the bottom, under the controls. Not a blurred artwork.
 *
 * Paused or [playing] false — it stands still; with motion turned down ([LocalMotionLevel] minimal) — never moves. Only
 * the drawing reads the moving values ([drawBehind]), so a frame redraws the background and nothing else.
 * [listen] false — the music plays elsewhere (the remote player): it drifts without this device's sound.
 */
@Composable
fun ArtworkTintedBackground(
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    listen: Boolean = true
) {
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val scheme = MaterialTheme.colorScheme
    // The light scheme's containers are pale pastels that barely show on its surface: there the blobs take the strong
    // roles, see-through; the dark scheme's containers are deep enough as they are
    val light = scheme.surface.luminance() > 0.5f
    val surface by animateColorAsState(scheme.surface, spec, label = "")
    val first by animateColorAsState(if (light) scheme.primary.copy(alpha = 0.34f) else scheme.primaryContainer, spec, label = "")
    val second by animateColorAsState(if (light) scheme.tertiary.copy(alpha = 0.3f) else scheme.tertiaryContainer, spec, label = "")
    val third by animateColorAsState(if (light) scheme.secondary.copy(alpha = 0.28f) else scheme.secondaryContainer, spec, label = "")
    val accent by animateColorAsState(scheme.primary, spec, label = "")
    val still = LocalMotionLevel.current == MotionLevel.Minimal
    val levels = if (listen) LocalPlayerServiceBinder.current?.audioLevels else null
    val phase = remember { mutableFloatStateOf(0f) }
    val bass = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing, still, levels) {
        if (still || !playing) {
            bass.floatValue = 0f
            return@LaunchedEffect
        }
        val target = FloatArray(AUDIO_BANDS)
        var last = 0L
        var energy = 0f
        while (true) withFrameMillis { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceIn(0f, 0.1f)
            last = now
            if (levels != null) levels.read(target) else target.fill(0f)
            // The bass is caught fast and let go slowly — a flash on the beat; loudness speeds the drift up
            val low = target[0]
            bass.floatValue = if (low > bass.floatValue) bass.floatValue + (low - bass.floatValue) * 0.55f else bass.floatValue * 0.9f
            energy += ((target[0] + target[1] + target[2]) / 3f - energy) * 0.06f
            phase.floatValue += dt * (0.5f + 1.3f * energy)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .drawBehind {
                val t = phase.floatValue
                val b = bass.floatValue
                val reach = maxOf(size.width, size.height) * 0.75f
                fun blob(color: Color, x: Float, y: Float, radius: Float, alpha: Float) {
                    val center = Offset(size.width * x, size.height * y)
                    drawCircle(
                        brush = Brush.radialGradient(listOf(color.copy(alpha = color.alpha * alpha), Color.Transparent), center, radius),
                        radius = radius,
                        center = center
                    )
                }
                blob(first, 0.25f + 0.2f * sin(t * 0.11f), 0.16f + 0.1f * cos(t * 0.09f), reach * (1f + 0.08f * b), 0.9f)
                blob(second, 0.85f + 0.12f * sin(t * 0.08f + 1f), 0.36f + 0.15f * sin(t * 0.12f), reach * 0.8f, 0.75f)
                blob(third, 0.2f + 0.15f * cos(t * 0.1f + 2f), 0.62f + 0.12f * sin(t * 0.07f), reach * 0.75f, 0.65f)
                blob(accent, 0.6f + 0.2f * sin(t * 0.13f + 0.5f), 0.4f + 0.15f * cos(t * 0.12f), reach * 0.5f * (1f + 0.12f * b), 0.18f + 0.22f * b)
                // Calmer under the controls
                drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to surface.copy(alpha = 0.55f)))
            }
    )
}
