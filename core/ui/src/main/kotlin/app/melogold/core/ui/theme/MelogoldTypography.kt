package app.melogold.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * The M3 Expressive type scale (baseline and emphasized styles) on the system font. Melogold ships
 * no fonts of its own: every phone keeps its own typeface (REDESIGN-M3E §8.4).
 */
fun melogoldTypography(fontFamily: FontFamily = FontFamily.Default): Typography =
    Typography(fontFamily = fontFamily)
