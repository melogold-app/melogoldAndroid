package app.melogold.compose.persist

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

@JvmInline
value class PersistMap(val map: MutableMap<String, MutableState<*>> = hashMapOf()) {
    fun clean(prefix: String) = map.keys.removeAll { it.startsWith(prefix) }
}

val LocalPersistMap = compositionLocalOf<PersistMap?> {
    Log.e("PersistMap", "Tried to reference uninitialized PersistMap, stacktrace:")
    runCatching { error("Stack:") }.exceptionOrNull()?.printStackTrace()
    null
}

/**
 * A prefix that [persist] and [PersistMapCleanup] put in front of every tag, so that several
 * independent navigation stacks (one per top-level section) can show the same screen without
 * sharing, or cleaning, each other's cached state. Call sites keep using their plain tags.
 *
 * Empty (no namespace) outside such a stack.
 */
val LocalPersistNamespace = staticCompositionLocalOf { "" }

/**
 * The full [PersistMap] key for [tag] in the current [LocalPersistNamespace].
 */
fun persistKey(namespace: String, tag: String) = namespace + tag
