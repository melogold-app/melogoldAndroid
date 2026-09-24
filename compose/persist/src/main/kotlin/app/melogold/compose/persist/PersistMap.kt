package app.melogold.compose.persist

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

@JvmInline
value class PersistMap(val map: MutableMap<String, MutableState<*>> = hashMapOf()) {
    /**
     * Drops every entry under [prefix]; values that are [AutoCloseable] (screen models) are closed.
     */
    fun clean(prefix: String) {
        val entries = map.entries.iterator()
        while (entries.hasNext()) {
            val (key, state) = entries.next()
            if (!key.startsWith(prefix)) continue
            (state.value as? AutoCloseable)?.close()
            entries.remove()
        }
    }
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
