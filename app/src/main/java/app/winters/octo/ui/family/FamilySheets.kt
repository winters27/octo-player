package app.winters.octo.ui.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.GlazeTabs
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyRole
import kotlinx.coroutines.delay
import java.time.Instant

// The Add a device sheet and the invite sheet: the same layout as the
// desktop's popups, stacked for a phone, rising from the bottom with a
// handle to pull it away.

private val Hairline = Color.White.copy(alpha = 0.08f)
private val Fill = Color.White.copy(alpha = 0.04f)
private val Edge = Color.White.copy(alpha = 0.08f)
private val TableShape = RoundedCornerShape(14.dp)
private val WellShape = RoundedCornerShape(22.dp)
private val PillShape = RoundedCornerShape(50)
private val QrSide = 216.dp
private val Mono = OctoType.bodySmall.copy(fontFamily = FontFamily.Monospace)
private val BigCode = OctoType.title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 1.sp)

@Composable
fun DeviceSheetHost(model: FamilyModel, server: String, username: String) {
    val sheet = model.sheet ?: return
    key(sheet.id) {
        // Renewing pauses while the app is out of sight.
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val watch = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> model.sheetOnScreen(true)
                    Lifecycle.Event.ON_STOP -> model.sheetOnScreen(false)
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(watch)
            onDispose { owner.lifecycle.removeObserver(watch) }
        }
        val context = LocalContext.current
        GlassSheet(visible = true, onDismiss = model::dismissAdded) {
            DeviceSheetContent(
                sheet,
                server,
                username,
                avatarName = sheet.forName ?: model.me?.displayName?.ifBlank { null } ?: username,
                owner = model.me?.role == FamilyRole.Owner,
                actions = PhoneSheetActions(
                    otherApps = model::showOtherApps,
                    backToCode = model::backToCode,
                    newCode = model::newCode,
                    retry = model::retrySheet,
                    done = model::dismissAdded,
                    open = { openLink(context, it) },
                    copy = { copyText(context, it) },
                ),
            )
        }
    }
}

@Composable
fun InviteSheetHost(model: FamilyModel, server: String) {
    val invite = model.invite ?: return
    val context = LocalContext.current
    GlassSheet(visible = true, onDismiss = model::closeInvite) {
        InviteSheetContent(
            invite,
            server,
            owner = model.me?.role == FamilyRole.Owner,
            newLink = model::sendNewLink,
            actions = PhoneSheetActions(done = model::closeInvite, open = { openLink(context, it) }, copy = { copyText(context, it) }),
        )
    }
}

class PhoneSheetActions(
    val otherApps: () -> Unit = {},
    val backToCode: () -> Unit = {},
    val newCode: () -> Unit = {},
    val retry: () -> Unit = {},
    val done: () -> Unit = {},
    val open: (String) -> Unit = {},
    val copy: (String) -> Unit = {},
)

@Composable
fun ColumnScope.DeviceSheetContent(sheet: DeviceSheet, server: String, username: String, avatarName: String, owner: Boolean, actions: PhoneSheetActions, now: () -> Instant = Instant::now) {
    val code = sheet.code
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000 - clock.toEpochMilli() % 1_000)
            clock = now()
        }
    }
    val seconds = secondsLeft(code?.expires, clock)
    val subtitle = when {
        sheet.view == DeviceSheetView.OtherApps -> OTHER_APPS_SUBTITLE
        sheet.waiting -> "Making a code"
        sheet.stale -> "This code expired"
        else -> countdownLine(seconds)
    }
    val warn = sheet.view == DeviceSheetView.Code && code != null && !sheet.stale && countdownWarns(seconds)
    val options = code?.let { deviceLinkOptions(it, sheet.server(server), sheet.awayAllowed) }
    var picked by remember { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options?.default ?: LinkReach.Anywhere
    val done = remember { FocusRequester() }
    Line {
        Header(avatarName, sheet.title, subtitle, if (warn) OctoColors.SignalOrange else OctoColors.TextMuted, clock = sheet.view == DeviceSheetView.Code, close = actions.done)
        if (sheet.view == DeviceSheetView.Code && code != null && !sheet.stale) Announce(countdownAnnouncement(seconds))
    }
    if (sheet.view == DeviceSheetView.Code && options?.choosable == true) Line { ReachTabs(reach) { picked = it } }
    val shown = sheet.shown
    when {
        shown == null && sheet.error != null -> Line { Problem(sheet.error!!, actions.retry) }
        shown == null -> Line { Skeleton(sheet.view) }
        sheet.view == DeviceSheetView.Code -> CodeBody(sheet, shown, options!!, reach, sheet.server(server), sheet.username(username), owner, server, actions)
        else -> AppsBody(shown, sheet.server(server), sheet.username(username), actions.copy)
    }
    Footer(done, actions.done) {
        if (sheet.view == DeviceSheetView.Code) QuietAction(OTHER_APPS_LINK, OctoIcons.Key, actions.otherApps)
        else QuietAction(BACK_TO_QR, OctoIcons.QrCode, actions.backToCode)
    }
}

@Composable
fun ColumnScope.InviteSheetContent(invite: InviteSheet, server: String, owner: Boolean, newLink: () -> Unit, actions: PhoneSheetActions) {
    val options = invite.options
    var picked by remember(invite.username) { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options.default
    val url = options.linkFor(reach)
    val done = remember { FocusRequester() }
    Line { Header(invite.name, invite.title, INVITE_SUBTITLE, OctoColors.TextMuted, clock = true, close = actions.done) }
    if (options.choosable) Line { ReachTabs(reach) { picked = it } }
    Line {
        Label("Scan")
        when {
            invite.loading || invite.url == null -> SkeletonBlock(QrWellSide, QrWellSide, 22.dp, Modifier.align(Alignment.CenterHorizontally))
            url == null -> Unavailable(server, owner, actions)
            else -> QrWell(url, "QR code for ${invite.name}'s invite link", dim = false)
        }
        if (url != null) Caption(if (reach == LinkReach.Home && options.choosable) HOME_ONLY else inviteCaption(invite.name), center = true)
    }
    Line {
        Label("Open the link")
        if (url != null) LinkRow(url, actions) else NoLink()
    }
    Line {
        Label("Then")
        Text(inviteNext(invite.name), style = OctoType.bodySmall, color = OctoColors.TextPrimary)
        invite.error?.let { Text(it, style = OctoType.caption, color = OctoColors.SignalOrange) }
    }
    Footer(done, actions.done) { QuietAction(SEND_NEW_LINK, OctoIcons.Share, newLink, enabled = !invite.loading) }
}

// One line of the sheet. The sheet scrolls line by line, so each part is
// its own line rather than one tall column.
@Composable
private fun Line(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Header(name: String, title: String, subtitle: String, subtitleColor: Color, clock: Boolean, close: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(Modifier.size(44.dp).background(OctoColors.AccentSelected, CircleShape).border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, style = OctoType.section, color = OctoColors.TextPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = OctoType.headline, color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (clock) Icon(painterResource(OctoIcons.Timer), contentDescription = null, tint = subtitleColor, modifier = Modifier.size(14.dp))
                Text(subtitle, style = OctoType.caption, color = subtitleColor)
            }
        }
        IconButton(onClick = close) {
            Icon(painterResource(OctoIcons.Close), contentDescription = "Close", tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Announce(words: String?) {
    if (words == null) return
    Box(Modifier.size(1.dp).semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = words
    })
}

@Composable
private fun ReachTabs(reach: LinkReach, pick: (LinkReach) -> Unit) {
    val all = LinkReach.entries
    GlazeTabs(all.size, all.indexOf(reach), { pick(all[it]) }, Modifier.fillMaxWidth().padding(vertical = 4.dp)) { index, chosen ->
        Text(
            all[index].label,
            style = OctoType.label,
            color = if (chosen) OctoColors.TextPrimary else OctoColors.TextSecondary,
            modifier = Modifier.semantics { contentDescription = all[index].label },
        )
    }
}

private val QrWellSide = QrSide + 28.dp

@Composable
private fun ColumnScope.QrWell(link: String, label: String, dim: Boolean, overlay: @Composable () -> Unit = {}) {
    val motion = motionScale()
    Box(
        Modifier.align(Alignment.CenterHorizontally).size(QrWellSide).background(Fill, WellShape).border(1.dp, Edge, WellShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(link, animationSpec = octoTween(motion, OctoDuration.Neutral), label = "qr") {
            QrTile(it, label, Modifier.alpha(if (dim) 0.12f else 1f))
        }
        overlay()
    }
}

// The QR code on white, with a generous quiet edge.
@Composable
private fun QrTile(text: String, label: String, modifier: Modifier) {
    val code = remember(text) { qrCode(text) }
    Box(modifier.size(QrSide).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(QrSide - 32.dp)) {
            val cell = size.width / code.size
            for (y in 0 until code.size) for (x in 0 until code.size) {
                if (code.isDark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun ColumnScope.Unavailable(server: String, owner: Boolean, actions: PhoneSheetActions) {
    Box(
        Modifier.align(Alignment.CenterHorizontally).size(QrWellSide).background(Fill, WellShape).border(1.dp, Edge, WellShape).padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(OctoIcons.Cloud), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(30.dp))
            Text(SET_OUTSIDE_FIRST, style = OctoType.body, color = OctoColors.TextPrimary, textAlign = TextAlign.Center)
            if (owner) GlazeButton(OPEN_STATUS, { actions.open(dashboardStatusPage(server)) }, size = ButtonSize.Small)
            else Text(ASK_OWNER_OUTSIDE, style = OctoType.caption, color = OctoColors.TextMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ColumnScope.CodeBody(
    sheet: DeviceSheet,
    code: FamilyDeviceAdded,
    options: LinkOptions,
    reach: LinkReach,
    server: String,
    username: String,
    owner: Boolean,
    ownServer: String,
    actions: PhoneSheetActions,
) {
    val link = options.linkFor(reach)
    val typed = if (reach == LinkReach.Home) options.home?.let(::linkServer) ?: server else server
    var notice by remember { mutableStateOf(false) }
    LaunchedEffect(sheet.renewed) {
        if (sheet.renewed > 0) {
            notice = true
            delay(NEW_CODE_MS)
            notice = false
        }
    }
    val motion = motionScale()
    val grey = Modifier.alpha(if (sheet.stale) 0.4f else 1f)
    Line {
        Label("Scan")
        if (link == null) {
            Unavailable(ownServer, owner, actions)
        } else {
            QrWell(link, CODE_QR_LABEL, dim = sheet.stale) {
                if (sheet.stale) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(STILL_THERE, style = OctoType.body, color = OctoColors.TextPrimary)
                        AccentButton(MAKE_NEW_CODE, onClick = actions.newCode, size = ButtonSize.Small)
                    }
                }
            }
        }
        if (notice) Announce(NEW_CODE)
        Crossfade(notice, Modifier.fillMaxWidth().heightIn(min = 22.dp), animationSpec = octoTween(motion, OctoDuration.Card), label = "notice") { showing ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showing) {
                    Row(Modifier.background(OctoColors.AccentSelected, PillShape).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(13.dp))
                        Text(NEW_CODE, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                    }
                } else if (link != null) {
                    Caption(if (reach == LinkReach.Home && options.choosable) HOME_ONLY else CODE_QR_CAPTION, center = true)
                }
            }
        }
    }
    Line(grey) {
        Label("Open the link")
        if (link != null) LinkRow(link, actions) else NoLink()
    }
    Line(grey) {
        Label("Type it in")
        Caption("In Octo, choose Join with a family code")
        Table {
            TableRow("Server", first = true) { Value(typed, Modifier.weight(1f)) }
            TableRow("Username") { Value(username, Modifier.weight(1f)) }
            TableRow("Code", tall = true) {
                Crossfade(code.pairCode.orEmpty(), Modifier.weight(1f), animationSpec = octoTween(motion, OctoDuration.Neutral), label = "digits") {
                    Text(groupedCode(it), style = BigCode, color = OctoColors.TextPrimary)
                }
                CopyButton(code.pairCode.orEmpty(), "Copy code", actions.copy)
            }
        }
    }
    if (sheet.refreshFailed) Line { Text(RENEW_FAILED, style = OctoType.caption, color = OctoColors.SignalOrange) }
}

@Composable
private fun ColumnScope.AppsBody(added: FamilyDeviceAdded, server: String, username: String, copy: (String) -> Unit) {
    val password = added.appPassword.orEmpty()
    Line { AppsRows(password, server, username, copy) }
    Line { AppsSteps(server, username) }
}

@Composable
private fun AppsRows(password: String, server: String, username: String, copy: (String) -> Unit) {
    Label("Sign in with these")
    Table {
        TableRow("Server", first = true) {
            Value(server, Modifier.weight(1f))
            CopyButton(server, "Copy server", copy)
        }
        TableRow("Username") {
            Value(username, Modifier.weight(1f))
            CopyButton(username, "Copy username", copy)
        }
        Hairline()
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("App password", style = OctoType.caption, color = OctoColors.TextMuted)
                Text(
                    SHOWN_ONCE,
                    style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = OctoColors.SignalOrange,
                    modifier = Modifier.padding(start = 8.dp).background(OctoColors.SignalOrange.copy(alpha = 0.14f), PillShape).padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupedPassword(password), style = BigCode.copy(fontSize = 19.sp), color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                CopyButton(password, "Copy app password", copy)
            }
        }
    }
}

@Composable
private fun AppsSteps(server: String, username: String) {
    Label("Steps for your app")
    var openApp by remember { mutableStateOf<String?>("Symfonium") }
    Table {
        otherAppSteps(server, username).forEachIndexed { index, app ->
            val expanded = openApp == app.app
            if (index > 0) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide steps" else "Show steps") { openApp = if (expanded) null else app.app }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(app.app, style = OctoType.label, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                Icon(painterResource(if (expanded) OctoIcons.Collapse else OctoIcons.Chevron), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.steps.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(20.dp).background(OctoColors.AccentTonal, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                            }
                            Text(step, style = OctoType.caption, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = OctoType.label, color = OctoColors.TextSecondary)

@Composable
private fun Caption(text: String, center: Boolean = false) =
    Text(text, style = OctoType.caption, color = OctoColors.TextMuted, textAlign = if (center) TextAlign.Center else null, modifier = if (center) Modifier.fillMaxWidth() else Modifier)

@Composable
private fun Value(text: String, modifier: Modifier = Modifier) = Text(text, style = Mono, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))

// The link in a soft pill: tap it to open it; the copy button copies all of it.
@Composable
private fun LinkRow(url: String, actions: PhoneSheetActions) {
    Row(
        Modifier.fillMaxWidth().background(Fill, PillShape).border(1.dp, Edge, PillShape).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(OctoIcons.Link), contentDescription = null, tint = OctoColors.Accent, modifier = Modifier.size(16.dp))
        Text(
            shownLink(url, 34),
            style = OctoType.bodySmall,
            color = OctoColors.AccentHover,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open the link") { actions.open(url) }
                .padding(vertical = 12.dp)
                .semantics { contentDescription = url },
        )
        CopyButton(url, "Copy link", actions.copy)
    }
}

@Composable
private fun NoLink() {
    Row(
        Modifier.fillMaxWidth().background(Fill, PillShape).border(1.dp, Edge, PillShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(OctoIcons.Link), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(16.dp))
        Text("No outside link yet", style = OctoType.caption, color = OctoColors.TextMuted)
    }
}

@Composable
private fun Table(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Fill, TableShape).border(1.dp, Edge, TableShape), content = content)
}

@Composable
private fun TableRow(label: String, first: Boolean = false, tall: Boolean = false, value: @Composable RowScope.() -> Unit) {
    if (!first) Hairline()
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tall) 58.dp else 46.dp).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.width(84.dp))
        value()
    }
}

// The icon turns to a check, with "Copied" beside it, for two seconds.
@Composable
private fun CopyButton(value: String, name: String, copy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (copied) {
            Text(COPIED, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.SignalGreen)
            Announce(COPIED)
        }
        IconButton(onClick = {
            copy(value)
            copied = true
        }) {
            Icon(
                painterResource(if (copied) OctoIcons.Check else OctoIcons.Copy),
                contentDescription = name,
                tint = if (copied) OctoColors.SignalGreen else OctoColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Problem(error: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Fill, TableShape).border(1.dp, Edge, TableShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(OctoIcons.Info), contentDescription = null, tint = OctoColors.SignalOrange, modifier = Modifier.size(20.dp))
            Text(error, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
        }
        GlazeButton(TRY_AGAIN, retry, size = ButtonSize.Small)
    }
}

@Composable
private fun ColumnScope.Skeleton(view: DeviceSheetView) {
    if (view == DeviceSheetView.OtherApps) {
        SkeletonBlock(120.dp, 12.dp, 6.dp)
        SkeletonBlock(Dp.Unspecified, 150.dp, 14.dp, Modifier.semantics { contentDescription = "Making an app password" })
        SkeletonBlock(Dp.Unspecified, 140.dp, 14.dp)
        return
    }
    SkeletonBlock(48.dp, 12.dp, 6.dp)
    SkeletonBlock(QrWellSide, QrWellSide, 22.dp, Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = "Making a code" })
    SkeletonBlock(96.dp, 12.dp, 6.dp)
    SkeletonBlock(Dp.Unspecified, 44.dp, 22.dp)
    SkeletonBlock(Dp.Unspecified, 150.dp, 14.dp)
}

@Composable
private fun SkeletonBlock(width: Dp, height: Dp, corner: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(corner)),
    )
}

@Composable
private fun QuietAction(text: String, icon: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(16.dp))
        Text(text, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = OctoColors.TextSecondary, maxLines = 2)
    }
}

// A hairline, the quiet action, and the one primary Done.
@Composable
private fun Footer(done: FocusRequester, close: () -> Unit, start: @Composable RowScope.() -> Unit) {
    Line(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Row(content = start) }
            AccentButton("Done", onClick = close, modifier = Modifier.width(112.dp).focusRequester(done), size = ButtonSize.Medium)
        }
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Octo", text))
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
