package app.melogold.android.preferences

import app.melogold.android.GlobalPreferencesHolder
import app.melogold.core.data.enums.CoilDiskCacheSize
import app.melogold.core.data.enums.ExoPlayerDiskCacheSize
import app.melogold.providers.innertube.Innertube

/** How many tracks "My top" holds; the list is always of all time. */
const val TOP_LIST_LENGTH = 50

object DataPreferences : GlobalPreferencesHolder() {
    var coilDiskCacheMaxSize by enum(CoilDiskCacheSize.`128MB`)
    var exoPlayerDiskCacheMaxSize by enum(ExoPlayerDiskCacheSize.`2GB`)
    var pauseHistory by boolean(false)
    var pausePlaytime by boolean(false)
    var pauseSearchHistory by boolean(false)
    var cachedQuickPicks by json(Innertube.RelatedPage())
    var autoSyncPlaylists by boolean(true)
}
