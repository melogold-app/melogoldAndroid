package app.melogold.android.ui.screens.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.query
import app.melogold.android.service.PlayerMediaBrowserService
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.logsRoute
import app.melogold.android.utils.isIgnoringBatteryOptimizations
import app.melogold.android.utils.toast
import app.melogold.core.ui.utils.isAtLeastAndroid6
import kotlinx.coroutines.flow.distinctUntilChanged

@SuppressLint("BatteryLife")
@Route
@Composable
fun OtherSettings() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val scrollState = rememberScrollState()

    var isAndroidAutoEnabled by remember {
        val component = ComponentName(context, PlayerMediaBrowserService::class.java)
        val disabledFlag = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val enabledFlag = PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        mutableStateOf(
            value = context.packageManager.getComponentEnabledSetting(component) == enabledFlag,
            policy = object : SnapshotMutationPolicy<Boolean> {
                override fun equivalent(a: Boolean, b: Boolean): Boolean {
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        if (b) enabledFlag else disabledFlag,
                        PackageManager.DONT_KILL_APP
                    )
                    return a == b
                }
            }
        )
    }

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(context.isIgnoringBatteryOptimizations)
    }

    val activityResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { isIgnoringBatteryOptimizations = context.isIgnoringBatteryOptimizations }
    )

    val queriesCount by remember {
        Database.queriesCount().distinctUntilChanged()
    }.collectAsState(initial = 0)

    SettingsCategoryScreen(
        title = stringResource(R.string.other),
        scrollState = scrollState
    ) {
        SettingsGroup(title = stringResource(R.string.android_auto)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.android_auto),
                text = stringResource(R.string.android_auto_description),
                isChecked = isAndroidAutoEnabled,
                onCheckedChange = { isAndroidAutoEnabled = it }
            )

            AnimatedVisibility(visible = isAndroidAutoEnabled) {
                SettingsDescription(text = stringResource(R.string.android_auto_warning))
            }
        }
        SettingsGroup(title = stringResource(R.string.search_history)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.pause_search_history),
                text = stringResource(R.string.pause_search_history_description),
                isChecked = DataPreferences.pauseSearchHistory,
                onCheckedChange = { DataPreferences.pauseSearchHistory = it }
            )

            AnimatedVisibility(visible = !(DataPreferences.pauseSearchHistory && queriesCount == 0)) {
                SettingsEntry(
                    title = stringResource(R.string.clear_search_history),
                    text = if (queriesCount > 0) stringResource(
                        R.string.format_clear_search_history_amount,
                        queriesCount
                    )
                    else stringResource(R.string.empty_history),
                    onClick = { query(Database::clearQueries) },
                    isEnabled = queriesCount > 0
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.service_lifetime)) {
            AnimatedVisibility(visible = !isIgnoringBatteryOptimizations) {
                SettingsDescription(
                    text = stringResource(R.string.service_lifetime_warning),
                    important = true
                )
            }

            val errorMsg = stringResource(R.string.no_battery_optimization_settings_found)
            SettingsEntry(
                title = stringResource(R.string.ignore_battery_optimizations),
                text = if (isIgnoringBatteryOptimizations) stringResource(R.string.ignoring_battery_optimizations)
                else stringResource(R.string.ignore_battery_optimizations_action),
                onClick = {
                    if (!isAtLeastAndroid6) return@SettingsEntry

                    try {
                        activityResultLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                        )
                    } catch (_: ActivityNotFoundException) {
                        try {
                            activityResultLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            context.toast(errorMsg)
                        }
                    }
                },
                isEnabled = !isIgnoringBatteryOptimizations
            )

            SettingsEntry(
                title = stringResource(R.string.need_help),
                text = stringResource(R.string.need_help_description),
                onClick = {
                    uriHandler.openUri("https://dontkillmyapp.com/")
                }
            )

            SettingsDescription(text = stringResource(R.string.service_lifetime_report_issue))
        }

        SettingsGroup(title = stringResource(R.string.troubleshooting)) {
            SettingsEntry(
                title = stringResource(R.string.show_logs),
                onClick = { logsRoute.global() }
            )
        }
    }
}
