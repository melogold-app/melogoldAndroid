package app.melogold.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The M3 Expressive type scale (baseline and emphasized styles) on the system font. Melogold ships
 * no fonts of its own: every phone keeps its own typeface (REDESIGN-M3E §8.4).
 *
 * @param applyFontPadding the legacy "Apply font padding" setting, applied to every style
 */
fun melogoldTypography(
    applyFontPadding: Boolean = false,
    fontFamily: FontFamily = FontFamily.Default
): Typography {
    val base = Typography(fontFamily = fontFamily)
    if (!applyFontPadding) return base

    val padded = PlatformTextStyle(includeFontPadding = true)
    fun TextStyle.padded() = copy(platformStyle = padded)

    return with(base) {
        Typography(
            displayLarge = displayLarge.padded(),
            displayMedium = displayMedium.padded(),
            displaySmall = displaySmall.padded(),
            headlineLarge = headlineLarge.padded(),
            headlineMedium = headlineMedium.padded(),
            headlineSmall = headlineSmall.padded(),
            titleLarge = titleLarge.padded(),
            titleMedium = titleMedium.padded(),
            titleSmall = titleSmall.padded(),
            bodyLarge = bodyLarge.padded(),
            bodyMedium = bodyMedium.padded(),
            bodySmall = bodySmall.padded(),
            labelLarge = labelLarge.padded(),
            labelMedium = labelMedium.padded(),
            labelSmall = labelSmall.padded(),
            displayLargeEmphasized = displayLargeEmphasized.padded(),
            displayMediumEmphasized = displayMediumEmphasized.padded(),
            displaySmallEmphasized = displaySmallEmphasized.padded(),
            headlineLargeEmphasized = headlineLargeEmphasized.padded(),
            headlineMediumEmphasized = headlineMediumEmphasized.padded(),
            headlineSmallEmphasized = headlineSmallEmphasized.padded(),
            titleLargeEmphasized = titleLargeEmphasized.padded(),
            titleMediumEmphasized = titleMediumEmphasized.padded(),
            titleSmallEmphasized = titleSmallEmphasized.padded(),
            bodyLargeEmphasized = bodyLargeEmphasized.padded(),
            bodyMediumEmphasized = bodyMediumEmphasized.padded(),
            bodySmallEmphasized = bodySmallEmphasized.padded(),
            labelLargeEmphasized = labelLargeEmphasized.padded(),
            labelMediumEmphasized = labelMediumEmphasized.padded(),
            labelSmallEmphasized = labelSmallEmphasized.padded()
        )
    }
}
