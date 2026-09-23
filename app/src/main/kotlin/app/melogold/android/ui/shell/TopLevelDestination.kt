package app.melogold.android.ui.shell

/**
 * The five top-level sections of the app, in bottom bar order (REDESIGN-M3E §1).
 */
enum class TopLevelDestination {
    Search,
    Library,
    Trends,
    WhatsNew,
    Settings;

    companion object {
        /**
         * The section shown on the very first launch. Later launches reopen the section the user
         * was in (`AppearancePreferences.lastTab`), REDESIGN-M3E §8.1.
         */
        val FirstLaunch = Trends
    }
}
