package app.melogold.android.ui.screens.searchresult

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.artistPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The best result of a search, large and first (tasks/0021, like the top result in Apple Music): the artist whose name is
 * what was typed — a round photo, the name, "Artist · subscribers" and Play (the artist's top songs in order, else
 * their radio). A tap on the card opens the artist. The user: «пишешь в поиск автора — он сразу сам подсвечивается, не
 * нужно лишний клик делать».
 */
@Composable
fun TopArtistCard(
    artist: Innertube.ArtistItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val binder = LocalPlayerServiceBinder.current
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Artwork(url = artist.thumbnail?.url, size = 96.dp, shape = CircleShape)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = artist.info?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = listOfNotNull(stringResource(R.string.results_artist), artist.subscribersCountText)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = {
                    val browseId = artist.key
                    scope.launch {
                        val page = withContext(Dispatchers.IO) { Innertube.artistPage(BrowseBody(browseId = browseId)) }
                            ?.getOrNull()
                        val songs = page?.songs.orEmpty().map { it.asMediaItem }
                        binder?.stopRadio()
                        if (songs.isNotEmpty()) binder?.player?.forcePlayAtIndex(songs, 0)
                        else page?.radioEndpoint?.let { binder?.playRadio(it) }
                    }
                },
                contentPadding = ButtonDefaults.SmallContentPadding,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_play_arrow_fill),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.collection_play))
            }
        }
    }
}

/** Whether a name is the query: case, ё/е, punctuation and extra spaces do not matter («кино!» is «Кино»). */
fun namesMatch(name: String?, query: String): Boolean {
    val wanted = normalizedName(query)
    return wanted.isNotEmpty() && normalizedName(name.orEmpty()) == wanted
}

private fun normalizedName(text: String): String = text.lowercase()
    .replace('ё', 'е')
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ")
