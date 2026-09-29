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
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PageSize
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.health.HealthModel
import app.winters.octo.desktop.health.RemoveQuestion
import app.winters.octo.desktop.health.healthColumns
import app.winters.octo.desktop.health.healthRows
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.LoadingLine
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.health.HEALTH_ALL_CLEAR
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthReport
import app.winters.octo.health.advice
import app.winters.octo.health.countLabel
import app.winters.octo.health.meaning
import app.winters.octo.health.overview
import app.winters.octo.health.title
import app.winters.octo.subsonic.Song
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Library health: what is worth fixing in the library's files. The checks
// that found something are listed with their counts; the one picked says
// what it means and what to do, and its songs fill the table below, where
// they play, open their menu and show their folder like any other list.
// Copies of one recording can be taken out of the library from their menu
// when the server offers that, always asked first. Nothing here rates a
// song: on some servers a low rating deletes it.
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
            menuExtra = { picked, close -> picked.singleOrNull()?.let { song -> songRows(app, health, song, close) } },
            // With no songs there is nothing to check, which is not the same as all clear.
            empty = {
                if (index.songs.isEmpty()) NothingHere("No songs to check yet", "Once your server has music, Octo looks it over here for second copies, split albums and missing tags.")
                else NothingHere("Everything looks right", HEALTH_ALL_CLEAR)
            },
        ) {
            item(key = "title") { PageTitle("Library health", detail = report.overview()) }
            if (check != null) {
                item(key = "checks") { Findings(report, check) { health.picked = it } }
                item(key = "about") { About(check, removable = check == HealthCheck.Duplicates && health.actions?.canRemove == true) }
            }
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

// What the picked check means and what to do about it.
@Composable
private fun About(check: HealthCheck, removable: Boolean) {
    Column(Modifier.widthIn(max = PageSize.Reading).padding(bottom = Space.Xl), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Txt(check.title(), DesktopType.section)
        Txt(check.meaning(), DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
        Txt(check.advice(), DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
        if (removable) Txt("To take a copy out of your library, right-click it and choose Remove from library.", DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
    }
}

// The page's own rows in a song's menu: copy where the server keeps the
// file, and remove a second copy when the server allows it.
private fun songRows(app: AppState, health: HealthModel, song: Song, close: () -> Unit): (@Composable ColumnScope.() -> Unit)? {
    val path = song.path?.takeIf(String::isNotBlank)
    val removable = health.canRemove(song)
    if (path == null && !removable) return null
    return {
        if (path != null) {
            MenuRow("Copy file path", {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path), null) }
                close()
            }, OctoIcons.Folder)
        }
        if (removable) {
            MenuRow("Remove from library", {
                close()
                health.removeQuestion(song)?.let { askToRemove(app, health, it) }
            }, OctoIcons.Delete, destructive = true)
        }
    }
}

// Asks before a copy leaves the library, in the middle of the window.
private fun askToRemove(app: AppState, health: HealthModel, question: RemoveQuestion) {
    app.popups.showCentred { close ->
        MenuTitle(question.title)
        PopupPadding {
            Txt(question.body, DesktopType.body, OctoColors.TextPrimary, maxLines = 6)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(OctoIcons.Delete, "Remove", {
                    close()
                    health.remove(question.song) { line ->
                        app.notice = line
                        app.library?.load()
                    }
                }, lit = true)
            }
        }
    }
}
