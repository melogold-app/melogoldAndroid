package app.melogold.android.preferences

import app.melogold.android.GlobalPreferencesHolder
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.compose.preferences.SharedPreferencesProperty
import app.melogold.core.ui.ArtworkColorScope
import app.melogold.core.ui.ColorMode
import app.melogold.core.ui.ColorSource
import app.melogold.core.ui.Contrast
import app.melogold.core.ui.Darkness
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.ThumbnailRoundness
import app.melogold.core.ui.utils.isAtLeastAndroid12

object AppearancePreferences : GlobalPreferencesHolder() {
    /**
     * Stored under the pre-redesign key, whose old values are migrated on read (REDESIGN-M3E §4.4):
     * `Default` → [ColorSource.Brand], `MaterialYou` → [ColorSource.System], `Dynamic` (the old
     * default, "color of the playing track") → the platform default.
     */
    var colorSource by SharedPreferencesProperty(
        name = "colorSource",
        get = { key -> colorSourceOf(getString(key, null)) },
        set = { key, value -> putString(key, value.name) },
        default = defaultColorSource
    )
    var artworkColorScope by enum(ArtworkColorScope.PlayerOnly)
    var colorMode by enum(ColorMode.System)
    var darkness by enum(Darkness.Normal)
    var contrast by enum(Contrast.System)
    var motionLevel by enum(MotionLevel.Expressive)
    var hapticsEnabled by boolean(true)

    /**
     * The section the user was in, reopened on the next launch (REDESIGN-M3E §8.1). There is no
     * "start section" setting; the very first launch opens [TopLevelDestination.FirstLaunch].
     */
    var lastTab by enum(TopLevelDestination.FirstLaunch)

    var thumbnailRoundness by enum(ThumbnailRoundness.Heavy)
    var applyFontPadding by boolean(false)
    val isShowingThumbnailInLockscreenProperty = boolean(true)
    var isShowingThumbnailInLockscreen by isShowingThumbnailInLockscreenProperty
    var swipeToHideSong by boolean(false)
    var swipeToHideSongConfirm by boolean(true)
    var maxThumbnailSize by int(1920)
    var hideExplicit by boolean(false)
    var autoPip by boolean(false)
    var openPlayer by boolean(true)
}

/**
 * Wallpaper colors where the platform has them (API 31+), the brand scheme otherwise.
 */
private val defaultColorSource get() = if (isAtLeastAndroid12) ColorSource.System else ColorSource.Brand

private fun colorSourceOf(stored: String?): ColorSource = when (stored) {
    null -> defaultColorSource

    "Default" -> ColorSource.Brand

    "MaterialYou" -> ColorSource.System

    "Dynamic" -> defaultColorSource

    else -> ColorSource.entries.firstOrNull { it.name == stored } ?: defaultColorSource
}
