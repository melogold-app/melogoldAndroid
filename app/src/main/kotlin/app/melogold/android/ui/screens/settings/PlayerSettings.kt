package app.melogold.android.ui.screens.settings

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.ui.screens.Route
import app.melogold.android.utils.rememberEqualizerLauncher
import app.melogold.core.ui.utils.isAtLeastAndroid6

@OptIn(UnstableApi::class)
@Route
@Composable
fun PlayerSettings() = with(PlayerPreferences) {
    val binder = LocalPlayerServiceBinder.current
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder?.player?.audioSessionId })

    SettingsCategoryScreen(title = stringResource(R.string.player)) {
        SettingsGroup(title = stringResource(R.string.player)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.persistent_queue),
                text = stringResource(R.string.persistent_queue_description),
                isChecked = persistentQueue,
                onCheckedChange = { persistentQueue = it }
            )

            if (isAtLeastAndroid6) SwitchSettingsEntry(
                title = stringResource(R.string.resume_playback),
                text = stringResource(R.string.resume_playback_description),
                isChecked = resumePlaybackWhenDeviceConnected,
                onCheckedChange = {
                    resumePlaybackWhenDeviceConnected = it
                }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.stop_when_closed),
                text = stringResource(R.string.stop_when_closed_description),
                isChecked = stopWhenClosed,
                onCheckedChange = { stopWhenClosed = it }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.skip_on_error),
                text = stringResource(R.string.skip_on_error_description),
                isChecked = skipOnError,
                onCheckedChange = { skipOnError = it }
            )
        }
        SettingsGroup(title = stringResource(R.string.audio)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.loudness_normalization),
                text = stringResource(R.string.loudness_normalization_description),
                isChecked = volumeNormalization,
                onCheckedChange = { volumeNormalization = it }
            )

            AnimatedVisibility(visible = volumeNormalization) {
                var newValue by remember(volumeNormalizationBaseGain) {
                    mutableFloatStateOf(volumeNormalizationBaseGain)
                }

                SliderSettingsEntry(
                    title = stringResource(R.string.loudness_base_gain),
                    text = stringResource(R.string.loudness_base_gain_description),
                    state = newValue,
                    onSlide = { newValue = it },
                    onSlideComplete = { volumeNormalizationBaseGain = newValue },
                    toDisplay = { stringResource(R.string.format_db, "%.1f".format(it)) },
                    range = -20f..20f,
                    steps = 79,
                    showTicks = false
                )
            }

            SwitchSettingsEntry(
                title = stringResource(R.string.sponsor_block),
                text = stringResource(R.string.sponsor_block_description),
                isChecked = sponsorBlockEnabled,
                onCheckedChange = {
                    sponsorBlockEnabled = it
                }
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.audio_focus),
                text = stringResource(R.string.audio_focus_description),
                isChecked = handleAudioFocus,
                onCheckedChange = { handleAudioFocus = it }
            )

            SettingsEntry(
                title = stringResource(R.string.equalizer),
                text = stringResource(R.string.equalizer_description),
                onClick = launchEqualizer
            )
        }
    }
}
