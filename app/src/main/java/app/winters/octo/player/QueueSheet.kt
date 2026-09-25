package app.winters.octo.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlazeInset
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.elevation3
import app.winters.octo.design.mix
import app.winters.octo.playback.QueueEntry
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.DragHandle
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.songs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val RowShape = RoundedCornerShape(12.dp)

// The sheet's own colour, so a row hides what is under it while it moves.
private val RowFill = mix(OctoColors.Background, OctoColors.Accent, 0.05f)

// The song that is on, lit a little brighter than the sheet.
private val CurrentFill = mix(OctoColors.BackgroundTertiary, OctoColors.Accent, 0.12f)

// The "Up next" sheet: the song that is on, then the rest of the queue in
// the order it will play. Drag a song to move it, swipe it left to take it
// out, tap it to play it. Moving is off while shuffle is on.
@Composable
fun ColumnScope.QueueSheet(
    queue: List<QueueEntry>,
    shuffle: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onPlay: (Int) -> Unit,
) {
    val current = queue.firstOrNull()
    val upcoming = queue.drop(1)
    // The rows as drawn. A drag moves them at once; the queue catches up on drop.
    var rows by remember(upcoming) { mutableStateOf(upcoming) }
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Text("Up next", style = OctoType.section, color = OctoColors.TextPrimary)
        Text(
            "${songs(rows.size)} · ${(rows.sumOf { it.durationMs } / 1000).toInt().asLength()}",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
        )
    }
    if (current != null) {
        GlazeInset(
            fill = CurrentFill,
            shape = RowShape,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
        ) {
            SongLine(current, Modifier.padding(horizontal = 8.dp))
        }
    }
    if (shuffle && rows.isNotEmpty()) {
        Text(
            "Turn off shuffle to reorder",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    LazyColumn(
        Modifier.weight(1f, fill = false),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
    ) {
        items(rows, key = { it.key }) { entry ->
            ReorderableItem(reorder, key = entry.key, enabled = !shuffle) { dragging ->
                // Moves the song in the queue to the place of the one it landed on.
                val drop = {
                    val landed = rows.indexOfFirst { it.key == entry.key }
                    val to = upcoming.getOrNull(landed)?.index
                    if (to != null && to != entry.index) onMove(entry.index, to)
                }
                val swipe = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = false,
                    gesturesEnabled = !dragging,
                    onDismiss = {
                        rows = rows - entry
                        onRemove(entry.index)
                    },
                    backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                ) {
                    SongLine(
                        entry,
                        Modifier
                            .then(if (dragging) Modifier.elevation3(RowShape) else Modifier)
                            .background(RowFill, RowShape)
                            .longPressDraggableHandle(enabled = !shuffle, onDragStopped = drop)
                            .clickable { onPlay(entry.index) }
                            .padding(horizontal = 20.dp),
                        handle = if (shuffle) {
                            null
                        } else {
                            Modifier.draggableHandle(onDragStopped = drop)
                        },
                    )
                }
            }
        }
    }
}

// A song: artwork, title and artist, and a drag handle when it can move.
@Composable
private fun SongLine(entry: QueueEntry, modifier: Modifier = Modifier, handle: Modifier? = null) {
    Row(
        modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(entry.artwork, 44.dp, shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.artist, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (handle != null) DragHandle(handle)
    }
}
