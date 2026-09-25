package app.melogold.android.ui.screens.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.ui.screens.library.ImportDialogHost
import app.melogold.android.ui.screens.library.rememberImportAction
import app.melogold.android.internal
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.preferences.TOP_LIST_LENGTH
import app.melogold.android.query
import app.melogold.android.transaction
import app.melogold.android.ui.screens.Route
import app.melogold.android.utils.toast
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("RestrictedApi")
@Route
@Composable
fun DatabaseSettings() = with(DataPreferences) {
    val context = LocalContext.current

    val blacklistLength by remember { Database.blacklistLength().distinctUntilChanged() }
        .collectAsState(initial = 0)

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType = "application/vnd.sqlite3")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        query {
            Database.checkpoint()

            context.applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(Database.internal.path).use { input -> input.copyTo(output) }
            }
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
        SettingsGroup(
            title = stringResource(R.string.backup),
            description = stringResource(R.string.backup_description)
        ) {
            val errorMsg = stringResource(R.string.no_file_chooser_installed)

            SettingsEntry(
                title = stringResource(R.string.backup),
                text = stringResource(R.string.backup_action_description),
                onClick = {
                    val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())

                    try {
                        backupLauncher.launch("Melogold_backup_${dateFormat.format(Date())}.db")
                    } catch (_: ActivityNotFoundException) {
                        context.toast(errorMsg)
                    }
                }
            )
        }
        SettingsGroup(
            title = stringResource(R.string.library_import),
            description = stringResource(R.string.import_settings_description)
        ) {
            SettingsEntry(
                title = stringResource(R.string.library_import),
                text = stringResource(R.string.library_import_description),
                onClick = import
            )
        }
    }
}
