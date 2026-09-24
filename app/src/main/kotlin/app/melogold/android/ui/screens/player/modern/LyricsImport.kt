package app.melogold.android.ui.screens.player.modern

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.transaction
import app.melogold.domain.lyrics.LyricsFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LyricsImport"

/** Lyrics files are small; anything bigger is not lyrics. */
private const val MAX_BYTES = 2 * 1024 * 1024

/**
 * A picker for a lyrics file (TTML, LRC, enhanced LRC or plain text) of [mediaItem]. Synced
 * lyrics replace the synced side and switch the player to them, plain text replaces the plain
 * side. [onResult] gets whether the file was read.
 *
 * @param current the lyrics row stored now, whose other side is kept
 */
@Composable
fun rememberLyricsImporter(
    mediaItem: MediaItem,
    current: () -> Lyrics?,
    onResult: (Boolean) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item by rememberUpdatedState(mediaItem)
    val onDone by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { importLyrics(context, uri, item, current()) }
                    .onFailure { Log.w(TAG, "Could not import $uri", it) }
                    .getOrDefault(false)
            }
            onDone(imported)
        }
    }

    return { launcher.launch(arrayOf("*/*")) }
}

private fun importLyrics(context: Context, uri: Uri, mediaItem: MediaItem, current: Lyrics?): Boolean {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        // At most MAX_BYTES + 1 bytes: one more than allowed says the file is too big
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (out.size() <= MAX_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: return false
    if (bytes.size > MAX_BYTES) return false

    val text = bytes.decodeToString().removePrefix("﻿").trim()
    if (text.isEmpty()) return false

    val format = LyricsFormats.detect(text)
    val synced = LyricsFormats.parseSynced(text)
    if (format != LyricsFormats.Format.Plain && synced == null) return false

    transaction {
        Database.insert(mediaItem)
        Database.upsert(
            Lyrics(
                songId = mediaItem.mediaId,
                fixed = if (synced == null) text else current?.fixed,
                synced = if (synced != null) text else current?.synced,
                startTime = current?.startTime,
                fixedSource = if (synced == null) LyricsSource.File else current?.fixedSource,
                syncedSource = if (synced != null) LyricsSource.File else current?.syncedSource
            )
        )
    }

    if (synced != null) PlayerPreferences.preferSyncedLyrics = true
    return true
}
