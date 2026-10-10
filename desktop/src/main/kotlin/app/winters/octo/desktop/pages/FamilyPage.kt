package app.winters.octo.desktop.pages

import androidx.compose.foundation.clickable
import app.winters.octo.ui.family.loginSteps
import app.winters.octo.ui.family.HANDOVER_TITLE
import app.winters.octo.ui.family.HOME_ONLY_LOGIN
import app.winters.octo.ui.family.USE_IN_ANY_APP
import app.winters.octo.ui.family.PASSWORD_LINE
import app.winters.octo.ui.family.RESET_PASSWORD
import app.winters.octo.ui.family.CHANGE_PASSWORD
import app.winters.octo.ui.family.SIGN_OUT_EVERYWHERE_LINE
import app.winters.octo.ui.family.SIGN_OUT_EVERYWHERE
import app.winters.octo.ui.family.REMOVE_FROM_LIST
import app.winters.octo.ui.family.YOUR_LOGIN
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import app.winters.octo.design.FocusRing
import app.winters.octo.design.OctoSwitch
import app.winters.octo.ui.family.ALLOW_AWAY_LINE
import app.winters.octo.ui.family.ALLOW_AWAY
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.ui.family.presetLine
import app.winters.octo.ui.family.presetName
import app.winters.octo.desktop.family.showHandOver
import app.winters.octo.desktop.family.CopyButton
import app.winters.octo.desktop.family.Value
import app.winters.octo.desktop.family.copyText
import app.winters.octo.desktop.family.showInvite
import app.winters.octo.desktop.library.Cover
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
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.RequestQuality
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
    // Adding a device and a new member's invite float over the page.
    val popups = LocalPopups.current
    val inviting = model.invite?.username
    LaunchedEffect(inviting) { if (inviting != null) showInvite(popups, app, model) }
    val sections = model.sections().map { section ->
        when (section) {
            FamilySection.Login -> PageSection("login", YOUR_LOGIN, OctoIcons.Key, detail = model.login?.username) { LoginSection(app, model) }
            FamilySection.Plan -> PageSection("plan", MY_PLAN, OctoIcons.Family, detail = planTitle(me)) { PlanSection(model, me) }
            FamilySection.Saved -> PageSection("saved", SAVED, OctoIcons.Like, detail = savedDetail(model)) { SavedSection(app, model, me) }
            FamilySection.Requests -> PageSection("requests", REQUESTS, OctoIcons.Downloading, detail = requestsDetail(model.requests)) { RequestsSection(model) }
            FamilySection.Devices -> PageSection("devices", DEVICES, OctoIcons.Device, detail = plural(model.devices.size, "device")) { DevicesSection(app, model) }
            FamilySection.Members -> PageSection("members", MEMBERS, OctoIcons.Family, detail = membersDetail(info.manager?.members.orEmpty(), info.manager?.liveStreams ?: 0)) {
                MembersSection(model, info.manager?.members.orEmpty())
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
            ItemRow(request.title.ifBlank { request.target }, listOf(request.artist, requestKindLine(request)).filter(String::isNotBlank).joinToString(" · "), state = request, cover = request.coverArt ?: request.target) {
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
        if (model.devices.isEmpty()) SettingRow("No devices yet", "Apps you sign in to with your login show here by themselves.")
        model.devices.forEach { device ->
            ItemRow(device.name.ifBlank { "A device" }, deviceLine(device)) {
                if (device.current) Txt("This device", DesktopType.meta, OctoColors.TextMuted) else RowAction(REMOVE_FROM_LIST, { model.forget(device) }, enabled = !model.working)
            }
        }
    }
    Group {
        Rows {
            SettingRow(HANDOVER_TITLE, "Show a QR code to sign in on your phone or another computer, without typing your password.") {
                RowAction("Show QR code", { showHandOver(popups, app, model) }, icon = OctoIcons.QrCode)
            }
            SettingRow(SIGN_OUT_EVERYWHERE, SIGN_OUT_EVERYWHERE_LINE) {
                RowAction(CHANGE_PASSWORD, { changePassword(app) })
            }
        }
    }
}

// Your login: the one sign-in a member takes to any app. The server's
// addresses (the outside one and, when it differs, the home one), the
// username, each with a copy button, and the password, which they change
// here. Under it, how to use it in each app.
@Composable
private fun LoginSection(app: AppState, model: FamilyModel) {
    val login = model.login ?: return
    val popups = LocalPopups.current
    val anywhere = login.servers.anywhere?.takeIf { login.anywhereAvailable && login.awayAllowed }
    val home = login.servers.home
    val main = anywhere ?: home ?: app.connection?.client?.primaryUrl?.toString()?.removeSuffix("/").orEmpty()
    Rows {
        SettingRow("Server", if (anywhere == null && home != null) HOME_ONLY_LOGIN else null) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Value(main)
                    CopyButton(main, "Copy server", ::copyText)
                }
                if (anywhere != null && home != null && home != anywhere) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("At home: ", DesktopType.meta, OctoColors.TextMuted)
                        Value(home)
                        CopyButton(home, "Copy home address", ::copyText)
                    }
                }
            }
        }
        SettingRow("Username", null) {
            Value(login.username)
            CopyButton(login.username, "Copy username", ::copyText)
        }
        SettingRow("Password", PASSWORD_LINE) {
            RowAction(CHANGE_PASSWORD, { changePassword(app) })
        }
        SettingRow(HANDOVER_TITLE, "A QR code your phone or another computer scans to sign in with this login.") {
            RowAction("Show QR code", { showHandOver(popups, app, model) }, icon = OctoIcons.QrCode)
        }
    }
    Group(USE_IN_ANY_APP) {
        var open by remember { mutableStateOf<String?>("Octo") }
        Rows {
            loginSteps(main, login.username).forEach { app ->
                val expanded = open == app.app
                SettingRow(app.app, if (expanded) null else app.steps.first(), Modifier.clickable { open = if (expanded) null else app.app }) {
                    Glyph(if (expanded) OctoIcons.Expand else OctoIcons.Collapse, size = 16.dp, tint = OctoColors.TextMuted)
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        app.steps.forEachIndexed { i, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Txt("${i + 1}.", DesktopType.meta, OctoColors.TextMuted, Modifier.width(18.dp))
                                Txt(step, DesktopType.meta, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

// A manager resets a member's password: it stops working at once, on every
// app, and a new sign-in link shows for them to choose another.
private fun askToReset(popups: PopupHost, model: FamilyModel, member: FamilyMember) {
    val name = member.displayName.ifBlank { member.username }
    popups.showCentred { close ->
        MenuTitle("Reset $name's password?")
        PopupPadding {
            Txt("Their password stops working at once, on every app and device. You get a new sign-in link to give them, to choose another.", DesktopType.body, OctoColors.TextPrimary, maxLines = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(null, RESET_PASSWORD, {
                    close()
                    model.resetPassword(member)
                }, lit = true)
            }
        }
    }
}

@Composable
private fun MembersSection(model: FamilyModel, members: List<FamilyMember>) {
    Said(model)
    val popups = LocalPopups.current
    Rows {
        if (members.isEmpty()) SettingRow("No members yet", "Add someone below. They get a QR code or a link to sign up.")
        members.forEach { member ->
            ItemRow(member.displayName.ifBlank { member.username }, "${memberLine(member)} · ${storageLine(member.storageUsedBytes, member.storageLimitGb)}") {
                RowAction(RESET_PASSWORD, { askToReset(popups, model, member) }, enabled = !model.working)
            }
        }
    }
    AddMember(model)
}

// A new member: their username, the name the family sees, and what they
// may do. They get an invite to scan or open.
@Composable
private fun AddMember(model: FamilyModel) {
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(FamilyPreset.Member) }
    var away by remember { mutableStateOf(true) }
    Group("Add a member") {
        Card {
            Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.M)) {
                Txt(presetLine(preset), DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
                GlazeSegments(FamilyPreset.entries, preset, ::presetName, { preset = it })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = away, interactionSource = null, indication = FocusRing(Corner.RowShape), role = Role.Switch, onValueChange = { away = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.M),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(ALLOW_AWAY, DesktopType.body, OctoColors.TextPrimary)
                        Txt(ALLOW_AWAY_LINE, DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
                    }
                    // The row is the switch a screen reader hears; this only shows it.
                    OctoSwitch(away, { away = it }, Modifier.focusProperties { canFocus = false }.semantics { hideFromAccessibility() })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                    GlassField(name, { name = it }, Modifier.weight(1f), placeholder = "Their name, like Sam")
                    GlassField(username, { username = it.filterNot(Char::isWhitespace).lowercase() }, Modifier.weight(1f), placeholder = "Username, like sam")
                    GlazeCapsule(OctoIcons.Add, "Add", {
                        model.addMember(username, name, preset, away)
                        username = ""
                        name = ""
                    }, enabled = username.isNotBlank() && !model.working)
                }
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
            Cover(request.coverArt ?: request.target, Modifier.size(40.dp), shape = RoundedCornerShape(6.dp), online = (request.coverArt ?: request.target).startsWith("ext-"))
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
private fun ItemRow(title: String, line: String, state: FamilyRequest? = null, cover: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowHeight.Regular).hoverLift(Corner.RowShape).padding(horizontal = RowInset, vertical = Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        if (cover != null) Cover(cover, Modifier.size(40.dp), shape = RoundedCornerShape(6.dp), online = cover.startsWith("ext-"))
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
