package app.melogold.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.components.m3e.SegmentedGroup
import app.melogold.android.ui.components.m3e.SegmentedRow
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.settings.account.AccountCard
import app.melogold.android.ui.screens.settings.account.AccountSettingsGroup
import app.melogold.android.ui.screens.settings.account.ServerSettingsRow
import app.melogold.android.ui.screens.settingsPageRoute
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.compose.routing.RouteHandlerScope

/**
 * The root of Settings (M3 lists, REWRITE §3.5): the account card and the server on top, then the
 * sections as one grouped list; each opens its own page. No tabs: a row per section scales to any
 * number of sections and reads top to bottom.
 */
@Route
@Composable
fun RouteHandlerScope.SettingsRoot() {
    val scrollState = rememberScrollState()

    TabRootScaffold(
        title = stringResource(R.string.nav_settings),
        onScrollToTop = { scrollState.animateScrollTo(0) }
    ) { contentPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AccountCard()
            ServerSettingsRow()
            AccountSettingsGroup()

            SegmentedGroup {
                SettingsPage.entries.forEach { page ->
                    row { shapes ->
                        SegmentedRow(
                            headline = stringResource(page.title),
                            shapes = shapes,
                            icon = page.icon,
                            onClick = { settingsPageRoute(page) },
                            trailing = {
                                Icon(
                                    painter = painterResource(R.drawable.ms_chevron_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
