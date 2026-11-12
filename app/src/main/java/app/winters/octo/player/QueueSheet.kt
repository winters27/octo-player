package app.winters.octo.player

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.FirstBaseline
import app.winters.octo.design.Spinner
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.ui.common.SongTitle
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeInset
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.elevation3
import app.winters.octo.design.mix
import app.winters.octo.playback.NoSource
import app.winters.octo.playback.QueueEntry
import app.winters.octo.playback.queueSourceTitle
import app.winters.octo.playback.sourceRuns
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.DragHandle
import app.winters.octo.ui.common.RemoveBackground
import app.winters.octo.ui.common.asLength
import app.winters.octo.ui.common.factsLine
import app.winters.octo.ui.common.isOutsideLibrary
import app.winters.octo.ui.common.songs
import app.winters.octo.ui.common.GlassMenuAction
import app.winters.octo.ui.common.HeaderMore
import app.winters.octo.ui.common.MoreItem
import app.winters.octo.ui.common.QuietIconAction
import app.winters.octo.ui.common.GlassMenuHeader
import app.winters.octo.ui.common.GlassMenuPage
import app.winters.octo.ui.common.GlassMenuSeparator
import app.winters.octo.ui.common.rememberOpenedBeside
import app.winters.octo.ui.playlist.NewPlaylistForm
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val RowShape = RoundedCornerShape(12.dp)

// The sheet's own colour, so a row hides what is under it while it moves.
private val RowFill: Color @Composable @ReadOnlyComposable get() = mix(OctoColors.Background, OctoColors.Accent, 0.05f)

// The song that is on, lit a little brighter than the sheet.
private val CurrentFill: Color @Composable @ReadOnlyComposable get() = mix(OctoColors.BackgroundTertiary, OctoColors.Accent, 0.12f)

// Songs already played sit above the current one, dimmed.
private const val PLAYED_ALPHA = 0.5f

// The queue sheet over the player, a long working list, then the menu of
// one of its songs and the name form for saving the queue as a playlist,
// each a glass card floating over it. `backdrop` is what the cards frost.
@Composable
fun QueueSheets(model: PlayerViewModel, shuffle: Boolean, visible: Boolean, onDismiss: () -> Unit, backdrop: HazeState) {
    // The song whose menu is open, kept after closing so it can slide away.
    var menuOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<QueueMenuFor?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Closing the name form puts the keyboard away with it.
    val focus = LocalFocusManager.current
    LaunchedEffect(saving) { if (!saving) focus.clearFocus() }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        val played by model.played.collectAsStateWithLifecycle()
        val upNext by model.upNext.collectAsStateWithLifecycle()
        val building by model.building.collectAsStateWithLifecycle()
        val tail by model.tail.collectAsStateWithLifecycle()
        val head by model.head.collectAsStateWithLifecycle()
        val tailCount by model.tailCount.collectAsStateWithLifecycle()
        QueueSheet(
            played = played,
            queue = upNext,
            shuffle = shuffle,
            backdrop = backdrop,
            building = building,
            past = QueuePast(head, tail, tailCount.first, tailCount.second, model::moreHead, model::moreTail, model::playEntry),
            actions = QueueActions(
                move = model::moveInQueue,
                remove = model::removeFromQueue,
                play = model::playAt,
                menu = { entry, place ->
                    menuFor = QueueMenuFor(entry, place)
                    menuOpen = true
                },
                clear = model::clearQueue,
                removePlayed = model::removePlayed,
                save = { saving = true },
            ),
        )
    }
    GlassPopup(
        visible = menuOpen,
        anchor = rememberOpenedBeside(menuOpen),
        onDismiss = { menuOpen = false },
        backdrop = backdrop,
        title = "Song in the queue",
    ) {
        val shown = menuFor ?: return@GlassPopup
        QueueRowMenu(shown, model, onDone = { menuOpen = false })
    }
    GlassPopup(
        visible = saving,
        anchor = rememberOpenedBeside(saving),
        onDismiss = { saving = false },
        backdrop = backdrop,
        title = "Save as playlist",
    ) {
        NewPlaylistForm { name ->
            model.saveQueueAsPlaylist(name)
            saving = false
        }
    }
}

// Where a song sits relative to the one playing.
enum class QueuePlace { Played, Current, Upcoming }

class QueueMenuFor(val entry: QueueEntry, val place: QueuePlace)

// The queue past the songs the player holds: the songs before them read so
// far (`head`) and after them (`tail`), how many come after them in all and
// how long they play, the reads of the next page each way, and a tap.
class QueuePast(
    val head: List<QueueEntry> = emptyList(),
    val tail: List<QueueEntry> = emptyList(),
    val tailCount: Int = 0,
    val tailMs: Long = 0,
    val moreHead: () -> Unit = {},
    val moreTail: () -> Unit = {},
    val play: (QueueEntry) -> Unit = {},
)

// What the queue sheet's rows and tools do.
class QueueActions(
    val move: (from: Int, to: Int) -> Unit,
    val remove: (QueueEntry) -> Unit,
    val play: (Int) -> Unit,
    val menu: (QueueEntry, QueuePlace) -> Unit,
    val clear: () -> Unit,
    val removePlayed: () -> Unit,
    val save: () -> Unit,
)

// The "Up next" sheet: songs already played, dimmed, then the song that is
// on, then the rest of the queue in the order it will play, under headings
// by where each part came from ("Next from you", "Next from OK Computer"),
// as the desktop's queue shows it. It opens at
// the song that is on. Drag a song by its handle to move it, swipe it left
// to take it out, tap it to play it, long-press it for its menu. Moving is
// off while shuffle is on. Its top line scrolls back to the song on, and its
// More menu clears the queue, takes out the songs played or saves it as a
// playlist.
@Composable
fun ColumnScope.QueueSheet(
    played: List<QueueEntry>,
    queue: List<QueueEntry>,
    shuffle: Boolean,
    actions: QueueActions,
    backdrop: HazeState,
    // Lists still being added, each shown as one quiet row after the songs
    // the player holds; the rest of the list shows once it is in.
    building: List<app.winters.octo.playback.BuildingRow> = emptyList(),
    // The queue past the songs the player holds, read as the list reaches it.
    past: QueuePast = QueuePast(),
) {
    val current = queue.firstOrNull()
    val upcoming = queue.drop(1)
    // The rows as drawn. A drag moves them at once; the queue catches up on drop.
    var rows by remember(upcoming) { mutableStateOf(upcoming) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = played.size)
    // The next page each way once the list shows its top or its end.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(atTop, past.head.size) { if (atTop) past.moreHead() }
    LaunchedEffect(atEnd, past.tail.size, past.tailCount) { if (atEnd && building.isEmpty()) past.moreTail() }
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = rows.indexOfFirst { it.key == from.key }
        val b = rows.indexOfFirst { it.key == to.key }
        if (a >= 0 && b >= 0) {
            rows = rows.toMutableList().apply { add(b, removeAt(a)) }
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    fun longPress(entry: QueueEntry, place: QueuePlace) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        actions.menu(entry, place)
    }

    // One line: "Up next" and what is to come, then a way back to the song
    // on and the rest of the tools in More.
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Up next", style = OctoType.section, color = OctoColors.TextPrimary, maxLines = 1)
        Text(
            queueSummary(rows, past.tailCount, past.tailMs),
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        QuietIconAction(OctoIcons.Playback, "Show the song playing", enabled = current != null, onClick = { scope.launch { listState.animateScrollToItem(played.size) } })
        HeaderMore(queueMore(upcoming.isNotEmpty(), played.isNotEmpty(), current != null, actions), quiet = true, backdrop = backdrop)
    }
    LazyColumn(
        Modifier.weight(1f, fill = false),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
    ) {
        // Played songs before the player's window, read as the list reaches them.
        items(past.head, key = { it.key }) { entry ->
            SongLine(
                entry,
                Modifier
                    .alpha(PLAYED_ALPHA)
                    .combinedClickable(onClickLabel = "Play", onClick = { past.play(entry) })
                    .padding(horizontal = 20.dp),
            )
        }
        items(played, key = { it.key }) { entry ->
            SongLine(
                entry,
                Modifier
                    .alpha(PLAYED_ALPHA)
                    .combinedClickable(
                        onClickLabel = "Play",
                        onLongClickLabel = "Song options",
                        onLongClick = { longPress(entry, QueuePlace.Played) },
                        onClick = { actions.play(entry.index) },
                    )
                    .padding(horizontal = 20.dp),
            )
        }
        if (current != null) {
            item(key = current.key) {
                GlazeInset(
                    fill = CurrentFill,
                    shape = RowShape,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                ) {
                    SongLine(
                        current,
                        Modifier
                            .combinedClickable(
                                onClickLabel = null,
                                onLongClickLabel = "Song options",
                                onLongClick = { longPress(current, QueuePlace.Current) },
                                onClick = {},
                            )
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (shuffle && rows.isNotEmpty()) {
            item(key = "shuffle-note") {
                Text(
                    "Turn off shuffle to reorder",
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        // What is to come, part by part. A single list with no name needs
        // no heading under "Up next".
        val runs = sourceRuns(rows) { it.source }
        val headed = runs.size > 1 || runs.singleOrNull()?.first.let { it != null && it != NoSource }
        runs.forEach { (source, run) ->
            if (headed) item(key = "part:${run.first().key}") { PartHeading(queueSourceTitle(source)) }
            items(run, key = { it.key }) { entry ->
                ReorderableItem(reorder, key = entry.key, enabled = !shuffle) { dragging ->
                    // Moves the song in the queue to the place of the one it landed on.
                    val drop = {
                        val landed = rows.indexOfFirst { it.key == entry.key }
                        val to = upcoming.getOrNull(landed)?.index
                        if (to != null && to != entry.index) actions.move(entry.index, to)
                    }
                    val swipe = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(
                        state = swipe,
                        enableDismissFromStartToEnd = false,
                        gesturesEnabled = !dragging,
                        onDismiss = {
                            rows = rows.filterNot { it.key == entry.key }
                            actions.remove(entry)
                        },
                        backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                    ) {
                        SongLine(
                            entry,
                            Modifier
                                .then(if (dragging) Modifier.elevation3(RowShape) else Modifier)
                                .background(RowFill, RowShape)
                                .combinedClickable(
                                    onClickLabel = "Play",
                                    onLongClickLabel = "Song options",
                                    onLongClick = { longPress(entry, QueuePlace.Upcoming) },
                                    onClick = { actions.play(entry.index) },
                                )
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
        // The songs after the player's window, read as the list reaches them:
        // tap to play, swipe to take out. While a list is being added the
        // rest waits for it.
        if (building.isEmpty()) {
            items(past.tail, key = { it.key }) { entry ->
                val swipe = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = false,
                    onDismiss = { actions.remove(entry) },
                    backgroundContent = { RemoveBackground(swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart, RowShape) },
                ) {
                    SongLine(
                        entry,
                        Modifier
                            .background(RowFill, RowShape)
                            .combinedClickable(onClickLabel = "Play", onClick = { past.play(entry) })
                            .padding(horizontal = 20.dp),
                    )
                }
            }
            val left = past.tailCount - past.tail.size
            if (left > 0) item(key = "tail-count") { TailLine(left) }
        }
        // A list still being added: one quiet row where its songs go.
        building.forEach { list ->
            item(key = "building:${list.listId}") { BuildingLine(list.play) }
        }
    }
}

// The quiet row under the songs read so far: how many more the queue holds.
@Composable
private fun TailLine(left: Int) {
    Text(
        tailWords(left),
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

// "1 more song", "240 more songs".
fun tailWords(left: Int): String = if (left == 1) "1 more song" else "$left more songs"

// The quiet row of a list still being added to the queue: a small spinner
// on the words' cap-height middle, as the Home copy card's sync line has.
@Composable
private fun BuildingLine(play: Boolean) {
    val captionCap = rememberCapHeightPx(OctoType.caption)
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Spinner(Modifier.alignBy { it.measuredHeight / 2 }, size = 12.dp, color = OctoColors.TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(
            buildingWords(play),
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.alignBy { capMiddle(it[FirstBaseline].toFloat(), captionCap).roundToInt() },
        )
    }
}

// What a list still being added says: a played list's rest, or a big add.
fun buildingWords(play: Boolean): String = if (play) "Adding the rest of the list" else "Adding songs to the queue"

// The heading over one part of what is to come.
@Composable
private fun PartHeading(title: String) {
    Text(
        title,
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

// What is to come: the count and length of the listener's own songs (those
// the player holds and, past them, the rest of the queue: `tailCount` songs
// playing `tailMs`), and how many Autoplay added after them. A summary too
// long for one line breaks only between facts ("40,500 songs ·" over
// "3,619 hr 38 min"), never inside one.
internal fun queueSummary(rows: List<QueueEntry>, tailCount: Int = 0, tailMs: Long = 0): String {
    val (auto, own) = rows.partition { it.autoplay }
    val facts = listOfNotNull(
        songs(own.size + tailCount),
        ((own.sumOf { it.durationMs } + tailMs) / 1000).toInt().asLength(),
        auto.size.takeIf { it > 0 }?.let { "$it from Autoplay" },
    )
    return factsLine(facts, " · ")
}

// What the queue's More menu holds, in order, and whether each can be done.
enum class QueueTool(val label: String) { Clear("Clear"), RemovePlayed("Remove played"), Save("Save as playlist") }

fun queueTools(upcoming: Boolean, played: Boolean, current: Boolean): List<Pair<QueueTool, Boolean>> = listOf(
    QueueTool.Clear to (upcoming || played),
    QueueTool.RemovePlayed to played,
    QueueTool.Save to current,
)

private fun queueMore(upcoming: Boolean, played: Boolean, current: Boolean, actions: QueueActions): List<MoreItem> =
    queueTools(upcoming, played, current).map { (tool, enabled) ->
        when (tool) {
            QueueTool.Clear -> MoreItem(tool.label, OctoIcons.Delete, enabled = enabled, destructive = true, onClick = actions.clear)
            QueueTool.RemovePlayed -> MoreItem(tool.label, OctoIcons.History, enabled = enabled, onClick = actions.removePlayed)
            QueueTool.Save -> MoreItem(tool.label, OctoIcons.AddToPlaylist, enabled = enabled, onClick = actions.save)
        }
    }

// A song's menu from the queue: play it next, take it out, or stop the
// music once it has played. The song that is on can only be the last one.
@Composable
private fun QueueRowMenu(shown: QueueMenuFor, model: PlayerViewModel, onDone: () -> Unit) {
    val entry = shown.entry
    GlassMenuPage(
        header = {
            GlassMenuHeader(entry.title, entry.artist) {
                Artwork(entry.artwork, 40.dp, shape = RoundedCornerShape(6.dp), outside = isOutsideLibrary(entry.trackId))
            }
            GlassMenuSeparator()
        },
    ) {
        if (shown.place != QueuePlace.Current) {
            GlassMenuAction(OctoIcons.PlayNext, "Play next", onClick = {
                model.playNextInQueue(entry)
                onDone()
            })
        }
        if (shown.place != QueuePlace.Played) {
            GlassMenuAction(OctoIcons.SleepTimer, "Stop after this song", onClick = {
                model.sleepAfter(entry)
                onDone()
            })
        }
        if (shown.place != QueuePlace.Current) {
            GlassMenuSeparator()
            GlassMenuAction(OctoIcons.Delete, "Remove from queue", onClick = {
                model.removeFromQueue(entry)
                onDone()
            })
        }
    }
}

// A song: artwork, title and artist, an "Autoplay" label on a song Autoplay
// added, and a drag handle when it can move.
@Composable
private fun SongLine(entry: QueueEntry, modifier: Modifier = Modifier, handle: Modifier? = null) {
    Row(
        modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(entry.artwork, 44.dp, shape = RoundedCornerShape(6.dp), outside = isOutsideLibrary(entry.trackId))
        Column(Modifier.weight(1f)) {
            SongTitle(entry.title, explicit = entry.explicit, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
            Text(entry.artist, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.autoplay) Text("Autoplay", style = OctoType.caption, color = OctoColors.TextMuted)
        if (handle != null) DragHandle(handle)
    }
}
