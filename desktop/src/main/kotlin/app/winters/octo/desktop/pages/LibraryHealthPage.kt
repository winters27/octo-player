package app.winters.octo.desktop.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PageSize
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.health.HealthModel
import app.winters.octo.desktop.health.healthColumns
import app.winters.octo.desktop.health.healthRows
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.runFix
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.health.FixStep
import app.winters.octo.health.HEALTH_ALL_CLEAR
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthReport
import app.winters.octo.health.LOOK_UP_TAGS
import app.winters.octo.health.RECENTLY_REMOVED
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.UNDO_LAST_CHANGE
import app.winters.octo.health.advice
import app.winters.octo.health.countLabel
import app.winters.octo.health.countText
import app.winters.octo.health.fillsFromAlbum
import app.winters.octo.health.fixAllLabel
import app.winters.octo.health.fixMeaning
import app.winters.octo.health.meaning
import app.winters.octo.health.overview
import app.winters.octo.health.title
import app.winters.octo.subsonic.Song
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Library health: what is worth fixing in the library's files, and the
// fixes. The checks that found something are listed with their counts; the
// one picked says what it means and, when the server can fix it, offers to
// fix them all, showing every change before it is made. Its songs fill the
// table below, where they play, open their menu and show their folder like
// any other list, and their menu has the fixes for one song. Every fix is
// said after in the notice line, with Undo. Nothing here rates a song: on
// some servers a low rating deletes it.
@Composable
fun LibraryHealthPage(app: AppState, visit: Visit) {
    val list = rememberListState(app.navigator, visit)
    val health = app.health
    LaunchedEffect(app.connection) { health.askServer() }
    WithLibrary(app) { index ->
        LaunchedEffect(index) { health.check(index.songs) }
        val report = health.report ?: return@WithLibrary Column(Modifier.padding(start = PageSide, end = PageSide, top = Space.Xxl)) {
            PageTitle("Library health")
            LoadingLine("Checking your library")
        }
        val check = health.shown
        val rows = remember(report, check) { healthRows(report, check) }
        SongTable(
            app,
            rows.songs,
            healthColumns(check),
            list,
            id = "health:${check?.name ?: "none"}",
            number = { at, _ -> rows.numbers.getOrElse(at) { "" } },
            groupTitle = { at -> rows.titles[at] },
            groupDetail = { at -> rows.details[at] },
            menuExtra = { picked, close -> picked.singleOrNull()?.let { song -> songRows(app, health, report, check, song, close) } },
            // With no songs there is nothing to check, which is not the same as all clear.
            empty = {
                if (index.songs.isEmpty()) NothingHere("No songs to check yet", "Once your server has music, Octo looks it over here for second copies, split albums and missing tags.")
                else NothingHere("Everything looks right", HEALTH_ALL_CLEAR)
            },
        ) {
            item(key = "title") { PageTitle("Library health", detail = report.overview()) }
            item(key = "tools") { Tools(app, health) }
            if (check != null) {
                item(key = "checks") { Findings(report, check) { health.picked = it } }
                item(key = "about") { About(app, health, report, check, index.songs) }
            }
        }
    }
}

// Under the title: how a fix is going while one runs, its ring filling as
// the downloads drawer's do, with Stop; and the server's trash.
@Composable
private fun Tools(app: AppState, health: HealthModel) {
    val running = health.running
    val trash = health.actions?.canRestore == true
    if (running == null && !trash) return
    Row(
        Modifier.widthIn(max = PageSize.Reading).padding(bottom = Space.Xl),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running != null) {
            ProgressRing(running.done.toFloat() / running.total.coerceAtLeast(1), size = IconSize.Table)
            Txt(running.words, DesktopType.body, OctoColors.TextSecondary, Modifier.weight(1f))
            GlazeCapsule(null, "Stop", { health.stop() })
        } else {
            GlazeCapsule(OctoIcons.History, RECENTLY_REMOVED, { showTrash(app) })
        }
    }
}

// The checks that found something, one row each with its count, the one
// shown on the darker pill; hairlines between them.
@Composable
private fun Findings(report: HealthReport<Song>, shown: HealthCheck, pick: (HealthCheck) -> Unit) {
    Column(Modifier.widthIn(max = PageSize.Reading).padding(bottom = Space.Xl)) {
        report.findings.forEachIndexed { i, check ->
            if (i > 0) Separator(Modifier.padding(horizontal = Space.M))
            val lit = check == shown
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RowHeight.Regular)
                    .hoverLift(Corner.ControlShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { pick(check) },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (lit) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
                Row(Modifier.padding(horizontal = Space.L), verticalAlignment = Alignment.CenterVertically) {
                    Txt(check.title(), DesktopType.body, if (lit) OctoColors.TextPrimary else OctoColors.TextSecondary, Modifier.weight(1f))
                    Txt(check.countLabel(report.count(check)), DesktopType.meta, if (lit) OctoColors.TextSecondary else OctoColors.TextMuted)
                }
            }
        }
    }
}

// How many songs a bulk lookup takes at once: each is a fingerprint and a
// catalog search on the server, a few seconds apiece.
internal const val LOOKUP_BATCH = 25

// What the server can fix for a check, as the page offers it.
internal class CheckFixes(
    // The button that fixes them all, and what it opens; null when the
    // server cannot.
    val all: Pair<String, () -> Unit>?,
    // A second button: looking up the songs a fill cannot reach.
    val lookUp: Pair<String, () -> Unit>?,
)

internal fun fixesFor(app: AppState, health: HealthModel, report: HealthReport<Song>, check: HealthCheck, all: List<Song>): CheckFixes {
    val actions = health.actions
    if (actions == null || health.running != null) return CheckFixes(null, null)
    val songs = report.songs(check)
    val lookUp = if (actions.canLookUp && actions.canEdit && songs.isNotEmpty()) {
        val batch = songs.take(LOOKUP_BATCH)
        "Look up ${if (songs.size > batch.size) "the first ${batch.size}" else countText(batch.size, "song", "songs")}" to { lookUpSongs(app, batch, check) }
    } else {
        null
    }
    return when (check) {
        HealthCheck.Duplicates -> CheckFixes(
            if (actions.canRemove) check.fixAllLabel(report.duplicates.size) to { askToFixDuplicates(app, report.duplicates) } else null,
            null,
        )
        HealthCheck.SplitAlbums -> CheckFixes(
            if (actions.canJoinAlbums) check.fixAllLabel(report.splitAlbums.size) to { askToJoinAlbums(app, report.splitAlbums) } else null,
            null,
        )
        HealthCheck.NoCover -> CheckFixes(if (actions.canAddCover) check.fixAllLabel(songs.size) to { askToAddCovers(app, songs) } else null, null)
        HealthCheck.NoLength -> {
            val upgrades = app.upgrades
            CheckFixes(if (upgrades?.canUpgrade == true) check.fixAllLabel(songs.size) to { upgrades.request(songs) } else null, null)
        }
        HealthCheck.NoTrackNumber -> CheckFixes(null, lookUp)
        HealthCheck.NoYear, HealthCheck.NoGenre, HealthCheck.NoAlbumArtist -> {
            val fills = if (actions.canEdit) fillsFromAlbum(songs, all, check.tag!!, SubsonicHealth) else emptyList()
            CheckFixes(if (fills.isNotEmpty()) check.fixAllLabel(fills.size) to { askToFill(app, check, fills) } else null, lookUp)
        }
    }
}

// What the picked check means and what to do about it: the server's fixes
// when it has them, else what to do by hand.
@Composable
private fun About(app: AppState, health: HealthModel, report: HealthReport<Song>, check: HealthCheck, all: List<Song>) {
    val fixes = remember(report, check, health.actions, health.running == null, all) { fixesFor(app, health, report, check, all) }
    val fixable = fixes.all != null || fixes.lookUp != null
    Column(Modifier.widthIn(max = PageSize.Reading).padding(bottom = Space.Xl), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Txt(check.title(), DesktopType.section)
        Txt(check.meaning(), DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
        Txt(if (fixable) check.fixMeaning() else check.advice(), DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
        if (fixable) {
            Row(Modifier.padding(top = Space.S), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                fixes.all?.let { (label, open) -> GlazeCapsule(OctoIcons.Check, label, open, lit = true) }
                fixes.lookUp?.let { (label, open) -> GlazeCapsule(OctoIcons.Search, label, open) }
            }
            Txt("Right-click a song for the fixes for that song alone.", DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
        }
    }
}

// The page's own rows in a song's menu: the fixes for this song, and copy
// where the server keeps the file. Delete from disk is in every song menu.
private fun songRows(app: AppState, health: HealthModel, report: HealthReport<Song>, check: HealthCheck?, song: Song, close: () -> Unit): (@Composable ColumnScope.() -> Unit)? {
    val actions = health.actions
    val idle = health.running == null
    val path = song.path?.takeIf(String::isNotBlank)
    val group = report.duplicates.firstOrNull { set -> set.copies.any { it.id == song.id } }
    val split = report.splitAlbums.firstOrNull { album -> album.parts.any { part -> part.songs.any { it.id == song.id } } }
    val copies = idle && actions?.canRemove == true && group != null && check == HealthCheck.Duplicates
    val join = idle && actions?.canJoinAlbums == true && split != null && check == HealthCheck.SplitAlbums
    val lookUp = idle && actions?.canLookUp == true && actions.canEdit
    val cover = idle && actions?.canAddCover == true && check == HealthCheck.NoCover
    val undo = idle && actions?.canEdit == true && song.id in health.changed
    if (path == null && !copies && !join && !lookUp && !cover && !undo) return null
    return {
        if (copies) {
            val best = group!!.best.id == song.id
            MenuRow(if (best) "Fix these copies" else "Keep this copy instead", {
                close()
                askToFixCopies(app, group, song)
            }, OctoIcons.Check)
        }
        if (join) MenuRow("Join this album", { close(); askToJoinAlbums(app, listOf(split!!)) }, OctoIcons.Album)
        if (lookUp) MenuRow(LOOK_UP_TAGS, { close(); lookUpSong(app, song, check) }, OctoIcons.Search)
        if (cover) MenuRow("Find a cover", { close(); app.runFix("Finding a cover", listOf(FixStep.AddCover(song.id, song.title)), check) }, OctoIcons.Album)
        if (undo) MenuRow(UNDO_LAST_CHANGE, { close(); app.runFix("Putting it back", listOf(FixStep.Undo(song.id, song.title))) }, OctoIcons.History)
        if (path != null) {
            MenuRow("Copy file path", {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path), null) }
                close()
            }, OctoIcons.Folder)
        }
    }
}
