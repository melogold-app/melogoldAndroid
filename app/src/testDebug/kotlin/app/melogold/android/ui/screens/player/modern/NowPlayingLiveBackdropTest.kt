package app.melogold.android.ui.screens.player.modern

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * tasks/0029 §6: without the shader the backdrop of tasks/0028 stays as it was. Below Android 13 there is no AGSL; a
 * shader that does not compile (here: the Skia of Robolectric does not know the `eval` of the devices) is caught, and the
 * same backdrop takes over.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w411dp-h891dp-xxhdpi")
class NowPlayingLiveBackdropTest {
    @get:Rule
    val compose = createComposeRule()

    private val red = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.rgb(200, 30, 30)) }

    private fun picture(live: Boolean, playing: Boolean): Bitmap {
        val backdrop = assertNotNull(red.toArtworkBackdrop())
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NowPlayingLiveBackdrop(
                    backdrop = backdrop,
                    fallback = androidx.compose.ui.graphics.Color.Black,
                    playing = playing,
                    visible = true,
                    live = live
                )
            }
        }
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    private fun assertRed(bitmap: Bitmap) {
        val p = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue(Color.red(p) > 2 * Color.green(p) && Color.red(p) > 2 * Color.blue(p), "red: ${Color.red(p)} ${Color.green(p)} ${Color.blue(p)}")
    }

    @Test
    @Config(sdk = [30])
    fun `below Android 13 the blurred artwork of 0028 is drawn`() {
        // Not playing: the clock of the layers would never let the test idle
        assertRed(picture(live = true, playing = false))
    }

    @Test
    @Config(sdk = [34])
    fun `a shader that does not compile gives the blurred artwork of 0028`() {
        assertRed(picture(live = true, playing = false))
    }

    @Test
    @Config(sdk = [30])
    fun `a still backdrop is drawn too`() {
        assertRed(picture(live = false, playing = false))
    }
}
