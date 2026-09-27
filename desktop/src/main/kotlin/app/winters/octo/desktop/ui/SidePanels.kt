package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.server.Connection
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Separator
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsSource
import app.winters.octo.lyrics.lineAt
import app.winters.octo.lyrics.parseLyricsText
import app.winters.octo.lyrics.serverLyrics
import app.winters.octo.subsonic.Song
import dev.chrisbanes.haze.HazeState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale

val SidePanelWidth = 340.dp

// The panel on the right: the queue or the lyrics, in glass.
@Composable
fun SidePanelView(app: AppState, panel: SidePanel, backdrop: HazeState, modifier: Modifier = Modifier) {
    FloatingGlaze(backdrop, modifier, shape = SidebarShape) {
        Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(if (panel == SidePanel.Queue) "Queue" else "Lyrics", OctoType.headline, modifier = Modifier.weight(1f))
                if (panel == SidePanel.Queue) TextAction("Clear", { app.player.clear() }, enabled = app.player.state.value.queue.isNotEmpty())
                IconAction(OctoIcons.Close, "Close", { app.toggleSidePanel(panel) }, size = 32.dp, iconSize = 18.dp)
            }
            when (panel) {
                SidePanel.Queue -> QueueList(app)
                SidePanel.Lyrics -> LyricsView(app)
            }
        }
    }
}

// The queue: the song playing, then the songs to come in the order they
// will play. Drag a song by its handle to move it; the cross takes it out;
// a double click plays it now.
@Composable
private fun QueueList(app: AppState) {
    val state by app.player.state.collectAsState()
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val upcoming = app.player.state.value.upcoming
        val a = upcoming.indexOfFirst { it.key == from.key }
        val b = upcoming.indexOfFirst { it.key == to.key }
        if (a >= 0 && b >= 0) app.player.moveUpcoming(a, b)
    }
    val current = state.current
    if (current == null) {
        Txt("Nothing is queued. Play something, or right-click a song and pick Add to queue.", OctoType.bodySmall, OctoColors.TextMuted, Modifier.padding(18.dp), maxLines = 4)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
        item(key = "now-title") { PanelHeading("Now playing") }
        item(key = "now") { QueueRow(app, current, playing = true, onRemove = null) }
        if (state.upcoming.isNotEmpty()) item(key = "next-title") { PanelHeading("Up next") }
        items(state.upcoming, key = { it.key }) { entry ->
            ReorderableItem(reorder, key = entry.key) { dragging ->
                QueueRow(
                    app,
                    entry,
                    playing = false,
                    onRemove = { app.player.remove(entry.key) },
                    handle = Modifier.draggableHandle(),
                    lifted = dragging,
                )
            }
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    Txt(text, OctoType.caption, OctoColors.TextMuted, Modifier.padding(start = 10.dp, top = 10.dp, bottom = 6.dp))
}

private class Clicks {
    var at = 0L
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QueueRow(
    app: AppState,
    entry: QueueEntry,
    playing: Boolean,
    onRemove: (() -> Unit)?,
    handle: Modifier = Modifier,
    lifted: Boolean = false,
) {
    val song = entry.song
    val clicks = remember { Clicks() }
    val pointer = LocalPointer.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .hoverLift(RoundedCornerShape(10.dp), clickable = false, lifted = lifted)
            .onRightClick { app.popups.showAt(pointer.point) { close -> SongMenu(app, listOf(song), close) } }
            .onPointerEvent(PointerEventType.Press) {
                val now = System.currentTimeMillis()
                if (now - clicks.at < 400 && !playing) app.player.skipTo(entry.key)
                clicks.at = now
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(handle.pointerHoverIcon(PointerIcon.Hand)) {
            Cover(song.coverArt, Modifier.size(38.dp), shape = RoundedCornerShape(6.dp), placeholder = OctoIcons.Songs)
        }
        Column(Modifier.weight(1f)) {
            Txt(song.title, OctoType.label, if (playing) OctoColors.Accent else OctoColors.TextPrimary)
            Txt(song.displayArtist ?: song.artist.orEmpty(), OctoType.caption, OctoColors.TextMuted)
        }
        Txt(lengthText(song.duration), OctoType.caption, OctoColors.TextMuted)
        if (onRemove != null) IconAction(OctoIcons.Close, "Take out of the queue", onRemove, size = 26.dp, iconSize = 14.dp, tint = OctoColors.TextSecondary)
    }
}

// The lyrics of the song playing, from the server: the synced line being
// sung lit and kept in view, or plain text as it is.
@Composable
private fun LyricsView(app: AppState) {
    val state by app.player.state.collectAsState()
    val song = state.current?.song
    val connection = app.connection
    if (song == null || connection == null) {
        Txt("Play a song to see its lyrics here.", OctoType.bodySmall, OctoColors.TextMuted, Modifier.padding(18.dp))
        return
    }
    val loaded = rememberLoad(song.id) { lyricsFor(connection, song) }
    loaded.show(Modifier.padding(horizontal = 18.dp), loadingText = "Finding lyrics") { lyrics ->
        if (lyrics == null || lyrics.isEmpty) {
            Txt(if (lyrics?.instrumental == true) "This song has no words." else "No lyrics for this song.", OctoType.bodySmall, OctoColors.TextMuted, Modifier.padding(18.dp))
            return@show
        }
        val position by rememberPosition(app.player)
        val lines = lyrics.lines
        val at = if (lyrics.synced) lines.lineAt(position) else -1
        val list = rememberLazyListState()
        LaunchedEffect(at) { if (at >= 0) list.animateScrollToItem((at - 3).coerceAtLeast(0)) }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(18.dp)) {
            itemsIndexed(lines) { index, line ->
                val past = lyrics.synced && index < at
                Txt(
                    line.text.ifBlank { "..." },
                    if (index == at) OctoType.section else OctoType.body,
                    if (index == at || !lyrics.synced) OctoColors.TextPrimary else OctoColors.TextSecondary,
                    Modifier
                        .padding(vertical = 6.dp)
                        .alpha(if (past) 0.5f else 1f)
                        .then(if (lyrics.synced) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { app.player.seekTo(line.startMs) } else Modifier),
                    maxLines = 4,
                )
            }
        }
    }
}

// Asks the server for lyrics: by song id where it lists songLyrics, with
// the synced and word-timed kinds, or else by artist and title.
suspend fun lyricsFor(connection: Connection, song: Song): Lyrics? {
    val client = connection.client
    if (connection.lyricsByIdOn) {
        serverLyrics(client.lyricsBySongId(song.id, enhanced = connection.supports("songLyrics", 2)), Locale.getDefault().language)?.let { return it }
    }
    val text = client.lyrics(song.artist.orEmpty(), song.title) ?: return null
    return parseLyricsText(text, LyricsSource.Server)
}

@Composable
fun PanelSeparator() = Separator(Modifier.padding(vertical = 6.dp))
