@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.melogold.android.LocalAppContainer
import app.melogold.android.R
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.update.UpdateState
import app.melogold.android.utils.formatSize
import java.util.Locale

/**
 * "Version X is out" at the top of Settings while an update waits (REWRITE §4.14), like
 * Clementine's: "Update" downloads it with its progress and hands it to the system installer;
 * "What's new" shows the notes of the release. The Settings tab shows "1" meanwhile.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val updater = LocalAppContainer.current.updates
    val state by updater.state.collectAsState()
    val release = state.pending ?: return
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val notes = remember(release, locale) { release.notesFor(locale) }
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    var askPermission by rememberSaveable { mutableStateOf(false) }
    var waitingForPermission by rememberSaveable { mutableStateOf(false) }

    // Download and install need "Install unknown apps" first: asked once, before the download
    fun update() = when {
        !updater.canInstall() -> askPermission = true
        state is UpdateState.Ready -> updater.install()
        else -> updater.download()
    }

    // Back from the system settings with the permission: go on by itself
    LifecycleResumeEffect(waitingForPermission) {
        if (waitingForPermission && updater.canInstall()) {
            waitingForPermission = false
            update()
        }
        onPauseOrDispose { }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp)
            .testTag("update_card")
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // The filled primary shape stands out on the card's primary container
            ShapeIcon(
                icon = R.drawable.ms_system_update,
                shape = IconShape.Cookie9Sided,
                contentDescription = null,
                size = 48.dp,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title, release.version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = when (val current = state) {
                        is UpdateState.Downloading -> stringResource(R.string.update_downloading, (current.progress * 100).toInt())
                        is UpdateState.Ready -> stringResource(R.string.update_ready)
                        is UpdateState.Installing -> stringResource(R.string.update_installing)
                        is UpdateState.Failed -> stringResource(
                            if (current.reason == UpdateState.Reason.Install) R.string.update_failed_install else R.string.update_failed_download
                        )

                        else -> stringResource(R.string.update_available_text, context.formatSize(release.sizeBytes))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        val current = state
        if (current is UpdateState.Downloading) LinearWavyProgressIndicator(
            progress = { current.progress },
            modifier = Modifier.fillMaxWidth()
        ) else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = ::update,
                enabled = current !is UpdateState.Installing,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.testTag("update_action")
            ) {
                Text(
                    text = stringResource(
                        when (current) {
                            is UpdateState.Ready, is UpdateState.Installing -> R.string.update_install
                            is UpdateState.Failed -> R.string.update_retry
                            else -> R.string.update_action
                        }
                    )
                )
            }
            if (notes != null) TextButton(onClick = { notesOpen = true }) { Text(text = stringResource(R.string.update_whats_new)) }
        }
    }

    if (notesOpen && notes != null) AlertDialog(
        onDismissRequest = { notesOpen = false },
        title = { Text(text = stringResource(R.string.update_whats_new_title, release.version)) },
        text = {
            Text(
                text = notes,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    notesOpen = false
                    update()
                }
            ) { Text(text = stringResource(R.string.update_action)) }
        },
        dismissButton = { TextButton(onClick = { notesOpen = false }) { Text(text = stringResource(R.string.update_close)) } }
    )

    if (askPermission) AlertDialog(
        onDismissRequest = { askPermission = false },
        title = { Text(text = stringResource(R.string.update_permission_title)) },
        text = { Text(text = stringResource(R.string.update_permission_text)) },
        confirmButton = {
            TextButton(
                onClick = {
                    askPermission = false
                    waitingForPermission = true
                    context.startActivity(updater.installPermissionIntent())
                }
            ) { Text(text = stringResource(R.string.update_permission_action)) }
        },
        dismissButton = { TextButton(onClick = { askPermission = false }) { Text(text = stringResource(R.string.cancel)) } }
    )
}

/** "Check for updates" of About: the version and what the last check found; a tap checks now. */
@Composable
fun UpdateCheckEntry(version: String) {
    val updater = LocalAppContainer.current.updates
    val state by updater.state.collectAsState()

    SettingsEntry(
        title = stringResource(R.string.update_check),
        text = when (val current = state) {
            UpdateState.Disabled -> stringResource(R.string.update_disabled, version)
            UpdateState.Checking -> stringResource(R.string.update_checking)
            UpdateState.Idle -> stringResource(R.string.update_version, version)
            is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, version)
            is UpdateState.Failed -> current.release?.let { stringResource(R.string.update_version_new, version, it.version) }
                ?: stringResource(R.string.update_offline, version)

            else -> stringResource(R.string.update_version_new, version, current.pending?.version.orEmpty())
        },
        isEnabled = updater.enabled,
        onClick = { updater.check(force = true) }
    )
}
