package app.winters.octo.ui.family

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
import app.winters.octo.subsonic.familyJoinLink
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SectionTitle
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle(FAMILY) }
            val info = model.info
            if (info == null) {
                item(key = "waiting") { Line(model.problem ?: "Asking the server about your plan.") }
            } else {
                item(key = "notices") { NoticesRow() }
                model.said?.let { item(key = "said") { Line(it, OctoColors.TextSecondary) } }
                if (model.approves) inbox(model)
                info.manager?.let { members(it.members, it.liveStreams) }
                plan(info.me)
                requests(model)
                devices(model)
                saved(hub, info.me)
            }
        }
        BackButton(onBack)
    }
    model.added?.let { AddedSheet(it, hub) }
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
        ItemLine(request.title.ifBlank { request.target }, listOf(request.artist, requestKindLine(request)).filter(String::isNotBlank).joinToString(" · "), state = request) {
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

// The code or password, shown this once, with a way to copy it.
@Composable
private fun AddedSheet(added: FamilyDeviceAdded, hub: FamilyHub) {
    val context = LocalContext.current
    val code = added.pairCode
    val secret = code ?: added.appPassword.orEmpty()
    val server = added.server.orEmpty()
    GlassSheet(visible = true, onDismiss = hub.model::dismissAdded) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (code != null) "Pair code" else "App password", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Text(secret, style = OctoType.title, color = OctoColors.TextPrimary)
            Text(
                if (code != null) "In Octo on the other device, choose $JOIN_WITH_A_FAMILY_CODE and type ${added.username} and this code. It works once."
                else "In the other app, sign in to ${server.ifEmpty { "this server" }} as ${added.username} with this password. It shows only now.",
                style = OctoType.bodySmall,
                color = OctoColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlazeButton("Copy", { copy(context, secret) }, size = ButtonSize.Small)
                if (code != null && server.isNotEmpty()) GlazeButton("Copy link", { copy(context, familyJoinLink(server, added.username, code)) }, size = ButtonSize.Small)
                AccentButton("Done", onClick = hub.model::dismissAdded, size = ButtonSize.Small)
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

private fun LazyListScope.members(members: List<FamilyMember>, live: Int) {
    title("members", MEMBERS)
    if (live > 0) item(key = "members:live") { Line("$live playing now") }
    items(members, key = { "member:${it.username}" }) { member ->
        ItemLine(member.displayName.ifBlank { member.username }, memberLine(member)) {
            Text(storageLine(member.storageUsedBytes, member.storageLimitGb), style = OctoType.caption, color = OctoColors.TextSecondary)
        }
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
        Text(requestTitle(request), style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 2)
        Text("${request.displayName.ifBlank { request.username }} · ${requestKindLine(request)}", style = OctoType.caption, color = OctoColors.TextMuted)
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
private fun ItemLine(title: String, line: String, state: FamilyRequest? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
