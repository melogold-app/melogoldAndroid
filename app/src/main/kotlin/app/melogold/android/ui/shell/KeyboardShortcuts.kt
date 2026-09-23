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
    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val ctrl = event.isCtrlPressed
        val plain = !ctrl && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed

        return when {
            ctrl && event.keyCode == KeyEvent.KEYCODE_F ||
                plain && event.keyCode == KeyEvent.KEYCODE_SLASH -> nav()?.focusSearch() != null

            plain && event.keyCode == KeyEvent.KEYCODE_SPACE -> player()?.let {
                if (it.shouldBePlaying) it.pause()
                else {
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    it.play()
                }
            } != null

            ctrl && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> player()?.forceSeekToNext() != null

            ctrl && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> player()?.forceSeekToPrevious() != null

            else -> false
        }
    }
}
