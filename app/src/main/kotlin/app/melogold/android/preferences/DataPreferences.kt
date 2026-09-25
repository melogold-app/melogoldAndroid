package app.melogold.android.preferences

import app.melogold.android.GlobalPreferencesHolder
import app.melogold.core.data.enums.CoilDiskCacheSize
import app.melogold.core.data.enums.ExoPlayerDiskCacheSize

/** How many tracks "My top" holds; the list is always of all time. */
const val TOP_LIST_LENGTH = 50

object DataPreferences : GlobalPreferencesHolder() {
    var coilDiskCacheMaxSize by enum(CoilDiskCacheSize.`128MB`)
    // Every played track is cached; the oldest go when the cache is full (REWRITE §4.7, tasks/0001-cache.md)
    var exoPlayerDiskCacheMaxSize by enum(ExoPlayerDiskCacheSize.`4GB`)
    var pauseHistory by boolean(false)
    var pausePlaytime by boolean(false)
    var pauseSearchHistory by boolean(false)

    /** Downloads wait for Wi-Fi (REWRITE §3.5.6). */
    var downloadsWifiOnly by boolean(false, name = "downloads.wifiOnly")
}
