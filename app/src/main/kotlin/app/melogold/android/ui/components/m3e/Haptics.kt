package app.melogold.android.ui.components.m3e

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Haptic feedback with the types REDESIGN-M3E §4.7 assigns to Melogold interactions. There is no
 * "Vibration" setting: the system setting for touch feedback applies.
 */
@Stable
class Haptics internal constructor(private val feedback: HapticFeedback) {
    /** Like / unlike, switches. */
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** A new option in a connected button group. */
    fun segmentTick() = perform(HapticFeedbackType.SegmentTick)

    /** A completed action, e.g. a track added to a playlist. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    fun perform(type: HapticFeedbackType) = feedback.performHapticFeedback(type)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current

    return remember(feedback) { Haptics(feedback = feedback) }
}
