package app.melogold.android.preferences

import app.melogold.android.GlobalPreferencesHolder

object PlayerPreferences : GlobalPreferencesHolder() {
    val trackLoopEnabledProperty = boolean(false)
    var trackLoopEnabled by trackLoopEnabledProperty
    val queueLoopEnabledProperty = boolean(true)
    var queueLoopEnabled by queueLoopEnabledProperty
    val volumeNormalizationProperty = boolean(false)
    var volumeNormalization by volumeNormalizationProperty
    val volumeNormalizationBaseGainProperty = float(5.00f)
    var volumeNormalizationBaseGain by volumeNormalizationBaseGainProperty
    val resumePlaybackWhenDeviceConnectedProperty = boolean(false)
    var resumePlaybackWhenDeviceConnected by resumePlaybackWhenDeviceConnectedProperty
    val speedProperty = float(1f)
    var speed by speedProperty
    var stopWhenClosed by boolean(false)

    var isShowingLyrics by boolean(false)
    var preferSyncedLyrics by boolean(true)

    var lyricsKeepScreenAwake by boolean(false)

    val handleAudioFocusProperty = boolean(true)
    var handleAudioFocus by handleAudioFocusProperty

    /** Other devices of the account may control this one (tasks/0018): the live stream is opened with `remote=1`. */
    val remoteControlEnabledProperty = boolean(true)
    var remoteControlEnabled by remoteControlEnabledProperty

    val sponsorBlockEnabledProperty = boolean(false)
    var sponsorBlockEnabled by sponsorBlockEnabledProperty
}
