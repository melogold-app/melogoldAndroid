package app.melogold.android.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.BuildConfig
import app.melogold.android.R
import app.melogold.android.ui.screens.Route
import app.melogold.core.data.utils.Version
import app.melogold.core.data.utils.version
import app.melogold.providers.github.GitHub
import app.melogold.providers.github.models.Release
import app.melogold.providers.github.requests.releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VERSION_NAME = BuildConfig.VERSION_NAME.substringBeforeLast("-")
private const val REPO_OWNER = "melogold-app"
private const val REPO_NAME = "melogoldAndroid"

private suspend fun Version.getNewerVersion(
    repoOwner: String = REPO_OWNER,
    repoName: String = REPO_NAME,
    contentType: String = "application/vnd.android.package-archive"
) = GitHub.releases(
    owner = repoOwner,
    repo = repoName
)?.mapCatching { releases ->
    releases
        .sortedByDescending { it.publishedAt }
        .firstOrNull { release ->
            !release.draft &&
                !release.preRelease &&
                release.tag.version > this &&
                release.assets.any {
                    it.contentType == contentType && it.state == Release.Asset.State.Uploaded
                }
        }
}

@Route
@Composable
fun About() = SettingsCategoryScreen(
    title = stringResource(R.string.about),
    description = stringResource(
        R.string.format_version_credits,
        VERSION_NAME
    )
) {
    val uriHandler = LocalUriHandler.current

    SettingsGroup(title = stringResource(R.string.social)) {
        SettingsEntry(
            title = stringResource(R.string.github),
            text = stringResource(R.string.view_source),
            onClick = {
                uriHandler.openUri("https://github.com/$REPO_OWNER/$REPO_NAME")
            }
        )
    }

    SettingsGroup(title = stringResource(R.string.contact)) {
        SettingsEntry(
            title = stringResource(R.string.report_bug),
            text = stringResource(R.string.report_bug_description),
            onClick = {
                uriHandler.openUri(
                    @Suppress("MaximumLineLength")
                    "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=bug&template=bug_report.yaml"
                )
            }
        )

        SettingsEntry(
            title = stringResource(R.string.request_feature),
            text = stringResource(R.string.redirect_github),
            onClick = {
                uriHandler.openUri(
                    @Suppress("MaximumLineLength")
                    "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=enhancement&template=feature_request.md"
                )
            }
        )
    }

    var newVersionDialogOpened by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(title = stringResource(R.string.version)) {
        SettingsEntry(
            title = stringResource(R.string.check_new_version),
            text = stringResource(R.string.current_version, VERSION_NAME),
            onClick = { newVersionDialogOpened = true }
        )
    }

    if (newVersionDialogOpened) NewVersionDialog(onDismiss = { newVersionDialogOpened = false })
}

/** "Check for updates": looking, then the newer release with "More information", or up to date. */
@Composable
private fun NewVersionDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var newerVersion: Result<Release?>? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        newerVersion = withContext(Dispatchers.IO) {
            VERSION_NAME.version
                .getNewerVersion()
                ?.onFailure(Throwable::printStackTrace)
        } ?: Result.failure(IllegalStateException("No answer from GitHub"))
    }

    val release = newerVersion?.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(if (release != null) R.string.new_version_available else R.string.check_new_version))
        },
        text = {
            when {
                newerVersion == null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator()
                }

                release != null -> Text(text = release.name ?: release.tag)
                newerVersion?.isFailure == true -> Text(text = stringResource(R.string.error_github))
                else -> Text(text = stringResource(R.string.up_to_date))
            }
        },
        confirmButton = {
            if (release != null) TextButton(onClick = { uriHandler.openUri(release.frontendUrl.toString()) }) {
                Text(text = stringResource(R.string.more_information))
            } else TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.done)) }
        },
        dismissButton = if (release != null) {
            { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) } }
        } else null
    )
}
