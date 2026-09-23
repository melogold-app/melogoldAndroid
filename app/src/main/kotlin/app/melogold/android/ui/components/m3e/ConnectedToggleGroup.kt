@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.melogold.android.R
import kotlinx.collections.immutable.ImmutableList

/**
 * A single-choice row of connected toggle buttons: the M3 Expressive replacement of
 * `SegmentedButton` (search source, "By time · All songs", "Lyrics · Queue", "Official · Custom",
 * "Scan · Show code"). The checked option shows a check mark; changing it ticks the haptics.
 *
 * If connected groups ever have to go, `SingleChoiceSegmentedButtonRow` fits the same signature
 * and only this file changes.
 *
 * @param label the text of an option; it is limited to one line
 */
@Composable
fun <T> ConnectedToggleGroup(
    options: ImmutableList<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = rememberHaptics()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val checked = option == selected

            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) {
                        haptics.segmentTick()
                        onSelect(option)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                enabled = enabled,
                icon = if (checked) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ms_check),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                    }
                } else null,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
            ) {
                Text(
                    text = label(option),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
