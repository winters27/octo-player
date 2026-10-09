package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.ui.common.Artwork
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.family.FamilyHub
import app.winters.octo.family.FamilyNotifier
import app.winters.octo.subsonic.FamilyDevice
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// A request's cover id as the picture to draw, on the server in use.
val LocalFamilyCovers = staticCompositionLocalOf<(String) -> String?> { { null } }

// The one FamilyHub, for screens and the shell.
@HiltViewModel
class FamilyShellViewModel @Inject constructor(val hub: FamilyHub) : ViewModel()

// Family on the server in use: a manager's requests waiting and members
// first, then what the account may do in plain words with the library's size, the
// account's requests, devices and saved songs. The server decides; this
// shows what it says.
@Composable
fun FamilyScreen(onBack: () -> Unit, owner: FamilyShellViewModel = hiltViewModel()) {
    val hub = owner.hub
    val model = hub.model
    DisposableEffect(Unit) {
        model.watch()
        onDispose { model.stop() }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFamilyCovers provides { id: String -> hub.coverRef(id) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
                item(key = "title") { ScreenTitle(FAMILY) }
                val info = model.info
                if (info == null) {
                    item(key = "waiting") { Line(model.problem ?: "Asking the server about your plan.") }
                } else {
                    item(key = "notices") { NoticesRow() }
                    model.said?.let { item(key = "said") { Line(it, OctoColors.TextSecondary) } }
                    if (model.approves) inbox(model)
                    info.manager?.let { members(model, it.members, it.liveStreams) }
                    plan(info.me)
                    requests(model)
                    devices(model)
                    saved(hub, info.me)
                }
            }
        }
        BackButton(onBack)
    }
    model.added?.let { AddedSheet(it, hub) }
    model.shown?.let { ShownSheet(it, model) }
    if (model.askingPassword) PasswordSheet(model)
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color = OctoColors.TextMuted) {
    Text(text, style = OctoType.bodySmall, color = color, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

private fun LazyListScope.title(key: String, text: String) {
    item(key = "title:$key") { SectionTitle(text, Modifier.padding(top = 16.dp)) }
}

// On Android 13 and later notices need the listener's yes; asked here,
// where it is clear what they are for.
@Composable
private fun NoticesRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(FamilyNotifier.canPost(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (allowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hear when a request is decided", style = OctoType.body, color = OctoColors.TextPrimary)
        Text("Octo can tell you when a request is approved, declined or added, and a manager when requests wait.", style = OctoType.caption, color = OctoColors.TextMuted)
        GlazeButton("Turn on notices", { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }, size = ButtonSize.Small)
    }
}

private fun LazyListScope.plan(me: FamilyMe) {
    val can = me.abilities
    title("plan", MY_PLAN)
    item(key = "plan:who") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(planTitle(me), style = OctoType.body, color = OctoColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your library", style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
                Text(storageLine(me.storageUsedBytes, can.storageLimitGb), style = OctoType.caption, color = OctoColors.TextSecondary)
            }
            storageShare(me.storageUsedBytes, can.storageLimitGb)?.let { Meter(it) }
            if (can.weeklyRequestLimit > 0 || me.requestsThisWeek > 0) {
                Text(weeklyLine(me.requestsThisWeek, can.weeklyRequestLimit), style = OctoType.caption, color = OctoColors.TextMuted)
            }
        }
    }
    items(abilityLines(can), key = { "ability:${it.text}" }) { line ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (line.on) OctoIcons.Check else OctoIcons.Close),
                contentDescription = null,
                tint = if (line.on) OctoColors.TextSecondary else OctoColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
            Text(line.text, style = OctoType.bodySmall, color = if (line.on) OctoColors.TextPrimary else OctoColors.TextMuted, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun Meter(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(OctoColors.TextMuted.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(OctoColors.Accent))
    }
}

private fun LazyListScope.requests(model: FamilyModel) {
    title("requests", REQUESTS)
    if (model.requests.isEmpty()) {
        item(key = "requests:none") { Line("No requests yet. Ask for a copy of a song found online from its menu: $REQUEST_A_COPY.") }
        return
    }
    items(model.requests, key = { "request:${it.id}" }) { request ->
        ItemLine(request.title.ifBlank { request.target }, listOf(request.artist, requestKindLine(request)).filter(String::isNotBlank).joinToString(" · "), state = request, cover = request.coverArt ?: request.target) {
            if (request.state == FamilyRequestState.Pending) GlazeButton("Cancel", { model.cancel(request.id) }, size = ButtonSize.ExtraSmall, enabled = !model.working)
        }
    }
}

private fun LazyListScope.devices(model: FamilyModel) {
    title("devices", DEVICES)
    items(model.devices, key = { "device:${it.id}" }) { device -> DeviceLine(model, device) }
    item(key = "devices:add") { AddDevice(model) }
}

@Composable
private fun DeviceLine(model: FamilyModel, device: FamilyDevice) {
    var confirming by remember(device.id) { mutableStateOf(false) }
    ItemLine(device.name.ifBlank { "A device" }, deviceLine(device)) {
        when {
            device.current -> Text("This phone", style = OctoType.caption, color = OctoColors.TextMuted)
            confirming -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccentButton("Sign out", onClick = {
                    confirming = false
                    model.signOut(device)
                }, size = ButtonSize.ExtraSmall, enabled = !model.working)
                GlazeButton("Keep", { confirming = false }, size = ButtonSize.ExtraSmall)
            }
            else -> GlazeButton("Sign out", { confirming = true }, size = ButtonSize.ExtraSmall, enabled = !model.working)
        }
    }
}

@Composable
private fun AddDevice(model: FamilyModel) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a device", style = OctoType.body, color = OctoColors.TextPrimary)
        Text("Octo on another phone or computer joins with a 6 digit code. Any other music app signs in with an app password.", style = OctoType.caption, color = OctoColors.TextMuted)
        GlassInput(name, { name = it }, placeholder = "Its name, like Living room", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlazeButton("Octo app", {
                model.addDevice(name, FamilyDeviceKind.OctoApp)
                name = ""
            }, size = ButtonSize.Small, enabled = !model.working)
            GlazeButton("Other app", {
                model.addDevice(name, FamilyDeviceKind.SubsonicApp)
                name = ""
            }, size = ButtonSize.Small, enabled = !model.working)
        }
    }
}

// The pair code as a QR code another phone's camera reads (with the code
// beside it for typing), or an app password, shown this once, each with a
// way to copy it.
@Composable
private fun AddedSheet(added: FamilyDeviceAdded, hub: FamilyHub) {
    val context = LocalContext.current
    val server = added.server?.takeIf(String::isNotBlank) ?: hub.serverAddress().orEmpty()
    val link = addedDeviceLink(added, server)
    GlassSheet(visible = true, onDismiss = hub.model::dismissAdded) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (link != null) "Add a device" else "App password", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            if (link != null) {
                QrImage(link, label = "QR code to add a device")
                Text(
                    "Scan this with the new device's camera, or in Octo there choose $JOIN_WITH_A_FAMILY_CODE and type ${added.username} and ${added.pairCode}. It works once.",
                    style = OctoType.bodySmall,
                    color = OctoColors.TextSecondary,
                )
            } else {
                Text(added.appPassword.orEmpty(), style = OctoType.title, color = OctoColors.TextPrimary)
                Text("In the other app, sign in to ${server.ifEmpty { "this server" }} as ${added.username} with this password. It shows only now.", style = OctoType.bodySmall, color = OctoColors.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link != null) {
                    GlazeButton("Copy link", { copy(context, link) }, size = ButtonSize.Small)
                    GlazeButton("Copy code", { copy(context, added.pairCode.orEmpty()) }, size = ButtonSize.Small)
                } else {
                    GlazeButton("Copy address", { copy(context, server) }, size = ButtonSize.Small)
                    GlazeButton("Copy password", { copy(context, added.appPassword.orEmpty()) }, size = ButtonSize.Small)
                }
                AccentButton("Done", onClick = hub.model::dismissAdded, size = ButtonSize.Small)
            }
        }
    }
}

// A manager's link (an invite, a member's new device) as a QR code, with a
// way to copy or send it, until closed.
@Composable
private fun ShownSheet(shown: ShownLink, model: FamilyModel) {
    val context = LocalContext.current
    GlassSheet(visible = true, onDismiss = model::closeShown) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(shown.title, style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            QrImage(shown.url, label = shown.title)
            Text(shown.note, style = OctoType.bodySmall, color = OctoColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlazeButton("Copy link", { copy(context, shown.url) }, size = ButtonSize.Small)
                GlazeButton("Send", {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, shown.url)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, shown.title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, size = ButtonSize.Small)
                AccentButton("Done", onClick = model::closeShown, size = ButtonSize.Small)
            }
        }
    }
}

// The family page wants the account's password before a manager's change.
@Composable
private fun PasswordSheet(model: FamilyModel) {
    var password by remember { mutableStateOf("") }
    GlassSheet(visible = true, onDismiss = model::cancelPassword) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your account password", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Text("This phone signs in with a code of its own. Changes to the family need your account password once.", style = OctoType.bodySmall, color = OctoColors.TextSecondary)
            GlassInput(
                password,
                { password = it },
                placeholder = "Password",
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("Go on", onClick = { model.signInToPage(password) }, size = ButtonSize.Small, enabled = password.isNotEmpty())
                GlazeButton("Cancel", model::cancelPassword, size = ButtonSize.Small)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Octo", text))
}

private fun LazyListScope.saved(hub: FamilyHub, me: FamilyMe) {
    val saved = hub.model.saved
    title("saved", SAVED)
    if (saved.isEmpty) {
        item(key = "saved:none") { Line("Songs and albums found online that you save show here.") }
        return
    }
    val asks = outsideActions(me).offersRequest
    items(saved.songs, key = { "saved:song:${it.id}" }) { song ->
        ItemLine(song.title, listOfNotNull(song.artist, song.album).joinToString(" · ")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (asks) GlazeButton("Request", { hub.ask(song.id, song.title) }, size = ButtonSize.ExtraSmall)
                GlazeButton("Remove", { hub.model.unsave(song.id, album = false) }, size = ButtonSize.ExtraSmall)
            }
        }
    }
    items(saved.albums, key = { "saved:album:${it.id}" }) { album ->
        ItemLine(album.name, "Album · ${album.artist.orEmpty()}") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (asks) GlazeButton("Request", { hub.ask(album.id, album.name) }, size = ButtonSize.ExtraSmall)
                GlazeButton("Remove", { hub.model.unsave(album.id, album = true) }, size = ButtonSize.ExtraSmall)
            }
        }
    }
}

private fun LazyListScope.members(model: FamilyModel, members: List<FamilyMember>, live: Int) {
    title("members", MEMBERS)
    if (live > 0) item(key = "members:live") { Line("$live playing now") }
    items(members, key = { "member:${it.username}" }) { member ->
        ItemLine(member.displayName.ifBlank { member.username }, "${memberLine(member)} · ${storageLine(member.storageUsedBytes, member.storageLimitGb)}") {
            GlazeButton("Add a device", { model.memberLink(member) }, size = ButtonSize.ExtraSmall, enabled = !model.working)
        }
    }
    item(key = "members:add") { AddMember(model) }
}

// A new member: the name the family sees, their username and what they may
// do. They get an invite as a QR code or a link.
@Composable
private fun AddMember(model: FamilyModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf(FamilyPreset.Member) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a member", style = OctoType.body, color = OctoColors.TextPrimary)
        GlassInput(name, { name = it }, placeholder = "Their name, like Sam", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        GlassInput(username, { username = it.filterNot(Char::isWhitespace).lowercase() }, placeholder = "Username, like sam", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FamilyPreset.entries.forEach { choice ->
                if (choice == preset) AccentButton(presetName(choice), onClick = { preset = choice }, size = ButtonSize.ExtraSmall)
                else GlazeButton(presetName(choice), { preset = choice }, size = ButtonSize.ExtraSmall)
            }
        }
        Text(presetLine(preset), style = OctoType.caption, color = OctoColors.TextMuted)
        AccentButton("Add and show their invite", onClick = {
            model.addMember(username, name, preset)
            username = ""
            name = ""
        }, size = ButtonSize.Small, enabled = username.isNotBlank() && !model.working)
    }
}

private fun LazyListScope.inbox(model: FamilyModel) {
    title("inbox", REQUESTS_WAITING)
    if (model.inbox.isEmpty()) {
        item(key = "inbox:none") { Line("Nothing waiting.") }
        return
    }
    items(model.inbox, key = { "inbox:${it.id}" }) { request -> InboxLine(model, request) }
}

// One waiting request: who asks, for what, a note for them, and the choice.
@Composable
private fun InboxLine(model: FamilyModel, request: FamilyRequest) {
    var note by rememberSaveable(request.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val id = request.coverArt ?: request.target
            LocalFamilyCovers.current(id)?.let { Artwork(it, 44.dp, Modifier.padding(end = 12.dp), shape = RoundedCornerShape(6.dp), outside = id.startsWith("ext-")) }
            Column(Modifier.weight(1f)) {
                Text(requestTitle(request), style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 2)
                Text("${request.displayName.ifBlank { request.username }} · ${requestKindLine(request)}", style = OctoType.caption, color = OctoColors.TextMuted)
            }
        }
        GlassInput(note, { note = it }, placeholder = "A note for them (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Approve", onClick = { model.approve(request.id, note) }, size = ButtonSize.Small, enabled = !model.working)
            GlazeButton("Decline", { model.decline(request.id, note) }, size = ButtonSize.Small, enabled = !model.working)
        }
    }
}

// A row of a list: its title, a line under it, a request's state when it
// is one, and actions at the end.
@Composable
private fun ItemLine(title: String, line: String, state: FamilyRequest? = null, cover: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val ref = cover?.let(LocalFamilyCovers.current)
        if (ref != null) Artwork(ref, 44.dp, Modifier.padding(end = 12.dp), shape = RoundedCornerShape(6.dp), outside = cover.startsWith("ext-"))
        Column(Modifier.weight(1f)) {
            Text(title, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1)
            if (line.isNotBlank()) Text(line, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2)
            state?.let { Text(requestStateLine(it), style = OctoType.caption, color = stateColor(it.state)) }
        }
        Box(Modifier.padding(start = 12.dp)) { actions() }
    }
}

private fun stateColor(state: FamilyRequestState) = when (state) {
    FamilyRequestState.Done -> OctoColors.SignalGreen
    FamilyRequestState.Declined, FamilyRequestState.Failed -> OctoColors.SignalOrange
    else -> OctoColors.TextSecondary
}

// The request sheet, over everything: which quality, the week's requests
// left, and whether the library is full. Opened from a song's menu and
// from Saved.
@Composable
fun FamilyRequestSheetHost(owner: FamilyShellViewModel = hiltViewModel()) {
    val hub = owner.hub
    val ask = hub.asking
    val me = hub.me
    GlassSheet(visible = ask != null && me != null, onDismiss = hub::closeSheet) {
        if (ask != null && me != null) RequestSheetBody(hub, ask, requestSheet(me))
    }
}

@Composable
private fun ColumnScope.RequestSheetBody(hub: FamilyHub, ask: app.winters.octo.family.CopyAsk, sheet: RequestSheet) {
    var picked by remember(ask) { mutableStateOf(sheet.initial) }
    Text(REQUEST_A_COPY, style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
    Text(ask.title, style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    sheet.choices.forEach { choice ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = choice.enabled, role = Role.RadioButton) { picked = choice.quality }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(choice.label, style = OctoType.body, color = if (choice.enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
                Text(choice.detail, style = OctoType.caption, color = OctoColors.TextMuted)
            }
            if (picked == choice.quality) Icon(painterResource(OctoIcons.Check), contentDescription = "Picked", tint = OctoColors.TextPrimary, modifier = Modifier.size(18.dp))
        }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sheet.storageLine?.let { Text(it, style = OctoType.caption, color = OctoColors.SignalOrange) }
        sheet.quotaLine?.let { Text(it, style = OctoType.caption, color = OctoColors.TextSecondary) }
        Text(sheet.approvalLine, style = OctoType.caption, color = OctoColors.TextMuted)
    }
    AccentButton("Request", onClick = { hub.request(ask, picked) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth())
}
