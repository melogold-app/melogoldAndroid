@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import android.content.ClipData
import android.content.ClipDescription
import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Format
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.utils.toast
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Stream info" (REWRITE §3.10.10), opened from the player menu: the video id, the format, the
 * bitrate and size, how much is cached and the loudness. "Copy" puts it all on the clipboard for a
 * bug report.
 */
@Composable
fun StreamInfoSheet(
    mediaId: String,
    binder: PlayerService.Binder,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val unknown = stringResource(R.string.unknown)
    val copied = stringResource(R.string.copied)

    var format by remember(mediaId) { mutableStateOf<Format?>(null) }
    LaunchedEffect(mediaId) {
        Database.format(mediaId).distinctUntilChanged().collect { format = it }
    }

    var cachedBytes by remember(mediaId) {
        mutableLongStateOf(runCatching { binder.cache.getCachedBytes(mediaId, 0, -1) }.getOrDefault(0L))
    }
    DisposableEffect(binder, mediaId) {
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                cachedBytes += span.length
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                cachedBytes -= span.length
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        binder.cache.addListener(mediaId, listener)
        onDispose { binder.cache.removeListener(mediaId, listener) }
    }

    val length = format?.contentLength?.takeIf { it > 0 }
    val rows = listOf(
        stringResource(R.string.id) to mediaId,
        stringResource(R.string.itag) to (format?.itag?.toString() ?: unknown),
        stringResource(R.string.stream_info_format) to (format?.mimeType ?: unknown),
        stringResource(R.string.bitrate) to (
            format?.bitrate?.takeIf { it > 0 }?.let { stringResource(R.string.format_kbps, it / 1000) } ?: unknown
            ),
        stringResource(R.string.size) to (length?.let { Formatter.formatShortFileSize(context, it) } ?: unknown),
        stringResource(R.string.cached) to buildString {
            append(Formatter.formatShortFileSize(context, cachedBytes))
            length?.let { append(" (${(cachedBytes.toFloat() / it * 100).roundToInt().coerceIn(0, 100)} %)") }
        },
        stringResource(R.string.loudness) to (
            format?.loudnessDb?.let { stringResource(R.string.format_db, "%.2f".format(it)) } ?: unknown
            )
    )

    Menu(modifier = modifier.testTag("stream_info")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.menu_stream_info),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val text = rows.joinToString("\n") { (label, value) -> "$label: $value" }
                    scope.launch {
                        clipboard.setText(text)
                        context.toast(copied)
                    }
                }
            ) { Text(text = stringResource(R.string.stream_info_copy)) }
        }

        rows.forEach { (label, value) ->
            ListItem(
                overlineContent = { Text(text = label) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ) {
                Text(text = value)
            }
        }
    }
}

suspend fun Clipboard.setText(
    text: String,
    description: String? = null,
    mimeTypes: List<String> = listOf("text/plain")
) = setClipEntry(
    ClipEntry(
        ClipData(
            /* description = */ ClipDescription(
                /* label = */ description ?: text,
                /* mimeTypes = */ mimeTypes.toTypedArray()
            ),
            /* item = */ ClipData.Item(text)
        )
    )
)
