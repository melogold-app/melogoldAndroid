package app.melogold.android.ui.shell

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Asks for a runtime permission; [request]'s callback gets whether it is granted. */
fun interface PermissionRequester {
    fun request(permission: String, onResult: (Boolean) -> Unit)
}

/**
 * Registered at the root, so a menu that closes right after asking still gets the system dialog
 * and its answer: the notifications for the first "Download" (REWRITE §3.2.3), the storage for
 * "Save as file" on Android 9 and older.
 */
@Composable
fun rememberPermissionRequester(): PermissionRequester {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }

    return remember(context, launcher) {
        PermissionRequester { permission, onResult ->
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) onResult(true)
            else {
                pending[0] = onResult
                launcher.launch(permission)
            }
        }
    }
}

val LocalPermissions = staticCompositionLocalOf { PermissionRequester { _, onResult -> onResult(false) } }
