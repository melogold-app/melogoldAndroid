package app.melogold.android.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.melogold.core.ui.MotionLevel

/**
 * Whether the user asked the system to remove animations
 * ("Remove animations" / animator duration scale set to 0). Follows changes while composed.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current

    fun read() = runCatching {
        Settings.Global.getFloat(
            /* cr = */ context.contentResolver,
            /* name = */ Settings.Global.ANIMATOR_DURATION_SCALE,
            /* def = */ 1f
        ) == 0f
    }.getOrDefault(false)

    var reduceMotion by remember(context) { mutableStateOf(read()) }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = read()
            }
        }
        context.contentResolver.registerContentObserver(
            /* uri = */ Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            /* notifyForDescendants = */ false,
            /* observer = */ observer
        )

        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    return reduceMotion
}

/**
 * Whether battery saver is on. Follows changes while composed.
 */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService<PowerManager>() }
    var powerSave by remember(powerManager) { mutableStateOf(powerManager?.isPowerSaveMode == true) }

    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSave = powerManager?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            /* context = */ context,
            /* receiver = */ receiver,
            /* filter = */ IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            /* flags = */ ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose { context.unregisterReceiver(receiver) }
    }

    return powerSave
}

/**
 * The motion level to actually use: [MotionLevel.Minimal] when the system removed animations or
 * battery saver is on (REDESIGN-M3E §4.7), [preferred] otherwise.
 */
@Composable
fun rememberEffectiveMotionLevel(preferred: MotionLevel): MotionLevel {
    val reduceMotion = rememberReduceMotion()
    val powerSave = rememberPowerSaveMode()

    return if (reduceMotion || powerSave) MotionLevel.Minimal else preferred
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
