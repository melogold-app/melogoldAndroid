package app.melogold.android.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import app.melogold.core.ui.ColorSource
import app.melogold.core.ui.Darkness
import app.melogold.core.ui.theme.MelogoldBrand
import app.melogold.core.ui.utils.isAtLeastAndroid12
import com.kieronquinn.monetcompat.core.MonetCompat
import com.kieronquinn.monetcompat.extensions.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.Variant
import java.util.concurrent.Executor
import dev.kdrag0n.monet.colors.Color as MonetColor

/**
 * The spec version of every scheme Melogold builds. `material-color-utilities` falls back to
 * SPEC_2021 for the Fidelity and Content variants, which the 2025 spec does not define.
 */
internal val MelogoldSpecVersion = ColorSpec.SpecVersion.SPEC_2025

/**
 * Builds the app-wide color scheme (REDESIGN-M3E §4.4). The contrast always follows the system.
 *
 * - [ColorSource.System]: `dynamic*ColorScheme` on API 31+; below that the wallpaper palettes that
 *   MonetCompat (kdrag0n monet) extracts.
 * - [ColorSource.Brand] (and [ColorSource.Custom] until it exists): Fidelity scheme from the seed
 *   `#FE6B08` with the emerald `#12B866` as tertiary palette.
 *
 * [Darkness] then overrides the surface roles of dark schemes.
 */
@Composable
fun rememberMelogoldColorScheme(
    source: ColorSource,
    isDark: Boolean,
    darkness: Darkness,
    monet: MonetCompat?
): ColorScheme {
    val context = LocalContext.current
    // Dynamic colors follow the configuration (uiMode changes are handled by the activity itself)
    val configuration = LocalConfiguration.current
    val contrastLevel = rememberContrastLevel()

    return remember(source, isDark, darkness, contrastLevel, monet, configuration) {
        melogoldColorScheme(
            context = context,
            source = source,
            isDark = isDark,
            contrastLevel = contrastLevel,
            monet = monet
        ).withDarkness(isDark = isDark, darkness = darkness)
    }
}

fun melogoldColorScheme(
    context: Context,
    source: ColorSource,
    isDark: Boolean,
    contrastLevel: Double,
    monet: MonetCompat?
): ColorScheme = when (source) {
    ColorSource.System -> systemColorScheme(
        context = context,
        isDark = isDark,
        contrastLevel = contrastLevel,
        monet = monet
    )

    ColorSource.Brand, ColorSource.Custom -> null
} ?: brandColorScheme(isDark = isDark, contrastLevel = contrastLevel)

/**
 * The Melogold brand scheme. Its `primaryContainer` is the seed itself; white text on it only
 * reaches ~2.9:1, so at standard contrast `onPrimaryContainer` is the near-black tone 5 (~6.6:1).
 */
fun brandColorScheme(isDark: Boolean, contrastLevel: Double = 0.0): ColorScheme {
    val source = Hct.fromInt(MelogoldBrand.Seed.toArgb())
    val fidelity = SchemeFidelity(
        sourceColorHct = source,
        isDark = isDark,
        contrastLevel = contrastLevel,
        specVersion = MelogoldSpecVersion
    )

    val scheme = DynamicScheme(
        sourceColorHct = source,
        variant = Variant.FIDELITY,
        isDark = isDark,
        contrastLevel = contrastLevel,
        primaryPalette = fidelity.primaryPalette,
        secondaryPalette = fidelity.secondaryPalette,
        tertiaryPalette = TonalPalette.fromInt(MelogoldBrand.Emerald.toArgb()),
        neutralPalette = fidelity.neutralPalette,
        neutralVariantPalette = fidelity.neutralVariantPalette,
        specVersion = MelogoldSpecVersion
    ).toColorScheme()

    return if (contrastLevel < 0.5) scheme.copy(
        onPrimaryContainer = Color(fidelity.primaryPalette.tone(5))
    ) else scheme
}

private fun systemColorScheme(
    context: Context,
    isDark: Boolean,
    contrastLevel: Double,
    monet: MonetCompat?
): ColorScheme? {
    if (isAtLeastAndroid12)
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    return monet?.let(::monetPalettes)
        ?.toDynamicScheme(isDark = isDark, contrastLevel = contrastLevel)
        ?.toColorScheme()
}

/**
 * Key colors of the five tonal palettes of a wallpaper-based scheme.
 */
private data class KeyColors(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val neutral: Int,
    val neutralVariant: Int
) {
    fun toDynamicScheme(isDark: Boolean, contrastLevel: Double) = DynamicScheme(
        sourceColorHct = Hct.fromInt(primary),
        variant = Variant.TONAL_SPOT,
        isDark = isDark,
        contrastLevel = contrastLevel,
        primaryPalette = TonalPalette.fromInt(primary),
        secondaryPalette = TonalPalette.fromInt(secondary),
        tertiaryPalette = TonalPalette.fromInt(tertiary),
        neutralPalette = TonalPalette.fromInt(neutral),
        neutralVariantPalette = TonalPalette.fromInt(neutralVariant),
        specVersion = MelogoldSpecVersion
    )
}

/**
 * The wallpaper palettes MonetCompat extracted (API 24–30), or `null` if it has none yet.
 */
private fun monetPalettes(monet: MonetCompat): KeyColors? = runCatching {
    val colors = monet.getMonetColors()

    fun Map<Int, MonetColor>.key() = (this[KEY_SHADE] ?: values.elementAt(size / 2)).toArgb()

    KeyColors(
        primary = colors.accent1.key(),
        secondary = colors.accent2.key(),
        tertiary = colors.accent3.key(),
        neutral = colors.neutral1.key(),
        neutralVariant = colors.neutral2.key()
    )
}.getOrNull()

/** The mid shade of a system/monet palette (0 is white, 1000 is black). */
private const val KEY_SHADE = 500

/**
 * Pure black backgrounds for OLED screens. [Darkness.AMOLED] blackens the base surfaces and dims
 * the containers; [Darkness.PureBlack] makes every container black except the highest one.
 */
fun ColorScheme.withDarkness(isDark: Boolean, darkness: Darkness): ColorScheme {
    if (!isDark) return this

    return when (darkness) {
        Darkness.Normal -> this

        Darkness.AMOLED -> copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.5f),
            surfaceContainer = lerp(surfaceContainer, Color.Black, 0.4f),
            surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.3f),
            surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.2f)
        )

        Darkness.PureBlack -> copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.5f)
        )
    }
}

/**
 * The contrast level to build schemes with: the system setting on API 34+ (kept up to date while
 * composed), the standard level below.
 */
@Composable
fun rememberContrastLevel(): Double {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 0.0

    val context = LocalContext.current
    val uiModeManager = remember(context) { context.getSystemService<UiModeManager>() }
    var level by remember(uiModeManager) { mutableFloatStateOf(uiModeManager?.contrast ?: 0f) }

    DisposableEffect(uiModeManager) {
        val listener = UiModeManager.ContrastChangeListener { level = it }
        val executor = Executor { it.run() }
        uiModeManager?.addContrastChangeListener(executor, listener)
        onDispose { uiModeManager?.removeContrastChangeListener(listener) }
    }

    return level.toDouble().coerceIn(-1.0, 1.0)
}

/**
 * Converts a `material-color-utilities` scheme into a Compose [ColorScheme].
 */
fun DynamicScheme.toColorScheme() = ColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(surfaceTint),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant)
)
