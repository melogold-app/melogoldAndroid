package app.melogold.android.ui.screens.player.modern

import android.os.SystemClock
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the expanded player shows. [Queue] is an overlay on top of the other two. */
enum class PlayerMode { NowPlaying, Lyrics, Queue }

/**
 * Mode and controls-visibility state of the new player.
 *
 * [baseMode] is what the user asked for (never [PlayerMode.Queue]); the mode actually shown also
 * depends on the lyrics (see [effectiveBase]) and on the queue sheet.
 */
@Stable
class PlayerModeState(initialBase: PlayerMode) {
    var baseMode by mutableStateOf(initialBase)
        private set

    /** The song the user explicitly opened the lyrics for, see [effectiveBase]. */
    var lyricsRequestedFor by mutableStateOf<String?>(null)

    /** Drives the NowPlaying <-> Lyrics animation, seekable for predictive back. */
    val transitionState = SeekableTransitionState(initialBase)

    var controlsVisible by mutableStateOf(true)
        internal set

    /** Uptime of the last touch (or other "wake up the controls" event). */
    var lastPressAt by mutableLongStateOf(0L)
        private set

    /** Whether the controls were hidden when the last touch started. */
    var hiddenAtLastPress = false
        private set

    var userScrolling by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)

    fun openLyrics(mediaId: String) {
        baseMode = PlayerMode.Lyrics
        lyricsRequestedFor = mediaId
    }

    fun closeLyrics() {
        baseMode = PlayerMode.NowPlaying
        lyricsRequestedFor = null
    }

    /** Called for every pointer press anywhere on the player, before anything handles it. */
    fun onPress() {
        hiddenAtLastPress = !controlsVisible
        lastPressAt = SystemClock.uptimeMillis()
    }

    /** Shows the controls and restarts the auto-hide timer. */
    fun reveal() {
        lastPressAt = SystemClock.uptimeMillis()
    }

    /**
     * When the lyrics mode was kept from a previous song and this song has no lyrics, show Now
     * Playing instead, but keep [baseMode] so the lyrics come back on the next song that has them.
     */
    fun effectiveBase(content: LyricsContent, mediaId: String) = if (
        baseMode == PlayerMode.Lyrics &&
        content is LyricsContent.NotFound &&
        lyricsRequestedFor != mediaId
    ) PlayerMode.NowPlaying else baseMode
}
