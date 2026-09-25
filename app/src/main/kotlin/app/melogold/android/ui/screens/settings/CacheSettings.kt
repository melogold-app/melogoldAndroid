@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import app.melogold.android.LocalAppContainer
import app.melogold.android.models.DownloadState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.melogold.android.R
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.utils.formatSize
import app.melogold.android.ui.components.m3e.SegmentedGroupDefaults
import app.melogold.android.ui.screens.Route
import app.melogold.core.data.enums.ExoPlayerDiskCacheSize
import coil3.imageLoader

@androidx.annotation.OptIn(UnstableApi::class)
@Route
@Composable
fun CacheSettings() = with(DataPreferences) {
    val context = LocalContext.current
    val imageCache = remember(context) { context.imageLoader.diskCache }
    val unlimited = stringResource(R.string.settings_cache_unlimited)

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_storage),
        description = stringResource(R.string.settings_storage_description)
    ) {
        DownloadsGroup()

        var imageCacheSize by remember(imageCache) { mutableLongStateOf(imageCache?.size ?: 0L) }
        imageCache?.let { diskCache ->
            val fraction = imageCacheSize.toFloat() / coilDiskCacheMaxSize.bytes.coerceAtLeast(1)

            SettingsGroup(title = stringResource(R.string.image_cache)) {
                CacheUsageEntry(
                    used = stringResource(
                        R.string.format_cache_space_used_percentage,
                        context.formatSize(imageCacheSize),
                        (fraction * 100).toInt()
                    ),
                    fraction = fraction,
                    onClear = {
                        diskCache.clear()
                        imageCacheSize = 0L
                    }
                )
                EnumValueSelectorSettingsEntry(
                    title = stringResource(R.string.max_size),
                    selectedValue = coilDiskCacheMaxSize,
                    onValueSelect = { coilDiskCacheMaxSize = it },
                    valueText = { context.formatSize(it.bytes) }
                )
            }
        }

        LocalAppContainer.current.playerCache.let { cache ->
            val diskCacheSize by remember { derivedStateOf { cache.cacheSpace } }
            val limited = exoPlayerDiskCacheMaxSize != ExoPlayerDiskCacheSize.Unlimited
            val fraction = diskCacheSize.toFloat() / exoPlayerDiskCacheMaxSize.bytes.coerceAtLeast(1)

            SettingsGroup(title = stringResource(R.string.song_cache)) {
                CacheUsageEntry(
                    used = if (limited) stringResource(
                        R.string.format_cache_space_used_percentage,
                        context.formatSize(diskCacheSize),
                        (fraction * 100).toInt()
                    ) else stringResource(R.string.format_cache_space_used, context.formatSize(diskCacheSize)),
                    fraction = fraction.takeIf { limited }
                )
                EnumValueSelectorSettingsEntry(
                    title = stringResource(R.string.max_size),
                    selectedValue = exoPlayerDiskCacheMaxSize,
                    onValueSelect = { exoPlayerDiskCacheMaxSize = it },
                    valueText = { if (it == ExoPlayerDiskCacheSize.Unlimited) unlimited else context.formatSize(it.bytes) }
                )
            }
        }
    }
}

/**
 * Downloads (REWRITE §3.5.6): the space they take with "Clear" (after a dialog: they are the only
 * copy on the device), and "Only over Wi‑Fi".
 */
@Composable
private fun DownloadsGroup() {
    val context = LocalContext.current
    val downloads = LocalAppContainer.current.downloads
    val states by downloads.visible.collectAsState()
    val completed = states.values.count { it.state == DownloadState.Completed }
    val bytes = remember(states) { downloads.size }
    val tracks = pluralStringResource(R.plurals.library_tracks_count, completed, completed)
    var confirming by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.settings_downloads)) {
        CacheUsageEntry(
            used = "${context.formatSize(bytes)} · $tracks",
            fraction = null,
            onClear = if (states.isEmpty()) null else ({ confirming = true })
        )
        SwitchSettingsEntry(
            title = stringResource(R.string.settings_downloads_wifi_only),
            text = stringResource(R.string.settings_downloads_wifi_only_description),
            isChecked = DataPreferences.downloadsWifiOnly,
            onCheckedChange = downloads::setWifiOnly
        )
    }

    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(text = stringResource(R.string.settings_downloads_delete_all_title)) },
        text = {
            Text(text = stringResource(R.string.settings_downloads_delete_all_text, tracks, context.formatSize(bytes)))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    confirming = false
                    downloads.removeAll()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(text = stringResource(R.string.settings_downloads_delete)) }
        },
        dismissButton = {
            TextButton(onClick = { confirming = false }) { Text(text = stringResource(R.string.cancel)) }
        }
    )
}

/** How full a cache is: "128 MB used (25%)", the bar, and "Clear" where it can be cleared. */
@Composable
private fun CacheUsageEntry(
    used: String,
    fraction: Float?,
    onClear: (() -> Unit)? = null
) = SegmentedListItem(
    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
    supportingContent = fraction?.let {
        {
            LinearProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    },
    trailingContent = onClear?.let {
        { TextButton(onClick = it) { Text(text = stringResource(R.string.clear)) } }
    },
    colors = SegmentedGroupDefaults.colors()
) {
    Column { Text(text = used) }
}

