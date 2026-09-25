package app.melogold.android.ui.screens.library

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.data.importer.ImportFailure
import app.melogold.android.data.importer.ImportState
import app.melogold.android.data.importer.ImportSummary
import app.melogold.android.ui.screens.builtInPlaylistRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.android.utils.toast
import app.melogold.core.data.enums.BuiltInPlaylist

/**
 * "Import from ViTune or ViMusic" (REWRITE §3.2.8, §4.5): picks the backup file; the import itself runs in the app
 * and [ImportDialogHost] shows how it goes.
 */
@Composable
fun rememberImportAction(): () -> Unit {
    val context = LocalContext.current
    val importer = LocalAppContainer.current.importer
    val noPicker = stringResource(R.string.no_file_chooser_installed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importer::start)
    }
    return {
        try {
            // Backups come as .db without a sure MIME type: any file, the importer checks it
            launcher.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            context.toast(noPicker)
        }
    }
}

/** The dialog of a running or finished import: "Importing…", then what came, or why it could not. */
@Composable
fun ImportDialogHost() {
    val importer = LocalAppContainer.current.importer
    val nav = LocalMainNav.current
    val state by importer.state.collectAsState()

    when (val current = state) {
        null -> Unit

        ImportState.Running -> AlertDialog(
            onDismissRequest = { },
            title = { Text(text = stringResource(R.string.import_running)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = { }
        )

        is ImportState.Done -> {
            // ViTune's "Songs" is History › "Most played" here: people coming from it look for their tracks
            val hasHistory = current.summary.plays + current.summary.playsKnown > 0
            val done = @Composable {
                TextButton(onClick = importer::dismiss) { Text(text = stringResource(R.string.import_done_ok)) }
            }
            val openHistory = @Composable {
                TextButton(
                    onClick = {
                        importer.dismiss()
                        nav.navigate(TopLevelDestination.Library) { builtInPlaylistRoute.ensureGlobal(BuiltInPlaylist.History) }
                    }
                ) { Text(text = stringResource(R.string.import_open_history)) }
            }
            AlertDialog(
                onDismissRequest = importer::dismiss,
                title = { Text(text = stringResource(R.string.import_done_title)) },
                text = { Summary(current.summary) },
                confirmButton = if (hasHistory) openHistory else done,
                dismissButton = done.takeIf { hasHistory }
            )
        }

        is ImportState.Failed -> AlertDialog(
            onDismissRequest = importer::dismiss,
            title = { Text(text = stringResource(R.string.import_failed_title)) },
            text = {
                Text(
                    text = stringResource(
                        when (current.reason) {
                            ImportFailure.NotABackup -> R.string.import_not_backup
                            ImportFailure.TooOld -> R.string.import_too_old
                            ImportFailure.Unsupported -> R.string.import_unsupported
                            ImportFailure.Unreadable -> R.string.import_unreadable
                        }
                    )
                )
            },
            confirmButton = { TextButton(onClick = importer::dismiss) { Text(text = stringResource(R.string.import_done_ok)) } }
        )
    }
}

@Composable
private fun Summary(summary: ImportSummary) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(
            R.string.import_summary,
            summary.tracks,
            summary.plays,
            summary.favorites,
            summary.lyrics,
            summary.playlists,
            summary.saved
        )
    )
    val notes = listOfNotNull(
        summary.playsKnown.takeIf { it > 0 }?.let { stringResource(R.string.import_plays_known, it) },
        summary.localSkipped.takeIf { it > 0 }?.let { stringResource(R.string.import_local_skipped, it) },
        summary.datesSkipped.takeIf { it > 0 }?.let { stringResource(R.string.import_dates_skipped, it) },
        stringResource(R.string.import_sync_note).takeIf { LocalAppContainer.current.account.session != null && summary.plays > 0 }
    )
    notes.forEach { note ->
        Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
