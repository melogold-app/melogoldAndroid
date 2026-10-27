package app.melogold.android.service

import android.os.Handler
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 27.10.2026, One UI 9 (Android 17): the foreground stays through a pause, so the music goes on without asking for it
 * from the background, where Android 17 refuses it and silences the audio. Robolectric: the main looper's clock is
 * paused, so the hold is measured without waiting.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundHoldTest {
    private val handler = Handler(Looper.getMainLooper())
    private val looper = shadowOf(Looper.getMainLooper())

    @Test
    fun `a pause keeps the foreground for the hold, then leaves it once`() {
        val hold = ForegroundHold(handler, holdMs = 600_000L)
        var left = 0

        hold.stopped { left++ }
        // Every event of a paused player says "stopped" again: the hold does not start over
        looper.idleFor(Duration.ofMinutes(5))
        hold.stopped { left++ }
        assertTrue(hold.isHolding)
        assertEquals(0, left, "kept through a short pause")

        looper.idleFor(Duration.ofMinutes(5).plusSeconds(1))
        assertEquals(1, left, "left after ten minutes from the pause, not from the last event")
        assertFalse(hold.isHolding)
        looper.idleFor(Duration.ofMinutes(20))
        assertEquals(1, left)
    }

    @Test
    fun `the music going on keeps the foreground`() {
        val hold = ForegroundHold(handler, holdMs = 600_000L)
        var left = 0

        hold.stopped { left++ }
        looper.idleFor(Duration.ofMinutes(9))
        hold.cancel()
        looper.idleFor(Duration.ofMinutes(30))
        assertEquals(0, left, "played again before the hold ran out")
        assertFalse(hold.isHolding)

        // The next pause holds anew
        hold.stopped { left++ }
        looper.idleFor(Duration.ofMinutes(11))
        assertEquals(1, left)
    }
}
