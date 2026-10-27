package app.melogold.android.service

import android.os.Handler

/**
 * The player stays in the foreground for [holdMs] after the music stops (a pause, a lost audio focus, headphones
 * pulled out), not only while it plays.
 *
 * 27.10.2026, One UI 9 (Android 17): "at some moment the sound is gone till the end of the track, the track goes on in
 * silence". Android 17 silences the audio of an app in the background without a foreground service, with no error
 * (background audio hardening). The service left the foreground at every pause and asked for it again when the music
 * went on — from the background, where the system refuses it. Google's advice: keep the `mediaPlayback` foreground
 * service through interruptions of under 10 minutes.
 */
internal class ForegroundHold(
    private val handler: Handler,
    private val holdMs: Long = HOLD_MS
) {
    private var pending: Runnable? = null

    val isHolding: Boolean get() = pending != null

    /** The music stopped: [leave] the foreground after the hold, unless it plays again before ([cancel]). */
    fun stopped(leave: () -> Unit) {
        if (pending != null) return
        val runnable = Runnable {
            pending = null
            leave()
        }
        pending = runnable
        handler.postDelayed(runnable, holdMs)
    }

    /** The music plays again, or the foreground is left now: nothing to leave later. */
    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    companion object {
        const val HOLD_MS = 10 * 60 * 1000L
    }
}
