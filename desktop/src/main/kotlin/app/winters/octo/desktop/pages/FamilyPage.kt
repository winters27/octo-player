package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Corner
import app.winters.octo.design.CutTxt
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalPopups
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.MeterLine
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupHost
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.subsonic.FamilyDevice
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.RequestQuality
import app.winters.octo.subsonic.familyJoinLink
import app.winters.octo.ui.family.DEVICES
import app.winters.octo.ui.family.FAMILY
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.FamilySection
import app.winters.octo.ui.family.MEMBERS
import app.winters.octo.ui.family.MY_PLAN
import app.winters.octo.ui.family.REQUESTS
import app.winters.octo.ui.family.REQUESTS_WAITING
import app.winters.octo.ui.family.REQUEST_A_COPY
import app.winters.octo.ui.family.SAVED
import app.winters.octo.ui.family.abilityLines
import app.winters.octo.ui.family.deviceLine
import app.winters.octo.ui.family.memberLine
import app.winters.octo.ui.family.outsideActions
import app.winters.octo.ui.family.planTitle
import app.winters.octo.ui.family.plural
import app.winters.octo.ui.family.requestKindLine
import app.winters.octo.ui.family.requestSheet
import app.winters.octo.ui.family.requestStateLine
import app.winters.octo.ui.family.requestTitle
import app.winters.octo.ui.family.storageLine
import app.winters.octo.ui.family.storageShare
import app.winters.octo.ui.family.weeklyLine
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Family on an Octo server with it on: this account's plan in plain words,
// its saved songs, requests and devices, and for a manager the members and
// the requests waiting. The server decides everything; this shows it.
@Composable
fun FamilyPage(app: AppState, visit: Visit) {
    val model = app.family
    DisposableEffect(app.connection) {
        model.watch()
        onDispose { model.stop() }
    }
    val info = model.info
    if (info == null) {
        SectionedPage(
            app,
            visit,
            FAMILY,
            listOf(
                PageSection("plan", MY_PLAN, OctoIcons.Family) {
                    Rows { SettingRow(if (model.problem == null) "Reading" else "Not here right now", model.problem ?: "Asking the server about your plan.") }
                },
            ),
        )
        return
    }
    val me = info.me
    val sections = model.sections().map { section ->
        when (section) {
            FamilySection.Plan -> PageSection("plan", MY_PLAN, OctoIcons.Family, detail = planTitle(me)) { PlanSection(model, me) }
            FamilySection.Saved -> PageSection("saved", SAVED, OctoIcons.Like, detail = savedDetail(model)) { SavedSection(app, model, me) }
            FamilySection.Requests -> PageSection("requests", REQUESTS, OctoIcons.Downloading, detail = requestsDetail(model.requests)) { RequestsSection(model) }
            FamilySection.Devices -> PageSection("devices", DEVICES, OctoIcons.Device, detail = plural(model.devices.size, "device")) { DevicesSection(app, model) }
            FamilySection.Members -> PageSection("members", MEMBERS, OctoIcons.Family, detail = membersDetail(info.manager?.members.orEmpty(), info.manager?.liveStreams ?: 0)) {
                MembersSection(info.manager?.members.orEmpty())
            }
            FamilySection.Inbox -> PageSection("inbox", REQUESTS_WAITING, OctoIcons.Check, detail = if (model.inbox.isEmpty()) "None waiting" else "${model.inbox.size} waiting") {
                InboxSection(model)
            }
        }
    }
    SectionedPage(app, visit, FAMILY, sections)
}

private fun savedDetail(model: FamilyModel): String {
    val saved = model.saved
    if (saved.isEmpty) return "Nothing saved yet"
    return listOfNotNull(
        saved.songs.size.takeIf { it > 0 }?.let { plural(it, "song") },
        saved.albums.size.takeIf { it > 0 }?.let { plural(it, "album") },
    ).joinToString(", ")
}

private fun requestsDetail(requests: List<FamilyRequest>): String {
    val waiting = requests.count { it.state == FamilyRequestState.Pending }
    return when {
        requests.isEmpty() -> "None yet"
        waiting > 0 -> "$waiting waiting"
        else -> plural(requests.size, "request")
    }
}

private fun membersDetail(members: List<FamilyMember>, live: Int): String =
    listOf(plural(members.size, "member"), if (live > 0) "$live playing now" else null).filterNotNull().joinToString(", ")

@Composable
private fun Said(model: FamilyModel) {
    val said = model.said ?: return
    Txt(said, DesktopType.meta, OctoColors.TextSecondary, Modifier.padding(horizontal = RowInset), maxLines = 4)
}

@Composable
private fun PlanSection(model: FamilyModel, me: FamilyMe) {
    val can = me.abilities
    Rows {
        Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Your library", DesktopType.body, modifier = Modifier.weight(1f))
                Txt(storageLine(me.storageUsedBytes, can.storageLimitGb), DesktopType.meta, OctoColors.TextSecondary)
            }
            storageShare(me.storageUsedBytes, can.storageLimitGb)?.let { MeterLine(it) }
        }
        if (can.weeklyRequestLimit > 0 || me.requestsThisWeek > 0) {
            SettingRow("Requests this week", weeklyLine(me.requestsThisWeek, can.weeklyRequestLimit))
        }
    }
    Said(model)
    Group("What your plan includes") {
        Rows {
            abilityLines(can).forEach { line ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = RowHeight.Regular).padding(horizontal = RowInset, vertical = Space.M),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.L),
                ) {
                    Glyph(if (line.on) OctoIcons.Check else OctoIcons.Close, size = IconSize.Inline, tint = if (line.on) OctoColors.TextSecondary else OctoColors.TextMuted)
                    Txt(line.text, DesktopType.body, if (line.on) OctoColors.TextPrimary else OctoColors.TextMuted, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun SavedSection(app: AppState, model: FamilyModel, me: FamilyMe) {
    Said(model)
    val saved = model.saved
    if (saved.isEmpty) {
        Rows { SettingRow("Nothing saved yet", "Songs and albums found online that you add show here.") }
        return
    }
    val offersRequest = outsideActions(me).offersRequest
    val popups = LocalPopups.current
    if (saved.songs.isNotEmpty()) {
        Group("Songs") {
            Rows {
                saved.songs.forEach { song ->
                    ItemRow(song.title, listOfNotNull(song.artist, song.album).joinToString(" · ")) {
                        if (offersRequest) RowAction(REQUEST_A_COPY, { askForCopy(popups, app, song.id, song.title) }, enabled = !model.working)
                        RowAction("Remove", { model.unsave(song.id, album = false) }, enabled = !model.working)
                    }
                }
            }
        }
    }
    if (saved.albums.isNotEmpty()) {
        Group("Albums") {
            Rows {
                saved.albums.forEach { album ->
                    ItemRow(album.name, album.artist.orEmpty()) {
                        if (offersRequest) RowAction(REQUEST_A_COPY, { askForCopy(popups, app, album.id, album.name) }, enabled = !model.working)
                        RowAction("Remove", { model.unsave(album.id, album = true) }, enabled = !model.working)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestsSection(model: FamilyModel) {
    Said(model)
    if (model.requests.isEmpty()) {
        Rows { SettingRow("No requests yet", "Ask for a copy of a song found online from its menu: $REQUEST_A_COPY.") }
        return
    }
    Rows {
        model.requests.forEach { request ->
            ItemRow(request.title.ifBlank { request.target }, listOf(request.artist, requestKindLine(request)).filter(String::isNotBlank).joinToString(" · "), state = request) {
                if (request.state == FamilyRequestState.Pending) RowAction("Cancel", { model.cancel(request.id) }, enabled = !model.working)
            }
        }
    }
}

@Composable
private fun DevicesSection(app: AppState, model: FamilyModel) {
    Said(model)
    val popups = LocalPopups.current
    Rows {
        if (model.devices.isEmpty()) SettingRow("No devices yet", "Devices you sign in on show here.")
        model.devices.forEach { device ->
            ItemRow(device.name.ifBlank { "A device" }, deviceLine(device)) {
                if (device.current) Txt("This device", DesktopType.meta, OctoColors.TextMuted) else RowAction("Sign out", { askToSignOut(popups, model, device) }, enabled = !model.working)
            }
        }
    }
    AddDevice(app, model)
}

@Composable
private fun AddDevice(app: AppState, model: FamilyModel) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FamilyDeviceKind.OctoApp) }
    val popups = LocalPopups.current
    val added = model.added
    LaunchedEffect(added) { if (added != null) showAdded(popups, app, model, added) }
    Group("Add a device") {
        Card {
            Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.M)) {
                Txt(
                    if (kind == FamilyDeviceKind.OctoApp) "Octo on another phone or computer joins with a 6 digit code." else "Any other music app signs in with your username and an app password.",
                    DesktopType.meta,
                    OctoColors.TextMuted,
                    maxLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                    GlazeSegments(listOf(FamilyDeviceKind.OctoApp, FamilyDeviceKind.SubsonicApp), kind, { if (it == FamilyDeviceKind.OctoApp) "Octo app" else "Other app" }, { kind = it })
                    GlassField(name, { name = it }, Modifier.weight(1f), placeholder = if (kind == FamilyDeviceKind.OctoApp) "Its name, like Living room" else "Its name, like Symfonium")
                    GlazeCapsule(OctoIcons.Add, "Add", {
                        model.addDevice(name, kind)
                        name = ""
                    }, enabled = !model.working)
                }
            }
        }
    }
}

// The code or password, shown once, with a way to copy it.
private fun showAdded(popups: PopupHost, app: AppState, model: FamilyModel, added: FamilyDeviceAdded) {
    popups.showCentred(width = 420.dp) { close ->
        val done = {
            model.dismissAdded()
            close()
        }
        val code = added.pairCode
        val password = added.appPassword
        MenuTitle(if (code != null) "Pair code" else "App password")
        PopupPadding {
            val secret = code ?: password.orEmpty()
            Txt(secret, DesktopType.pageTitle, OctoColors.TextPrimary)
            if (code != null) {
                Txt("In Octo on the other device, choose Join with a family code and type your username and this code. It works once${added.expires?.let { ", until it expires" } ?: ""}.", DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
            } else {
                val server = added.server ?: app.connection?.client?.primaryUrl?.toString()?.removeSuffix("/")
                Txt("In the other app, sign in to ${server ?: "this server"} as ${added.username} with this password. It shows only now.", DesktopType.body, OctoColors.TextSecondary, maxLines = 4)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                if (code != null) {
                    val server = added.server ?: app.connection?.client?.primaryUrl?.toString()?.removeSuffix("/").orEmpty()
                    GlazeCapsule(null, "Copy link", { copy(familyJoinLink(server, added.username, code)) })
                }
                GlazeCapsule(null, "Copy", { copy(secret) })
                GlazeCapsule(OctoIcons.Check, "Done", done, lit = true)
            }
        }
    }
}

private fun copy(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

private fun askToSignOut(popups: PopupHost, model: FamilyModel, device: FamilyDevice) {
    popups.showCentred { close ->
        MenuTitle("Sign out ${device.name.ifBlank { "this device" }}")
        PopupPadding {
            Txt("It stops working at once. Signing in again needs a new code or password.", DesktopType.body, OctoColors.TextPrimary, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(null, "Sign out", {
                    close()
                    model.signOut(device)
                }, lit = true)
            }
        }
    }
}

@Composable
private fun MembersSection(members: List<FamilyMember>) {
    Rows {
        if (members.isEmpty()) SettingRow("No members yet", "Add members on the Octo dashboard's Family page.")
        members.forEach { member ->
            ItemRow(member.displayName.ifBlank { member.username }, memberLine(member)) {
                Txt(storageLine(member.storageUsedBytes, member.storageLimitGb), DesktopType.meta, OctoColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun InboxSection(model: FamilyModel) {
    Said(model)
    if (model.inbox.isEmpty()) {
        Rows { SettingRow("Nothing waiting", "Members' requests show here for you to approve or decline.") }
        return
    }
    Rows {
        model.inbox.forEach { request -> InboxRow(model, request) }
    }
}

// One waiting request: who asks, for what, a note for them, and the choice.
@Composable
private fun InboxRow(model: FamilyModel, request: FamilyRequest) {
    var note by remember(request.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
            Column(Modifier.weight(1f)) {
                CutTxt(requestTitle(request), DesktopType.tableTitle)
                Txt("${request.displayName.ifBlank { request.username }} · ${requestKindLine(request)}", DesktopType.meta, OctoColors.TextMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            GlassField(note, { note = it }, Modifier.weight(1f), placeholder = "A note for them (optional)")
            GlazeCapsule(null, "Decline", { model.decline(request.id, note) }, enabled = !model.working)
            GlazeCapsule(OctoIcons.Check, "Approve", { model.approve(request.id, note) }, lit = true, enabled = !model.working)
        }
    }
}

// A row of a list: its title, a line under it, the request's state when
// it is one, and actions at the end.
@Composable
private fun ItemRow(title: String, line: String, state: FamilyRequest? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowHeight.Regular).hoverLift(Corner.RowShape).padding(horizontal = RowInset, vertical = Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        Column(Modifier.weight(1f)) {
            CutTxt(title, DesktopType.tableTitle)
            if (line.isNotBlank()) Txt(line, DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
        }
        if (state != null) {
            Box(Modifier.width(240.dp), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    if (state.state == FamilyRequestState.Approved) ProgressRing(null, size = IconSize.Inline)
                    Txt(requestStateLine(state), DesktopType.meta, stateColor(state.state), maxLines = 2, align = TextAlign.End)
                }
            }
        }
        actions()
    }
}

private fun stateColor(state: FamilyRequestState) = when (state) {
    FamilyRequestState.Done -> OctoColors.SignalGreen
    FamilyRequestState.Declined, FamilyRequestState.Failed -> OctoColors.SignalOrange
    else -> OctoColors.TextSecondary
}

// The request sheet: which quality, the week's requests left, and whether
// the library is full. Opened from a song's menu and from Saved.
fun askForCopy(popups: PopupHost, app: AppState, id: String, title: String) {
    val me = app.family.me ?: return
    val sheet = requestSheet(me)
    popups.showCentred(width = 400.dp) { close ->
        var picked by remember { mutableStateOf(sheet.initial) }
        MenuTitle("$REQUEST_A_COPY of $title")
        sheet.choices.forEach { choice ->
            MenuRow(
                choice.label,
                { picked = choice.quality },
                enabled = choice.enabled,
                detail = choice.detail,
                checked = picked == choice.quality,
            )
        }
        PopupPadding {
            sheet.storageLine?.let { Txt(it, DesktopType.meta, OctoColors.SignalOrange, maxLines = 2) }
            sheet.quotaLine?.let { Txt(it, DesktopType.meta, OctoColors.TextSecondary, maxLines = 3) }
            Txt(sheet.approvalLine, DesktopType.meta, OctoColors.TextMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(OctoIcons.Download, "Request", {
                    close()
                    request(app, id, picked)
                }, lit = true)
            }
        }
    }
}

// Sends it, and says what became of it in the window's one quiet line.
private fun request(app: AppState, id: String, quality: RequestQuality) {
    app.scope.launch {
        app.family.request(id, quality)
        app.family.said?.let { app.notice = it }
    }
}
