package app.melogold.android.ui.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.R
import app.melogold.android.data.repo.PendingMutation
import app.melogold.android.data.repo.PendingMutationStore
import app.melogold.android.data.repo.UNDO_TIMEOUT_MS
import app.melogold.android.data.repo.pendingMutations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val SHOWN_POLL_MS = 50L

/**
 * The app-wide snackbar (REDESIGN-M3E §1.3): one host above the mini player, reached through
 * [LocalAppSnackbar]. A deletion offers "Undo" through [undoable]; other changes that can be
 * reverted, through [showUndo].
 *
 * @param recommendedTimeout how long a snackbar with an action stays for the user's accessibility
 * settings, from the time it would stay otherwise
 */
@Stable
class AppSnackbar internal constructor(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
    private val undoLabel: () -> String,
    private val mutations: () -> PendingMutationStore = { pendingMutations },
    private val recommendedTimeout: (Long) -> Long = { it }
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
     * "[message] · Undo" after a change that [onUndo] reverts.
     */
    fun showUndo(message: String, onUndo: () -> Unit): Job = show(
        message = message,
        actionLabel = undoLabel(),
        onAction = onUndo
    )

    /**
     * "[message] · Undo" for a deletion that waits (REWRITE §3.11.9): [mutation] hides what it
     * deletes at once, and reaches Room when the snackbar goes away — after [timeoutMillis], when a
     * newer snackbar replaces it, or with the others when the app goes to the background. "Undo"
     * drops it.
     */
    fun undoable(message: String, mutation: PendingMutation, timeoutMillis: Long = UNDO_TIMEOUT_MS): Job {
        val store = mutations()
        store.add(mutation)
        val visuals = UndoVisuals(message = message, actionLabel = undoLabel())

        return scope.launch {
            try {
                hostState.currentSnackbarData?.dismiss()
                coroutineScope {
                    fun dismissIfShown() = hostState.currentSnackbarData?.takeIf { it.visuals === visuals }?.dismiss()

                    val timer = launch {
                        while (hostState.currentSnackbarData?.visuals !== visuals) delay(SHOWN_POLL_MS)
                        delay(recommendedTimeout(timeoutMillis))
                        dismissIfShown()
                    }
                    // Written with the rest when the app went to the background: nothing to undo
                    val watcher = launch {
                        store.pending.first { mutation !in it }
                        dismissIfShown()
                    }

                    val result = hostState.showSnackbar(visuals)
                    timer.cancel()
                    watcher.cancel()
                    if (result == SnackbarResult.ActionPerformed) store.undo(mutation)
                }
            } finally {
                // Nothing to write when it was undone
                store.commit(mutation)
            }
        }
    }
}

private class UndoVisuals(
    override val message: String,
    override val actionLabel: String
) : SnackbarVisuals {
    override val withDismissAction = false

    // [AppSnackbar.undoable] times it
    override val duration = SnackbarDuration.Indefinite
}

@Composable
fun rememberAppSnackbar(): AppSnackbar {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessibility = LocalAccessibilityManager.current

    return remember(context, scope, accessibility) {
        AppSnackbar(
            hostState = SnackbarHostState(),
            scope = scope,
            undoLabel = { context.getString(R.string.snackbar_undo) },
            recommendedTimeout = { millis ->
                accessibility?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = millis,
                    containsIcons = false,
                    containsText = true,
                    containsControls = true
                ) ?: millis
            }
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
