package app.melogold.android.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.melogold.android.AppContainer
import app.melogold.android.LocalAppContainer
import app.melogold.compose.persist.LocalPersistMap
import app.melogold.compose.persist.LocalPersistNamespace
import app.melogold.compose.persist.persistKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The state holder of one screen (REWRITE §4.11.3). It lives in the `PersistMap` under the route's
 * key, so it survives rotation and a parked section, and is closed when the route leaves the stack.
 */
abstract class ScreenModel : AutoCloseable {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun close() = scope.cancel()
}

/**
 * The [ScreenModel] stored under [key], created with [create] the first time.
 */
@Composable
inline fun <reified M : ScreenModel> rememberScreenModel(
    key: String,
    crossinline create: AppContainer.() -> M
): M {
    val container = LocalAppContainer.current
    val persistMap = LocalPersistMap.current
    val fullKey = persistKey(namespace = LocalPersistNamespace.current, tag = key)

    if (persistMap == null) {
        val model = remember(fullKey) { container.create() }
        DisposableEffect(model) { onDispose { model.close() } }
        return model
    }

    return remember(persistMap, fullKey) {
        val state = persistMap.map.getOrPut(fullKey) { mutableStateOf(container.create()) }
        state.value as? M ?: container.create().also {
            (state.value as? AutoCloseable)?.close()
            persistMap.map[fullKey] = mutableStateOf(it)
        }
    }
}
