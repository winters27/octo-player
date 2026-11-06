package app.winters.octo.ui.downloads

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.data.ServerDownloads
import app.winters.octo.data.SheetView
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoInk
import app.winters.octo.design.OctoType
import app.winters.octo.design.SheetFill
import app.winters.octo.design.Spinner
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.subsonic.AcquisitionEvent
import app.winters.octo.subsonic.FIND_OFF
import app.winters.octo.subsonic.FIND_SEARCHING
import app.winters.octo.subsonic.FindSourceState
import app.winters.octo.subsonic.FoundCandidate
import app.winters.octo.subsonic.FoundSongs
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.family.MORE_BELOW
import kotlinx.coroutines.launch
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
    Box(Modifier.fillMaxSize()) {
        GlassSheet(visible = view != null, onDismiss = model::close) {
            when (val now = view) {
                SheetView.List, null -> DownloadList(model)
                is SheetView.Log -> DownloadLog(model, now.key)
                is SheetView.Find -> FindSongs(model, now)
            }
        }
        ReplaceAnywaySheet(model, (view as? SheetView.Find)?.title)
    }
}

// The question before a copy that needs the person's word replaces the
// library's, in a sheet over Find songs: "Replace your copy?" and the
// song's whole title, the two copies side by side, then the server's
// sentence word for word. "Replace anyway" sends the pick again with
// anyway=true; "Keep mine", back, a tap off it or pulling it down sends
// nothing. The buttons stay in place under words that scroll, should they
// ever not fit.
@Composable
private fun ReplaceAnywaySheet(model: ServerDownloads, fallbackTitle: String?) {
    val question by model.question.collectAsStateWithLifecycle()
    val confirm = question?.takeIf { it.kind == AskKind.Confirm }
    // The last question stays drawn while its sheet slides away.
    var shown by remember { mutableStateOf(confirm) }
    LaunchedEffect(confirm) { confirm?.let { shown = it } }
    val asked = shown
    GlassSheet(visible = confirm != null, onDismiss = { confirm?.let(model::keepMine) }, scrolls = false) {
        if (asked != null) {
            val title = asked.title.ifBlank { fallbackTitle.orEmpty() }
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { paneTitle = REPLACE_TITLE },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(REPLACE_TITLE, style = OctoType.headline, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
                    if (title.isNotBlank()) Text(title, style = OctoType.bodySmall, color = OctoColors.TextSecondary)
                }
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val lines = compareLines(asked.compare)
                        if (lines.isNotEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Chip).padding(14.dp).semantics(mergeDescendants = true) {},
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                lines.forEachIndexed { index, line ->
                                    Text(line, style = OctoType.bodySmall, color = if (index == 0) OctoColors.TextSecondary else OctoColors.TextPrimary)
                                }
                            }
                        }
                        Text(asked.words, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                    }
                    MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlazeButton(KEEP_MINE, { model.keepMine(asked) }, size = ButtonSize.Medium, modifier = Modifier.weight(1f))
                    AccentButton(REPLACE_ANYWAY, onClick = model::replaceAnyway, size = ButtonSize.Medium, fill = OctoColors.Accent, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// A soft fade at the bottom of words that scroll, with a "More below"
// button that scrolls to their end; gone once the end shows.
@Composable
private fun MoreBelow(scroll: ScrollState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(octoTween(motion, OctoDuration.Card)), exit = fadeOut(octoTween(motion, OctoDuration.Card))) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, SheetFill))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .padding(bottom = 4.dp)
                    .background(OctoColors.AccentSelected, PillShape)
                    .border(1.dp, Thread, PillShape)
                    .clickable(role = Role.Button, onClickLabel = MORE_BELOW) { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(MORE_BELOW, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                Icon(painterResource(OctoIcons.Collapse), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private val PillShape = RoundedCornerShape(50)

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

@Composable
private fun ColumnScope.DownloadList(model: ServerDownloads) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val keeping by model.keeping.collectAsStateWithLifecycle()
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
            Column {
                val keep = keepAnywayLabel(row)
                DownloadLine(row, model.artwork(row.coverArt), keepBelow = keep != null) {
                    row.logKey?.let(model::showLog) ?: if (row.finished) row.findId?.let { model.find(it, row.title) } else Unit
                }
                if (keep != null) row.logKey?.let { key -> KeepAnywayLine(keep, keeping[key]) { model.keepAnyway(key) } }
            }
        }
    }
}

// `keepBelow`: a Keep anyway line follows, so the row's own bottom edge
// sits close above it.
@Composable
private fun DownloadLine(row: DownloadRow, art: String?, keepBelow: Boolean = false, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "${row.title}, ${row.artist}"
                stateDescription = row.status
            }
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (keepBelow) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(art, LineArt, shape = RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                kindLabel(row.kind)?.let { KindMark(it) }
            }
            if (row.artist.isNotBlank()) Text(row.artist, style = OctoType.bodySmall, color = OctoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.status, style = OctoType.caption, color = if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextMuted, maxLines = 2)
        }
        when {
            // Waiting for the person: the accent dot that says something
            // needs you, centered in the ring's place.
            waitsForYou(row) -> Box(Modifier.size(LineMark), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(OctoColors.Accent, CircleShape))
            }
            !row.finished -> Ring(row.fraction, LineMark)
            row.phase == RowPhase.Done -> Mark(OctoIcons.Downloaded, OctoColors.TextSecondary)
            else -> Mark(OctoIcons.Info, OctoColors.SignalOrange)
        }
    }
}

// A held download's Keep anyway, under its row's words and lined up with
// them, set like the artist line above it: dimmed while it is sent, and the
// server's words under it when it said no, wrapping where the row's words
// do, short of the ring. The row above never moves for it.
@Composable
private fun KeepAnywayLine(label: String, keeping: Keeping?, onKeep: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = LineText, end = LineTextEnd, bottom = 2.dp)) {
        // The button's own inset pulled back, so its words start where the row's do.
        Box(Modifier.offset(x = -QuietInset)) {
            QuietButton(label, enabled = keeping?.sending != true, style = OctoType.bodySmall, onClick = onKeep)
        }
        keeping?.error?.let { KeepRefusal(it, Modifier.padding(bottom = 6.dp)) }
    }
}

// The server's words when it would not keep a download anyway.
@Composable
private fun KeepRefusal(words: String, modifier: Modifier = Modifier) {
    Text(
        words,
        style = OctoType.caption,
        color = OctoColors.SignalOrange,
        maxLines = 2,
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    )
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
    val keeping by model.keeping.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.logKey == key } ?: log?.let { rowOf(it) }
    // Offered while the log's own reading of the download says so.
    val keep = log?.let { keepAnywayLabel(rowOf(it)) }
    val keepState = keeping[key]
    val events = log?.event.orEmpty()
    val running = row?.finished == false
    // Waiting for the person, its newest line is still.
    val busy = running && row?.let(::waitsForYou) != true
    val list = rememberLazyListState()
    // A running download opens on its newest step and keeps it in view; a
    // finished one opens at its start. Scrolling up lets go; back at the
    // end, or "Latest step", follows again.
    var follow by remember(key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(key, row != null) { if (row != null && follow == null) follow = !row.finished }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && follow != null) follow = !list.canScrollForward
        }
    }
    // By the line's own place: the list has not laid the new lines out yet.
    LaunchedEffect(events.size, follow) {
        if (follow == true && events.isNotEmpty()) list.animateScrollToItem(events.lastIndex)
    }
    Bar(LOG_TITLE, back = "Back to $SERVER_DOWNLOADS", onBack = model::showList) {
        row?.let(::logFindId)?.let { id -> QuietButton(FIND_SONGS) { model.find(id, row.title, key) } }
        keep?.let { QuietButton(it, enabled = keepState?.sending != true) { model.keepAnyway(key) } }
        if (row?.finished == true) QuietButton("Clear") {
            model.clear(row.key)
            model.showList()
        }
    }
    // The server's words right under the bar whose Keep anyway it said no to.
    if (keep != null) keepState?.error?.let { KeepRefusal(it, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) }
    // The song and where it is stay put; only the steps scroll.
    if (row != null) LogHead(row, events, model.artwork(row.coverArt))
    problem?.let { Text(it, style = OctoType.caption, color = OctoColors.SignalOrange, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
    Rule(Modifier.padding(top = 14.dp))
    Box(Modifier.weight(1f, fill = false)) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp)) {
            if (log == null && problem == null) item(key = "wait") { Ring(null, 22.dp) }
            itemsIndexed(events, key = { index, _ -> index }) { index, line ->
                LogLine(line, last = index == events.lastIndex, live = busy && index == events.lastIndex)
            }
        }
        if (follow == false && running && list.canScrollForward) {
            GlazeButton(
                "Latest step",
                { follow = true },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                size = ButtonSize.Small,
                icon = painterResource(OctoIcons.Downloading),
            )
        }
    }
}

// The song, the steps it walks with the one it is on, and the copy being
// fetched.
@Composable
private fun LogHead(row: DownloadRow, events: List<AcquisitionEvent>, art: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(art, 56.dp, shape = RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = OctoType.headline, color = OctoColors.TextPrimary, maxLines = 2)
                val by = listOfNotNull(row.artist.takeIf(String::isNotBlank), row.album?.takeIf(String::isNotBlank))
                if (by.isNotEmpty()) Text(by.joinToString(" · "), style = OctoType.bodySmall, color = OctoColors.TextSecondary, maxLines = 2)
            }
        }
        Steps(row, stepOf(row, events))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.status, style = OctoType.bodySmall, color = if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary)
            val facts = listOfNotNull(row.quality, row.source, kindLabel(row.kind))
            if (facts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { facts.forEach { Tag(it) } }
        }
    }
}

// The four steps as a strip: done ones filled, the one it is on filling
// with the file while it downloads (breathing while nobody can tell how
// far, still while it waits for the person), and the ones still to come
// faint.
@Composable
private fun Steps(row: DownloadRow, at: Int) {
    val failed = row.phase == RowPhase.Failed
    val motion = motionScale()
    val pulse = if (row.finished || row.fraction != null || waitsForYou(row) || motion.distance == 0f) 1f else rememberPulse()
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = if (at >= DownloadStep.entries.size) "Every step done" else "Step ${at + 1} of ${DownloadStep.entries.size}, ${DownloadStep.entries[at].label}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DownloadStep.entries.forEach { step ->
            val index = step.ordinal
            val target = when {
                index < at -> 1f
                index > at -> 0f
                failed -> 1f
                row.phase == RowPhase.Downloading -> row.fraction ?: 0.5f
                else -> 0.5f
            }
            val fill = when {
                failed && index == at -> OctoColors.SignalOrange
                row.phase == RowPhase.Done -> OctoColors.SignalGreen
                else -> OctoColors.TextPrimary
            }
            val shown by animateFloatAsState(target, octoTween(motion, OctoDuration.Neutral), label = "step")
            val alpha = if (index == at && !row.finished) pulse else 1f
            val track = Track
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val round = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = round)
                    if (shown > 0f) drawRoundRect(fill.copy(alpha = alpha), size = Size(size.width * shown, size.height), cornerRadius = round)
                }
                Text(
                    step.label,
                    style = OctoType.caption,
                    maxLines = 1,
                    color = when {
                        failed && index == at -> OctoColors.SignalOrange
                        index == at && !row.finished -> OctoColors.TextPrimary
                        index < at || row.phase == RowPhase.Done -> OctoColors.TextSecondary
                        else -> OctoColors.TextMuted
                    },
                )
            }
        }
    }
}

// A slow breath, for the step whose end nobody can tell yet.
@Composable
private fun rememberPulse(): Float {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    return alpha
}

// A small fact in a soft capsule: "FLAC 16-bit 44.1 kHz", "Soulseek".
@Composable
private fun Tag(text: String) {
    Text(text, style = OctoType.caption, color = OctoColors.TextSecondary, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Chip).padding(horizontal = 8.dp, vertical = 2.dp))
}

// One line of the log, on the thread that joins them. The newest line of a
// running download turns while it waits.
@Composable
private fun LogLine(line: AcquisitionEvent, last: Boolean, live: Boolean) {
    val mark = markOf(line.logKind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (live) LiveFill else markFill(mark)), contentAlignment = Alignment.Center) {
                if (live && mark != LogMark.Done && mark != LogMark.Failed) {
                    Spinner(size = 13.dp, color = OctoColors.TextPrimary)
                } else {
                    Icon(painterResource(markIcon(mark)), contentDescription = null, tint = markTint(mark), modifier = Modifier.size(13.dp))
                }
            }
            if (!last) Box(Modifier.width(1.dp).weight(1f).background(Thread))
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    line.text,
                    style = OctoType.bodySmall,
                    fontWeight = if (live) FontWeight.SemiBold else null,
                    color = if (mark == LogMark.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
                Text(logTime(line.at), style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(start = 8.dp, top = 3.dp))
            }
            line.detail?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
            if (line.candidate.isNotEmpty()) Copies(line.candidate)
        }
    }
}

// The copies a search offered, on one soft card: the first few, then the
// rest on asking.
@Composable
private fun Copies(copies: List<FoundCandidate>) {
    var all by remember(copies) { mutableStateOf(false) }
    val shown = if (all) copies else copies.take(LOG_COPIES_SHOWN)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Card).padding(vertical = 2.dp)) {
        shown.forEachIndexed { index, copy ->
            if (index > 0) Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(Thread))
            CopyLine(copy, compact = true)
        }
        if (copies.size > LOG_COPIES_SHOWN) {
            QuietButton(if (all) "Show fewer" else "Show all ${copies.size}") { all = !all }
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

@Composable
private fun markTint(mark: LogMark): Color = when (mark) {
    LogMark.Done -> OctoColors.SignalGreen
    LogMark.Failed -> OctoColors.SignalOrange
    else -> OctoColors.TextSecondary
}

@Composable
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
    val asksFirst by model.asksFirst.collectAsStateWithLifecycle()
    val question by model.question.collectAsStateWithLifecycle()
    val replacing by model.replacing.collectAsStateWithLifecycle()
    val rows by model.rows.collectAsStateWithLifecycle()
    val searching = found?.searching != false && problem == null
    // On an older server: a copy waiting for "Replace it", for a song
    // already in the library, with the search it came from. A new search,
    // Search again too, asks nothing of the old one's copies. A server that
    // asks first says itself when to ask, in `question`.
    var asking by remember(view, found?.id) { mutableStateOf<Pair<String, FoundCandidate>?>(null) }
    Bar(FIND_SONGS, back = if (view.from != null) "Back to the log" else "Back to $SERVER_DOWNLOADS", onBack = { view.from?.let(model::showLog) ?: model.showList() }) {
        if (!searching) {
            QuietButton("Search again", {
                asking = null
                model.searchAgain()
            })
        }
    }
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item(key = "head") { FindHead(view, found, asksFirst, model::artwork) }
        found?.source?.let { sources -> item(key = "sources") { Sources(sources) } }
        listOfNotNull(problem, found?.error, picked?.takeIf { !it.queued }?.detail).forEachIndexed { index, words ->
            item(key = "problem$index") { Text(words, style = OctoType.caption, color = OctoColors.SignalOrange, modifier = Modifier.padding(vertical = 6.dp)) }
        }
        // The usual question for a better copy: asked here on an older
        // server, or as the server answered on one that asks first.
        val ready = question?.takeIf { it.kind == AskKind.Ready && it.search == found?.id }
        val local = asking?.takeIf { (search, _) -> search == found?.id }
        if (ready != null || local != null) {
            val copy = ready?.copy ?: local!!.second
            item(key = "ask") {
                ReplaceItCard(
                    copy,
                    asksFirst,
                    no = { if (ready != null) model.keepMine(ready) else asking = null },
                    yes = {
                        if (ready != null) {
                            model.replaceAnyway()
                        } else {
                            asking = null
                            model.pick(local!!.first, copy)
                        }
                    },
                )
            }
        }
        item(key = "rule") { Rule() }
        // The library's copy looks like another take: said over the copies.
        takeLine(found?.song?.take)?.let { line ->
            item(key = "take") {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mark(OctoIcons.Info, OctoColors.TextSecondary, 16.dp)
                    Text(line, style = OctoType.caption, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
        val copies = found?.candidate.orEmpty()
        if (copies.isEmpty() && !searching) item(key = "none") { Text("Nothing found on your sources.", style = OctoType.bodySmall, color = OctoColors.TextMuted) }
        // The list still grows while sources answer, so a copy is picked
        // only once it is done.
        if (copies.isNotEmpty() && found?.searching == true) {
            item(key = "wait") { Text(FIND_PICK_WAIT, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        val shown = found
        // While a copy replaces the library's, its row says how far it is,
        // and no other copy of the song can be picked.
        val busy = shown?.song?.libraryId?.let { it in replacing } == true
        items(copies, key = ::copyKey) { copy ->
            val mine = replacing.values.firstOrNull { it.search == shown?.id && it.copyKey == copyKey(copy) }
            CopyLine(
                copy,
                compact = false,
                onPick = if (shown != null && !busy && canPick(shown, copy)) {
                    {
                        // A server that asks first is asked at once; an older one is asked here.
                        if (shown.song.libraryId != null && !asksFirst) asking = shown.id to copy else model.pick(shown.id, copy)
                    }
                } else {
                    null
                },
                replacing = mine?.let { replacingRow(it, rows) },
                replacingNow = mine != null,
            )
        }
    }
}

@Composable
private fun FindHead(view: SheetView.Find, found: FoundSongs?, asksFirst: Boolean, artwork: (String?) -> String?) {
    val song = found?.song
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(artwork(song?.coverArt ?: view.id), 64.dp, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song?.title?.takeIf(String::isNotBlank) ?: view.title, style = OctoType.headline, color = OctoColors.TextPrimary)
            song?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = OctoType.bodySmall, color = OctoColors.TextSecondary) }
        }
    }
    if (song != null) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ownedCopyText(song.quality, song.size)?.let { Text(it, style = OctoType.bodySmall, color = OctoColors.TextPrimary) }
            Text(if (song.libraryId != null) findReplaces(asksFirst) else FIND_DOWNLOADS, style = OctoType.caption, color = OctoColors.TextMuted)
        }
    }
}

// Each source as a small pill on one line, wrapping only when there are
// many: how it stands as a mark, its name, and a quiet word on what it
// found or why it could not search.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(sources: List<FindSourceState>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sources.forEach { source ->
            val (name, word) = sourcePill(source.name, source.state, source.text)
            Row(
                Modifier.clip(CircleShape).background(Chip).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (source.state) {
                    FIND_SEARCHING -> Spinner(size = 12.dp, color = OctoColors.TextSecondary)
                    FIND_OFF -> Mark(OctoIcons.Close, OctoColors.TextMuted, 12.dp)
                    FIND_FAILED -> Mark(OctoIcons.Info, OctoColors.SignalOrange, 12.dp)
                    else -> Mark(OctoIcons.Check, OctoColors.TextSecondary, 12.dp)
                }
                Text(name, style = OctoType.caption, color = if (source.state == FIND_OFF) OctoColors.TextMuted else OctoColors.TextPrimary, maxLines = 1)
                word?.let { Text(it, style = OctoType.caption, color = if (source.state == FIND_FAILED) OctoColors.SignalOrange else OctoColors.TextMuted) }
            }
        }
    }
}

// The state a source that could not search is in.
private const val FIND_FAILED = "failed"

// A source's pill: its name, and the quiet word after it: what it is doing,
// what it found, or why it could not search. Nothing more for a source done
// with nothing to say.
internal fun sourcePill(name: String, state: String?, text: String?): Pair<String, String?> = name to when (state) {
    FIND_SEARCHING -> "searching"
    FIND_OFF -> text ?: "off"
    FIND_FAILED -> text ?: "could not search"
    else -> text
}

// One copy: its kind on a badge, its title, where it sits, its facts, and
// whether Octo would take it. On Find songs it can be got; the song's
// cover is drawn once, over the list.
@Composable
private fun CopyLine(copy: FoundCandidate, compact: Boolean, onPick: (() -> Unit)? = null, replacing: DownloadRow? = null, replacingNow: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = if (compact) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                copyBadge(copy)?.let { Badge(it, lossless = isLosslessCopy(copy)) }
                vsYoursTag(copy)?.let { VsYours(it, vsYoursSpoken(copy).orEmpty()) }
                Text(candidateTitle(copy), style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            val where = copy.album?.takeIf(String::isNotBlank) ?: copy.folder
            where?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(badgeFacts(copy).joinToString(" · "), style = OctoType.caption, color = OctoColors.TextSecondary, maxLines = 2)
            // Octo's order for a copy it would try; why not, for one it would pass over.
            val verdict = copy.rank?.let(::rankWords) ?: candidateVerdict(copy)
            verdict?.let { Text(it, style = OctoType.caption, color = if (copy.rank == 1) OctoColors.TextPrimary else OctoColors.TextMuted, maxLines = 2) }
            if (replacingNow) {
                Row(
                    Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Ring(replacing?.fraction, 16.dp)
                    Text(replacingWords(replacing), style = OctoType.caption, color = OctoColors.TextPrimary)
                }
            }
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

// How a copy stands against the library's: a quiet outlined word beside
// its kind, read out in full.
@Composable
private fun VsYours(text: String, spoken: String) {
    Text(
        text,
        style = OctoType.caption,
        color = OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, Thread, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = spoken },
    )
}

// The usual question before a better copy replaces the library's, as a
// card over the copies.
@Composable
private fun ReplaceItCard(copy: FoundCandidate, asksFirst: Boolean, no: () -> Unit, yes: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Chip).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(replaceQuestion(copy), style = OctoType.bodySmall, color = OctoColors.TextPrimary)
        Text(findReplaces(asksFirst), style = OctoType.caption, color = OctoColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlazeButton("Cancel", no, size = ButtonSize.Small)
            GlazeButton("Replace it", yes, size = ButtonSize.Small, icon = painterResource(OctoIcons.Lossless))
        }
    }
}

// A copy's kind, small and boxed: "FLAC" a little brighter than "MP3".
@Composable
private fun Badge(text: String, lossless: Boolean) {
    Text(
        text,
        style = OctoType.caption,
        fontWeight = FontWeight.SemiBold,
        color = if (lossless) OctoColors.TextPrimary else OctoColors.TextSecondary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (lossless) LosslessChip else Chip).padding(horizontal = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// Parts
// ---------------------------------------------------------------------------

// What a log is called on the sheet's top line.
const val LOG_TITLE = "Download log"

// The sheet's top line inside a log or Find songs: back, the view's name,
// then its actions at the end. The name never gives way: when the actions
// do not fit beside it, they go on a line of their own under it, their
// words lined up with the name's.
@Composable
private fun Bar(title: String, back: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(BackSize).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(OctoIcons.ChevronBack), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(22.dp))
                }
                Text(title, style = OctoType.section, color = OctoColors.TextPrimary, maxLines = 1, softWrap = false, modifier = Modifier.semantics { heading() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp),
    ) { (name, tools), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val head = name.measure(loose)
        val beside = head.width + tools.maxIntrinsicWidth(constraints.maxHeight) <= width
        if (beside) {
            val line = tools.measure(loose.copy(maxWidth = (width - head.width).coerceAtLeast(0)))
            val height = maxOf(head.height, line.height)
            layout(width, height) {
                head.place(0, (height - head.height) / 2)
                line.place(width - line.width, (height - line.height) / 2)
            }
        } else {
            val indent = (BackSize - QuietInset).roundToPx()
            val line = tools.measure(loose.copy(maxWidth = (width - indent).coerceAtLeast(0)))
            layout(width, head.height + line.height) {
                head.place(0, 0)
                line.place(indent, head.height)
            }
        }
    }
}

// The sheet bar's back button.
private val BackSize = 44.dp

@Composable
private fun Rule(modifier: Modifier = Modifier.padding(vertical = 12.dp)) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Thread))
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
    val ink = OctoColors.TextPrimary
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(ink.copy(alpha = 0.18f), 0f, 360f, false, inset, box, style = Stroke(stroke))
        drawArc(ink, -90f, 360f * fraction.coerceIn(0f, 1f), false, inset, box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// A thin line filling as the file comes in.
@Composable
private fun ProgressLine(fraction: Float?) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Track)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(OctoColors.TextPrimary))
    }
}

private val Chip: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.08f)
private val Thread: Color @Composable @ReadOnlyComposable get() = OctoInk.Quaternary
private val Track: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.14f)
private val LosslessChip: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.16f)
private val Card: Color @Composable @ReadOnlyComposable get() = OctoInk.Quinary
private val LiveFill: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.14f)

// A list row's cover, and where its words start: past the row's edge, the
// cover and the gap.
private val LineArt = 48.dp
private val LineText = 20.dp + LineArt + 14.dp

// The ring or mark at a list row's end, and where the row's words stop
// short of it.
private val LineMark = 22.dp
private val LineTextEnd = 14.dp + LineMark + 20.dp

// How far a QuietButton's words sit inside its edge.
private val QuietInset = 10.dp
