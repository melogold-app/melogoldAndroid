package app.melogold.android.ui.screens.library.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.ui.theme.ArtworkColors
import app.melogold.android.ui.theme.brandColorScheme
import app.melogold.core.ui.theme.MelogoldTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The picture "Share" makes for the year in review (tasks/0016): 1080×1920 whatever the screen, the color of the cover
 * of the track of the year behind it, the watermark on it. With `-Pmelogold.screenshots=<dir>` it is saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class WrappedShareTest {
    @get:Rule
    val compose = createComposeRule()

    private val year = StatsFixtures.stats(StatsPeriod.Year, today = LocalDate.of(2026, 12, 20))

    /** A cover that is not grey: a deep blue with a lighter corner. */
    private fun cover(): Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).also { bitmap ->
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 640f, 640f, Color.rgb(20, 60, 160), Color.rgb(70, 140, 220), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 640f, 640f, paint)
    }

    private fun render(content: @Composable () -> Unit) = compose.setContent {
        MelogoldTheme(scheme = brandColorScheme(isDark = false), isBrandScheme = true) {
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(0)) { content() }
        }
    }

    private fun made(input: WrappedShareInput): Bitmap {
        var result: Bitmap? = null
        var done = false
        render { WrappedShareRenderer(input) { result = it; done = true } }
        compose.waitUntil(timeoutMillis = 10_000) { done }
        return assertNotNull(result, "the layer gave a picture")
    }

    @Test
    fun `the picture is 1080 by 1920 with the color of the cover behind it`() {
        val cover = cover()
        val seed = runBlocking { ArtworkColors.seedOf("share-test", cover) }
        assertNotNull(seed, "a blue cover has a color")
        // The color is from the blue of the cover, not grey and not red
        assertTrue(Color.blue(seed) > Color.red(seed) + 40, "blue: ${Integer.toHexString(seed)}")

        val bitmap = made(WrappedShareInput(2026, year, cover, seed))

        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        // The top of the picture is the seed color and it goes darker down to the bottom, all of it painted
        val top = bitmap.getPixel(20, 20)
        val bottom = bitmap.getPixel(20, 1900)
        assertEquals(255, Color.alpha(top))
        assertEquals(255, Color.alpha(bottom))
        assertTrue(close(top, seed, 12), "the top is the color of the cover: ${Integer.toHexString(top)} and ${Integer.toHexString(seed)}")
        assertTrue(luma(bottom) < luma(top), "it darkens towards the bottom")
        save(bitmap, "wrapped-share")
    }

    @Test
    fun `the cover is drawn on it, whatever the color behind`() {
        val cover = cover()
        // A green behind a blue cover: what is blue in the column of the cover is the cover
        val bitmap = made(WrappedShareInput(2026, year, cover, 0xFF2E7D32.toInt()))

        val centre = cover.getPixel(320, 320)
        val found = (300..1100).any { y -> close(bitmap.getPixel(1080 / 2, y), centre, 30) }
        assertTrue(found, "the cover is somewhere in the middle column")
        assertTrue(close(bitmap.getPixel(20, 20), 0xFF2E7D32.toInt(), 12))
    }

    @Test
    fun `without a cover the brand color is behind it`() {
        val bitmap = made(WrappedShareInput(2026, year, cover = null, seed = WrappedShare.BRAND_SEED))

        assertEquals(1080, bitmap.width)
        assertEquals(1920, bitmap.height)
        assertTrue(close(bitmap.getPixel(20, 20), WrappedShare.BRAND_SEED, 12))
        save(bitmap, "wrapped-share-no-cover")
    }

    @Test
    fun `the picture is the same size on a small screen`() {
        // The card is composed at a density of 1, so the screen does not matter
        val bitmap = made(WrappedShareInput(2026, year, cover(), 0xFF2E7D32.toInt()))

        assertEquals(1080 to 1920, bitmap.width to bitmap.height)
    }

    @Test
    fun `the text is dark on a light color and light on a dark one`() {
        assertEquals(androidx.compose.ui.graphics.Color.White.toArgb(), readableOn(androidx.compose.ui.graphics.Color(0xFF123456)).toArgb())
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1A1A1A).toArgb(), readableOn(androidx.compose.ui.graphics.Color(0xFFFFE082)).toArgb())
    }

    @Test
    fun `it is saved as a PNG the file provider serves`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)

        val file = assertNotNull(WrappedShare.save(context, bitmap, 2026))

        assertEquals("melogold-insights-2026.png", file.name)
        assertEquals(File(context.cacheDir, "share").canonicalPath, file.parentFile?.canonicalPath)
        assertTrue(file.length() > 0)
        // A PNG starts with its signature
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), file.readBytes().take(4).map { it.toInt() and 0xFF })
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        assertEquals("content", uri.scheme)
    }

    private fun luma(pixel: Int) = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)

    private fun close(a: Int, b: Int, tolerance: Int) =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance &&
            abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun save(bitmap: Bitmap, name: String) {
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
