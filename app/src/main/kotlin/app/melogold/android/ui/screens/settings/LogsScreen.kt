package app.melogold.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.utils.Logcat
import app.melogold.android.utils.logcat
import app.melogold.compose.routing.RouteHandler
import app.melogold.core.ui.utils.ActivityIntentBundleAccessor

/** The app's log, newest at the bottom, to share with a bug report. */
@Route
@Composable
fun LogsScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val logs = logcat()
        val context = LocalContext.current
        val listState = rememberLazyListState()

        // Follow the newest lines while the list is at the bottom
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty() && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }

        CollectionScaffold(
            title = stringResource(R.string.logs),
            subtitle = null,
            onBack = pop,
            actions = {
                IconButton(onClick = { context.shareLogs(logs) }, enabled = logs.isNotEmpty()) {
                    Icon(painter = painterResource(R.drawable.ms_share), contentDescription = stringResource(R.string.menu_share))
                }
            },
            modifier = Modifier.testTag("logs")
        ) { padding ->
            LazyColumn(
                state = listState,
                contentPadding = padding,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize()
            ) {
                items(count = logs.size, key = { logs[logs.size - it - 1].id }) { index ->
                    when (val line = logs[logs.size - index - 1]) {
                        is Logcat.FormattedLine -> LogLine(line = line)
                        is Logcat.RawLine -> Text(
                            text = line.raw,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** One line of the log: its level, time and tag, the message on one line or all of it after a tap. */
@Composable
private fun LogLine(line: Logcat.FormattedLine) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = line.level.look()

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = line.level.name,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${line.timestamp} · ${line.tag}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = line.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Logcat.FormattedLine.Level.look(): Pair<Int, Color> = when (this) {
    Logcat.FormattedLine.Level.Error -> R.drawable.ms_error to MaterialTheme.colorScheme.error
    Logcat.FormattedLine.Level.Warning -> R.drawable.ms_warning to MaterialTheme.colorScheme.tertiary
    Logcat.FormattedLine.Level.Debug -> R.drawable.ms_bug_report to MaterialTheme.colorScheme.primary
    Logcat.FormattedLine.Level.Info -> R.drawable.ms_info to MaterialTheme.colorScheme.onSurfaceVariant
    Logcat.FormattedLine.Level.Unknown -> R.drawable.ms_help to MaterialTheme.colorScheme.outline
}

private fun Context.shareLogs(logs: List<Logcat>) {
    val extras = ActivityIntentBundleAccessor.bundle {
        text = logs.joinToString(separator = "\n") {
            when (it) {
                is Logcat.FormattedLine -> "[${it.timestamp}] ${it.level.name} (${it.pid}) ${it.tag} - ${it.message}"
                is Logcat.RawLine -> it.raw
            }
        }
    }

    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                putExtras(extras)
                type = "text/plain"
            },
            null
        )
    )
}
