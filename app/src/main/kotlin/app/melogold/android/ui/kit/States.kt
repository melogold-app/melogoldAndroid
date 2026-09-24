package app.melogold.android.ui.kit

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.MelogoldLoadingIndicator
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.model.Loadable
import kotlinx.coroutines.delay

private const val LOADING_DELAY_MS = 300L

/**
 * Shows [loadable] by the table of REWRITE §3.0: the loading indicator, an error with its actions,
 * or [content].
 *
 * @param onOpenLibrary the extra action of the offline error; hidden when null
 */
@Composable
fun <T> LoadableContent(
    loadable: Loadable<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLibrary: (() -> Unit)? = null,
    content: @Composable (Loadable.Content<T>) -> Unit
) = when (loadable) {
    Loadable.Loading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DelayedLoadingIndicator()
    }

    is Loadable.Error -> ErrorState(
        kind = loadable.kind,
        onRetry = onRetry,
        onOpenLibrary = onOpenLibrary,
        modifier = modifier.fillMaxSize()
    )

    is Loadable.Content -> content(loadable)
}

/**
 * The loading indicator, shown only if loading takes longer than 300 ms so fast loads don't flash.
 */
@Composable
fun DelayedLoadingIndicator(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MS)
        visible = true
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), modifier = modifier) {
        MelogoldLoadingIndicator()
    }
}

/**
 * A failed load: what happened, what to do, and [Retry][onRetry].
 */
@Composable
fun ErrorState(
    kind: Loadable.Error.Kind,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLibrary: (() -> Unit)? = null
) = Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ShapeIcon(
            icon = if (kind == Loadable.Error.Kind.Offline) R.drawable.ms_cloud_off else R.drawable.ms_error,
            shape = IconShape.Cookie9Sided,
            contentDescription = null,
            size = 72.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(
                when (kind) {
                    Loadable.Error.Kind.Offline -> R.string.kit_error_offline
                    Loadable.Error.Kind.Blocked -> R.string.kit_error_blocked
                    Loadable.Error.Kind.Parser -> R.string.kit_error_parser
                    Loadable.Error.Kind.Unknown -> R.string.kit_error_unknown
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Button(onClick = onRetry) { Text(stringResource(R.string.kit_retry)) }

        if (kind == Loadable.Error.Kind.Offline && onOpenLibrary != null) TextButton(onClick = onOpenLibrary) {
            Text(stringResource(R.string.kit_open_library))
        }
    }
}

/**
 * The "No network — data from 14:02" chip above content that could not be refreshed.
 */
@Composable
fun StaleChip(
    since: Long,
    reason: Loadable.Error.Kind?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val time = remember(since) {
        val flags = if (DateUtils.isToday(since)) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        DateUtils.formatDateTime(context, since, flags)
    }

    AssistChip(
        onClick = onClick,
        label = {
            Text(
                stringResource(
                    if (reason == Loadable.Error.Kind.Offline) R.string.kit_stale_offline else R.string.kit_stale,
                    time
                )
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ms_cloud_off),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        modifier = modifier
    )
}
