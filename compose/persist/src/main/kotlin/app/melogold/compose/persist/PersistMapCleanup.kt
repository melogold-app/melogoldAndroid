package app.melogold.compose.persist

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the navigation stack around a composable is being *parked*: taken out of composition
 * only because the user switched to another top-level section, to be restored when they come
 * back. [PersistMapCleanup] keeps the cached state of parked screens.
 *
 * The owner of the stack flips [isParked] before the stack leaves composition.
 */
@Stable
class TabParking {
    @Volatile
    var isParked: Boolean = false
}

/**
 * The [TabParking] of the stack around the caller, `null` outside a parkable stack.
 */
val LocalTabParking = staticCompositionLocalOf<TabParking?> { null }

@Composable
fun PersistMapCleanup(prefix: String) {
    val context = LocalContext.current
    val persistMap = LocalPersistMap.current
    val key = persistKey(namespace = LocalPersistNamespace.current, tag = prefix)
    val parking = LocalTabParking.current

    DisposableEffect(persistMap) {
        onDispose {
            if (parking?.isParked == true) return@onDispose
            if (context.findActivityNullable()?.isChangingConfigurations == false)
                persistMap?.clean(key)
        }
    }
}

fun Context.findActivityNullable(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
