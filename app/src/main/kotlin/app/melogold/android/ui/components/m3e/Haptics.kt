package app.melogold.android.ui.components.m3e

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.melogold.android.preferences.AppearancePreferences

/**
 * Haptic feedback that respects the "Vibration" setting (`AppearancePreferences.hapticsEnabled`),
 * with the feedback types REDESIGN-M3E §4.7 assigns to Melogold interactions.
 */
@Stable
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean
) {
    /** Like / unlike, switches. */
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** A new option in a connected button group. */
    fun segmentTick() = perform(HapticFeedbackType.SegmentTick)

    /** A completed action, e.g. a track added to a playlist. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    fun perform(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = AppearancePreferences.hapticsEnabled

    return remember(feedback, enabled) { Haptics(feedback = feedback, enabled = enabled) }
}
