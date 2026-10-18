package app.melogold.android.ui.screens.player.modern

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The backdrop of "Now playing" as it is drawn (tasks/0028): the cover of "The Dark Side of the Moon" (black, a thin
 * rainbow) gives an almost black backdrop, not a red one; a white cover a grey one; a red cover a red one. Robolectric
 * has no blur of the whole layer (`RenderEffect`), the layers are stretched with the linear filter, as below Android 12.
 * With `-Pmelogold.screenshots=<dir>` the pictures are saved there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class NowPlayingBackdropTest {
    @get:Rule
    val compose = createComposeRule()

    private fun cover(draw: (Canvas, Paint) -> Unit): Bitmap =
        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), Paint()) }

    private val darkSide = cover { canvas, paint ->
        canvas.drawColor(Color.rgb(2, 2, 2))
        val rainbow = intArrayOf(Color.rgb(230, 30, 30), Color.rgb(240, 200, 20), Color.rgb(30, 160, 60), Color.rgb(40, 90, 220))
        rainbow.forEachIndexed { i, color ->
            paint.color = color
            canvas.drawRect(300f, 270f + i * 15f, 600f, 285f + i * 15f, paint)
        }
    }
    private val white = cover { canvas, _ -> canvas.drawColor(Color.WHITE) }
    private val red = cover { canvas, _ -> canvas.drawColor(Color.rgb(200, 30, 30)) }

    private fun drawn(
        cover: Bitmap,
        seconds: Float = 0f,
        name: String,
        fallback: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black
    ): Bitmap {
        val backdrop = assertNotNull(cover.toArtworkBackdrop())
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NowPlayingBackdrop(
                    backdrop = backdrop,
                    fallback = fallback,
                    time = { seconds }
                )
            }
        }
        val picture = compose.onRoot().captureToImage().asAndroidBitmap()
        save(picture, name)
        return picture
    }

    private fun mean(bitmap: Bitmap): IntArray {
        val sum = LongArray(3)
        var n = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val p = bitmap.getPixel(x, y)
            sum[0] += Color.red(p)
            sum[1] += Color.green(p)
            sum[2] += Color.blue(p)
            n++
        }
        return IntArray(3) { (sum[it] / n).toInt() }
    }

    @Test
    fun `a black cover with a thin rainbow gives an almost black backdrop, not a red one`() {
        val (r, g, b) = mean(drawn(darkSide, name = "backdrop-dark-side")).toList()

        assertTrue(r < 40 && g < 40 && b < 40, "dark: $r $g $b")
        assertTrue(r - b < 25, "not red: $r $g $b")
    }

    @Test
    fun `a white cover gives a grey backdrop dimmed under the white text`() {
        val (r, g, b) = mean(drawn(white, name = "backdrop-white")).toList()

        assertTrue(abs(r - g) < 6 && abs(g - b) < 6, "grey: $r $g $b")
        assertTrue(r in 40..95, "dimmed to 30 %, then the vignette: $r")
    }

    @Test
    fun `a red cover gives a red backdrop`() {
        val (r, g, b) = mean(drawn(red, name = "backdrop-red")).toList()

        assertTrue(r > 2 * g && r > 2 * b, "red: $r $g $b")
    }

    @Test
    fun `the layers turn and still cover the screen`() {
        // The fallback is magenta here: nothing of it may show in a corner, at any time
        val magenta = androidx.compose.ui.graphics.Color.Magenta
        val later = drawn(darkSide, seconds = 600f, name = "backdrop-dark-side-10-minutes", fallback = magenta)

        val corners = listOf(0 to 0, later.width - 1 to 0, 0 to later.height - 1, later.width - 1 to later.height - 1)
        corners.forEach { (x, y) ->
            val p = later.getPixel(x, y)
            assertTrue(Color.green(p) > 100 || Color.red(p) < 100, "$x,$y is not the fallback")
            assertTrue(Color.red(p) < 80 && Color.blue(p) < 80, "$x,$y is dark, as the cover is")
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val folder = System.getProperty("melogold.screenshots")?.takeIf { it.isNotBlank() } ?: return
        File(folder).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
