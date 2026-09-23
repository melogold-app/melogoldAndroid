package app.melogold.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.core.ui.utils.roundedShape

enum class ThumbnailRoundness(val dp: Dp) {
    None(0.dp),
    Light(2.dp),
    Medium(8.dp),
    Heavy(12.dp),
    Heavier(16.dp),
    Heaviest(18.dp);

    val shape get() = dp.roundedShape
}

/**
 * Where the app-wide color scheme comes from.
 */
enum class ColorSource {
    /**
     * Wallpaper colors: the platform dynamic color scheme on API 31+, MonetCompat palettes below.
     */
    System,

    /**
     * The Melogold brand scheme, built from the grapefruit peel seed `#FE6B08`.
     */
    Brand,

    /**
     * A user-picked seed color. Not implemented yet (backlog P2), rendered as [Brand].
     */
    Custom
}

/**
 * Where the color scheme derived from the current track's artwork is applied.
 */
enum class ArtworkColorScope {
    PlayerOnly,
    WholeApp,
    Off
}

/**
 * How much motion the UI uses. [Minimal] is also forced when the system disables animations or
 * battery saver is on.
 */
enum class MotionLevel {
    Expressive,
    Restrained,
    Minimal
}

/**
 * Color contrast level. [System] follows `UiModeManager.getContrast()` on API 34+ and is
 * [Standard] below.
 */
enum class Contrast(val level: Double) {
    System(0.0),
    Standard(0.0),
    Medium(0.5),
    High(1.0)
}

enum class ColorMode {
    System,
    Light,
    Dark
}

enum class Darkness {
    Normal,
    AMOLED,
    PureBlack
}
