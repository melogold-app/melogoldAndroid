package app.melogold.android.ui.kit

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.models.DownloadState
import app.melogold.android.models.TrackDownload
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The download of [videoId] as it goes, or null without one; only its own changes recompose. */
@Composable
fun rememberDownload(videoId: String): TrackDownload? {
    val downloads = LocalAppContainer.current.downloads
    val flow = remember(downloads, videoId) { downloads.visible.map { it[videoId] }.distinctUntilChanged() }
    return flow.collectAsState(initial = downloads.visible.value[videoId]).value
}

/** Whether [videoId] is whole in the player's cache: "available offline" (tasks/0001-audio-cache.md). */
@Composable
fun rememberCached(videoId: String): Boolean {
    val cached = LocalAppContainer.current.cachedTracks
    val flow = remember(cached, videoId) { cached.tracks.map { videoId in it }.distinctUntilChanged() }
    return flow.collectAsState(initial = videoId in cached.tracks.value).value
}

/**
 * The mark of a track row (REWRITE §3.11.11): downloaded, downloading (a ring with the progress),
 * waiting, paused or failed; without a download, "available offline" when the cache holds it whole
 * (the same pin, outlined: it may give way to newer tracks); nothing otherwise.
 */
@Composable
fun DownloadBadge(videoId: String, modifier: Modifier = Modifier) {
    val iconModifier = modifier.size(16.dp)
    val download = rememberDownload(videoId)
    if (download == null) {
        if (rememberCached(videoId)) Icon(
            painter = painterResource(R.drawable.ms_offline_pin),
            contentDescription = stringResource(R.string.download_cached),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = iconModifier
        )
        return
    }

    when (download.state) {
        DownloadState.Completed -> Icon(
            painter = painterResource(R.drawable.ms_offline_pin_fill),
            contentDescription = stringResource(R.string.download_completed),
            tint = MaterialTheme.colorScheme.primary,
            modifier = iconModifier
        )

        DownloadState.Downloading -> {
            val label = stringResource(R.string.download_state_starting)
            val progressModifier = modifier
                .size(14.dp)
                .semantics { contentDescription = label }
            download.progress?.let { progress ->
                CircularProgressIndicator(progress = { progress }, strokeWidth = 2.dp, modifier = progressModifier)
            } ?: CircularProgressIndicator(strokeWidth = 2.dp, modifier = progressModifier)
        }

        DownloadState.Queued, DownloadState.Waiting -> Icon(
            painter = painterResource(R.drawable.ms_download),
            contentDescription = stringResource(R.string.download_state_queued),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = iconModifier
        )

        DownloadState.Paused -> Icon(
            painter = painterResource(R.drawable.ms_pause),
            contentDescription = stringResource(R.string.download_state_paused),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = iconModifier
        )

        DownloadState.Failed -> Icon(
            painter = painterResource(R.drawable.ms_error),
            contentDescription = stringResource(R.string.download_failure_unknown),
            tint = MaterialTheme.colorScheme.error,
            modifier = iconModifier
        )
    }
}
