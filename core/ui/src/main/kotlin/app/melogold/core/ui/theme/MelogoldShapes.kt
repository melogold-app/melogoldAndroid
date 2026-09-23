package app.melogold.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material shape scale. It is the M3 Expressive default, spelled out so the token table below
 * and the theme cannot drift apart.
 */
val MelogoldShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Corner radii of Melogold surfaces (REDESIGN-M3E §4.6). `MaterialShapes` (cookie, heart, …) are
 * only used for the four collection icons, the account avatar and loading indicators; artwork is
 * never clipped to them.
 */
object MelogoldShapeTokens {
    /** List thumbnails. The user's `thumbnailRoundness` setting overrides it. */
    val Thumbnail = 12.dp

    /** Cards, collection tiles and grouped lists (outer corners). */
    val Card = 16.dp

    /** Inner corners of segmented list items. */
    val SegmentInner = 4.dp

    /** Gap between segmented list items. */
    val SegmentGap = 2.dp

    /** Items of an uncontained carousel. */
    val CarouselUncontained = 16.dp

    /** Items of a multi-browse carousel (`Modifier.maskClip`). */
    val CarouselMultiBrowse = 28.dp

    /** Top corners of the mini player. */
    val MiniPlayerTop = 16.dp

    /** Sheets, the player sheet, dialogs and the artwork in the player. */
    val Sheet = 28.dp
}
