package app.melogold.android.ui.components.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.melogold.android.R
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.data.overrides.originalAlbum
import app.melogold.android.data.overrides.originalArtist
import app.melogold.android.data.overrides.originalTitle

/**
 * "Track details" (tasks/0012): the user's own title, artist and album of a track, on every device of the account.
 * Under each field is what YouTube calls the track; an empty field keeps it. "As on YouTube" removes the override.
 */
@Composable
fun TrackDetailsMenu(mediaItem: MediaItem, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val videoId = mediaItem.mediaId
    val override = remember(videoId) { TrackOverrides[videoId] }
    var title by rememberSaveable { mutableStateOf(override?.title.orEmpty()) }
    var artist by rememberSaveable { mutableStateOf(override?.artistsText.orEmpty()) }
    var album by rememberSaveable { mutableStateOf(override?.albumTitle.orEmpty()) }

    Menu(modifier = modifier.imePadding().testTag("track_details")) {
        Text(
            text = stringResource(R.string.track_details_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )

        DetailsField(value = title, onValueChange = { title = it }, label = R.string.track_details_name, youTube = mediaItem.originalTitle)
        DetailsField(value = artist, onValueChange = { artist = it }, label = R.string.track_details_artist, youTube = mediaItem.originalArtist)
        DetailsField(value = album, onValueChange = { album = it }, label = R.string.track_details_album, youTube = mediaItem.originalAlbum)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            TextButton(
                onClick = {
                    TrackOverrides.set(videoId, null, null, null)
                    onDone()
                },
                enabled = override != null
            ) { Text(text = stringResource(R.string.track_details_reset)) }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text(text = stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    TrackOverrides.set(videoId, title, artist, album)
                    onDone()
                }
            ) { Text(text = stringResource(R.string.track_details_save)) }
        }
    }
}

@Composable
private fun DetailsField(value: String, onValueChange: (String) -> Unit, label: Int, youTube: String?) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(text = stringResource(label)) },
    placeholder = youTube?.takeIf { it.isNotBlank() }?.let { { Text(text = it, maxLines = 1) } },
    // What stays when the field is empty, also while it is not focused
    supportingText = youTube?.takeIf { it.isNotBlank() }?.let {
        { Text(text = stringResource(R.string.track_details_youtube, it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    },
    singleLine = true,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 4.dp)
)
