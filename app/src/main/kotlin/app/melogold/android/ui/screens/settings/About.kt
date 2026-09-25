package app.melogold.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.melogold.android.BuildConfig
import app.melogold.android.R
import app.melogold.android.ui.screens.Route

private val VERSION_NAME = BuildConfig.VERSION_NAME.substringBeforeLast("-")
private const val REPO_OWNER = "melogold-app"
private const val REPO_NAME = "melogoldAndroid"

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

    SettingsGroup(title = stringResource(R.string.version)) {
        UpdateCheckEntry(version = VERSION_NAME)
    }
}
