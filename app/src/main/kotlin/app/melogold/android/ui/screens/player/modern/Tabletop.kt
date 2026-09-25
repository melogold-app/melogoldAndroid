package app.melogold.android.ui.screens.player.modern

import androidx.compose.material3.adaptive.allHorizontalHingeBounds
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** A horizontal fold across the window: its top and bottom edges from the top of the window. */
data class Fold(val top: Dp, val bottom: Dp)

/**
 * A tabletop posture forced from debug builds (`TabletopCommand`) to see the layout on a device
 * that doesn't fold: the fold as fractions of the window height, or null.
 */
object TabletopPreview {
    var fold by mutableStateOf<ClosedFloatingPointRange<Float>?>(null)
}

/**
 * The fold of a half-open foldable lying like a laptop (tabletop posture, REDESIGN §2.7), or null
 * in any other posture: the player then puts what is watched above the fold and what is touched
 * below it.
 */
@Composable
fun tabletopFold(): Fold? {
    val density = LocalDensity.current

    TabletopPreview.fold?.let { fraction ->
        val height = LocalWindowInfo.current.containerSize.height
        return with(density) { Fold(top = (height * fraction.start).toDp(), bottom = (height * fraction.endInclusive).toDp()) }
    }

    val posture = currentWindowAdaptiveInfoV2().windowPosture
    if (!posture.isTabletop) return null

    return posture.allHorizontalHingeBounds.firstOrNull()?.let { bounds ->
        with(density) { Fold(top = bounds.top.toDp(), bottom = bounds.bottom.toDp()) }
    }
}
