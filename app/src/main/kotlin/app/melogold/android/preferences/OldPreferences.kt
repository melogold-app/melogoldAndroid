package app.melogold.android.preferences

import app.melogold.android.GlobalPreferencesHolder

internal object OldPreferences : GlobalPreferencesHolder() {
    val oldColorPaletteName by enum(ColorPaletteName.Dynamic, "colorPaletteName")
    val oldColorPaletteMode by enum(ColorPaletteMode.System, "colorPaletteMode")

    enum class ColorPaletteName {
        Default,
        Dynamic,
        MaterialYou,
        PureBlack,
        AMOLED
    }

    enum class ColorPaletteMode {
        Light,
        Dark,
        System
    }
}
