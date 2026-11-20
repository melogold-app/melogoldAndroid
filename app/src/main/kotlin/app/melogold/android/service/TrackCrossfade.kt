package app.melogold.android.service

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Keeps the MediaSession player as the source of truth. The second player only renders an outgoing tail. */
@OptIn(UnstableApi::class)
internal class TrackCrossfade(
    private val player: ExoPlayer,
    private val handler: Handler,
    private val tailPlayer: (MediaItem) -> ExoPlayer,
    private val canPlayNext: (MediaItem) -> Boolean
) : Player.Listener {
    private data class Tail(val item: MediaItem, val player: ExoPlayer)
    private class Transition(val destination: String, val requestedAt: Long) {
        var startedAt: Long? = null
    }

    private var tail: Tail? = null
    private var transition: Transition? = null
    private var lastPlaying: String? = null
    private val ticker = Runnable { tick() }
    private var enabled = false

    init { player.addListener(this) }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
        schedule()
    }

    fun release() {
        handler.removeCallbacks(ticker)
        player.removeListener(this)
        clear()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!canRun()) clear()
        schedule()
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        val previous = old.mediaItem
        val destination = new.mediaItem?.mediaId
        val active = transition
        if (active != null && destination == active.destination && previous?.mediaId != destination) return
        if (active != null || previous?.mediaId == destination) {
            clear()
            return
        }
        if (!canRun() || previous == null || destination == null || lastPlaying != previous.mediaId ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || old.positionMs == C.TIME_UNSET) {
            clear()
            return
        }
        val outgoing = prepare(previous, old.positionMs)
        outgoing.playbackParameters = player.playbackParameters
        outgoing.seekTo(old.positionMs)
        transition = Transition(destination, SystemClock.elapsedRealtime())
        tick()
    }

    private fun canRun() = enabled && player.playWhenReady &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE

    private fun schedule() {
        handler.removeCallbacks(ticker)
        if (canRun()) handler.postDelayed(ticker, 50)
    }

    private fun tick() {
        if (!canRun()) {
            clear()
            return
        }
        val active = transition
        if (active != null) advance(active)
        else if (player.isPlaying) {
            lastPlaying = player.currentMediaItem?.mediaId
            considerAutomatic()
        }
        schedule()
    }

    private fun considerAutomatic() {
        val item = player.currentMediaItem ?: return
        if (player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem() ||
            player.duration == C.TIME_UNSET || !player.isCurrentMediaItemSeekable || player.isCurrentMediaItemLive) return
        val index = player.nextMediaItemIndex
        val next = player.getMediaItemAt(index)
        if (next.mediaId == item.mediaId || !canPlayNext(next)) return
        val fadeMediaMs = (DURATION_MS * player.playbackParameters.speed).toLong()
        val end = player.duration - fadeMediaMs
        val remaining = player.duration - player.currentPosition
        if (end <= 0 || remaining !in 200L..(fadeMediaMs + 4000)) return
        val outgoing = prepare(item, end)
        if (remaining > fadeMediaMs || outgoing.playbackState != Player.STATE_READY) return
        outgoing.playbackParameters = player.playbackParameters
        if (kotlin.math.abs(outgoing.currentPosition - player.currentPosition) > 300) return
        transition = Transition(next.mediaId, SystemClock.elapsedRealtime())
        outgoing.volume = 1f
        outgoing.play()
        player.volume = 0f
        player.seekTo(index, 0)
    }

    private fun prepare(item: MediaItem, offset: Long): ExoPlayer {
        tail?.takeIf { it.item.mediaId == item.mediaId }?.let { return it.player }
        tail?.player?.release()
        val outgoing = tailPlayer(item)
        tail = Tail(item, outgoing)
        outgoing.volume = 0f
        outgoing.setMediaItem(item, offset)
        outgoing.prepare()
        return outgoing
    }

    private fun advance(active: Transition) {
        val outgoing = tail?.player ?: return clear()
        val now = SystemClock.elapsedRealtime()
        if (player.currentMediaItem?.mediaId != active.destination || outgoing.playerError != null ||
            outgoing.playbackState == Player.STATE_ENDED || now - active.requestedAt >= DURATION_MS * 2) return clear()
        if (!outgoing.playWhenReady) {
            // For a manual change, prefer the ordinary switch when the outgoing tail cannot be loaded quickly.
            if (outgoing.playbackState != Player.STATE_READY) {
                if (now - active.requestedAt > 250) clear()
                return
            }
            player.volume = 0f
            outgoing.volume = 1f
            outgoing.play()
        }
        if (player.isPlaying) active.startedAt = active.startedAt ?: now
        val elapsed = active.startedAt?.let { now - it } ?: 0
        if (elapsed >= DURATION_MS) return clear()
        val progress = elapsed.toFloat() / DURATION_MS
        player.volume = progress
        outgoing.volume = 1f - progress
    }

    private fun clear() {
        if (transition != null) player.volume = 1f
        transition = null
        val outgoing = tail?.player
        tail = null
        outgoing?.release()
    }

    private companion object {
        const val DURATION_MS = 3000L
    }
}
