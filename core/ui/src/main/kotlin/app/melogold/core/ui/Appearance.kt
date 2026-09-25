package app.melogold.core.ui

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import app.melogold.core.ui.theme.MelogoldShapeTokens
import app.melogold.core.ui.utils.isAtLeastAndroid6
import app.melogold.core.ui.utils.isAtLeastAndroid8
import app.melogold.core.ui.utils.roundedShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The legacy theme read by screens that are not migrated to Material 3 yet. It is a bridge: it is
 * always derived from the Material theme by [app.melogold.core.ui.theme.MelogoldTheme] and never
 * computed separately.
 */
@Immutable
data class Appearance(
    val colorPalette: ColorPalette,
    val typography: Typography,
    val thumbnailShapeCorners: Dp
) {
    val thumbnailShape = thumbnailShapeCorners.roundedShape
    operator fun component4() = thumbnailShape

    companion object {
        fun from(
            scheme: ColorScheme,
            isBrandScheme: Boolean = false
        ): Appearance {
            val colorPalette = ColorPalette.from(scheme = scheme, isDefault = isBrandScheme)

            return Appearance(
                colorPalette = colorPalette,
                typography = typographyOf(color = colorPalette.text),
                thumbnailShapeCorners = MelogoldShapeTokens.Thumbnail
            )
        }
    }
}

val LocalAppearance = staticCompositionLocalOf<Appearance> { error("No appearance provided") }

fun Activity.setSystemBarAppearance(isDark: Boolean) {
    with(WindowCompat.getInsetsController(window, window.decorView.rootView)) {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
    }

    val color = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()

    // TODO: Android now expects a background behind the system bars as well
    @Suppress("DEPRECATION")
    if (!isAtLeastAndroid6) window.statusBarColor = color
    @Suppress("DEPRECATION")
    if (!isAtLeastAndroid8) window.navigationBarColor = color
}

@Composable
fun Activity.SystemBarAppearance(palette: ColorPalette) = SystemBarAppearance(isDark = palette.isDark)

@Composable
fun Activity.SystemBarAppearance(isDark: Boolean) = LaunchedEffect(isDark) {
    withContext(Dispatchers.Main) {
        setSystemBarAppearance(isDark)
    }
}
