package app.melogold.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.melogold.core.ui.isDark
import com.materialkolor.blend.Blend
import com.materialkolor.palettes.TonalPalette

/**
 * Melogold brand colors. They seed color schemes and are never used directly as text colors:
 * `#FE6B08` reaches only ~2.9:1 against white and the emerald ~2.6:1.
 */
object MelogoldBrand {
    /** Grapefruit peel, the icon color. Seed of the brand scheme. */
    val Seed = Color(0xFFFE6B08)

    /** Emerald, the tertiary palette of the brand scheme and the source of [ExtendedColors.success]. */
    val Emerald = Color(0xFF12B866)
}

/**
 * Color roles Material 3 does not have.
 *
 * @property success "Synced", "cache ready" and similar states. Always paired with an icon or a
 *   word, never shown as color alone.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color
) {
    companion object {
        val Unspecified = ExtendedColors(
            success = Color.Unspecified,
            onSuccess = Color.Unspecified,
            successContainer = Color.Unspecified,
            onSuccessContainer = Color.Unspecified
        )
    }
}

val LocalExtendedColors = staticCompositionLocalOf { ExtendedColors.Unspecified }

/**
 * Derives [ExtendedColors] for [scheme]: the brand emerald harmonized towards the scheme's primary
 * color, so "success" belongs to any scheme (system, brand or artwork).
 */
fun extendedColorsOf(scheme: ColorScheme): ExtendedColors {
    val harmonized = Blend.harmonize(
        designColor = MelogoldBrand.Emerald.toArgb(),
        sourceColor = scheme.primary.toArgb()
    )
    val palette = TonalPalette.fromInt(harmonized)
    fun tone(tone: Int) = Color(palette.tone(tone))

    return if (scheme.isDark) ExtendedColors(
        success = tone(80),
        onSuccess = tone(20),
        successContainer = tone(30),
        onSuccessContainer = tone(90)
    ) else ExtendedColors(
        success = tone(40),
        onSuccess = tone(100),
        successContainer = tone(90),
        onSuccessContainer = tone(10)
    )
}
