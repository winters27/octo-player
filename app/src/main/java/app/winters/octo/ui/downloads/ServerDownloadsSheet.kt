package app.winters.octo.ui.downloads

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.data.ServerDownloads
import app.winters.octo.data.SheetView
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.subsonic.AcquisitionEvent
import app.winters.octo.subsonic.FIND_OFF
import app.winters.octo.subsonic.FIND_SEARCHING
import app.winters.octo.subsonic.FindSourceState
import app.winters.octo.subsonic.FoundCandidate
import app.winters.octo.subsonic.FoundSongs
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.QuietButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// The sheet's title. "Downloads" is the phone's own (songs kept on it), so
// the server's are named for where they happen.
const val SERVER_DOWNLOADS = "Server downloads"

@HiltViewModel
class ServerDownloadsViewModel @Inject constructor(val downloads: ServerDownloads) : ViewModel()

// The server downloads sheet, over everything: every download the server is
// doing for this person or did lately, each opening to its log, and Find
// songs. Opened from the Library page, a song's add button while it is on
// its way, or a song's menu.
@Composable
fun ServerDownloadsHost(vm: ServerDownloadsViewModel = hiltViewModel()) {
    val model = vm.downloads
    val view by model.view.collectAsStateWithLifecycle()
    GlassSheet(visible = view != null, onDismiss = model::close) {
        when (val now = view) {
            SheetView.List, null -> DownloadList(model)
            is SheetView.Log -> DownloadLog(model, now.key)
            is SheetView.Find -> FindSongs(model, now)
        }
    }
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.DownloadList(model: ServerDownloads) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(SERVER_DOWNLOADS, style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            drawerSummary(rows).takeIf(String::isNotEmpty)?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
        }
        if (rows.any { it.finished }) QuietButton("Clear finished") { model.clear() }
    }
    if (rows.isEmpty()) {
        Text(if (loaded) DOWNLOADS_EMPTY else "Asking the server", style = OctoType.bodySmall, color = OctoColors.TextMuted, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.key }) { row ->
            // A higher quality copy still waiting its turn has no log yet; a
            // finished one without its log can still be looked for again.
            DownloadLine(row, model.artwork(row.coverArt)) {
                row.logKey?.let(model::showLog) ?: if (row.finished) row.findId?.let { model.find(it, row.title) } else Unit
            }
        }
    }
}

@Composable
private fun DownloadLine(row: DownloadRow, art: String?, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "${row.title}, ${row.artist}"
                stateDescription = row.status
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(art, 48.dp, shape = RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                kindLabel(row.kind)?.let { KindMark(it) }
            }
            if (row.artist.isNotBlank()) Text(row.artist, style = OctoType.bodySmall, color = OctoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.status, style = OctoType.caption, color = if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextMuted, maxLines = 2)
        }
        when {
            !row.finished -> Ring(row.fraction, 22.dp)
            row.phase == RowPhase.Done -> Mark(OctoIcons.Downloaded, OctoColors.TextSecondary)
            else -> Mark(OctoIcons.Info, OctoColors.SignalOrange)
        }
    }
}

@Composable
private fun KindMark(text: String) {
    Text(
        text.uppercase(),
        style = OctoType.caption,
        color = OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Chip).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------------------
// One download's log
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.DownloadLog(model: ServerDownloads, key: String) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val log by model.log.collectAsStateWithLifecycle()
    val problem by model.logProblem.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.logKey == key } ?: log?.let { rowOf(it) }
    val events = log?.event.orEmpty()
    Bar(back = "Back to $SERVER_DOWNLOADS", onBack = model::showList) {
        row?.findId?.let { id -> QuietButton(FIND_SONGS) { model.find(id, row.title, key) } }
        if (row?.finished == true) QuietButton("Clear") {
            model.clear(row.key)
            model.showList()
        }
    }
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (row != null) item(key = "head") { LogHead(row, model.artwork(row.coverArt)) }
        problem?.let { item(key = "problem") { Text(it, style = OctoType.caption, color = OctoColors.SignalOrange, modifier = Modifier.padding(vertical = 6.dp)) } }
        item(key = "rule") { Rule() }
        if (log == null && problem == null) item(key = "wait") { Ring(null, 22.dp) }
        itemsIndexed(events, key = { index, _ -> index }) { index, line -> LogLine(line, last = index == events.lastIndex) }
    }
}

@Composable
private fun LogHead(row: DownloadRow, art: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(art, 64.dp, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = OctoType.headline, color = OctoColors.TextPrimary, maxLines = 2)
            if (row.artist.isNotBlank()) Text(row.artist, style = OctoType.bodySmall, color = OctoColors.TextSecondary)
            row.album?.takeIf(String::isNotBlank)?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!row.finished) Ring(row.fraction, 16.dp)
            Text(row.status, style = OctoType.bodySmall, color = if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary)
        }
        if (row.phase == RowPhase.Downloading) ProgressLine(row.fraction)
        val facts = listOfNotNull(row.quality, row.source, kindLabel(row.kind))
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = OctoType.caption, color = OctoColors.TextMuted)
    }
}

// One line of the log, on the thread that joins them.
@Composable
private fun LogLine(line: AcquisitionEvent, last: Boolean) {
    val mark = markOf(line.logKind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(26.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(markFill(mark)), contentAlignment = Alignment.Center) {
                Icon(painterResource(markIcon(mark)), contentDescription = null, tint = markTint(mark), modifier = Modifier.size(14.dp))
            }
            if (!last) Box(Modifier.width(1.dp).weight(1f).background(Thread))
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(line.text, style = OctoType.bodySmall, color = if (mark == LogMark.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                Text(logTime(line.at), style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(start = 8.dp))
            }
            line.detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
            line.candidate.forEach { CopyLine(it, cover = null, compact = true) }
        }
    }
}

@DrawableRes
private fun markIcon(mark: LogMark): Int = when (mark) {
    LogMark.Queued -> OctoIcons.History
    LogMark.Search -> OctoIcons.Search
    LogMark.Found -> OctoIcons.Songs
    LogMark.Try -> OctoIcons.Download
    LogMark.Transfer -> OctoIcons.Downloading
    LogMark.Check -> OctoIcons.Check
    LogMark.Tags -> OctoIcons.Rename
    LogMark.Cover -> OctoIcons.Album
    LogMark.Lyrics -> OctoIcons.Lyrics
    LogMark.Library -> OctoIcons.Library
    LogMark.Done -> OctoIcons.Downloaded
    LogMark.Failed -> OctoIcons.Close
    LogMark.Note -> OctoIcons.Info
}

private fun markTint(mark: LogMark): Color = when (mark) {
    LogMark.Done -> OctoColors.SignalGreen
    LogMark.Failed -> OctoColors.SignalOrange
    else -> OctoColors.TextSecondary
}

private fun markFill(mark: LogMark): Color = when (mark) {
    LogMark.Done -> OctoColors.SignalGreen.copy(alpha = 0.16f)
    LogMark.Failed -> OctoColors.SignalOrange.copy(alpha = 0.16f)
    else -> Chip
}

// ---------------------------------------------------------------------------
// Find songs
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.FindSongs(model: ServerDownloads, view: SheetView.Find) {
    val found by model.found.collectAsStateWithLifecycle()
    val problem by model.findProblem.collectAsStateWithLifecycle()
    val picked by model.picked.collectAsStateWithLifecycle()
    val searching = found?.searching != false && problem == null
    // A copy waiting for "Replace it", for a song already in the library,
    // with the search it came from. A new search, Search again too, asks
    // nothing of the old one's copies.
    var asking by remember(view, found?.id) { mutableStateOf<Pair<String, FoundCandidate>?>(null) }
    Bar(back = if (view.from != null) "Back to the log" else "Back to $SERVER_DOWNLOADS", onBack = { view.from?.let(model::showLog) ?: model.showList() }) {
        if (!searching) {
            QuietButton("Search again", {
                asking = null
                model.searchAgain()
            })
        }
    }
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item(key = "head") { FindHead(view, found, model::artwork) }
        found?.source?.let { sources -> item(key = "sources") { Sources(sources) } }
        listOfNotNull(problem, found?.error, picked?.takeIf { !it.queued }?.detail).forEachIndexed { index, words ->
            item(key = "problem$index") { Text(words, style = OctoType.caption, color = OctoColors.SignalOrange, modifier = Modifier.padding(vertical = 6.dp)) }
        }
        asking?.takeIf { (search, _) -> search == found?.id }?.let { (search, copy) ->
            item(key = "ask") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Chip).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Replace your copy with ${candidateFacts(copy).take(2).joinToString(", ")}?", style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                    Text(FIND_REPLACES, style = OctoType.caption, color = OctoColors.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlazeButton("Cancel", { asking = null }, size = ButtonSize.Small)
                        GlazeButton("Replace it", {
                            asking = null
                            model.pick(search, copy)
                        }, size = ButtonSize.Small, icon = painterResource(OctoIcons.Lossless))
                    }
                }
            }
        }
        item(key = "rule") { Rule() }
        val copies = found?.candidate.orEmpty()
        if (copies.isEmpty() && !searching) item(key = "none") { Text("Nothing found on your sources.", style = OctoType.bodySmall, color = OctoColors.TextMuted) }
        // The list still grows while sources answer, so a copy is picked
        // only once it is done.
        if (copies.isNotEmpty() && found?.searching == true) {
            item(key = "wait") { Text(FIND_PICK_WAIT, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        val shown = found
        items(copies, key = ::copyKey) { copy ->
            CopyLine(copy, model.artwork(shown?.song?.coverArt), compact = false, onPick = if (shown != null && canPick(shown, copy)) {
                {
                    if (shown.song.libraryId != null) asking = shown.id to copy else model.pick(shown.id, copy)
                }
            } else {
                null
            })
        }
    }
}

@Composable
private fun FindHead(view: SheetView.Find, found: FoundSongs?, artwork: (String?) -> String?) {
    val song = found?.song
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(artwork(song?.coverArt ?: view.id), 64.dp, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(FIND_SONGS.uppercase(), style = OctoType.caption, color = OctoColors.TextMuted)
            Text(song?.title?.takeIf(String::isNotBlank) ?: view.title, style = OctoType.headline, color = OctoColors.TextPrimary, maxLines = 2)
            song?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = OctoType.bodySmall, color = OctoColors.TextSecondary) }
        }
    }
    if (song != null) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ownedCopyText(song.quality, song.size)?.let { Text(it, style = OctoType.bodySmall, color = OctoColors.TextPrimary) }
            Text(if (song.libraryId != null) FIND_REPLACES else FIND_DOWNLOADS, style = OctoType.caption, color = OctoColors.TextMuted)
        }
    }
}

@Composable
private fun Sources(sources: List<FindSourceState>) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (source.state) {
                    FIND_SEARCHING -> Spinner(size = 14.dp, color = OctoColors.TextSecondary)
                    FIND_OFF -> Mark(OctoIcons.Close, OctoColors.TextMuted, 14.dp)
                    "failed" -> Mark(OctoIcons.Info, OctoColors.SignalOrange, 14.dp)
                    else -> Mark(OctoIcons.Check, OctoColors.TextSecondary, 14.dp)
                }
                val words = if (source.state == FIND_SEARCHING) "Searching" else source.text ?: if (source.state == "failed") "Could not search" else "Done"
                Text("${source.name}: $words", style = OctoType.caption, color = if (source.state == FIND_OFF) OctoColors.TextMuted else OctoColors.TextSecondary)
            }
        }
    }
}

// One copy: what it is, where it sits, its facts, and whether Octo would
// take it. On Find songs it can be got.
@Composable
private fun CopyLine(copy: FoundCandidate, cover: String?, compact: Boolean, onPick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compact) Artwork(cover, 44.dp, shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(candidateTitle(copy), style = if (compact) OctoType.caption else OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = copy.album?.takeIf(String::isNotBlank) ?: copy.folder
            where?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(candidateFacts(copy).joinToString(" · "), style = OctoType.caption, color = OctoColors.TextSecondary, maxLines = 3)
            candidateVerdict(copy)?.let { Text(it, style = OctoType.caption, color = if (copy.rank != null) OctoColors.TextSecondary else OctoColors.TextMuted, maxLines = 2) }
        }
        if (onPick != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Get this copy", onClick = onPick)
                    .semantics { contentDescription = "Get this copy" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(OctoIcons.Download), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Parts
// ---------------------------------------------------------------------------

// The sheet's top line inside a log or Find songs: back, then its actions.
@Composable
private fun Bar(back: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(OctoIcons.ChevronBack), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Thread))
}

@Composable
private fun Mark(@DrawableRes icon: Int, tint: Color, size: Dp = 18.dp) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

// A ring filling as a download comes in; turning while nobody knows how far.
@Composable
private fun Ring(fraction: Float?, size: Dp) {
    if (fraction == null) {
        Spinner(size = size, color = OctoColors.TextSecondary)
        return
    }
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, inset, box, style = Stroke(stroke))
        drawArc(Color.White, -90f, 360f * fraction.coerceIn(0f, 1f), false, inset, box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// A thin line filling as the file comes in.
@Composable
private fun ProgressLine(fraction: Float?) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Track)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(OctoColors.TextPrimary))
    }
}

private val Chip = Color.White.copy(alpha = 0.08f)
private val Thread = Color.White.copy(alpha = 0.10f)
private val Track = Color.White.copy(alpha = 0.14f)
