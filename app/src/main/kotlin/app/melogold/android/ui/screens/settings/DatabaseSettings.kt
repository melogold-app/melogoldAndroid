package app.melogold.android.ui.screens.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.data.importer.LibraryBackup
import app.melogold.android.ui.screens.library.ImportDialogHost
import app.melogold.android.ui.screens.library.rememberImportAction
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.preferences.TOP_LIST_LENGTH
import app.melogold.android.transaction
import app.melogold.android.ui.screens.Route
import app.melogold.android.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("RestrictedApi")
@Route
@Composable
fun DatabaseSettings() = with(DataPreferences) {
    val context = LocalContext.current

    val blacklistLength by remember { Database.blacklistLength().distinctUntilChanged() }
        .collectAsState(initial = 0)

    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.backup_saved)
    val notSaved = stringResource(R.string.backup_failed)
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType = "application/vnd.sqlite3")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "No output for $uri" }
                    output.use { LibraryBackup.export(context, it) }
                }.onFailure { Log.w("DatabaseSettings", "Could not save a copy", it) }.isSuccess
            }
            context.toast(if (done) saved else notSaved)
        }
    }

    val import = rememberImportAction()
    ImportDialogHost()

    SettingsCategoryScreen(title = stringResource(R.string.database)) {
        SettingsGroup(title = stringResource(R.string.cleanup)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.pause_playback_history),
                text = stringResource(R.string.pause_playback_history_description),
                isChecked = pauseHistory,
                onCheckedChange = { pauseHistory = !pauseHistory }
            )

            AnimatedVisibility(visible = pauseHistory) {
                SettingsDescription(
                    text = stringResource(R.string.pause_playback_history_warning),
                    important = true
                )
            }

            SwitchSettingsEntry(
                title = stringResource(R.string.pause_playback_time),
                text = stringResource(
                    R.string.format_pause_playback_time_description,
                    TOP_LIST_LENGTH
                ),
                isChecked = pausePlaytime,
                onCheckedChange = { pausePlaytime = !pausePlaytime }
            )

            SettingsEntry(
                title = stringResource(R.string.reset_blacklist),
                text = if (blacklistLength > 0) pluralStringResource(
                    R.plurals.format_reset_blacklist_description,
                    blacklistLength,
                    blacklistLength
                ) else stringResource(R.string.blacklist_empty),
                isEnabled = blacklistLength > 0,
                onClick = {
                    transaction {
                        Database.resetBlacklist()
                    }
                }
            )
        }
        // A copy every Melogold client imports (docs/spec/backup-format.md)
        SettingsGroup(
            title = stringResource(R.string.library_copy),
            description = stringResource(R.string.library_copy_description)
        ) {
            val noPicker = stringResource(R.string.no_file_chooser_installed)

            SettingsEntry(
                title = stringResource(R.string.backup_save),
                text = stringResource(R.string.backup_save_description),
                onClick = {
                    try {
                        backupLauncher.launch(LibraryBackup.suggestedName())
                    } catch (_: ActivityNotFoundException) {
                        context.toast(noPicker)
                    }
                }
            )
            SettingsEntry(
                title = stringResource(R.string.backup_import),
                text = stringResource(R.string.import_settings_description),
                onClick = import
            )
        }
    }
}
