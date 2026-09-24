package app.melogold.core.ui

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette

/**
 * The legacy palette read by screens that are not migrated to Material 3 yet (via
 * [LocalAppearance]). It is never computed on its own: [MelogoldTheme][app.melogold.core.ui.theme.MelogoldTheme]
 * derives it from the Material [ColorScheme] with [ColorPalette.from].
 */
@Immutable
data class ColorPalette(
    val background0: Color,
    val background1: Color,
    val background2: Color,
    val accent: Color,
    val onAccent: Color,
    val red: Color = Color(0xffbf4040),
    val blue: Color = Color(0xff4472cf),
    val yellow: Color = Color(0xfffff176),
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val isDefault: Boolean,
    val isDark: Boolean
) {
    companion object {
        /**
         * Maps Material 3 color roles onto the legacy palette (REDESIGN-M3E §4.3).
         *
         * @param isDefault whether [scheme] is the Melogold brand scheme
         */
        fun from(scheme: ColorScheme, isDefault: Boolean = false) = ColorPalette(
            background0 = scheme.surface,
            background1 = scheme.surfaceContainer,
            background2 = scheme.surfaceContainerHigh,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            red = scheme.error,
            blue = scheme.tertiary,
            yellow = scheme.secondaryContainer,
            text = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            textDisabled = scheme.onSurface.copy(alpha = 0.38f),
            isDefault = isDefault,
            isDark = scheme.isDark
        )
    }
}

/**
 * Whether this scheme is a dark one, judged by its surface color.
 */
val ColorScheme.isDark get() = surface.luminance() < 0.5f

// A static fallback with the dark neutral tones of the Melogold brand scheme (seed #FE6B08). Only
// code that has not been migrated yet (the "Now playing" screen) reads it.
val defaultDarkPalette = ColorPalette(
    background0 = Color(0xff1d100a),
    background1 = Color(0xff2a1c16),
    background2 = Color(0xff362720),
    text = Color(0xfff7ddd2),
    textSecondary = Color(0xffe2bfb1),
    textDisabled = Color(0x61f7ddd2),
    accent = Color(0xffffb694),
    onAccent = Color(0xff571f00),
    red = Color(0xffffb4ab),
    isDefault = true,
    isDark = true
)

/**
 * The dominant color of [bitmap]. Still used by the player background; the app-wide artwork color
 * scheme uses `material-color-utilities` instead (`A/ui/theme/ArtworkColorScheme.kt`).
 */
fun dynamicAccentColorOf(
    bitmap: Bitmap,
    isDark: Boolean
): Hsl? {
    val palette = Palette
        .from(bitmap)
        .maximumColorCount(8)
        .addFilter(if (isDark) ({ _, hsl -> hsl[0] !in 36f..100f }) else null)
        .generate()

    val hsl = if (isDark) {
        palette.dominantSwatch ?: Palette
            .from(bitmap)
            .maximumColorCount(8)
            .generate()
            .dominantSwatch
    } else {
        palette.dominantSwatch
    }?.hsl ?: return null

    val arr = if (hsl[1] < 0.08)
        palette.swatches
            .map(Palette.Swatch::getHsl)
            .sortedByDescending(FloatArray::component2)
            .find { it[1] != 0f }
            ?: hsl
    else hsl

    return arr.hsl
}

inline val ColorPalette.isPureBlack get() = background0 == Color.Black
inline val ColorPalette.collapsedPlayerProgressBar
    get() = if (isPureBlack) defaultDarkPalette.background0 else background2
inline val ColorPalette.favoritesIcon get() = if (isDefault) red else accent
inline val ColorPalette.shimmer get() = if (isDefault) Color(0xff838383) else accent
inline val ColorPalette.surface get() = if (isPureBlack) Color(0xff272727) else background2

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.overlay get() = Color.Black.copy(alpha = 0.75f)

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlay get() = defaultDarkPalette.text

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlayShimmer get() = defaultDarkPalette.shimmer
