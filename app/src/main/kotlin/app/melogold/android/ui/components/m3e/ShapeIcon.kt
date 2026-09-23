@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The container shapes Melogold uses (REDESIGN-M3E §4.6): the four collection tiles (Favorites:
 * heart, Offline: 9-sided cookie, Top: sunny, History: 4-leaf clover), the account avatar
 * (cookie) and plain tonal circles. Artwork is never clipped to these.
 */
enum class IconShape {
    Circle,
    Heart,
    Cookie9Sided,
    Sunny,
    Clover4Leaf
}

val IconShape.shape: Shape
    @Composable get() = when (this) {
        IconShape.Circle -> CircleShape
        IconShape.Heart -> MaterialShapes.Heart.toShape()
        IconShape.Cookie9Sided -> MaterialShapes.Cookie9Sided.toShape()
        IconShape.Sunny -> MaterialShapes.Sunny.toShape()
        IconShape.Clover4Leaf -> MaterialShapes.Clover4Leaf.toShape()
    }

/**
 * A [shape]d tonal container with arbitrary content (e.g. avatar initials).
 */
@Composable
fun ShapeContainer(
    shape: IconShape,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: @Composable BoxScope.() -> Unit
) = Box(
    modifier = modifier
        .size(size)
        .background(color = containerColor, shape = shape.shape),
    contentAlignment = Alignment.Center,
    content = content
)

/**
 * An icon centered in a [shape]d tonal container.
 */
@Composable
fun ShapeIcon(
    @DrawableRes icon: Int,
    shape: IconShape,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) = ShapeContainer(
    shape = shape,
    modifier = modifier,
    size = size,
    containerColor = containerColor
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = Modifier.size(size * ICON_FRACTION)
    )
}

private const val ICON_FRACTION = 0.55f
