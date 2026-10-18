package app.melogold.android.ui.screens.player.modern

import kotlin.math.min

/*
 * The sizes of "Now playing" that follow the size of the whole area (tasks/0028), the same numbers on every platform.
 * The lyrics line is a share of the HEIGHT of the area, not of the width of its column: the column changes with the
 * layout, and the same text was huge in one window and small in another. Now a window of any width shows the same
 * number of lines, and the wraps do not jump when the layout changes.
 */

/** The line of the lyrics is this share of the height of the area... */
private const val LINE_SHARE_OF_HEIGHT = 0.042f

/** ...but not smaller than this, dp... */
private const val MIN_LINE = 18f

/** ...and not larger than this. */
private const val MAX_LINE = 64f

/** The size the lyrics were set in before (headline medium); the other sizes of a line keep their proportions to it. */
const val LYRICS_BASE_LINE = 28f

/** The side of the artwork in the wide layout (the artwork on one side, the rest on the other): of the height... */
private const val WIDE_COVER_SHARE_OF_HEIGHT = 0.50f

/** ...and of the width, whichever is smaller. */
private const val WIDE_COVER_SHARE_OF_WIDTH = 0.38f

/** About this many characters fit a line of the lyrics in a wide layout. */
private const val WIDE_LINE_CHARACTERS = 28

/** An average character of bold text is about this wide, in em. */
private const val CHARACTER_EM = 0.54f

/**
 * The size of a lyrics line, in dp (set in sp): `clamp(0.042 · H, 18, 64)`, where H is the height of the whole area
 * of "Now playing" in dp.
 */
fun lyricsLineSize(areaHeight: Float): Float = (LINE_SHARE_OF_HEIGHT * areaHeight).coerceIn(MIN_LINE, MAX_LINE)

/** The side of the artwork in the wide layout: `min(0.50 · H, 0.38 · W)` of the whole area. */
fun wideCoverSide(areaHeight: Float, areaWidth: Float): Float =
    min(WIDE_COVER_SHARE_OF_HEIGHT * areaHeight, WIDE_COVER_SHARE_OF_WIDTH * areaWidth)

/** The widest the text of the lyrics gets in a wide layout (about 28 characters of [lineSize]), without its padding. */
fun wideLyricsTextWidth(lineSize: Float): Float = WIDE_LINE_CHARACTERS * CHARACTER_EM * lineSize
