package app.melogold.android.ui.screens.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.sync.epochMs
import app.melogold.android.ui.components.m3e.SegmentedGroup
import app.melogold.android.ui.components.m3e.SegmentedRow
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.share.copyLink
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.compose.routing.RouteHandler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The links of the account as the screen has them (tasks/0017). */
sealed interface MySharesState {
    data object Loading : MySharesState

    data class Loaded(val shares: List<ShareDto>) : MySharesState

    data object Failed : MySharesState
}

/**
 * Account › "My links" (tasks/0017, API §4.11): the playlists shared with a link, the newest first, each with how many
 * tracks and when; a link can be copied, or deleted, and then it stops opening.
 */
@Route
@Composable
fun MySharesScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val shares = LocalAppContainer.current.shares
        val snackbar = LocalAppSnackbar.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf<MySharesState>(MySharesState.Loading) }
        var reload by remember { mutableIntStateOf(0) }
        val copied = stringResource(R.string.share_link_copied)
        val deleted = stringResource(R.string.share_link_deleted)
        val deleteFailed = stringResource(R.string.share_delete_failed)

        LaunchedEffect(reload) {
            state = runCatching { MySharesState.Loaded(shares.mine()) }.getOrElse { MySharesState.Failed }
        }

        MySharesContent(
            state = state,
            onBack = pop,
            onCopy = { share ->
                context.copyLink("Melogold", share.url)
                snackbar.show(copied)
            },
            onDelete = { share ->
                scope.launch {
                    runCatching { shares.delete(share.shareId) }
                        .onSuccess {
                            snackbar.show(deleted)
                            reload++
                        }
                        .onFailure { snackbar.show(deleteFailed) }
                }
            }
        )
    }
}

/** The page without its sources; deleting asks first. */
@Composable
fun MySharesContent(
    state: MySharesState,
    onBack: () -> Unit,
    onCopy: (ShareDto) -> Unit,
    onDelete: (ShareDto) -> Unit
) {
    var deleting by remember { mutableStateOf<ShareDto?>(null) }

    AccountPage(title = stringResource(R.string.my_links), onBack = onBack, description = stringResource(R.string.my_links_text)) {
        when (state) {
            MySharesState.Loading -> Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) { DelayedLoadingIndicator() }

            MySharesState.Failed -> Text(
                text = stringResource(R.string.my_links_error),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .testTag("my_links_error")
            )

            is MySharesState.Loaded -> if (state.shares.isEmpty()) Text(
                text = stringResource(R.string.my_links_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .testTag("my_links_empty")
            ) else SegmentedGroup(modifier = Modifier.testTag("my_links_list")) {
                state.shares.forEach { share ->
                    row { shapes ->
                        SegmentedRow(
                            headline = share.name,
                            supporting = stringResource(
                                R.string.my_links_row,
                                pluralStringResource(R.plurals.library_tracks_count, share.tracks.size, share.tracks.size),
                                remember(share.createdAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(share.createdAt.epochMs())) }
                            ),
                            icon = R.drawable.ms_link,
                            shapes = shapes,
                            onClick = { onCopy(share) },
                            modifier = Modifier.testTag("my_link_${share.shareId}"),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onCopy(share) }, modifier = Modifier.testTag("my_link_copy_${share.shareId}")) {
                                        Icon(
                                            painter = painterResource(R.drawable.ms_content_copy),
                                            contentDescription = stringResource(R.string.share_copy_link)
                                        )
                                    }
                                    IconButton(onClick = { deleting = share }, modifier = Modifier.testTag("my_link_delete_${share.shareId}")) {
                                        Icon(
                                            painter = painterResource(R.drawable.ms_delete),
                                            contentDescription = stringResource(R.string.share_delete_link)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    deleting?.let { share ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(text = stringResource(R.string.share_delete_title)) },
            text = { Text(text = stringResource(R.string.share_delete_text, share.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(share)
                    },
                    modifier = Modifier.testTag("my_link_delete_confirm")
                ) { Text(text = stringResource(R.string.share_delete_link)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(text = stringResource(R.string.cancel)) } }
        )
    }
}
