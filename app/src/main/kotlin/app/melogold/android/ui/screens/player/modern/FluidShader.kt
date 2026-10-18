package app.melogold.android.ui.screens.player.modern

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import app.melogold.android.ui.theme.FLUID_BLOBS
import app.melogold.android.ui.theme.FLUID_SIDE
import app.melogold.android.ui.theme.FluidCover
import app.melogold.android.ui.theme.FluidMotion

/*
 * The shader of the living backdrop of "Now playing" (tasks/0029), AGSL, Android 13+. A line-by-line translation of the
 * reference, Linux `resources/shaders/fluid.frag` (GLSL ES 3.00); the numbers are the same on every platform.
 *
 * AGSL has no `textureLod` over mip levels. The mip chain of the artwork is made on the CPU ([FluidCover]) and every
 * level the shader can reach is a texture of its own (`mip2` is 64 × 64 … `mip7` is 2 × 2): `lodSample` mixes the two
 * neighbours of the fractional lod, each filtered bilinearly, with the mirrored repeat of the edges: the trilinear
 * filtering of `GL_LINEAR_MIPMAP_LINEAR` and `GL_MIRRORED_REPEAT`. With the constants below (lod from 3.0 to 5.8) the
 * levels 3 – 6 are the ones read; the others are there so the lod can move without a change of the set.
 */

/** The first and the last level the shader reads. */
internal const val FLUID_FIRST_LEVEL = 2
internal const val FLUID_LAST_LEVEL = 7

/** The AGSL source. `uRes` is the size of the area in pixels, `vUv` is from the **lower** left corner, as in GL. */
internal const val FLUID_AGSL = """
uniform shader mip2;
uniform shader mip3;
uniform shader mip4;
uniform shader mip5;
uniform shader mip6;
uniform shader mip7;
uniform float2 uRes;
uniform float uLiquid;
uniform float uLiquidFlow;
uniform float uLavaFlow;
uniform float uPulse;
uniform float uLoud;
uniform float uLuma;
uniform float uBlob[6];
uniform float3 uPal[6];
uniform float uShare[6];
uniform int uPalCount;

const float uLavaMix = 0.55;
const float uLiquidBase = 0.16;
const float uBlobScale = 1.0;
const float uZoom = 0.02;
const float uLodBase = 5.3;
const float uLodPulse = 3.0;
const float uBright = 1.0;
const float uSat = 1.6;

float2 rot(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// One level, bilinear, the edges mirrored (the tile mode of the child); `uv` is 0..1, the sample is in pixels
float3 levelSample(int level, float2 uv) {
    if (level <= 2) return float3(mip2.eval(uv * 64.0).rgb);
    if (level == 3) return float3(mip3.eval(uv * 32.0).rgb);
    if (level == 4) return float3(mip4.eval(uv * 16.0).rgb);
    if (level == 5) return float3(mip5.eval(uv * 8.0).rgb);
    if (level == 6) return float3(mip6.eval(uv * 4.0).rgb);
    return float3(mip7.eval(uv * 2.0).rgb);
}

// textureLod of a trilinear texture: the two neighbouring levels, mixed by the fraction of the lod
float3 lodSample(float2 uv, float lod) {
    float l = clamp(lod, 2.0, 6.999);
    float f = floor(l);
    int i = int(f);
    return mix(levelSample(i, uv), levelSample(i + 1, uv), l - f);
}

float3 blurAt(float2 uv, float lod) {
    uv.y = 1.0 - uv.y;
    float r = exp2(lod) / 256.0 * 1.4;
    float3 c = lodSample(uv, lod) * 0.36;
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 1.5708 + 0.785;
        c += lodSample(uv + r * float2(cos(a), sin(a)), lod) * 0.16;
    }
    return c;
}

half4 main(float2 fragCoord) {
    float2 vUv = float2(fragCoord.x / uRes.x, 1.0 - fragCoord.y / uRes.y);
    float aspect = uRes.x / uRes.y;
    float pulse = clamp(uPulse, 0.0, 1.0);
    float2 p = (vUv - 0.5) * float2(aspect, 1.0) * (1.0 - uZoom * pulse);
    float lod = mix(uLodBase, uLodPulse, pulse);

    float2 q = rot(p, uLiquidFlow * 0.045);
    float2 q2 = rot(p * 0.82, -uLiquidFlow * 0.065 + 1.5708)
        + 0.18 * float2(sin(uLiquidFlow * 0.059), cos(uLiquidFlow * 0.043));
    float amp = uLiquidBase + 0.62 * uLiquid;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        q += amp * 0.32 * float2(sin(q.y * 2.3 + uLiquidFlow * 0.7 + fi * 1.3), cos(q.x * 2.0 - uLiquidFlow * 0.55 + fi * 2.1));
        q2 += amp * 0.28 * float2(cos(q2.y * 1.9 - uLiquidFlow * 0.6 + fi), sin(q2.x * 2.4 + uLiquidFlow * 0.5 + fi * 1.7));
    }
    float3 col = mix(blurAt(0.5 + q * 0.62, lod), blurAt(0.5 + q2 * 0.62, lod + 0.5), 0.38);

    float3 acc = float3(0.0);
    float field = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i < uPalCount) {
            float fi = float(i);
            float rad = (0.13 + 0.48 * sqrt(uShare[i])) * uBlobScale * (1.0 + 0.55 * uBlob[i]);
            float2 c = float2(sin(uLavaFlow * 0.21 + fi * 2.4) * aspect * 0.42, cos(uLavaFlow * 0.17 + fi * 1.7) * 0.42);
            c += 0.07 * float2(sin(uLavaFlow * 0.9 + fi), cos(uLavaFlow * 1.1 + fi * 2.0));
            float f = rad * rad / (dot(p - c, p - c) + 0.003);
            field += f;
            acc += uPal[i] * f;
        }
    }
    float3 lavaCol = acc / max(field, 1e-3);
    float m = smoothstep(0.7, 1.8, field);
    col = mix(col * 0.82, lavaCol, m * uLavaMix + 0.08);

    float k = clamp(0.30 / max(uLuma, 0.01), 0.30, 1.0) * uBright * (1.0 + 0.16 * pulse + 0.08 * uLoud);
    float l = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(l), col, uSat) * k;
    float v = smoothstep(0.55, 1.0, length((vUv - 0.5) / 0.75));
    col *= 1.0 - 0.3 * v;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/**
 * The shader of one artwork: the levels of [cover] as children, the palette from the shares. The uniforms of the
 * moment are set by [update].
 *
 * @param source the AGSL; the tests give the shader in the older syntax of the child call that Robolectric's Skia knows
 * @throws IllegalArgumentException the shader does not compile on this device
 */
@RequiresApi(33)
internal class FluidShader(cover: FluidCover, palette: List<Pair<Int, Float>>, source: String = FLUID_AGSL) {
    val shader = RuntimeShader(source)

    // The bitmaps are kept: the shaders only point at them
    private val bitmaps: List<Bitmap>
    private val blobs = FloatArray(FLUID_BLOBS)

    init {
        bitmaps = (FLUID_FIRST_LEVEL..FLUID_LAST_LEVEL).map { level ->
            val side = FLUID_SIDE shr level
            val bitmap = Bitmap.createBitmap(cover.levels[level], side, side, Bitmap.Config.ARGB_8888)
            val child = BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            // Bilinear, as the min filter of the reference
            child.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("mip$level", child)
            bitmap
        }

        val colors = FloatArray(FLUID_BLOBS * 3)
        val shares = FloatArray(FLUID_BLOBS)
        palette.take(FLUID_BLOBS).forEachIndexed { i, (rgb, share) ->
            // The colors as they are, 0..1, no `dark_backdrop`
            colors[i * 3] = (rgb shr 16 and 0xFF) / 255f
            colors[i * 3 + 1] = (rgb shr 8 and 0xFF) / 255f
            colors[i * 3 + 2] = (rgb and 0xFF) / 255f
            shares[i] = share
        }
        shader.setFloatUniform("uPal", colors)
        shader.setFloatUniform("uShare", shares)
        shader.setIntUniform("uPalCount", palette.size.coerceAtMost(FLUID_BLOBS))
    }

    /** The uniforms of this frame: the size of the area in pixels, the motion, the brightness of the artwork. */
    fun update(width: Float, height: Float, motion: FluidMotion, luma: Float) {
        shader.setFloatUniform("uRes", width, height)
        shader.setFloatUniform("uLiquid", motion.liquid)
        shader.setFloatUniform("uLiquidFlow", motion.liquidFlow)
        shader.setFloatUniform("uLavaFlow", motion.lavaFlow)
        shader.setFloatUniform("uPulse", motion.pulse)
        shader.setFloatUniform("uLoud", motion.audio.loud)
        shader.setFloatUniform("uLuma", luma)
        motion.blobs.copyInto(blobs)
        shader.setFloatUniform("uBlob", blobs)
    }
}
