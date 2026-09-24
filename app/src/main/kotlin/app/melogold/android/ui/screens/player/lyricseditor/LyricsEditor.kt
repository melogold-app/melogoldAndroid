@file:OptIn(ExperimentalMaterial3Api::class)

package app.melogold.android.ui.screens.player.lyricseditor

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.service.PlayerService
import app.melogold.android.transaction
import app.melogold.android.ui.kit.TextInputDialog
import app.melogold.android.utils.toast
import app.melogold.domain.lyrics.LrcFormat
import app.melogold.domain.lyrics.LyricsDraft
import app.melogold.domain.lyrics.LyricsFormats
import app.melogold.domain.lyrics.TtmlFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_UNDO = 200

enum class EditorTab { Text, Sync, Preview }

/**
 * The draft being edited and the drafts before it, for "Undo". The text of the Text tab is applied
 * to the draft when the tab is left ([commitText]), so undo does not go keystroke by keystroke.
 */
@Stable
class LyricsEditorState(private val initial: LyricsDraft) {
    var draft by mutableStateOf(initial)
        private set

    /** The text of the Text tab while it is being typed. */
    var text by mutableStateOf(initial.toText())

    private val history = mutableStateListOf<LyricsDraft>()

    val canUndo get() = history.isNotEmpty() || text != draft.toText()
    val changed get() = draft != initial || text != draft.toText()

    fun update(transform: (LyricsDraft) -> LyricsDraft) {
        commitText()
        val next = transform(draft)
        if (next == draft) return
        history += draft
        if (history.size > MAX_UNDO) history.removeAt(0)
        draft = next
    }

    fun commitText() {
        if (text == draft.toText()) return
        history += draft
        draft = draft.withText(text)
        text = draft.toText()
    }

    fun undo() {
        if (text != draft.toText()) {
            text = draft.toText()
            return
        }
        history.removeLastOrNull()?.let {
            draft = it
            text = it.toText()
        }
    }
}

/** The draft to start from: the synced lyrics in track time, else the plain ones, else nothing. */
fun initialDraft(lyrics: Lyrics?): LyricsDraft {
    lyrics?.synced?.takeIf { it.isNotBlank() }?.let(LyricsFormats::parseSynced)?.let {
        return LyricsDraft.from(it).shiftedBy(lyrics.startTime ?: 0L)
    }
    lyrics?.fixed?.takeIf { it.isNotBlank() }?.let { return LyricsDraft.fromText(it) }
    return LyricsDraft(lines = emptyList())
}

/**
 * Saves [draft] as "your lyrics": the synced lyrics as TTML (when a line is marked) and the plain
 * text next to them; what the draft doesn't have stays as it was.
 */
fun saveLyricsDraft(mediaItem: MediaItem, current: Lyrics?, draft: LyricsDraft) {
    val synced = draft.toSyncedLyrics()?.let(TtmlFormat::write)
    val plain = draft.toText().takeIf { it.isNotBlank() }

    transaction {
        Database.insert(mediaItem)
        Database.upsert(
            Lyrics(
                songId = mediaItem.mediaId,
                fixed = plain ?: current?.fixed,
                synced = synced ?: current?.synced,
                // The editor works in track time
                startTime = if (synced != null) null else current?.startTime,
                fixedSource = if (plain != null) LyricsSource.User else current?.fixedSource,
                syncedSource = if (synced != null) LyricsSource.User else current?.syncedSource
            )
        )
    }

    if (synced != null) PlayerPreferences.preferSyncedLyrics = true
}

/**
 * The lyrics editor (docs/spec/lyrics.md, "Редактор") as an M3 full-screen dialog: type or paste
 * the text, then mark every line (or word) while the song plays, set the singer side and the
 * backing vocals, and preview the result with the player's renderer.
 */
@Composable
fun LyricsEditorDialog(
    mediaItem: MediaItem,
    binder: PlayerService.Binder,
    initial: LyricsDraft,
    onSave: (LyricsDraft) -> Unit,
    onDismiss: () -> Unit
) {
    val state = remember(mediaItem.mediaId) { LyricsEditorState(initial) }
    var tab by rememberSaveable { mutableStateOf(if (initial.lines.isEmpty()) EditorTab.Text else EditorTab.Sync) }
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }

    val close = { if (state.changed) confirmingDiscard = true else onDismiss() }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(onBack = close)

        // The dialog has its own window: its system bar icons follow the surface as the app's do
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .testTag("lyrics_editor")
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
            ) {
                EditorTopBar(
                    state = state,
                    onClose = close,
                    onSave = {
                        state.commitText()
                        onSave(state.draft)
                    }
                )

                // Tabs switch between views of the same lyrics (M3 tabs, not a segmented choice)
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    EditorTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = {
                                state.commitText()
                                tab = entry
                            },
                            text = {
                                Text(
                                    text = stringResource(
                                        when (entry) {
                                            EditorTab.Text -> R.string.lyrics_editor_tab_text
                                            EditorTab.Sync -> R.string.lyrics_editor_tab_sync
                                            EditorTab.Preview -> R.string.lyrics_editor_tab_preview
                                        }
                                    ),
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        EditorTab.Text -> TextTab(state = state)
                        EditorTab.Sync -> SyncTab(state = state, binder = binder)
                        EditorTab.Preview -> PreviewTab(draft = state.draft, mediaId = mediaItem.mediaId, binder = binder)
                    }
                }
            }
        }

        if (confirmingDiscard) AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(text = stringResource(R.string.lyrics_editor_discard_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDiscard = false
                        onDismiss()
                    }
                ) { Text(text = stringResource(R.string.lyrics_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(text = stringResource(R.string.cancel)) }
            }
        )
    }
}

/** × · "Lyrics" · Undo, Save and a menu with the language and the export. */
@Composable
private fun EditorTopBar(
    state: LyricsEditorState,
    onClose: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported = stringResource(R.string.lyrics_editor_exported)

    var menu by remember { mutableStateOf(false) }
    var editingLanguage by rememberSaveable { mutableStateOf(false) }

    fun export(uri: Uri?, write: (LyricsDraft) -> String?) {
        uri ?: return
        val text = write(state.draft) ?: return
        scope.launch {
            val done = withContext(Dispatchers.IO) { context.writeText(uri, text) }
            if (done) context.toast(exported)
        }
    }

    val exportTtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/ttml+xml")) { uri ->
        export(uri) { draft -> draft.toSyncedLyrics()?.let(TtmlFormat::write) }
    }
    val exportLrc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        export(uri) { draft -> draft.toSyncedLyrics()?.let { LrcFormat.write(it) } }
    }

    TopAppBar(
        title = { Text(text = stringResource(R.string.lyrics_editor_title)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painter = painterResource(R.drawable.ms_close), contentDescription = stringResource(R.string.cancel))
            }
        },
        actions = {
            IconButton(onClick = state::undo, enabled = state.canUndo) {
                Icon(
                    painter = painterResource(R.drawable.ms_undo),
                    contentDescription = stringResource(R.string.lyrics_editor_undo)
                )
            }
            TextButton(onClick = onSave) { Text(text = stringResource(R.string.lyrics_editor_save)) }

            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_more_vert),
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.lyrics_editor_language)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ms_translate), contentDescription = null) },
                        onClick = {
                            menu = false
                            editingLanguage = true
                        }
                    )
                    val timed = state.draft.hasTiming
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.lyrics_editor_export_ttml)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ms_file_export), contentDescription = null) },
                        enabled = timed,
                        onClick = {
                            menu = false
                            state.commitText()
                            exportTtml.launch("lyrics.ttml")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.lyrics_editor_export_lrc)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ms_file_export), contentDescription = null) },
                        enabled = timed,
                        onClick = {
                            menu = false
                            state.commitText()
                            exportLrc.launch("lyrics.lrc")
                        }
                    )
                }
            }
        }
    )

    if (editingLanguage) TextInputDialog(
        title = stringResource(R.string.lyrics_editor_language_title),
        label = stringResource(R.string.lyrics_editor_language_label),
        confirmLabel = stringResource(R.string.done),
        initialValue = state.draft.language.orEmpty(),
        onDismiss = { editingLanguage = false },
        onConfirm = { code ->
            editingLanguage = false
            state.update { it.copy(language = code.trim().takeIf { value -> value.isNotEmpty() }) }
        }
    )
}

/** The text, one line of the song per line; backing vocals in parentheses at the end of a line. */
@Composable
private fun TextTab(state: LyricsEditorState) = OutlinedTextField(
    value = state.text,
    onValueChange = { state.text = it },
    label = { Text(text = stringResource(R.string.lyrics_editor_text_label)) },
    supportingText = { Text(text = stringResource(R.string.lyrics_editor_text_hint)) },
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("lyrics_editor_text")
)

private fun Context.writeText(uri: Uri, text: String) = runCatching {
    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
}.getOrDefault(false)
