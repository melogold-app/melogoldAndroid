package app.melogold.android.ui.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app-wide snackbar (REDESIGN-M3E §1.3): one host above the mini player, reached through
 * [LocalAppSnackbar]. Every destructive action offers "Undo" through [showUndo].
 */
@Stable
class AppSnackbar internal constructor(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
    private val undoLabel: () -> String
) {
    /**
     * Shows [message]; [onAction] runs if the user taps [actionLabel]. A newer message replaces
     * the one on screen.
     */
    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
        onAction: () -> Unit = { }
    ): Job = scope.launch {
        hostState.currentSnackbarData?.dismiss()
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            duration = duration
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
    }

    /**
     * "[message] · Undo" after a destructive action; [onUndo] reverts it.
     */
    fun showUndo(message: String, onUndo: () -> Unit): Job = show(
        message = message,
        actionLabel = undoLabel(),
        onAction = onUndo
    )
}

@Composable
fun rememberAppSnackbar(): AppSnackbar {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    return remember(context, scope) {
        AppSnackbar(
            hostState = SnackbarHostState(),
            scope = scope,
            undoLabel = { context.getString(R.string.snackbar_undo) }
        )
    }
}

@Composable
fun AppSnackbarHost(
    snackbar: AppSnackbar,
    modifier: Modifier = Modifier
) = SnackbarHost(
    hostState = snackbar.hostState,
    modifier = modifier
)

val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar> { error("No AppSnackbar provided") }
