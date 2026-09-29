package app.melogold.android.ui.screens.library.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.melogold.android.R
import app.melogold.android.data.stats.ListeningStats
import app.melogold.android.ui.theme.ArtworkColors
import app.melogold.android.utils.centerSquare
import app.melogold.android.utils.squareThumbnail
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat

private const val TAG = "WrappedShare"

/** What the picture of the year is made of: the numbers, the cover of the track of the year, and the color of it. */
class WrappedShareInput(val year: Int, val stats: ListeningStats, val cover: Bitmap?, val seed: Int)

/**
 * The picture "Share" of the year in review makes (tasks/0016): 1080×1920 PNG, the background the color of the cover
 * of the track of the year, "Melogold · Insights 2026" on it.
 */
object WrappedShare {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    /** The seed of the Melogold brand, for a year without a cover to take a color from. */
    const val BRAND_SEED = 0xFFFE6B08.toInt()
    private const val COVER_SIDE = 640

    /** Fetches the cover of the track of the year and works out its color; runs off the main thread. */
    suspend fun prepare(context: Context, stats: ListeningStats, year: Int): WrappedShareInput {
        val track = stats.topTracks.firstOrNull()
        val cover = track?.song?.thumbnailUrl?.let { loadCover(context, it) }
        val seed = cover?.let { ArtworkColors.seedOf(track.song.id, it) } ?: BRAND_SEED
        return WrappedShareInput(year, stats, cover, seed)
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url.squareThumbnail(COVER_SIDE))
            .size(COVER_SIDE)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()?.centerSquare()
    }.getOrNull()

    /** Writes [bitmap] as a PNG into the cache and offers it to other apps; nothing when there is no picture. */
    suspend fun share(context: Context, bitmap: Bitmap?, year: Int) {
        val file = bitmap?.let { save(context, it, year) }
        if (file == null) {
            Toast.makeText(context, R.string.stats_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.stats_share_text, year))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** The PNG in `cache/share/`, which the file provider serves; an older one of the same year is replaced. */
    suspend fun save(context: Context, bitmap: Bitmap, year: Int): File? = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.cacheDir, "share").apply { mkdirs() }
            File(folder, "melogold-insights-$year.png").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            }
        }.getOrNull()
    }

    private const val PNG_QUALITY = 100
}

/**
 * Makes the picture of [input] off the screen and hands it over: the card is composed at exactly 1080×1920 pixels (a
 * density of 1, whatever the screen has), drawn into a graphics layer instead of onto the screen, and read from there.
 * Nothing shows. [onRendered] gets null if the layer could not be read.
 */
@Composable
fun WrappedShareRenderer(input: WrappedShareInput?, onRendered: suspend (Bitmap?) -> Unit) {
    if (input == null) return
    val layer = rememberGraphicsLayer()

    LaunchedEffect(input) {
        // One frame to compose and lay the card out, one more to draw it into the layer
        withFrameNanos { }
        withFrameNanos { }
        onRendered(
            runCatching { layer.toImageBitmap().asAndroidBitmap() }
                .onFailure { Log.w(TAG, "The picture of the year could not be read from its layer", it) }
                .getOrNull()
        )
    }

    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(WrappedShare.WIDTH, WrappedShare.HEIGHT))
                    // One pixel of the screen: a node with no size is never drawn, so its layer would stay empty
                    layout(1, 1) { placeable.place(0, 0) }
                }
                .drawWithContent {
                    layer.record(size = IntSize(WrappedShare.WIDTH, WrappedShare.HEIGHT)) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            WrappedShareCard(input)
        }
    }
}

private const val LIST_ARTISTS = 5

/** Black or white, whichever reads on [background]. */
fun readableOn(background: Color): Color = if (background.luminance() > READABLE_LUMINANCE) Color(0xFF1A1A1A) else Color.White

private const val READABLE_LUMINANCE = 0.45f

/**
 * The card as a picture, drawn in a 1080×1920 space where 1 dp is 1 px: the watermark, the cover of the track of the year
 * with its name, the five artists of the year, and the minutes.
 */
@Composable
fun WrappedShareCard(input: WrappedShareInput, modifier: Modifier = Modifier) {
    val seed = Color(input.seed)
    val top = seed
    val bottom = lerp(seed, Color.Black, DARKEN)
    val ink = readableOn(lerp(top, bottom, 0.5f))
    val track = input.stats.topTracks.firstOrNull()
    val minutes = input.stats.minutes

    Column(
        modifier = modifier
            .size(WrappedShare.WIDTH.dp, WrappedShare.HEIGHT.dp)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 96.dp, vertical = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.stats_share_watermark, input.year),
            color = ink,
            style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(COVER_SIZE.dp)
                .clip(RoundedCornerShape(56.dp))
                .background(ink.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            input.cover?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(40.dp))

        if (track != null) {
            Text(
                text = stringResource(R.string.stats_wrapped_track),
                color = ink.copy(alpha = 0.75f),
                style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium)
            )
            Text(
                text = track.title,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Bold, lineHeight = 84.sp)
            )
            track.artist?.let {
                Text(
                    text = it,
                    color = ink.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 48.sp)
                )
            }
        }
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.stats_top_artists),
            color = ink.copy(alpha = 0.75f),
            style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium)
        )
        Spacer(modifier = Modifier.height(12.dp))
        input.stats.topArtists.take(LIST_ARTISTS).forEachIndexed { index, artist ->
            Text(
                text = "${index + 1}  ${artist.name}",
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = NumberFormat.getIntegerInstance().format(minutes),
            color = ink,
            style = TextStyle(fontSize = 128.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            text = pluralStringResource(R.plurals.stats_wrapped_minutes, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            color = ink,
            style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Medium)
        )
    }
}

private const val COVER_SIZE = 560
private const val DARKEN = 0.45f
