@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import kotlinx.collections.immutable.ImmutableList

/**
 * The frame of a collection (REWRITE §3.2): a medium flexible app bar with the title, a subtitle
 * ("312 tracks · 18 h 40 min"), Back and [actions], collapsing into a small bar as the list
 * scrolls. The content gets the padding of the bar and of the mini player.
 */
@Composable
fun CollectionScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = { },
    content: @Composable (PaddingValues) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = subtitle?.let { { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ms_arrow_back),
                            contentDescription = stringResource(R.string.kit_back)
                        )
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("collection")
    ) { padding ->
        content(padding)
    }
}

/** "Play · Shuffle" at the top of a collection. */
@Composable
fun PlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) = Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f)) {
        Icon(
            painter = painterResource(R.drawable.ms_play_arrow),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize)
        )
        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        Text(text = stringResource(R.string.collection_play))
    }
    FilledTonalButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.weight(1f)) {
        Icon(
            painter = painterResource(R.drawable.ms_shuffle),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize)
        )
        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        Text(text = stringResource(R.string.collection_shuffle))
    }
}

/**
 * The sort of a list as a chip: "Date added ↓". Picking the current option again reverses it;
 * an option without a direction ([hasDirection] false, e.g. "Own order") shows no arrow.
 */
@Composable
fun <T> SortChip(
    options: ImmutableList<T>,
    selected: T,
    descending: Boolean,
    label: @Composable (T) -> String,
    onSelect: (option: T, descending: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hasDirection: (T) -> Boolean = { true }
) = Box(modifier = modifier) {
    var expanded by remember { mutableStateOf(false) }
    fun arrow(option: T) = when {
        !hasDirection(option) -> ""
        descending -> " ↓"
        else -> " ↑"
    }

    AssistChip(
        onClick = { expanded = true },
        label = { Text(text = label(selected) + arrow(selected)) },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.ms_keyboard_arrow_down),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        modifier = Modifier.testTag("sort_chip")
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(text = label(option)) },
                trailingIcon = if (option == selected) {
                    { Text(text = arrow(option).trim().ifEmpty { "✓" }) }
                } else null,
                onClick = {
                    expanded = false
                    onSelect(option, if (option == selected) !descending else descending)
                }
            )
        }
    }
}

/** The filter of a long list, opened from the search icon of its app bar. */
@Composable
fun CollectionFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(text = stringResource(R.string.collection_filter_hint)) },
        leadingIcon = { Icon(painter = painterResource(R.drawable.ms_search), contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(painter = painterResource(R.drawable.ms_close), contentDescription = stringResource(R.string.cancel))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus)
            .testTag("collection_filter")
    )
}

/** "3:45" or "1:02:03" as milliseconds; null when it is not a duration. */
fun parseDuration(text: String?): Long? = text
    ?.split(':')
    ?.map { it.trim().toLongOrNull() ?: return null }
    ?.takeIf { it.size in 2..3 }
    ?.fold(0L) { total, part -> total * 60 + part }
    ?.times(1_000)

/** A listening or playing time: "18 h 40 min", "40 min". */
@Composable
fun formatListeningTime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) stringResource(R.string.queue_duration_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.queue_duration_minutes, minutes.coerceAtLeast(1))
}
