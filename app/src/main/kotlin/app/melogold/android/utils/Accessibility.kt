package app.melogold.android.utils

import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService

/**
 * Whether the user asked the system to remove animations
 * ("Remove animations" / animator duration scale set to 0).
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current

    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                /* cr = */ context.contentResolver,
                /* name = */ Settings.Global.ANIMATOR_DURATION_SCALE,
                /* def = */ 1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Whether a screen reader with touch exploration (e.g. TalkBack) is currently active.
 */
@Composable
fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) { context.getSystemService<AccessibilityManager>() }
    var enabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }

    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)

        onDispose {
            accessibilityManager?.removeTouchExplorationStateChangeListener(listener)
        }
    }

    return enabled
}
