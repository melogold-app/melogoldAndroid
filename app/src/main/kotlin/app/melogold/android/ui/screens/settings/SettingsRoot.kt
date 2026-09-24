package app.melogold.android.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.settings.account.AccountCard
import app.melogold.android.ui.screens.settings.account.AccountSettingsGroup
import app.melogold.android.ui.screens.settings.account.ServerSettingsRow
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.compose.routing.RouteHandlerScope

private enum class SettingsTab(@param:StringRes val title: Int) {
    Account(R.string.settings_tab_account),
    Appearance(R.string.appearance),
    Player(R.string.player),
    Cache(R.string.cache),
    Database(R.string.database),
    Other(R.string.other),
    About(R.string.about)
}

/**
 * The root of the Settings section. **Temporary** (REDESIGN-M3E §6.1): the pre-redesign settings
 * categories, without the "back" arrow, under a tab row, plus the account stubs. Task T2.4
 * replaces it with the grouped root of §3.1.
 */
@Suppress("UnusedReceiverParameter")
@Route
@Composable
fun RouteHandlerScope.SettingsRoot() {
    val saveableStateHolder = rememberSaveableStateHolder()
    var tabIndex by rememberSaveable { mutableIntStateOf(SettingsTab.Account.ordinal) }
    // The old pages own their scroll state: "to the top" recreates the page
    var generation by rememberSaveable { mutableIntStateOf(0) }

    TabRootScaffold(
        title = stringResource(R.string.nav_settings),
        onScrollToTop = { generation++ }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { if (tabIndex == index) generation++ else tabIndex = index },
                        text = { Text(text = stringResource(tab.title), maxLines = 1) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                saveableStateHolder.SaveableStateProvider(tabIndex) {
                    key(generation) {
                        when (SettingsTab.entries[tabIndex]) {
                            SettingsTab.Account -> AccountPage()
                            SettingsTab.Appearance -> AppearanceSettings()
                            SettingsTab.Player -> PlayerSettings()
                            SettingsTab.Cache -> CacheSettings()
                            SettingsTab.Database -> DatabaseSettings()
                            SettingsTab.Other -> OtherSettings()
                            SettingsTab.About -> About()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPage(modifier: Modifier = Modifier) = Column(
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                .asPaddingValues()
        )
        .padding(16.dp)
) {
    AccountCard()
    ServerSettingsRow()
    AccountSettingsGroup()
}
