package app.melogold.android.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

val LocalMenuState = staticCompositionLocalOf { MenuState() }

/**
 * The menu sheet of the app. [display] replaces what it shows, so one menu can lead to another in
 * place (the lyrics menu's "More options" opens the track menu).
 */
@Stable
class MenuState {
    var isDisplayed by mutableStateOf(false)
        private set

    var content by mutableStateOf<@Composable () -> Unit>({})
        private set

    fun display(content: @Composable () -> Unit) {
        this.content = content
        isDisplayed = true
    }

    fun hide() {
        isDisplayed = false
    }
}

/**
 * Shows the menus of [state] in an M3 modal bottom sheet (REDESIGN-M3E T2.6): 28 dp top corners,
 * a drag handle and `surfaceContainerLow`; the sheet slides away before it leaves.
 *
 * [MenuState.isDisplayed] is the only switch: the sheet is composed while it is displayed or still
 * sliding away, and shows itself when it enters. The sheet lives in its own window and places
 * itself, so it takes no modifier (an alignment from the caller's layout would move it twice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(state: MenuState = LocalMenuState.current) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    LaunchedEffect(state.isDisplayed) {
        when {
            // Displayed again while it was sliding away
            state.isDisplayed -> if (sheetState.isVisible) sheetState.show()
            sheetState.isVisible -> sheetState.hide()
        }
    }

    if (state.isDisplayed || sheetState.isVisible) ModalBottomSheet(
        onDismissRequest = state::hide,
        sheetState = sheetState,
        modifier = Modifier.testTag("menu_sheet")
    ) {
        state.content()
    }
}
