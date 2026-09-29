package app.melogold.android

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.datasource.cache.Cache
import app.melogold.android.data.NetworkMonitor
import app.melogold.android.data.cache.CachedTracks
import app.melogold.android.data.downloads.Downloads
import app.melogold.android.data.downloads.FileExport
import app.melogold.android.data.importer.LegacyImporter
import app.melogold.android.data.foryou.ForYouBuilder
import app.melogold.android.data.repo.CatalogRepository
import app.melogold.android.data.repo.PendingMutationStore
import app.melogold.android.service.PlayerService
import app.melogold.android.data.repo.SongLinkFileCache
import app.melogold.android.sync.Account
import app.melogold.android.sync.Shares
import app.melogold.android.sync.SyncEngine
import app.melogold.android.update.AppUpdater
import app.melogold.providers.songlink.ExternalLinkResolver
import app.melogold.providers.songlink.PageFetcher
import app.melogold.providers.songlink.SongLinkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Hand-written DI (REWRITE §4.11.1): application-wide singletons, all lazy. Screens reach it
 * through [LocalAppContainer], services and workers through [MainApplication.container].
 */
class AppContainer(private val application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val network by lazy { NetworkMonitor(application) }

    /** Deletions waiting for "Undo" (REWRITE §3.11.9), written in [appScope]. */
    val pendingMutations by lazy { PendingMutationStore(scope = appScope) }

    /**
     * The cache of what plays (tasks/0001-audio-cache.md): every played track, the oldest go first. One for the app:
     * the player writes it, downloads copy from it, screens count what it holds whole.
     */
    val playerCache: Cache by lazy { PlayerService.createCache(application) }

    /** The tracks [playerCache] holds whole: "available offline". */
    val cachedTracks by lazy { CachedTracks(cache = { playerCache }, scope = appScope) }

    /** Real downloads (REWRITE §4.7); first reached on the main thread (see [MainApplication]). */
    val downloads by lazy { Downloads(application, appScope, playerCache = { playerCache }) }

    /** "Save as file" into Music/Melogold. */
    val fileExport by lazy { FileExport(application, downloads) }

    /** The account on the Melogold server and the sync of the library with it. */
    val account by lazy { Account(application) }
    val sync by lazy { SyncEngine(account, network, appScope) }

    /** Links to own playlists: snapshots on the server, "My links", opening a link (tasks/0017). */
    val shares by lazy { Shares(account) }

    /** Links of Spotify, Apple Music, Yandex Music and the like, found on YouTube (tasks/0017). */
    val externalLinks by lazy {
        ExternalLinkResolver(
            songLink = SongLinkClient(
                apiKey = BuildConfig.SONGLINK_API_KEY,
                cache = SongLinkFileCache(application.cacheDir.resolve("songlink.json"))
            ),
            pages = PageFetcher()
        )
    }

    /** Import of ViTune, ViMusic and Melogold backups into the library (REWRITE §4.5). */
    val importer by lazy { LegacyImporter(application, appScope, afterImport = { sync.afterImport() }) }

    /** The self-update from GitHub Releases (REWRITE §4.14). */
    val updates by lazy { AppUpdater(application, appScope) }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // region R3.5
    val catalog by lazy {
        CatalogRepository(
            dir = application.filesDir.resolve("catalog"),
            json = json
        )
    }

    val forYou by lazy {
        ForYouBuilder(
            file = application.filesDir.resolve("foryou.json"),
            json = json
        )
    }
    // endregion R3.5
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }
