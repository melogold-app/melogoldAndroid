@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player.lyricseditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.m3e.ConnectedToggleGroup
import app.melogold.android.ui.kit.TextInputDialog
import app.melogold.android.ui.screens.player.modern.LyricsContent
import app.melogold.android.ui.screens.player.modern.PlayerMode
import app.melogold.android.ui.screens.player.modern.PlayerModeState
import app.melogold.android.ui.screens.player.modern.SyncedLyricsView
import app.melogold.android.ui.screens.player.modern.buildLyricRows
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.rememberReduceMotion
import app.melogold.domain.lyrics.DraftLine
import app.melogold.domain.lyrics.LyricsDraft
import app.melogold.domain.lyrics.LyricsTiming
import app.melogold.domain.lyrics.VocalSide
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

/** How much earlier than the tap a mark lands: people tap a little after they hear. */
private const val REACTION_MS = 150L
private const val REWIND_MS = 3_000L
private const val NUDGE_MS = 100L

/** Tapping a marked line plays from a little before it. */
private const val REPLAY_LEAD_MS = 2_000L

/**
 * Tap to sync: the lines with their times, the line (or word) the next mark times highlighted, and
 * "Mark" / "End line" under them while the song plays.
 */
@Composable
internal fun SyncTab(state: LyricsEditorState, binder: PlayerService.Binder) {
    val player = binder.player
    val draft = state.draft
    val haptic = LocalHapticFeedback.current

    if (draft.lines.isEmpty()) {
        Hint(text = R.string.lyrics_editor_no_lines)
        return
    }

    // Opens at the line playing now; then follows the cursor while there are lines left to mark
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember {
            val position = player.currentPosition
            (draft.lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= position } - 2).coerceAtLeast(0)
        }
    )
    LaunchedEffect(draft.cursor) {
        if (draft.cursor in draft.lines.indices) listState.animateScrollToItem((draft.cursor - 2).coerceAtLeast(0))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // What a mark times: whole lines, or word after word
        ConnectedToggleGroup(
            options = persistentListOf(LyricsTiming.Line, LyricsTiming.Word),
            selected = draft.timing,
            onSelect = { timing -> state.update { it.copy(timing = timing).movedTo(it.cursor) } },
            label = {
                stringResource(if (it == LyricsTiming.Line) R.string.lyrics_editor_lines else R.string.lyrics_editor_words)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .testTag("lyrics_editor_lines")
        ) {
            itemsIndexed(draft.lines) { index, line ->
                SyncRow(
                    line = line,
                    isCursor = index == draft.cursor,
                    wordCursor = if (index == draft.cursor && draft.timing == LyricsTiming.Word) draft.wordCursor else -1,
                    onClick = {
                        state.update { it.movedTo(index) }
                        line.startMs?.let { player.seekTo((it - REPLAY_LEAD_MS).coerceAtLeast(0L)) }
                    },
                    onToggleSide = {
                        state.update {
                            it.withSide(index, if (line.side == VocalSide.Start) VocalSide.End else VocalSide.Start)
                        }
                    },
                    onNudge = { delta -> state.update { it.nudge(index, delta) } },
                    onBacking = { text -> state.update { it.withBacking(index, text) } },
                    onLanguage = { code -> state.update { it.withLineLanguage(index, code) } },
                    onClearTime = { state.update { it.clearTiming(index).movedTo(index) } }
                )
            }
        }

        SyncControls(
            player = player,
            draft = draft,
            onMark = {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                state.update { it.mark((player.currentPosition - REACTION_MS).coerceAtLeast(0L)) }
            },
            onMarkEnd = {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                state.update { it.markEnd((player.currentPosition - REACTION_MS).coerceAtLeast(0L)) }
            }
        )
    }
}

/**
 * A line: its start, the text (in word mode the timed words colored and the next one underlined),
 * the backing vocals, the side toggle and a menu for the rest.
 */
@Composable
private fun SyncRow(
    line: DraftLine,
    isCursor: Boolean,
    wordCursor: Int,
    onClick: () -> Unit,
    onToggleSide: () -> Unit,
    onNudge: (Long) -> Unit,
    onBacking: (String?) -> Unit,
    onLanguage: (String?) -> Unit,
    onClearTime: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var editingBacking by rememberSaveable { mutableStateOf(false) }
    var editingLanguage by rememberSaveable { mutableStateOf(false) }
    val timedColor = MaterialTheme.colorScheme.primary

    ListItem(
        selected = isCursor,
        onClick = onClick,
        onLongClick = { menu = true },
        leadingContent = {
            Text(
                text = line.startMs?.let(::formatTime) ?: stringResource(R.string.lyrics_editor_no_time),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (line.startMs != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 56.dp)
            )
        },
        supportingContent = line.backing?.let { { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSide) {
                    Icon(
                        painter = painterResource(
                            if (line.side == VocalSide.End) R.drawable.ms_format_align_right
                            else R.drawable.ms_format_align_left
                        ),
                        contentDescription = stringResource(R.string.lyrics_editor_side) + ": " + stringResource(
                            if (line.side == VocalSide.End) R.string.lyrics_editor_side_end
                            else R.string.lyrics_editor_side_start
                        )
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ms_more_vert),
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun Item(@StringRes text: Int, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                            text = { Text(text = stringResource(text)) },
                            enabled = enabled,
                            onClick = {
                                menu = false
                                onClick()
                            }
                        )

                        val timed = line.startMs != null
                        Item(R.string.lyrics_editor_earlier, timed) { onNudge(-NUDGE_MS) }
                        Item(R.string.lyrics_editor_later, timed) { onNudge(NUDGE_MS) }
                        Item(R.string.lyrics_editor_backing) { editingBacking = true }
                        Item(R.string.lyrics_editor_line_language) { editingLanguage = true }
                        Item(R.string.lyrics_editor_clear_time, timed, onClearTime)
                    }
                }
            }
        },
        shapes = ListItemDefaults.shapes(shape = RoundedCornerShape(16.dp), selectedShape = RoundedCornerShape(16.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        val words = line.words
        Text(
            text = if (wordCursor < 0 && line.wordStarts.isEmpty()) buildAnnotatedString { append(line.text) }
            else buildAnnotatedString {
                words.forEachIndexed { index, word ->
                    val timed = line.wordStarts.getOrNull(index) != null
                    val next = index == wordCursor
                    withStyle(
                        SpanStyle(
                            color = if (timed) timedColor else Color.Unspecified,
                            fontWeight = if (next) FontWeight.Bold else null,
                            textDecoration = if (next) TextDecoration.Underline else null
                        )
                    ) { append(word) }
                    if (index < words.lastIndex) append(" ")
                }
            },
            textAlign = if (line.side == VocalSide.End) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (editingBacking) TextInputDialog(
        title = stringResource(R.string.lyrics_editor_backing_label),
        label = stringResource(R.string.lyrics_editor_backing_label),
        confirmLabel = stringResource(R.string.done),
        initialValue = line.backing?.removeSurrounding("(", ")").orEmpty(),
        onDismiss = { editingBacking = false },
        onConfirm = { text ->
            editingBacking = false
            onBacking(text)
        }
    )

    if (editingLanguage) TextInputDialog(
        title = stringResource(R.string.lyrics_editor_language_title),
        label = stringResource(R.string.lyrics_editor_language_label),
        confirmLabel = stringResource(R.string.done),
        initialValue = line.language.orEmpty(),
        onDismiss = { editingLanguage = false },
        onConfirm = { code ->
            editingLanguage = false
            onLanguage(code.trim().takeIf { it.isNotEmpty() })
        }
    )
}

/**
 * The transport (back 3 s, play or pause), the position and what the next mark times, then "End
 * line" and the big "Mark" under the thumb.
 */
@Composable
private fun SyncControls(
    player: Player,
    draft: LyricsDraft,
    onMark: () -> Unit,
    onMarkEnd: () -> Unit
) = Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
    val playing by rememberPlaying(player)
    val line = draft.lines.getOrNull(draft.cursor)
    val next = when {
        line == null -> null
        draft.timing == LyricsTiming.Word && line.words.isNotEmpty() ->
            line.words.getOrNull(draft.wordCursor) ?: line.words.first()
        else -> line.text
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("lyrics_editor_controls")
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player.seekTo((player.currentPosition - REWIND_MS).coerceAtLeast(0L)) }) {
                Icon(painter = painterResource(R.drawable.ms_replay), contentDescription = stringResource(R.string.lyrics_editor_rewind))
            }
            FilledTonalIconButton(
                onClick = {
                    if (playing) player.pause()
                    else {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (playing) R.drawable.ms_pause else R.drawable.ms_play_arrow),
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play)
                )
            }
            PositionText(player = player, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                text = next?.let { stringResource(R.string.lyrics_editor_next, it) }
                    ?: stringResource(R.string.lyrics_editor_all_marked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onMarkEnd,
                enabled = draft.cursor > 0,
                modifier = Modifier.height(56.dp)
            ) {
                Text(text = stringResource(R.string.lyrics_editor_end_line), maxLines = 1)
            }
            Button(
                onClick = onMark,
                enabled = line != null,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("lyrics_editor_mark")
            ) {
                Text(
                    text = stringResource(R.string.lyrics_editor_mark),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/** The playback position as "1:23.45", updated while the tab is shown. */
@Composable
private fun PositionText(player: Player, modifier: Modifier = Modifier) {
    val position by produceState(player.currentPosition, player) {
        while (true) {
            value = player.currentPosition
            delay(50)
        }
    }

    Text(
        text = formatTime(position),
        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
        modifier = modifier
    )
}

/** The draft as the player will show it, with a small transport. */
@Composable
internal fun PreviewTab(draft: LyricsDraft, mediaId: String, binder: PlayerService.Binder) {
    val player = binder.player
    val lyrics = remember(draft) { draft.toSyncedLyrics() }
    if (lyrics == null) {
        Hint(text = R.string.lyrics_editor_no_lines)
        return
    }

    val content = remember(lyrics) {
        LyricsContent.Synced(lyrics = lyrics, rows = buildLyricRows(lyrics), startTimeMs = 0L)
    }
    val modeState = remember { PlayerModeState(PlayerMode.Lyrics) }
    val playing by rememberPlaying(player)

    Column(modifier = Modifier.fillMaxSize()) {
        SyncedLyricsView(
            content = content,
            mediaId = mediaId,
            player = player,
            shouldBePlaying = playing,
            anchor = 96.dp,
            controlsVisible = false,
            controlsOverlapPx = { 0 },
            reduceMotion = rememberReduceMotion(),
            modeState = modeState,
            onLineLongPress = { },
            modifier = Modifier.weight(1f)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            IconButton(onClick = { player.seekTo((player.currentPosition - REWIND_MS).coerceAtLeast(0L)) }) {
                Icon(painter = painterResource(R.drawable.ms_replay), contentDescription = stringResource(R.string.lyrics_editor_rewind))
            }
            FilledTonalIconButton(onClick = { if (playing) player.pause() else player.play() }) {
                Icon(
                    painter = painterResource(if (playing) R.drawable.ms_pause else R.drawable.ms_play_arrow),
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play)
                )
            }
        }
    }
}

@Composable
private fun Hint(@StringRes text: Int) = Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
        .fillMaxSize()
        .padding(32.dp)
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** Whether [player] plays, for the play/pause buttons of the editor. */
@Composable
private fun rememberPlaying(player: Player): State<Boolean> {
    val playing = remember(player) { mutableStateOf(player.isPlaying) }
    player.DisposableListener {
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
            }
        }
    }
    return playing
}

/** "m:ss.cc" */
internal fun formatTime(ms: Long): String {
    val centis = (ms / 10) % 100
    val seconds = (ms / 1_000) % 60
    val minutes = ms / 60_000
    return "%d:%02d.%02d".format(minutes, seconds, centis)
}
