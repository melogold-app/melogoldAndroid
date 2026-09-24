package app.melogold.android.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.melogold.android.R
import app.melogold.android.preferences.AppearancePreferences
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.ui.screens.Route
import app.melogold.android.utils.currentLocale
import app.melogold.android.utils.findActivity
import app.melogold.android.utils.startLanguagePicker
import app.melogold.core.ui.ColorMode
import app.melogold.core.ui.ColorSource
import app.melogold.core.ui.Darkness
import app.melogold.core.ui.utils.isAtLeastAndroid13
import kotlinx.collections.immutable.persistentListOf

@Route
@Composable
fun AppearanceSettings() = with(AppearancePreferences) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    SettingsCategoryScreen(title = stringResource(R.string.appearance)) {
        SettingsGroup(title = stringResource(R.string.colors)) {
            ValueSelectorSettingsEntry(
                title = stringResource(R.string.color_source),
                selectedValue = colorSource,
                // "Custom color" is backlog P2
                values = persistentListOf(ColorSource.System, ColorSource.Brand),
                onValueSelect = { colorSource = it },
                valueText = { it.nameLocalized }
            )
            EnumValueSelectorSettingsEntry(
                title = stringResource(R.string.color_mode),
                selectedValue = colorMode,
                onValueSelect = { colorMode = it },
                valueText = { it.nameLocalized }
            )
            AnimatedVisibility(visible = colorMode == ColorMode.Dark || (colorMode == ColorMode.System && isDark)) {
                EnumValueSelectorSettingsEntry(
                    title = stringResource(R.string.darkness),
                    selectedValue = darkness,
                    onValueSelect = { darkness = it },
                    valueText = { it.nameLocalized }
                )
            }
        }
        if (isAtLeastAndroid13) SettingsGroup(title = stringResource(R.string.text)) {
            SettingsEntry(
                title = stringResource(R.string.language),
                text = currentLocale()?.displayLanguage
                    ?: stringResource(R.string.color_source_default),
                onClick = {
                    context.findActivity().startLanguagePicker()
                }
            )

            // Melogold always uses the system font (REDESIGN-M3E §8.4), there is no font picker
        }
        SettingsGroup(title = stringResource(R.string.player)) {
            EnumValueSelectorSettingsEntry(
                title = stringResource(R.string.player_layout),
                selectedValue = PlayerPreferences.playerLayout,
                onValueSelect = { PlayerPreferences.playerLayout = it },
                valueText = { it.displayName() }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.lyrics_keep_screen_awake),
                text = stringResource(R.string.lyrics_keep_screen_awake_description),
                isChecked = PlayerPreferences.lyricsKeepScreenAwake,
                onCheckedChange = { PlayerPreferences.lyricsKeepScreenAwake = it }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.pip),
                text = stringResource(R.string.pip_description),
                isChecked = autoPip,
                onCheckedChange = { autoPip = it }
            )
        }
        SettingsGroup(title = stringResource(R.string.songs)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.swipe_to_hide_song),
                text = stringResource(R.string.swipe_to_hide_song_description),
                isChecked = swipeToHideSong,
                onCheckedChange = { swipeToHideSong = it }
            )
            AnimatedVisibility(
                visible = swipeToHideSong,
                label = ""
            ) {
                SwitchSettingsEntry(
                    title = stringResource(R.string.swipe_to_hide_song_confirm),
                    text = stringResource(R.string.swipe_to_hide_song_confirm_description),
                    isChecked = swipeToHideSongConfirm,
                    onCheckedChange = { swipeToHideSongConfirm = it }
                )
            }
            SwitchSettingsEntry(
                title = stringResource(R.string.hide_explicit),
                text = stringResource(R.string.hide_explicit_description),
                isChecked = hideExplicit,
                onCheckedChange = { hideExplicit = it }
            )
        }
    }
}

val ColorSource.nameLocalized
    @Composable get() = stringResource(
        when (this) {
            ColorSource.System -> R.string.color_source_system
            ColorSource.Brand -> R.string.color_source_brand
            ColorSource.Custom -> R.string.color_source_custom
        }
    )

val ColorMode.nameLocalized
    @Composable get() = stringResource(
        when (this) {
            ColorMode.System -> R.string.color_mode_system
            ColorMode.Light -> R.string.color_mode_light
            ColorMode.Dark -> R.string.color_mode_dark
        }
    )

val Darkness.nameLocalized
    @Composable get() = stringResource(
        when (this) {
            Darkness.Normal -> R.string.darkness_normal
            Darkness.AMOLED -> R.string.darkness_amoled
            Darkness.PureBlack -> R.string.darkness_pureblack
        }
    )
