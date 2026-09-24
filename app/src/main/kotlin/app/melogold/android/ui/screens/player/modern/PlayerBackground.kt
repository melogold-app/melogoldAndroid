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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.utils.thumbnail
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

private const val SAMPLE_SIZE = 112

/** The primary container shows through at the top of the player (REWRITE §3.10.2). */
private const val TOP_TINT_ALPHA = 0.35f

/**
 * A small software copy of the artwork at [uri], for picking its colors; null while loading or
 * when there is no artwork.
 */
@Composable
fun rememberArtworkBitmap(uri: Uri?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = uri?.let {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(it.toString().thumbnail(SAMPLE_SIZE))
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
        }
    }

    return bitmap
}

/**
 * The static background of the player (`ArtworkTintedBackground`, REWRITE §3.10.2): the surface of
 * the artwork's color scheme with its primary container fading in from the top. Nothing moves; a
 * new track only crossfades the colors.
 */
@Composable
fun ArtworkTintedBackground(modifier: Modifier = Modifier) {
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val surface by animateColorAsState(MaterialTheme.colorScheme.surface, spec, label = "")
    val tint by animateColorAsState(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = TOP_TINT_ALPHA),
        spec,
        label = ""
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surface)
            .background(Brush.verticalGradient(0f to tint, 0.65f to Color.Transparent))
    )
}
