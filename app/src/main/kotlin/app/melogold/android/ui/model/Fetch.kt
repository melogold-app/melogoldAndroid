package app.melogold.android.ui.model

/** The one network refresh of a detail screen that shows Room first (REWRITE §4.11.2). */
sealed interface Fetch {
    data object Running : Fetch
    data object Done : Fetch
    data class Failed(val kind: Loadable.Error.Kind) : Fetch
}

/** How much older than this Room data is refreshed into Room again. */
const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L
