package app.winters.octo.desktop.family

import app.winters.octo.ui.family.linkServer
import androidx.compose.runtime.key
import app.winters.octo.ui.family.shownLink
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.CardEdge
import app.winters.octo.design.CardFill
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.LocalTabStops
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupHost
import app.winters.octo.design.Separator
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.openJoinLink
import app.winters.octo.desktop.system.openInBrowser
import app.winters.octo.desktop.ui.LocalWindowShown
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.ui.family.ASK_OWNER_OUTSIDE
import app.winters.octo.ui.family.BACK_TO_QR
import app.winters.octo.ui.family.CODE_QR_CAPTION
import app.winters.octo.ui.family.CODE_QR_LABEL
import app.winters.octo.ui.family.COPIED
import app.winters.octo.ui.family.COPIED_MS
import app.winters.octo.ui.family.DeviceSheet
import app.winters.octo.ui.family.DeviceSheetView
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.HOME_ONLY
import app.winters.octo.ui.family.INVITE_SUBTITLE
import app.winters.octo.ui.family.InviteSheet
import app.winters.octo.ui.family.LinkOptions
import app.winters.octo.ui.family.LinkReach
import app.winters.octo.ui.family.MAKE_NEW_CODE
import app.winters.octo.ui.family.NEW_CODE
import app.winters.octo.ui.family.NEW_CODE_MS
import app.winters.octo.ui.family.OPEN_STATUS
import app.winters.octo.ui.family.OTHER_APPS_LINK
import app.winters.octo.ui.family.OTHER_APPS_SUBTITLE
import app.winters.octo.ui.family.RENEW_FAILED
import app.winters.octo.ui.family.SEND_NEW_LINK
import app.winters.octo.ui.family.SET_OUTSIDE_FIRST
import app.winters.octo.ui.family.SHOWN_ONCE
import app.winters.octo.ui.family.STILL_THERE
import app.winters.octo.ui.family.TRY_AGAIN
import app.winters.octo.ui.family.countdownAnnouncement
import app.winters.octo.ui.family.countdownLine
import app.winters.octo.ui.family.countdownWarns
import app.winters.octo.ui.family.dashboardStatusPage
import app.winters.octo.ui.family.deviceLinkOptions
import app.winters.octo.ui.family.groupedCode
import app.winters.octo.ui.family.groupedPassword
import app.winters.octo.ui.family.inviteCaption
import app.winters.octo.ui.family.inviteNext
import app.winters.octo.ui.family.middleEllipsized
import app.winters.octo.ui.family.otherAppSteps
import app.winters.octo.ui.family.secondsLeft
import app.winters.octo.ui.family.server
import app.winters.octo.ui.family.username
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant

// The Add a device popup: the pair code as a QR code, its link and the code
// to type, renewed while it is open; or an app password for another app.
// `sheet` is what it shows, the model's own unless told otherwise.
fun showDeviceSheet(
    popups: PopupHost,
    app: AppState,
    model: FamilyModel,
    sheet: () -> DeviceSheet? = { model.sheet },
    now: () -> Instant = Instant::now,
) {
    val id = sheet()?.id ?: return
    // Keyed by the popup, so one opened in another's place starts afresh.
    popups.showCentred(width = SheetWidth, maxHeight = 800.dp) { close -> key(id) { DeviceSheetPopup(app, model, id, sheet, now, close) } }
}

@Composable
private fun DeviceSheetPopup(app: AppState, model: FamilyModel, id: Int, sheet: () -> DeviceSheet?, now: () -> Instant, close: () -> Unit) {
    DisposableEffect(Unit) { onDispose { if (model.sheet?.id == id) model.dismissAdded() } }
    val shown = LocalWindowShown.current
    LaunchedEffect(shown) { model.sheetOnScreen(shown) }
    val current = sheet()
    if (current == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val client = app.connection?.client
    val server = client?.primaryUrl?.toString()?.removeSuffix("/").orEmpty()
    DeviceSheetCard(
        current,
        server = server,
        username = client?.username.orEmpty(),
        avatarName = current.forName ?: model.me?.displayName?.ifBlank { null } ?: client?.username.orEmpty(),
        actions = SheetActions(
            otherApps = model::showOtherApps,
            backToCode = model::backToCode,
            newCode = model::newCode,
            retry = model::retrySheet,
            done = close,
            open = app::openJoinLink,
            openPage = { openInBrowser(it, app.os) },
            owner = model.me?.role == FamilyRole.Owner,
        ),
        copy = ::copyText,
        now = now,
    )
}

// A new member's invite: the QR code and link that let them choose a
// password and join.
fun showInvite(popups: PopupHost, app: AppState, model: FamilyModel, invite: () -> InviteSheet? = { model.invite }) {
    if (invite() == null) return
    invites += 1
    val id = invites
    popups.showCentred(width = SheetWidth, maxHeight = 800.dp) { close -> key(id) { InvitePopup(app, model, invite, close) } }
}

private var invites = 0

@Composable
private fun InvitePopup(app: AppState, model: FamilyModel, invite: () -> InviteSheet?, close: () -> Unit) {
    DisposableEffect(Unit) { onDispose { model.closeInvite() } }
    val current = invite()
    if (current == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val server = app.connection?.client?.primaryUrl?.toString()?.removeSuffix("/").orEmpty()
    InviteCard(
        current,
        server,
        newLink = model::sendNewLink,
        actions = SheetActions(
            done = close,
            open = app::openJoinLink,
            openPage = { openInBrowser(it, app.os) },
            owner = model.me?.role == FamilyRole.Owner,
        ),
        copy = ::copyText,
    )
}

// What the popup's buttons do, and whether its viewer owns the family (who
// can set the outside address).
class SheetActions(
    val otherApps: () -> Unit = {},
    val backToCode: () -> Unit = {},
    val newCode: () -> Unit = {},
    val retry: () -> Unit = {},
    val done: () -> Unit = {},
    val open: (String) -> Unit = {},
    val openPage: (String) -> Unit = {},
    val owner: Boolean = false,
)

private fun SheetActions.handingFocusTo(done: FocusRequester): SheetActions {
    fun (() -> Unit).first(): () -> Unit = {
        runCatching { done.requestFocus() }
        this()
    }
    return SheetActions(otherApps.first(), backToCode.first(), newCode.first(), retry.first(), this.done, open, openPage, owner)
}

private val SheetWidth = 640.dp
private val QrSide = 188.dp
// The system's own coding type where it has one (Cascadia Mono, then
// Consolas, on Windows), else its monospace.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val MonoFamily: FontFamily = runCatching {
    val name = listOf("Cascadia Mono", "Consolas", "JetBrains Mono", "DejaVu Sans Mono", "Menlo").firstOrNull { org.jetbrains.skia.FontMgr.default.matchFamily(it).count() > 0 }
    if (name == null) FontFamily.Monospace
    else FontFamily(
        androidx.compose.ui.text.platform.SystemFont(name, FontWeight.Normal),
        androidx.compose.ui.text.platform.SystemFont(name, FontWeight.SemiBold),
    )
}.getOrDefault(FontFamily.Monospace)
private val Mono = DesktopType.table.copy(fontFamily = MonoFamily)
private val BigCode = DesktopType.table.copy(fontFamily = MonoFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
private val SectionLabel = DesktopType.label
private val TableShape = RoundedCornerShape(12.dp)
private val WellShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)

@Composable
fun DeviceSheetCard(
    sheet: DeviceSheet,
    server: String,
    username: String,
    avatarName: String,
    actions: SheetActions,
    copy: (String) -> Unit,
    now: () -> Instant = Instant::now,
) {
    val done = remember { FocusRequester() }
    FocusOnDone(done)
    // A control that goes away with the change it makes (the switch, the
    // view's link, the new code button) first hands the keyboard to Done.
    val actions = actions.handingFocusTo(done)
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
    var picked by remember(sheet.id) { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options?.default ?: LinkReach.Anywhere
    SheetFrame {
        Header(
            avatarName,
            sheet.title,
            subtitle,
            if (warn) OctoColors.SignalOrange else OctoColors.TextMuted,
            clock = sheet.view == DeviceSheetView.Code,
            close = actions.done,
        )
        if (sheet.view == DeviceSheetView.Code && code != null && !sheet.stale) Announce(countdownAnnouncement(seconds))
        if (sheet.view == DeviceSheetView.Code && options?.choosable == true) ReachSwitch(reach) { picked = it }
        val shown = sheet.shown
        when {
            shown == null && sheet.error != null -> Problem(sheet.error!!, actions.retry)
            shown == null -> Skeleton(sheet.view)
            sheet.view == DeviceSheetView.Code -> CodeBody(sheet, shown, options!!, reach, sheet.server(server), sheet.username(username), actions, copy)
            else -> AppsBody(shown, sheet.server(server), sheet.username(username), copy)
        }
        Footer(done, actions.done) {
            if (sheet.view == DeviceSheetView.Code) TextAction(OTHER_APPS_LINK, actions.otherApps, icon = OctoIcons.Key)
            else TextAction(BACK_TO_QR, actions.backToCode, icon = OctoIcons.QrCode)
        }
    }
}

@Composable
fun InviteCard(invite: InviteSheet, server: String, newLink: () -> Unit, actions: SheetActions, copy: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    FocusOnDone(focus)
    val options = invite.options
    var picked by remember(invite.username) { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options.default
    SheetFrame {
        Header(invite.name, invite.title, INVITE_SUBTITLE, OctoColors.TextMuted, clock = true, close = actions.done)
        if (options.choosable) ReachSwitch(reach) { picked = it }
        val url = options.linkFor(reach)
        Columns(
            left = {
                Label("Scan")
                when {
                    invite.loading || invite.url == null -> SkeletonBlock(QrWell, QrWell, 20.dp)
                    url == null -> Unavailable(server, actions)
                    else -> QrWell(url, "QR code for ${invite.name}'s invite link", dim = false)
                }
                if (url != null) Caption(if (reach == LinkReach.Home && options.choosable) HOME_ONLY else inviteCaption(invite.name), center = true)
            },
            right = {
                Label("Open the link")
                if (url != null) LinkRow(url, "Copy link", copy, actions.open) else NoLink()
                Spacer(Modifier.height(6.dp))
                Label("Then")
                Txt(inviteNext(invite.name), DesktopType.body, OctoColors.TextPrimary, maxLines = 3)
                invite.error?.let { Txt(it, DesktopType.meta, OctoColors.SignalOrange, maxLines = 2) }
            },
        )
        Footer(focus, actions.done) {
            TextAction(SEND_NEW_LINK, newLink, enabled = !invite.loading, icon = OctoIcons.Share)
        }
    }
}

@Composable
private fun SheetFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

// Focus starts on Done, once the popup has laid out.
@Composable
private fun FocusOnDone(done: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { done.requestFocus() }
    }
}

// Whose device it is (their initial), the title, and how long the code
// lasts beside a clock.
@Composable
private fun Header(name: String, title: String, subtitle: String, subtitleColor: Color, clock: Boolean, close: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(title, OctoType.headline, OctoColors.TextPrimary, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (clock) Glyph(OctoIcons.Timer, size = 14.dp, tint = subtitleColor)
                Txt(subtitle, DesktopType.meta, subtitleColor, maxLines = 2)
            }
        }
        // Escape closes it too, so the keyboard skips this.
        CompositionLocalProvider(LocalTabStops provides false) {
            IconAction(OctoIcons.Close, "Close", close, size = 32.dp, iconSize = 18.dp)
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(44.dp).background(OctoColors.AccentSelected, CircleShape).border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Txt(initial, OctoType.section, OctoColors.TextPrimary)
    }
}

// Works anywhere, or at home only: which link the QR code and the link row
// carry. The code is the same either way.
@Composable
private fun ReachSwitch(reach: LinkReach, pick: (LinkReach) -> Unit) {
    GlazeSegments(LinkReach.entries, reach, LinkReach::label, pick)
}

// Read out once, politely, when it changes; nothing to see.
@Composable
private fun Announce(words: String?) {
    if (words == null) return
    Box(Modifier.size(1.dp).semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = words
    })
}

private val QrWell = QrSide + 28.dp

// Two columns side by side, or one above the other when narrow.
@Composable
private fun Columns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(QrWell), verticalArrangement = Arrangement.spacedBy(8.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = left)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = right)
            }
        }
    }
}

// The QR code: a white tile with a generous quiet edge, set in the glass.
@Composable
private fun QrWell(link: String, label: String, dim: Boolean, overlay: @Composable () -> Unit = {}) {
    val motion = motionScale()
    Box(
        Modifier.size(QrWell).background(CardFill, WellShape).border(1.dp, CardEdge, WellShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(link, animationSpec = octoTween(motion, OctoDuration.Neutral), label = "qr") {
            QrImage(it, Modifier.alpha(if (dim) 0.12f else 1f), side = QrSide, label = label, quiet = 14.dp, corner = 14.dp)
        }
        overlay()
    }
}

// Anywhere chosen before the server has an outside address.
@Composable
private fun Unavailable(server: String, actions: SheetActions) {
    Box(Modifier.size(QrWell).background(CardFill, WellShape).border(1.dp, CardEdge, WellShape).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(OctoIcons.Cloud, size = 28.dp, tint = OctoColors.TextMuted)
            Txt(SET_OUTSIDE_FIRST, DesktopType.emphasis, OctoColors.TextPrimary, maxLines = 2, align = TextAlign.Center)
            if (actions.owner) {
                GlazeCapsule(null, OPEN_STATUS, { actions.openPage(dashboardStatusPage(server)) }, height = 34.dp)
            } else {
                Txt(ASK_OWNER_OUTSIDE, DesktopType.meta, OctoColors.TextMuted, maxLines = 3, align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun NoLink() {
    Row(
        Modifier.fillMaxWidth().background(CardFill, PillShape).border(1.dp, CardEdge, PillShape).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(OctoIcons.Link, size = 16.dp, tint = OctoColors.TextMuted)
        Txt("No outside link yet", DesktopType.meta, OctoColors.TextMuted)
    }
}

@Composable
private fun CodeBody(
    sheet: DeviceSheet,
    code: FamilyDeviceAdded,
    options: LinkOptions,
    reach: LinkReach,
    server: String,
    username: String,
    actions: SheetActions,
    copy: (String) -> Unit,
) {
    val link = options.linkFor(reach)
    // At home, the address to type is the home one.
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
    val grey = if (sheet.stale) Modifier.alpha(0.4f) else Modifier
    Columns(
        left = {
            Label("Scan")
            if (link == null) {
                Unavailable(server, actions)
            } else {
                QrWell(link, CODE_QR_LABEL, dim = sheet.stale) {
                    if (sheet.stale) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Txt(STILL_THERE, DesktopType.emphasis, OctoColors.TextPrimary)
                            GlazeCapsule(null, MAKE_NEW_CODE, actions.newCode, height = 36.dp)
                        }
                    }
                }
            }
            if (notice) Announce(NEW_CODE)
            Crossfade(notice, Modifier.width(QrWell).heightIn(min = 22.dp), animationSpec = octoTween(motion, OctoDuration.Card), label = "notice") { showing ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (showing) {
                        Row(
                            Modifier.background(OctoColors.AccentSelected, PillShape).padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Glyph(OctoIcons.Check, size = 13.dp)
                            Txt(NEW_CODE, DesktopType.label, OctoColors.TextPrimary)
                        }
                    } else if (link != null) {
                        Caption(if (reach == LinkReach.Home && options.choosable) HOME_ONLY else CODE_QR_CAPTION, center = true)
                    }
                }
            }
        },
        right = {
            Column(grey, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Open the link")
                if (link != null) LinkRow(link, "Copy link", copy, actions.open) else NoLink()
                Spacer(Modifier.height(10.dp))
                Label("Type it in")
                Caption("In Octo, choose Join with a family code")
                Table {
                    TableRow("Server", first = true) { Value(typed, Modifier.weight(1f)) }
                    TableRow("Username") { Value(username, Modifier.weight(1f)) }
                    TableRow("Code", tall = true) {
                        Crossfade(code.pairCode.orEmpty(), Modifier.weight(1f), animationSpec = octoTween(motion, OctoDuration.Neutral), label = "digits") {
                            Txt(groupedCode(it), BigCode, OctoColors.TextPrimary)
                        }
                        CopyButton(code.pairCode.orEmpty(), "Copy code", copy)
                    }
                }
            }
            if (sheet.refreshFailed) Txt(RENEW_FAILED, DesktopType.meta, OctoColors.SignalOrange, maxLines = 2)
        },
    )
}

@Composable
private fun AppsBody(added: FamilyDeviceAdded, server: String, username: String, copy: (String) -> Unit) {
    val password = added.appPassword.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TableRow("App password", tall = true) {
                Txt(groupedPassword(password), BigCode.copy(fontSize = 19.sp, letterSpacing = 1.sp), OctoColors.TextPrimary)
                Txt(
                    SHOWN_ONCE,
                    DesktopType.label,
                    OctoColors.SignalOrange,
                    Modifier.padding(start = 10.dp).background(OctoColors.SignalOrange.copy(alpha = 0.14f), PillShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                CopyButton(password, "Copy app password", copy)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Steps for your app")
        var openApp by remember { mutableStateOf<String?>("Symfonium") }
        Table {
            otherAppSteps(server, username).forEachIndexed { index, app ->
                val expanded = openApp == app.app
                if (index > 0) Separator()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hoverLift()
                        .focusProperties { canFocus = false }
                        .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide steps" else "Show steps") { openApp = if (expanded) null else app.app }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(app.app, DesktopType.tableTitle, OctoColors.TextPrimary, Modifier.weight(1f))
                    Glyph(if (expanded) OctoIcons.Expand else OctoIcons.Collapse, size = 16.dp, tint = OctoColors.TextMuted)
                }
                if (expanded) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        app.steps.forEachIndexed { i, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(20.dp).background(OctoColors.AccentTonal, CircleShape), contentAlignment = Alignment.Center) {
                                    Txt("${i + 1}", DesktopType.label, OctoColors.TextPrimary)
                                }
                                Txt(step, DesktopType.meta, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Txt(text, SectionLabel, OctoColors.TextSecondary)

@Composable
private fun Caption(text: String, center: Boolean = false) =
    Txt(text, DesktopType.meta, OctoColors.TextMuted, if (center) Modifier.fillMaxWidth() else Modifier, maxLines = 2, align = if (center) TextAlign.Center else null)

@Composable
private fun Value(text: String, modifier: Modifier = Modifier) = Txt(text, Mono, OctoColors.TextPrimary, modifier)

// The link in a soft pill: its icon, the link to click in the accent (its
// middle left out when long, all of it in the tooltip and the copy), and a
// copy button.
@Composable
private fun LinkRow(url: String, copyName: String, copy: (String) -> Unit, open: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardFill, PillShape).border(1.dp, CardEdge, PillShape).padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(OctoIcons.Link, size = 16.dp, tint = OctoColors.Accent)
        OctoTooltip(url, Modifier.weight(1f)) {
            Txt(
                shownLink(url),
                DesktopType.meta.copy(fontWeight = FontWeight.Medium),
                OctoColors.AccentHover,
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClickLabel = "Open the link") { open(url) }
                    .semantics { contentDescription = url },
            )
        }
        CopyButton(url, copyName, copy)
    }
}

@Composable
private fun Table(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardFill, TableShape).border(1.dp, CardEdge, TableShape), content = content)
}

@Composable
private fun TableRow(label: String, first: Boolean = false, tall: Boolean = false, value: @Composable RowScope.() -> Unit) {
    if (!first) Separator()
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 42.dp).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, DesktopType.meta, OctoColors.TextMuted, Modifier.width(92.dp))
        value()
    }
}

// A copy button: the icon turns to a check, with "Copied" beside it, for two
// seconds.
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
            Txt(COPIED, DesktopType.label, OctoColors.SignalGreen)
            Announce(COPIED)
        }
        IconAction(
            if (copied) OctoIcons.Check else OctoIcons.Copy,
            name,
            {
                copy(value)
                copied = true
            },
            size = 34.dp,
            iconSize = 18.dp,
            tint = if (copied) OctoColors.SignalGreen else Color.White,
        )
    }
}

@Composable
private fun Problem(error: String, retry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardFill, TableShape).border(1.dp, CardEdge, TableShape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(OctoIcons.Info, size = 20.dp, tint = OctoColors.SignalOrange)
        Txt(error, DesktopType.body, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 2)
        GlazeCapsule(null, TRY_AGAIN, retry, height = 36.dp)
    }
}

// Grey shapes where the QR code and rows will be, while the server answers.
@Composable
private fun Skeleton(view: DeviceSheetView) {
    if (view == DeviceSheetView.OtherApps) {
        Column(Modifier.semantics { contentDescription = "Making an app password" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(120.dp, 12.dp, 6.dp)
            SkeletonBlock(Dp.Unspecified, 140.dp, 12.dp)
            SkeletonBlock(Dp.Unspecified, 120.dp, 12.dp)
        }
        return
    }
    Columns(
        left = {
            SkeletonBlock(48.dp, 12.dp, 6.dp)
            SkeletonBlock(QrWell, QrWell, 20.dp, Modifier.semantics { contentDescription = "Making a code" })
        },
        right = {
            SkeletonBlock(96.dp, 12.dp, 6.dp)
            SkeletonBlock(Dp.Unspecified, 40.dp, 20.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonBlock(80.dp, 12.dp, 6.dp)
            SkeletonBlock(Dp.Unspecified, 142.dp, 12.dp)
        },
    )
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

// A hairline, then the quiet action on the left and the one primary Done.
@Composable
private fun Footer(done: FocusRequester, close: () -> Unit, start: @Composable RowScope.() -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Separator()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            start()
            Spacer(Modifier.weight(1f))
            AccentButton("Done", close, Modifier.width(120.dp).focusRequester(done), size = ButtonSize.Medium)
        }
    }
}

private fun copyText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
