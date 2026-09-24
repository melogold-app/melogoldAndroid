@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.m3e.SegmentedGroupDefaults
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.compose.routing.RouteHandler
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * A section of Settings, opened from the Settings root as a list row (M3: one level of list, then
 * a page; no tabs).
 */
enum class SettingsPage(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int
) {
    Appearance(R.string.appearance, R.drawable.ms_palette),
    Player(R.string.player, R.drawable.ms_play_arrow),
    Cache(R.string.settings_storage, R.drawable.ms_cached),
    Database(R.string.database, R.drawable.ms_backup),
    Other(R.string.other, R.drawable.ms_tune),
    About(R.string.about, R.drawable.ms_info)
}

/** The padding the page scaffold leaves for its app bar and the player: the pages apply it. */
private val LocalSettingsPadding = staticCompositionLocalOf { PaddingValues() }

/**
 * `settingsPageRoute`: one section under a medium app bar with its title that collapses as the
 * page scrolls (REWRITE §3.5).
 */
@Route
@Composable
fun SettingsPageScreen(page: SettingsPage) = RouteHandler {
    GlobalRoutes()

    Content {
        CollectionScaffold(
            title = stringResource(page.title),
            subtitle = null,
            onBack = pop,
            modifier = Modifier.testTag("settings_page")
        ) { padding ->
            CompositionLocalProvider(LocalSettingsPadding provides padding) {
                when (page) {
                    SettingsPage.Appearance -> AppearanceSettings()
                    SettingsPage.Player -> PlayerSettings()
                    SettingsPage.Cache -> CacheSettings()
                    SettingsPage.Database -> DatabaseSettings()
                    SettingsPage.Other -> OtherSettings()
                    SettingsPage.About -> About()
                }
            }
        }
    }
}

/**
 * The scrolling body of a settings page: an optional [description], then the groups. [title] is
 * shown by the app bar.
 */
@Composable
fun SettingsCategoryScreen(
    @Suppress("UNUSED_PARAMETER") title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    scrollState: ScrollState? = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) = Column(
    modifier = modifier
        .fillMaxSize()
        .let { if (scrollState != null) it.verticalScroll(state = scrollState) else it }
        .padding(LocalSettingsPadding.current)
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    description?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        )
    }

    content()
}

/**
 * A titled group of settings: the rows as one segmented list (outer corners rounded, 2dp gaps,
 * like the Settings root), an optional [description] under the title.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    important: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) = Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsEntryGroupText(title = title, modifier = Modifier.weight(1f))
        trailingContent?.invoke()
    }

    description?.let { SettingsDescription(text = it, important = important) }

    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GroupCorner)),
        content = content
    )

    SettingsGroupSpacer()
}

private val GroupCorner = 16.dp

/**
 * The shape of a row inside a group: its outer corners come from the group's clip, so every row
 * takes the inner (middle) shape.
 */
@Composable
private fun rowShapes() = ListItemDefaults.segmentedShapes(index = 1, count = 3)

@Composable
fun SettingsEntry(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    isEnabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) = SegmentedListItem(
    onClick = onClick,
    shapes = rowShapes(),
    enabled = isEnabled,
    supportingContent = text?.let { { Text(text = it) } },
    trailingContent = trailingContent,
    colors = SegmentedGroupDefaults.colors(),
    modifier = modifier.fillMaxWidth()
) {
    Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * A setting that is on or off: the whole row toggles the switch (M3 switch with its check). Only
 * the switch shows the state: the row doesn't take the "selected" look of checkable list items.
 */
@Composable
fun SwitchSettingsEntry(
    title: String,
    text: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) = SegmentedListItem(
    checked = isChecked,
    onCheckedChange = onCheckedChange,
    shapes = rowShapes().let { it.copy(selectedShape = it.shape) },
    enabled = isEnabled,
    supportingContent = text?.let { { Text(text = it) } },
    trailingContent = {
        Switch(
            checked = isChecked,
            onCheckedChange = null,
            enabled = isEnabled,
            thumbContent = if (isChecked) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ms_check),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else null
        )
    },
    colors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedContentColor = MaterialTheme.colorScheme.onSurface,
        selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedTrailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    modifier = modifier.fillMaxWidth()
) {
    Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A value on a scale: the title and the current value, the slider under them. */
@Composable
fun SliderSettingsEntry(
    title: String,
    text: String,
    state: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onSlide: (Float) -> Unit = { },
    onSlideComplete: () -> Unit = { },
    toDisplay: @Composable (Float) -> String = { it.toString() },
    steps: Int = 0,
    isEnabled: Boolean = true,
    showTicks: Boolean = steps != 0
) = SegmentedListItem(
    shapes = rowShapes(),
    enabled = isEnabled,
    supportingContent = {
        Column {
            Text(text = "$text · ${toDisplay(state)}")
            Slider(
                value = state,
                onValueChange = onSlide,
                onValueChangeFinished = onSlideComplete,
                valueRange = range,
                steps = steps,
                enabled = isEnabled,
                track = { sliderState ->
                    if (showTicks) SliderDefaults.Track(sliderState = sliderState, enabled = isEnabled)
                    else SliderDefaults.Track(
                        sliderState = sliderState,
                        enabled = isEnabled,
                        drawTick = { _, _ -> }
                    )
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    },
    colors = SegmentedGroupDefaults.colors(),
    modifier = modifier.fillMaxWidth()
) {
    Text(text = title)
}

@Composable
inline fun <reified T : Enum<T>> EnumValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    noinline onValueSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    noinline valueText: @Composable (T) -> String = { it.name },
    noinline trailingContent: (@Composable () -> Unit)? = null
) = ValueSelectorSettingsEntry(
    title = title,
    selectedValue = selectedValue,
    values = enumValues<T>().toList().toImmutableList(),
    onValueSelect = onValueSelect,
    modifier = modifier,
    isEnabled = isEnabled,
    valueText = valueText,
    trailingContent = trailingContent
)

/**
 * A setting with one value of a few: the value under the title; a tap opens a sheet with the
 * values as radio buttons (REWRITE §3.11.7 `ChoiceSheet`).
 */
@Composable
fun <T> ValueSelectorSettingsEntry(
    title: String,
    selectedValue: T,
    values: ImmutableList<T>,
    onValueSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    isEnabled: Boolean = true,
    valueText: @Composable (T) -> String = { it.toString() },
    trailingContent: (@Composable () -> Unit)? = null
) {
    val menuState = LocalMenuState.current

    SettingsEntry(
        modifier = modifier,
        title = title,
        text = text ?: valueText(selectedValue),
        onClick = {
            menuState.display {
                ChoiceSheet(
                    title = title,
                    values = values,
                    selected = selectedValue,
                    label = valueText,
                    onSelect = { value ->
                        menuState.hide()
                        onValueSelect(value)
                    }
                )
            }
        },
        isEnabled = isEnabled,
        trailingContent = trailingContent
    )
}

/** One value of a few, as radio buttons in the menu sheet. */
@Composable
private fun <T> ChoiceSheet(
    title: String,
    values: ImmutableList<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) = Menu(modifier = Modifier.testTag("choice_sheet")) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { heading() }
    )
    values.forEach { value ->
        SegmentedListItem(
            selected = value == selected,
            onClick = { onSelect(value) },
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
            leadingContent = { RadioButton(selected = value == selected, onClick = null) },
            colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
        ) {
            Text(text = label(value))
        }
    }
}

@Composable
fun SettingsDescription(
    text: String,
    modifier: Modifier = Modifier,
    important: Boolean = false
) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (important) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
)

@Composable
fun SettingsEntryGroupText(
    title: String,
    modifier: Modifier = Modifier
) = Text(
    text = title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        .semantics { heading() }
)

@Composable
fun SettingsGroupSpacer(modifier: Modifier = Modifier) = Spacer(modifier = modifier.height(16.dp))
