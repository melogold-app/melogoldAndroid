package app.melogold.android.ui.modifiers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.core.ui.utils.px
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * How far a swipe has moved the content. Each swipe owns its own animation: an old pool shared them between
 * components, and a mini player could keep the offset of another one (half off screen after "next").
 */
@Stable
class SwipeState internal constructor() {
    internal val offset = Animatable(0f)

    @Composable
    fun calculateOffset(bounds: ClosedRange<Dp>? = null) =
        offset.value.px.dp.let { if (bounds == null) it else it.coerceIn(bounds) }
}

@Composable
fun rememberSwipeState(key: Any?): SwipeState = remember(key) { SwipeState() }

fun Modifier.onSwipe(
    state: SwipeState? = null,
    key: Any = Unit,
    animateOffset: Boolean = false,
    orientation: Orientation = Orientation.Horizontal,
    delay: Duration = Duration.ZERO,
    decay: Density.() -> DecayAnimationSpec<Float> = { splineBasedDecay(this) },
    animationSpec: AnimationSpec<Float> = spring(),
    bounds: ClosedRange<Dp>? = null,
    requireUnconsumed: Boolean = false,
    onSwipeOut: suspend (animationJob: Job) -> Unit
) = onSwipe(
    state = state,
    key = key,
    animateOffset = animateOffset,
    onSwipeLeft = onSwipeOut,
    onSwipeRight = onSwipeOut,
    orientation = orientation,
    delay = delay,
    decay = decay,
    animationSpec = animationSpec,
    requireUnconsumed = requireUnconsumed,
    bounds = bounds
)

@Suppress("CyclomaticComplexMethod")
fun Modifier.onSwipe(
    state: SwipeState? = null,
    key: Any = Unit,
    animateOffset: Boolean = false,
    onSwipeLeft: suspend (animationJob: Job) -> Unit = { },
    onSwipeRight: suspend (animationJob: Job) -> Unit = { },
    orientation: Orientation = Orientation.Horizontal,
    delay: Duration = Duration.ZERO,
    decay: Density.() -> DecayAnimationSpec<Float> = { splineBasedDecay(this) },
    animationSpec: AnimationSpec<Float> = spring(),
    bounds: ClosedRange<Dp>? = null,
    requireUnconsumed: Boolean = false
) = this.composed {
    val swipeState = state ?: rememberSwipeState(key)
    // The swipe out and the way back run in the component's scope: the gesture's own coroutine restarts when the
    // content recomposes (a mini player does on every progress tick), and the offset stayed where it was cut off
    val animationScope = rememberCoroutineScope()
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)

    pointerInput(key, swipeState) {
        coroutineScope {
            val velocityTracker = VelocityTracker()

            // fling loop, doesn't really offset anything but simulates the animation beforehand
            while (isActive) {
                velocityTracker.resetTracking()

                awaitPointerEventScope {
                    val pointer = awaitFirstDown(requireUnconsumed = requireUnconsumed).id
                    // Undispatched: every move lands at once, in order. Dispatched snaps ran after the finger was
                    // up, each one cancelling the way back, and the content stayed off to the side
                    launch(start = CoroutineStart.UNDISPATCHED) { swipeState.offset.snapTo(0f) }

                    val onDrag: (PointerInputChange) -> Unit = {
                        val change =
                            if (orientation == Orientation.Horizontal) it.positionChange().x
                            else it.positionChange().y

                        launch(start = CoroutineStart.UNDISPATCHED) {
                            swipeState.offset.snapTo(swipeState.offset.value + change)
                        }

                        velocityTracker.addPosition(it.uptimeMillis, it.position)
                        if (change != 0f) it.consume()
                    }

                    if (orientation == Orientation.Horizontal) {
                        awaitHorizontalTouchSlopOrCancellation(pointer) { change, _ -> onDrag(change) }
                            ?: return@awaitPointerEventScope
                        horizontalDrag(pointer, onDrag)
                    } else {
                        awaitVerticalTouchSlopOrCancellation(pointer) { change, _ -> onDrag(change) }
                            ?: return@awaitPointerEventScope
                        verticalDrag(pointer, onDrag)
                    }
                }

                // drag completed, calculate velocity
                val targetOffset = decay().calculateTargetValue(
                    initialValue = swipeState.offset.value,
                    initialVelocity = velocityTracker.calculateVelocity()
                        .let { if (orientation == Orientation.Horizontal) it.x else it.y }
                )
                val size = if (orientation == Orientation.Horizontal) size.width else size.height

                // The swipe out starts at once (undispatched): started later, it interrupted the way back, both
                // stopped, and the content stayed where the finger let go
                animationScope.launch animationEnd@{
                    try {
                        when {
                            targetOffset >= size / 2 -> {
                                val animationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                    swipeState.offset.animateTo(
                                        targetValue = size.toFloat(),
                                        animationSpec = animationSpec
                                    )
                                }
                                delay(delay)
                                currentOnSwipeRight(animationJob)
                            }

                            targetOffset <= -size / 2 -> {
                                val animationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                    swipeState.offset.animateTo(
                                        targetValue = -size.toFloat(),
                                        animationSpec = animationSpec
                                    )
                                }
                                delay(delay)
                                currentOnSwipeLeft(animationJob)
                            }
                        }
                    } finally {
                        // Back in place whatever happened to the swipe (a callback that failed included); only a
                        // component that left the screen skips it
                        swipeState.offset.animateTo(targetValue = 0f, animationSpec = animationSpec)
                    }
                }
            }
        }
    }.let { modifier ->
        when {
            animateOffset && orientation == Orientation.Horizontal ->
                modifier.offset(x = swipeState.calculateOffset(bounds = bounds))

            animateOffset && orientation == Orientation.Vertical ->
                modifier.offset(y = swipeState.calculateOffset(bounds = bounds))

            else -> modifier
        }
    }
}
