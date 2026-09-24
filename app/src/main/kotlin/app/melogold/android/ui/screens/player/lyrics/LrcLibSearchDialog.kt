@file:OptIn(ExperimentalMaterial3Api::class)

package app.melogold.android.ui.screens.player.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.providers.lrclib.LrcLib
import app.melogold.providers.lrclib.models.Track
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Searches LRCLIB for the lyrics of [query], synced or plain, and lets the user pick one of the
 * tracks found: the search field on top, the tracks under it as a list, each saying whether its
 * lyrics are synced.
 */
@Composable
fun LrcLibSearchDialog(
    query: String,
    setQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier
) = BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
    val tracks = remember { mutableStateListOf<Track>() }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        loading = true
        // Wait for the typing to stop
        delay(1000.milliseconds)

        val result = LrcLib.search(query = query)
        tracks.clear()
        result?.getOrNull()?.let { found -> tracks.addAll(found) }
        result?.exceptionOrNull()?.printStackTrace()
        loading = false
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("lrclib_search")
    ) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
            Text(
                text = stringResource(R.string.choose_lyric_track),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = setQuery,
                singleLine = true,
                leadingIcon = { Icon(painter = painterResource(R.drawable.ms_search), contentDescription = null) },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 360.dp)
            ) {
                when {
                    loading -> DelayedLoadingIndicator()

                    tracks.isEmpty() -> Text(
                        text = stringResource(R.string.no_lyrics_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )

                    else -> LazyColumn {
                        items(items = tracks, key = { it.id }) { track ->
                            ListItem(
                                onClick = {
                                    onPick(track)
                                    onDismiss()
                                },
                                supportingContent = {
                                    val kind = stringResource(
                                        if (track.syncedLyrics.isNullOrBlank()) R.string.lyrics_result_plain
                                        else R.string.lyrics_result_synced
                                    )
                                    Text(text = "${track.duration.seconds.format()} · $kind")
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "${track.artistName} — ${track.trackName}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 16.dp)
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    }
}

private fun kotlin.time.Duration.format() = toComponents { minutes, seconds, _ ->
    "$minutes:${seconds.toString().padStart(2, '0')}"
}
