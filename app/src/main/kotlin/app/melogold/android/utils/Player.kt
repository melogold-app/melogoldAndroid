package app.melogold.android.utils

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.melogold.android.preferences.AppearancePreferences
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.service.PlayerService
import app.melogold.android.sync.remote.RemoteRouting
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.models.NavigationEndpoint
import kotlin.time.Duration

val Player.currentWindow: Timeline.Window?
    get() = if (mediaItemCount == 0) null else currentTimeline[currentMediaItemIndex]

inline val Timeline.windows: List<Timeline.Window>
    get() = List(windowCount) { this[it] }

inline val Timeline.mediaItems: List<MediaItem>
    get() = windows.map { it.mediaItem }

val Player.shouldBePlaying: Boolean
    get() = !(playbackState == Player.STATE_ENDED || !playWhenReady)

fun Player.removeMediaItems(range: IntRange) = removeMediaItems(range.first, range.last + 1)

fun Player.safeClearQueue() {
    if (currentMediaItemIndex > 0) removeMediaItems(0 until currentMediaItemIndex)
    if (currentMediaItemIndex < mediaItemCount - 1) {
        removeMediaItems(currentMediaItemIndex + 1 until mediaItemCount)
    }
}

fun Player.seamlessPlay(mediaItem: MediaItem) =
    if (mediaItem.mediaId == currentMediaItem?.mediaId) safeClearQueue() else forcePlay(mediaItem)

fun Player.shuffleQueue() {
    val mediaItems = currentTimeline
        .mediaItems
        .toMutableList()
        .apply { removeAt(currentMediaItemIndex) }
        .shuffled()

    safeClearQueue()
    addMediaItems(mediaItems)
}

fun Player.forcePlay(mediaItem: MediaItem) {
    setMediaItem(mediaItem, true)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayAtIndex(
    items: List<MediaItem>,
    index: Int
) {
    if (items.isEmpty()) return
    // While this device controls another, a tap on a track plays that list there (tasks/0018)
    if (RemoteRouting.playQueue(items, index)) return

    setMediaItems(items, index, C.TIME_UNSET)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayFromBeginning(items: List<MediaItem>) = forcePlayAtIndex(items, 0)

@Suppress("NestedBlockDepth") // TODO
fun Player.forceSeekToPrevious(
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    seekToStart: Boolean = true
): Unit = when {
    seekToStart && currentPosition > maxSeekToPreviousPosition -> seekToPrevious()

    hideExplicit -> if (mediaItemCount <= 1) {
        forceSeekToPrevious(hideExplicit = false)
    } else {
        var i = currentMediaItemIndex - 1
        while (
            i !in (0 until mediaItemCount) ||
            getMediaItemAt(i).mediaMetadata.extras?.songBundle?.explicit == true
        ) {
            if (i <= 0) i = mediaItemCount - 1 else i--
        }
        seekTo(i, C.TIME_UNSET)
    }

    // fall back to default behavior if there is only a single song

    hasPreviousMediaItem() -> seekToPreviousMediaItem()

    mediaItemCount > 0 -> seekTo(mediaItemCount - 1, C.TIME_UNSET)

    else -> {}
}

fun Player.forceSeekToNext() =
    if (hasNextMediaItem()) seekToNext() else seekTo(0, C.TIME_UNSET)

/**
 * The index [forceSeekToNext] lands on: the next one in the playing order (shuffle and repeat included), else the
 * first; `null` when it would stay on the same track (a queue of one, or none).
 */
fun Player.nextTrackIndex(): Int? = when {
    mediaItemCount <= 1 -> null
    hasNextMediaItem() -> nextMediaItemIndex
    else -> 0
}.takeIf { it != C.INDEX_UNSET && it != currentMediaItemIndex }

/**
 * The index [forceSeekToPrevious] lands on when it does not seek to the start of the current track (`seekToStart =
 * false`: a swipe of the mini player): the previous one (skipping explicit ones when they are hidden), else the last;
 * `null` when it would stay on the same track. Only the swipe needs it beforehand, to show the track that comes in.
 */
fun Player.previousTrackIndex(hideExplicit: Boolean = AppearancePreferences.hideExplicit): Int? {
    val count = mediaItemCount
    if (count <= 1) return null
    val target = when {
        hideExplicit -> {
            // At most one round: a queue of explicit tracks only must not loop here (composition calls this)
            var i = currentMediaItemIndex - 1
            var steps = 0
            while (
                (i !in 0 until count || getMediaItemAt(i).mediaMetadata.extras?.songBundle?.explicit == true) &&
                steps++ < count
            ) {
                if (i <= 0) i = count - 1 else i--
            }
            i
        }
        hasPreviousMediaItem() -> previousMediaItemIndex
        else -> count - 1
    }
    return target.takeIf { it in 0 until count && it != currentMediaItemIndex }
}

fun Player.addNext(mediaItem: MediaItem) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem)
    else -> addMediaItem(currentMediaItemIndex + 1, mediaItem)
}

fun Player.addNext(mediaItems: List<MediaItem>) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlayFromBeginning(mediaItems)
    else -> addMediaItems(currentMediaItemIndex + 1, mediaItems)
}

fun Player.enqueue(mediaItem: MediaItem) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem)
    else -> addMediaItem(mediaItemCount, mediaItem)
}

fun Player.enqueue(mediaItems: List<MediaItem>) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlayFromBeginning(mediaItems)
    else -> addMediaItems(mediaItemCount, mediaItems)
}

fun Player.findNextMediaItemById(mediaId: String): MediaItem? = runCatching {
    for (i in currentMediaItemIndex until mediaItemCount) {
        if (getMediaItemAt(i).mediaId == mediaId) return getMediaItemAt(i)
    }
    return null
}.getOrNull()

operator fun Timeline.get(
    index: Int,
    window: Timeline.Window = Timeline.Window(),
    positionProjection: Duration = Duration.ZERO
): Timeline.Window = getWindow(index, window, positionProjection.inWholeMicroseconds)

/**
 * Plays [mediaItem] alone, followed by its radio (REWRITE §2.3: search results, links, "Recently
 * played"). Local files get no radio.
 */
fun PlayerService.Binder.playWithRadio(mediaItem: MediaItem) {
    if (RemoteRouting.playQueue(listOf(mediaItem), 0)) return

    stopRadio()
    player.forcePlay(mediaItem)
    if (!mediaItem.mediaId.startsWith(LOCAL_KEY_PREFIX))
        setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
}
