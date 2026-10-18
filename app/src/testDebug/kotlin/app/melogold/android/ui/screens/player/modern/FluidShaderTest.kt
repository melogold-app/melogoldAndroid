package app.melogold.android.ui.screens.player.modern

import app.melogold.android.ui.theme.ColorShare
import app.melogold.android.ui.theme.FluidMotion
import app.melogold.android.ui.theme.prepareFluidCover
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The AGSL shader of the living backdrop (tasks/0029) compiles with the real Skia that Robolectric runs (native
 * graphics), and takes its uniforms. Robolectric cannot draw a `RuntimeShader` (a software canvas does not support it) and
 * its Skia knows only `sample(child, coord)`, not the `child.eval(coord)` of the devices: the source is compiled in
 * that spelling, nothing else of it is changed. The picture itself was compared with the reference `fluid.frag` on a
 * GLES 3 context (the same artwork, uniforms and levels): the difference is at most 1/255 of a channel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FluidShaderTest {
    private fun withSampleCalls(source: String) = Regex("""(mip\d)\.eval\(""").replace(source) { "sample(${it.groupValues[1]}, " }

    private fun shader(source: String = withSampleCalls(FLUID_AGSL)): FluidShader {
        val side = 512
        val pixels = IntArray(side * side) { i -> if ((i % side + i / side) % 64 < 32) 0xFFE0402A.toInt() else 0xFF2A60E0.toInt() }
        val fluid = assertNotNull(prepareFluidCover(pixels, side, side))
        val palette = listOf(ColorShare(0xE0402A, 0.6), ColorShare(0x2A60E0, 0.4))
        return FluidShader(fluid, palette.map { it.rgb to it.share.toFloat() }, source)
    }

    @Test
    fun `the shader compiles and takes the uniforms of a frame`() {
        val shader = shader()
        val motion = FluidMotion()
        motion.feed(FloatArray(48) { 0.5f })
        motion.step(1f / 60f, playing = true)

        shader.update(width = 1080f, height = 2400f, motion = motion, luma = 0.3f)
    }

    @Test
    fun `a broken shader is an error the backdrop can catch`() {
        val failed = runCatching { shader(source = "half4 main(float2 c) { return nonsense; }") }

        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `the shader keeps the numbers of the reference`() {
        // The constants of tasks/0029 §4 – §5, as the shader has them
        listOf(
            "uLavaMix = 0.55", "uLiquidBase = 0.16", "uBlobScale = 1.0", "uZoom = 0.02", "uLodBase = 5.3", "uLodPulse = 3.0",
            "uBright = 1.0", "uSat = 1.6", "smoothstep(0.7, 1.8, field)", "uLiquidFlow * 0.045", "/ 256.0 * 1.4", "* 0.36", "* 0.16",
            "0.30 / max(uLuma, 0.01)", "1.0 + 0.16 * pulse + 0.08 * uLoud", "0.3 * v"
        ).forEach { assertTrue(FLUID_AGSL.contains(it), "the shader has $it") }
        assertEquals(6, Regex("uniform shader mip").findAll(FLUID_AGSL).count())
    }
}
