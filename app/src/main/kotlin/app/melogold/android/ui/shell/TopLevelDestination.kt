package app.melogold.android.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.melogold.android.R

/**
 * The five top-level sections of the app, in bottom bar order: Trends · New · Library · Search ·
 * Settings (the user's decision of 2026-09-25).
 *
 * @param label the name in the navigation bar / rail
 * @param icon the outlined icon of an unselected item
 * @param selectedIcon the filled icon of the selected item; a semibold one when Material Symbols
 * has no filled form (M3 navigation bar guidelines)
 */
enum class TopLevelDestination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int
) {
    Trends(
        label = R.string.nav_trends,
        icon = R.drawable.ms_trending_up,
        selectedIcon = R.drawable.ms_trending_up_bold
    ),
    WhatsNew(
        label = R.string.nav_whats_new,
        icon = R.drawable.ms_new_releases,
        selectedIcon = R.drawable.ms_new_releases_fill
    ),
    Library(
        label = R.string.nav_library,
        icon = R.drawable.ms_library_music,
        selectedIcon = R.drawable.ms_library_music_fill
    ),
    Search(
        label = R.string.nav_search,
        icon = R.drawable.ms_search,
        selectedIcon = R.drawable.ms_search_bold
    ),
    Settings(
        label = R.string.nav_settings,
        icon = R.drawable.ms_settings,
        selectedIcon = R.drawable.ms_settings_fill
    );

    /**
     * The [app.melogold.compose.persist.LocalPersistNamespace] of this section's stack.
     */
    val persistNamespace get() = "tab/$name/"

    companion object {
        /**
         * The section shown on the very first launch. Later launches reopen the section the user
         * was in (`AppearancePreferences.lastTab`), REDESIGN-M3E §8.1.
         */
        val FirstLaunch = Trends
    }
}
