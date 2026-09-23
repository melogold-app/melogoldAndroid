@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The vibrant horizontal floating toolbar with a FAB, used by the lyrics mode of the player
 * (`|<<`, `>>|`, "Artwork" and a play FAB, REDESIGN-M3E §5.2). With TalkBack on, the toolbar
 * stays expanded.
 *
 * @param floatingActionButton usually a [VibrantToolbarFab]
 */
@Composable
fun VibrantFloatingToolbar(
    expanded: Boolean,
    floatingActionButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) = HorizontalFloatingToolbar(
    expanded = expanded,
    floatingActionButton = floatingActionButton,
    modifier = modifier,
    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    content = content
)

/**
 * The FAB of a [VibrantFloatingToolbar].
 */
@Composable
fun VibrantToolbarFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = FloatingToolbarDefaults.VibrantFloatingActionButton(
    onClick = onClick,
    modifier = modifier,
    content = content
)
