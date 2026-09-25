package app.melogold.android.ui.shell

import android.view.KeyEvent
import androidx.media3.common.Player
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.shouldBePlaying

/**
 * Hardware keyboard shortcuts for tablets and Chromebooks (REDESIGN-M3E §2.7):
 * - `Ctrl+F` and `/` open Search with the field focused;
 * - `Space` plays / pauses;
 * - `Ctrl+→` / `Ctrl+←` skip to the next / previous track.
 *
 * The activity asks [handle] only for key events nothing on screen consumed, so typing in a text
 * field or pressing a focused button is never taken over.
 */
class KeyboardShortcuts(
    private val nav: () -> MainNavState?,
    private val player: () -> Player?
) {
    private enum class Shortcut { Search, PlayPause, Next, Previous }

    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        return when (shortcutOf(event)) {
            Shortcut.Search -> nav()?.focusSearch() != null
            Shortcut.PlayPause -> player()?.togglePlayback() != null
            Shortcut.Next -> player()?.forceSeekToNext() != null
            Shortcut.Previous -> player()?.forceSeekToPrevious() != null
            null -> false
        }
    }

    private fun shortcutOf(event: KeyEvent): Shortcut? {
        val modifiers = event.metaState and KeyEvent.getModifierMetaStateMask()

        return when {
            modifiers.isCtrlOnly -> when (event.keyCode) {
                KeyEvent.KEYCODE_F -> Shortcut.Search
                KeyEvent.KEYCODE_DPAD_RIGHT -> Shortcut.Next
                KeyEvent.KEYCODE_DPAD_LEFT -> Shortcut.Previous
                else -> null
            }

            modifiers == 0 -> when (event.keyCode) {
                KeyEvent.KEYCODE_SLASH -> Shortcut.Search
                KeyEvent.KEYCODE_SPACE -> Shortcut.PlayPause
                else -> null
            }

            else -> null
        }
    }

    /** Ctrl (left, right or both) and no other modifier */
    private val Int.isCtrlOnly
        get() = (this and KeyEvent.META_CTRL_ON) != 0 && (this and KeyEvent.META_CTRL_MASK.inv()) == 0

    private fun Player.togglePlayback() {
        if (shouldBePlaying) pause()
        else {
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }
}
