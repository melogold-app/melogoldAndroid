@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.kit

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The colors of grouped (segmented) list items: filled with the same container as the tiles next
 * to them, so a group reads as one block on the page (M3 lists: "use segmented gaps and filled
 * list items to define a list group").
 */
@Composable
fun groupedListColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
)
