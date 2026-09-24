package app.melogold.android.ui.screens.player.modern

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.ui.theme.ArtworkColors
import app.melogold.android.utils.thumbnail
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct

private const val SAMPLE_SIZE = 112

// HCT tones of the top and bottom of the gradient: dark enough for the white text on top
private const val TOP_TONE = 24.0
private const val BOTTOM_TONE = 10.0
private const val MAX_CHROMA = 40.0

// Grey artwork (no seed color) gets a nearly neutral background in the hue of the app accent
private const val NEUTRAL_CHROMA = 6.0

private val colorAnimationSpec = tween<Color>(durationMillis = 800, easing = LinearOutSlowInEasing)

private fun toneOf(hct: Hct, tone: Double, maxChroma: Double) =
    Color(Hct.from(hct.hue, hct.chroma.coerceAtMost(maxChroma), tone).toInt())

/**
 * The static background of the player: a dark vertical gradient in the hue of the artwork's seed
 * color (the same `QuantizerCelebi` + `Score` seed as the artwork color scheme, see
 * [ArtworkColors]). Nothing moves; a new track only crossfades the colors.
 */
@Composable
fun PlayerBackground(
    artworkUri: Uri?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var seed by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(artworkUri) {
        val bitmap = artworkUri?.let { uri ->
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(uri.toString().thumbnail(SAMPLE_SIZE))
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()

                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                    ?.image
                    ?.toBitmap()
            }.getOrNull()
        }

        seed = bitmap?.let { ArtworkColors.seedOf(key = artworkUri.toString(), bitmap = it) }
    }

    val source = remember(seed, accent) { Hct.fromInt(seed ?: accent.toArgb()) }
    val maxChroma = if (seed == null) NEUTRAL_CHROMA else MAX_CHROMA

    val top by animateColorAsState(toneOf(source, TOP_TONE, maxChroma), colorAnimationSpec, label = "")
    val bottom by animateColorAsState(toneOf(source, BOTTOM_TONE, maxChroma), colorAnimationSpec, label = "")

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
    )
}
