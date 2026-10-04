package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.SeparatorColor
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.downloads.DownloadsModel
import app.winters.octo.desktop.downloads.DrawerView
import app.winters.octo.desktop.library.Cover
import app.winters.octo.subsonic.AcquisitionEvent
import app.winters.octo.subsonic.FIND_OFF
import app.winters.octo.subsonic.FIND_SEARCHING
import app.winters.octo.subsonic.FindSourceState
import app.winters.octo.subsonic.FoundCandidate
import app.winters.octo.subsonic.FoundSongs
import app.winters.octo.ui.downloads.DOWNLOADS_EMPTY
import app.winters.octo.ui.downloads.DownloadRow
import app.winters.octo.ui.downloads.FIND_DOWNLOADS
import app.winters.octo.ui.downloads.FIND_REPLACES
import app.winters.octo.ui.downloads.FIND_SONGS
import app.winters.octo.ui.downloads.LogMark
import app.winters.octo.ui.downloads.RowPhase
import app.winters.octo.ui.downloads.candidateFacts
import app.winters.octo.ui.downloads.candidateTitle
import app.winters.octo.ui.downloads.candidateVerdict
import app.winters.octo.ui.downloads.drawerSummary
import app.winters.octo.ui.downloads.kindLabel
import app.winters.octo.ui.downloads.logTime
import app.winters.octo.ui.downloads.markOf
import app.winters.octo.ui.downloads.ownedCopyText
import app.winters.octo.ui.downloads.rowOf

// The downloads drawer, as the side panel's Downloads tab: every download
// the server is doing for this person or did lately, each opening to its
// log, and Find songs, where a song's search runs again and one copy is
// picked. Open, it is asked about every two seconds.
@Composable
fun DownloadsPanel(app: AppState, modifier: Modifier = Modifier) {
    val model = app.downloads
    if (model == null || model.supported == false) {
        Txt(
            "This server does not keep a log of its downloads. An Octo server from October 2026 or later does.",
            DesktopType.body, OctoColors.TextMuted, modifier.padding(Space.Xl), maxLines = 4,
        )
        return
    }
    DisposableEffect(model) {
        model.open = true
        onDispose { model.open = false }
    }
    when (val view = model.view) {
        DrawerView.List -> DownloadList(model, modifier)
        is DrawerView.Log -> DownloadLog(app, model, view.key, modifier)
        is DrawerView.Find -> FindSongs(app, model, view, modifier)
    }
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

@Composable
private fun DownloadList(model: DownloadsModel, modifier: Modifier) {
    val rows by model.rows.collectAsState()
    val loaded by model.loaded.collectAsState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.S, top = Space.S), verticalAlignment = Alignment.CenterVertically) {
            Txt(drawerSummary(rows), DesktopType.meta, OctoColors.TextMuted, Modifier.weight(1f))
            if (rows.any { it.finished }) TextAction("Clear finished", { model.clear() })
        }
        if (rows.isEmpty()) {
            Txt(if (loaded) DOWNLOADS_EMPTY else "Asking the server", DesktopType.body, OctoColors.TextMuted, Modifier.padding(Space.L), maxLines = 4)
            return
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = Space.S, end = Space.S, top = Space.Xs, bottom = Space.Xl)) {
            items(rows, key = { it.key }) { row ->
                DownloadLine(
                    row,
                    // A higher quality copy still waiting its turn has no log yet.
                    onOpen = row.logKey?.let { key -> { model.showLog(key) } },
                    onFind = row.findId?.let { id -> { model.find(id, row.title, row.logKey) } },
                    onClear = { model.clear(row.key) },
                )
            }
        }
    }
}

// One download: its cover, its names, the line saying where it is, and a
// ring while it runs. Hovered, a finished one offers to leave the list, and
// any can be looked for again.
@Composable
private fun DownloadLine(row: DownloadRow, onOpen: (() -> Unit)?, onFind: (() -> Unit)?, onClear: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .hoverLift(Corner.RowShape, clickable = onOpen != null)
            .clickable(enabled = onOpen != null, role = Role.Button) { onOpen?.invoke() }
            .semantics {
                contentDescription = "${row.title}, ${row.artist}"
                stateDescription = row.status
            }
            .padding(horizontal = Space.S, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        Cover(row.coverArt, Modifier.size(LineCover), shape = Corner.ArtSShape, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                CutTxt(row.title, DesktopType.tableTitle, OctoColors.TextPrimary, Modifier.weight(1f, fill = false))
                kindLabel(row.kind)?.let { KindMark(it) }
            }
            if (row.artist.isNotBlank()) Txt(row.artist, DesktopType.meta, OctoColors.TextSecondary)
            Txt(row.status, DesktopType.meta, if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextMuted, maxLines = 2)
        }
        when {
            hovered && onFind != null && row.finished -> Row {
                IconAction(OctoIcons.Search, FIND_SONGS, onFind, size = ControlHeight.S, iconSize = IconSize.Table, tint = OctoColors.TextSecondary)
                IconAction(OctoIcons.Close, "Clear this one", onClear, size = ControlHeight.S, iconSize = IconSize.Table, tint = OctoColors.TextSecondary)
            }
            !row.finished -> ProgressRing(row.fraction, size = IconSize.Transport)
            row.phase == RowPhase.Done -> Glyph(OctoIcons.Downloaded, size = IconSize.Table, tint = OctoColors.TextSecondary)
            else -> Glyph(OctoIcons.Info, size = IconSize.Table, tint = OctoColors.SignalOrange)
        }
    }
}

// "Higher quality" or "Picked", small beside a title.
@Composable
private fun KindMark(text: String) {
    Txt(
        text.uppercase(),
        DesktopType.label,
        OctoColors.TextSecondary,
        Modifier.clip(RoundedCornerShape(Corner.ArtS)).background(Chip).padding(horizontal = Space.S, vertical = Space.Xxs),
    )
}

// ---------------------------------------------------------------------------
// One download's log
// ---------------------------------------------------------------------------

@Composable
private fun DownloadLog(app: AppState, model: DownloadsModel, key: String, modifier: Modifier) {
    val rows by model.rows.collectAsState()
    val log by model.log.collectAsState()
    val problem by model.logProblem.collectAsState()
    // The list's row knows how an upgrade ended; the log's own row is the download.
    val row = rows.firstOrNull { it.logKey == key } ?: log?.let { rowOf(it) }
    val events = log?.event.orEmpty()
    val list = rememberLazyListState()
    // The log opens at its start; lines that arrive later, while its end is
    // in view, keep the end in view.
    val seen = remember(key) { intArrayOf(-1) }
    LaunchedEffect(events.size) {
        val before = seen[0]
        seen[0] = events.size
        if (before <= 0 || events.size <= before) return@LaunchedEffect
        val atEnd = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: true
        if (atEnd) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    LazyColumn(modifier, state = list, contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.S, bottom = Space.Xl)) {
        item(key = "bar") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(OctoIcons.Back, "Back to downloads", model::showList, size = ControlHeight.M, iconSize = IconSize.Toolbar, tint = OctoColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                row?.findId?.let { id -> TextAction(FIND_SONGS, { model.find(id, row.title, key) }, icon = OctoIcons.Search) }
                if (row?.finished == true) TextAction("Clear", { model.clear(row.key); model.showList() })
            }
        }
        if (row != null) item(key = "head") { LogHead(row) }
        problem?.let { item(key = "problem") { Txt(it, DesktopType.meta, OctoColors.SignalOrange, Modifier.padding(vertical = Space.S), maxLines = 2) } }
        item(key = "rule") { Separator(Modifier.padding(vertical = Space.M)) }
        if (log == null && problem == null) item(key = "wait") { ProgressRing(null, size = IconSize.Transport) }
        itemsIndexed(events, key = { index, _ -> index }) { index, line ->
            LogLine(line, last = index == events.lastIndex, cover = row?.coverArt)
        }
    }
}

// The song, where it is now, and the copy being fetched.
@Composable
private fun LogHead(row: DownloadRow) {
    Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
        Cover(row.coverArt, Modifier.size(HeadCover), shape = Corner.ArtMShape, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(row.title, DesktopType.emphasis, maxLines = 2)
            if (row.artist.isNotBlank()) Txt(row.artist, DesktopType.meta, OctoColors.TextSecondary)
            row.album?.takeIf(String::isNotBlank)?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            if (!row.finished) ProgressRing(row.fraction, size = IconSize.Table)
            Txt(row.status, DesktopType.table, if (row.phase == RowPhase.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary, maxLines = 3)
        }
        if (row.phase == RowPhase.Downloading) Bar(row.fraction)
        val facts = listOfNotNull(row.quality, row.source, kindLabel(row.kind))
        if (facts.isNotEmpty()) Txt(facts.joinToString(" · "), DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
    }
}

// A thin line filling as the file comes in; still, when nobody knows how far.
@Composable
private fun Bar(fraction: Float?) {
    Box(Modifier.fillMaxWidth().height(BarHeight).clip(CircleShape).background(Track)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(OctoColors.TextPrimary))
    }
}

// One line of the log: its time, its mark on the thread that joins the
// lines, the words, and the copies a search offered when it is about them.
@Composable
private fun LogLine(line: AcquisitionEvent, last: Boolean, cover: String?) {
    val mark = markOf(line.logKind)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
        Txt(logTime(line.at), DesktopType.meta, OctoColors.TextMuted, Modifier.width(TimeWidth).padding(top = Space.Xxs))
        Column(Modifier.width(MarkSize).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(MarkSize).clip(CircleShape).background(markFill(mark)), contentAlignment = Alignment.Center) {
                Glyph(markIcon(mark), size = IconSize.Inline - Space.Xxs, tint = markTint(mark))
            }
            if (!last) Box(Modifier.width(FrameSize.Hairline).weight(1f).background(SeparatorColor))
        }
        Column(Modifier.weight(1f).padding(bottom = Space.L), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(line.text, DesktopType.table, if (mark == LogMark.Failed) OctoColors.SignalOrange else OctoColors.TextPrimary, maxLines = 4)
            line.detail?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted, maxLines = 5) }
            line.candidate.forEach { copy -> CopyLine(copy, cover, compact = true) }
        }
    }
}

private fun markIcon(mark: LogMark): ImageVector = when (mark) {
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
private fun FindSongs(app: AppState, model: DownloadsModel, view: DrawerView.Find, modifier: Modifier) {
    val found by model.found.collectAsState()
    val problem by model.findProblem.collectAsState()
    val picked by model.picked.collectAsState()
    val searching = found?.searching != false && problem == null
    LazyColumn(modifier, contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.S, bottom = Space.Xl)) {
        item(key = "bar") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    OctoIcons.Back,
                    if (view.from != null) "Back to the log" else "Back to downloads",
                    { view.from?.let(model::showLog) ?: model.showList() },
                    size = ControlHeight.M, iconSize = IconSize.Toolbar, tint = OctoColors.TextSecondary,
                )
                Txt(FIND_SONGS, DesktopType.emphasis, OctoColors.TextPrimary, Modifier.weight(1f).padding(start = Space.Xs))
                TextAction("Search again", model::searchAgain, enabled = !searching, icon = OctoIcons.Search)
            }
        }
        item(key = "head") { FindHead(view, found) }
        found?.source?.let { sources -> item(key = "sources") { Sources(sources) } }
        problem?.let { item(key = "problem") { Txt(it, DesktopType.meta, OctoColors.SignalOrange, Modifier.padding(vertical = Space.S), maxLines = 3) } }
        found?.error?.let { item(key = "error") { Txt(it, DesktopType.meta, OctoColors.SignalOrange, Modifier.padding(vertical = Space.S), maxLines = 3) } }
        picked?.takeIf { !it.queued }?.detail?.let { item(key = "picked") { Txt(it, DesktopType.meta, OctoColors.SignalOrange, Modifier.padding(vertical = Space.S), maxLines = 3) } }
        item(key = "rule") { Separator(Modifier.padding(vertical = Space.M)) }
        val copies = found?.candidate.orEmpty()
        if (copies.isEmpty() && !searching) item(key = "none") { Txt("Nothing found on your sources.", DesktopType.body, OctoColors.TextMuted) }
        items(copies, key = { it.index ?: it.hashCode() }) { copy ->
            CopyLine(copy, found?.song?.coverArt, compact = false, onPick = copy.index?.let { index -> { pickCopy(app, model, found!!, copy, index) } })
        }
    }
}

// The song looked for, the library's copy of it, and what picking does.
@Composable
private fun FindHead(view: DrawerView.Find, found: FoundSongs?) {
    val song = found?.song
    Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
        Cover(song?.coverArt ?: view.id, Modifier.size(HeadCover), shape = Corner.ArtMShape, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(song?.title?.takeIf(String::isNotBlank) ?: view.title, DesktopType.emphasis, maxLines = 2)
            song?.artist?.takeIf(String::isNotBlank)?.let { Txt(it, DesktopType.meta, OctoColors.TextSecondary) }
            song?.album?.takeIf(String::isNotBlank)?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted) }
        }
    }
    if (song != null) {
        Column(Modifier.fillMaxWidth().padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            ownedCopyText(song.quality, song.size)?.let { Txt(it, DesktopType.table, OctoColors.TextPrimary) }
            Txt(if (song.libraryId != null) FIND_REPLACES else FIND_DOWNLOADS, DesktopType.meta, OctoColors.TextMuted, maxLines = 3)
        }
    }
}

// Each source and how its look is going.
@Composable
private fun Sources(sources: List<FindSourceState>) {
    Column(Modifier.fillMaxWidth().padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                when (source.state) {
                    FIND_SEARCHING -> ProgressRing(null, size = IconSize.Inline)
                    FIND_OFF -> Glyph(OctoIcons.Close, size = IconSize.Inline, tint = OctoColors.TextMuted)
                    "failed" -> Glyph(OctoIcons.Info, size = IconSize.Inline, tint = OctoColors.SignalOrange)
                    else -> Glyph(OctoIcons.Check, size = IconSize.Inline, tint = OctoColors.TextSecondary)
                }
                val words = when (source.state) {
                    FIND_SEARCHING -> "Searching"
                    else -> source.text ?: if (source.state == "failed") "Could not search" else "Done"
                }
                Txt("${source.name}: $words", DesktopType.meta, if (source.state == FIND_OFF) OctoColors.TextMuted else OctoColors.TextSecondary, maxLines = 2)
            }
        }
    }
}

// One copy: its cover, title, the folder or album it sits in, its facts, and
// whether Octo would take it. On Find songs it can be picked.
@Composable
private fun CopyLine(copy: FoundCandidate, cover: String?, compact: Boolean, onPick: (() -> Unit)? = null) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .then(if (onPick != null) Modifier.hoverLift(Corner.RowShape, clickable = false) else Modifier)
            .padding(vertical = if (compact) Space.Xxs else Space.S, horizontal = if (compact) Space.None else Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        if (!compact) Cover(cover, Modifier.size(LineCover), shape = Corner.ArtSShape, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            CutTxt(candidateTitle(copy), if (compact) DesktopType.meta else DesktopType.tableTitle, OctoColors.TextPrimary)
            val where = listOfNotNull(copy.album?.takeIf(String::isNotBlank) ?: copy.folder, copy.file?.takeIf { copy.title != null && !compact })
            if (where.isNotEmpty()) CutTxt(where.joinToString(" · "), DesktopType.meta, OctoColors.TextMuted)
            Txt(candidateFacts(copy).joinToString(" · "), DesktopType.meta, OctoColors.TextSecondary, maxLines = 3)
            candidateVerdict(copy)?.let { Txt(it, DesktopType.meta, if (copy.rank != null) OctoColors.TextSecondary else OctoColors.TextMuted, maxLines = 2) }
        }
        if (onPick != null) {
            OctoTooltip("Get this copy") {
                IconAction(OctoIcons.Download, "Get this copy", onPick, size = ControlHeight.M, iconSize = IconSize.Toolbar, active = hovered, tint = if (hovered) OctoColors.TextPrimary else OctoColors.TextSecondary)
            }
        }
    }
}

// Picks a copy. Replacing a library song's copy is asked first; a song not
// in the library is simply fetched.
private fun pickCopy(app: AppState, model: DownloadsModel, found: FoundSongs, copy: FoundCandidate, index: Int) {
    if (found.song.libraryId == null) {
        model.pick(index)
        return
    }
    app.popups.showCentred { close ->
        MenuTitle(found.song.title)
        PopupPadding {
            Txt(
                "Replace your copy with ${candidateFacts(copy).take(2).joinToString(", ")}? $FIND_REPLACES",
                DesktopType.body, OctoColors.TextPrimary, maxLines = 6,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(OctoIcons.Lossless, "Replace it", {
                    close()
                    model.pick(index)
                }, lit = true)
            }
        }
    }
}

private val LineCover = RowHeight.Regular
private val HeadCover = FrameSize.PlayerCover + Space.Xs
private val TimeWidth = Space.Wide + Space.Xl
private val MarkSize = IconSize.Transport + Space.Xs
private val BarHeight = Space.Xs
private val Chip = Color.White.copy(alpha = 0.08f)
private val Track = Color.White.copy(alpha = 0.14f)
