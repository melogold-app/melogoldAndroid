package app.melogold.core.ui

/**
 * Where the app-wide color scheme comes from.
 */
enum class ColorSource {
    /**
     * Wallpaper colors: the platform dynamic color scheme (API 31+ only).
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
 * How much motion the UI uses. [Minimal] is also forced when the system disables animations or
 * battery saver is on.
 */
enum class MotionLevel {
    Expressive,
    Restrained,
    Minimal
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
