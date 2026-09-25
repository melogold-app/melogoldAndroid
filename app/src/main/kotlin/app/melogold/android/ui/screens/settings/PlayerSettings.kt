package app.melogold.android.ui.screens.settings

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.player.StreamInfoSheet
import app.melogold.android.ui.screens.player.formatSpeed
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.rememberEqualizerLauncher
import kotlin.math.roundToInt
import app.melogold.core.ui.utils.isAtLeastAndroid6

@OptIn(UnstableApi::class)
@Route
@Composable
fun PlayerSettings() = with(PlayerPreferences) {
    val binder = LocalPlayerServiceBinder.current
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder?.player?.audioSessionId })
    val menuState = LocalMenuState.current

    // The track now playing, for "Stream info"
    var nowPlaying by remember(binder) { mutableStateOf(binder?.player?.currentMediaItem, neverEqualPolicy()) }
    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nowPlaying = mediaItem
            }
        }
    }

    SettingsCategoryScreen(title = stringResource(R.string.player)) {
        SettingsGroup(title = stringResource(R.string.player)) {
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

            // One speed for every track (REWRITE §3.10.8); a speed other than 1× shows as a chip in
            // the player, which leads here
            var newSpeed by remember(speed) { mutableFloatStateOf(speed) }
            SliderSettingsEntry(
                title = stringResource(R.string.player_speed),
                text = stringResource(R.string.player_speed_description),
                state = newSpeed,
                onSlide = { newSpeed = (it * 20).roundToInt() / 20f },
                onSlideComplete = { speed = newSpeed },
                toDisplay = { stringResource(R.string.menu_speed_value, formatSpeed(it)) },
                range = 0.5f..2f,
                steps = 29,
                showTicks = false
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

            // How the current track plays: codec, bitrate, loudness (was in the player menu)
            val current = nowPlaying
            SettingsEntry(
                title = stringResource(R.string.menu_stream_info),
                text = current?.mediaMetadata?.title?.toString() ?: stringResource(R.string.player_nothing_playing),
                isEnabled = current != null && binder != null,
                onClick = {
                    val service = binder ?: return@SettingsEntry
                    val mediaId = current?.mediaId ?: return@SettingsEntry
                    menuState.display { StreamInfoSheet(mediaId = mediaId, binder = service) }
                }
            )
        }
    }
}
