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
import app.melogold.core.ui.Appearance
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.MotionLevel

val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.Expressive }

/**
 * The Melogold theme: Material 3 Expressive plus the legacy [Appearance] bridge, which is derived
 * from [scheme] so the screens that still use `LocalAppearance` get the same colors.
 *
 * It can be nested (e.g. the player with the artwork scheme): an inner call only needs [scheme], the
 * motion level defaults to the one of the enclosing theme.
 *
 * @param scheme the complete color scheme, built in `:app` (`A/ui/theme/ColorSchemes.kt`)
 * @param motionLevel the effective motion level (already lowered to [MotionLevel.Minimal] when the
 *   system asks for less motion)
 * @param isBrandScheme whether [scheme] is the Melogold brand scheme ([app.melogold.core.ui.ColorPalette.isDefault])
 */
@Composable
fun MelogoldTheme(
    scheme: ColorScheme,
    motionLevel: MotionLevel = LocalMotionLevel.current,
    isBrandScheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val typography = remember { melogoldTypography() }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = motionSchemeOf(motionLevel),
        shapes = MelogoldShapes,
        typography = typography
    ) {
        val appearance = remember(scheme, isBrandScheme) {
            Appearance.from(scheme = scheme, isBrandScheme = isBrandScheme)
        }
        val extendedColors = remember(scheme) { extendedColorsOf(scheme) }

        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalExtendedColors provides extendedColors,
            LocalMotionLevel provides motionLevel,
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
