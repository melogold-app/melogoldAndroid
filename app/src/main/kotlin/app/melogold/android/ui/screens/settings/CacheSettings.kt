@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.settings

import android.content.Context
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
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.ui.components.m3e.SegmentedGroupDefaults
import app.melogold.android.ui.screens.Route
import app.melogold.core.data.enums.ExoPlayerDiskCacheSize
import coil3.imageLoader

@androidx.annotation.OptIn(UnstableApi::class)
@Route
@Composable
fun CacheSettings() = with(DataPreferences) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val imageCache = remember(context) { context.imageLoader.diskCache }
    val unlimited = stringResource(R.string.settings_cache_unlimited)

    SettingsCategoryScreen(
        title = stringResource(R.string.cache),
        description = stringResource(R.string.cache_description)
    ) {
        var imageCacheSize by remember(imageCache) { mutableLongStateOf(imageCache?.size ?: 0L) }
        imageCache?.let { diskCache ->
            val fraction = imageCacheSize.toFloat() / coilDiskCacheMaxSize.bytes.coerceAtLeast(1)

            SettingsGroup(title = stringResource(R.string.image_cache)) {
                CacheUsageEntry(
                    used = stringResource(
                        R.string.format_cache_space_used_percentage,
                        context.size(imageCacheSize),
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
                    valueText = { context.size(it.bytes) }
                )
            }
        }

        binder?.cache?.let { cache ->
            val diskCacheSize by remember { derivedStateOf { cache.cacheSpace } }
            val limited = exoPlayerDiskCacheMaxSize != ExoPlayerDiskCacheSize.Unlimited
            val fraction = diskCacheSize.toFloat() / exoPlayerDiskCacheMaxSize.bytes.coerceAtLeast(1)

            SettingsGroup(title = stringResource(R.string.song_cache)) {
                CacheUsageEntry(
                    used = if (limited) stringResource(
                        R.string.format_cache_space_used_percentage,
                        context.size(diskCacheSize),
                        (fraction * 100).toInt()
                    ) else stringResource(R.string.format_cache_space_used, context.size(diskCacheSize)),
                    fraction = fraction.takeIf { limited }
                )
                EnumValueSelectorSettingsEntry(
                    title = stringResource(R.string.max_size),
                    selectedValue = exoPlayerDiskCacheMaxSize,
                    onValueSelect = { exoPlayerDiskCacheMaxSize = it },
                    valueText = { if (it == ExoPlayerDiskCacheSize.Unlimited) unlimited else context.size(it.bytes) }
                )
            }
        }
    }
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

private const val MIB = 1024L * 1024
private const val GIB = 1024L * MIB

/**
 * A size in the binary units the limits are set in: "128 MB", "2 GB", "1.5 GB" (Android's own
 * formatter counts in thousands and would show the 128 MB limit as "134 MB").
 */
private fun Context.size(bytes: Long) = when {
    bytes >= GIB && bytes % GIB == 0L -> getString(R.string.size_gb, (bytes / GIB).toString())
    bytes >= GIB -> getString(R.string.size_gb, "%.1f".format(bytes.toDouble() / GIB))
    else -> getString(R.string.size_mb, (bytes / MIB).toString())
}
