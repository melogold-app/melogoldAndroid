package app.melogold.android

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.melogold.android.data.foryou.ForYouBuilder
import app.melogold.android.data.repo.CatalogRepository
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
