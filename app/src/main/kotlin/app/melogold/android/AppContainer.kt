package app.melogold.android

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.melogold.android.data.NetworkMonitor
import app.melogold.android.data.downloads.Downloads
import app.melogold.android.data.downloads.FileExport
import app.melogold.android.data.foryou.ForYouBuilder
import app.melogold.android.data.repo.CatalogRepository
import app.melogold.android.data.repo.PendingMutationStore
import app.melogold.android.sync.Account
import app.melogold.android.sync.SyncEngine
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

    /** Real downloads (REWRITE §4.7); first reached on the main thread (see [MainApplication]). */
    val downloads by lazy { Downloads(application, appScope) }

    /** "Save as file" into Music/Melogold. */
    val fileExport by lazy { FileExport(application, downloads) }

    /** The account on the Melogold server and the sync of the library with it. */
    val account by lazy { Account(application) }
    val sync by lazy { SyncEngine(account, network, appScope) }

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
