package app.melogold.core.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import app.melogold.core.ui.Appearance
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.MotionLevel

val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.Expressive }

/** What an outer [MelogoldTheme] was configured with, so nested themes inherit it. */
@Immutable
private data class ThemeSettings(
    val thumbnailRoundness: Dp = MelogoldShapeTokens.Thumbnail,
    val applyFontPadding: Boolean = false
)

private val LocalThemeSettings = staticCompositionLocalOf { ThemeSettings() }

/**
 * The Melogold theme: Material 3 Expressive plus the legacy [Appearance] bridge, which is derived
 * from [scheme] so the screens that still use `LocalAppearance` get the same colors.
 *
 * It can be nested (e.g. the player with the artwork scheme): an inner call only needs [scheme], the
 * other parameters default to the values of the enclosing theme.
 *
 * @param scheme the complete color scheme, built in `:app` (`A/ui/theme/ColorSchemes.kt`)
 * @param motionLevel the effective motion level (already lowered to [MotionLevel.Minimal] when the
 *   system asks for less motion)
 * @param thumbnailRoundness corner radius of list thumbnails in the legacy screens
 * @param applyFontPadding the legacy "Apply font padding" setting
 * @param isBrandScheme whether [scheme] is the Melogold brand scheme ([app.melogold.core.ui.ColorPalette.isDefault])
 */
@Composable
fun MelogoldTheme(
    scheme: ColorScheme,
    motionLevel: MotionLevel = LocalMotionLevel.current,
    thumbnailRoundness: Dp = LocalThemeSettings.current.thumbnailRoundness,
    applyFontPadding: Boolean = LocalThemeSettings.current.applyFontPadding,
    isBrandScheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val typography = remember(applyFontPadding) { melogoldTypography(applyFontPadding) }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = motionSchemeOf(motionLevel),
        shapes = MelogoldShapes,
        typography = typography
    ) {
        val appearance = remember(scheme, thumbnailRoundness, applyFontPadding, isBrandScheme) {
            Appearance.from(
                scheme = scheme,
                thumbnailRoundness = thumbnailRoundness,
                applyFontPadding = applyFontPadding,
                isBrandScheme = isBrandScheme
            )
        }
        val extendedColors = remember(scheme) { extendedColorsOf(scheme) }

        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalExtendedColors provides extendedColors,
            LocalMotionLevel provides motionLevel,
            LocalThemeSettings provides ThemeSettings(thumbnailRoundness, applyFontPadding),
            // Legacy screens draw outside of any Surface; this keeps ripples and icons visible
            LocalContentColor provides scheme.onSurface,
            content = content
        )
    }
}

/**
 * Accessors for the parts of the Melogold theme that `MaterialTheme` does not know about.
 */
object MelogoldTheme {
    val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val motionLevel: MotionLevel
        @Composable @ReadOnlyComposable get() = LocalMotionLevel.current
}

/**
 * Expressive: `MotionScheme.expressive()` (overshoot, shape morphing). Restrained:
 * `MotionScheme.standard()`. Minimal: every transition is instant.
 */
fun motionSchemeOf(level: MotionLevel): MotionScheme = when (level) {
    MotionLevel.Expressive -> MotionScheme.expressive()
    MotionLevel.Restrained -> MotionScheme.standard()
    MotionLevel.Minimal -> InstantMotionScheme
}

@Immutable
private object InstantMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
