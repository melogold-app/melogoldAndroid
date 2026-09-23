package app.melogold.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The legacy size scale used by screens that still draw text with `BasicText`. New code uses
 * `MaterialTheme.typography`; both use the system font ([FontFamily.Default]).
 */
@Immutable
data class Typography(internal val style: TextStyle) {
    val xxs by lazy { style.copy(fontSize = 12.sp) }
    val xs by lazy { style.copy(fontSize = 14.sp) }
    val s by lazy { style.copy(fontSize = 16.sp) }
    val m by lazy { style.copy(fontSize = 18.sp) }
    val l by lazy { style.copy(fontSize = 20.sp) }
    val xxl by lazy { style.copy(fontSize = 32.sp) }

    fun copy(color: Color) = Typography(style = style.copy(color = color))
}

fun typographyOf(
    color: Color,
    applyFontPadding: Boolean,
    fontFamily: FontFamily = FontFamily.Default
) = Typography(
    style = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        color = color,
        platformStyle = PlatformTextStyle(includeFontPadding = applyFontPadding)
    )
)
