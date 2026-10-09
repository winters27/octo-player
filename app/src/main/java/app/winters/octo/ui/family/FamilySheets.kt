package app.winters.octo.ui.family

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import app.winters.octo.design.MonoFontFamily
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.SheetFill
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

// The Add a device sheet and the invite sheet: the same parts as the
// desktop's popups, laid out to fit one phone screen. The sheet opens all
// the way, its footer (the other-apps link and Done) stays put above the
// navigation bar, and when the rest still can't fit, a fade and a "More
// below" button say so.

private val Hairline = Color.White.copy(alpha = 0.08f)
private val Fill = Color.White.copy(alpha = 0.04f)
private val Edge = Color.White.copy(alpha = 0.08f)
private val CardShape = RoundedCornerShape(14.dp)
private val WellShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)
private val Mono = OctoType.bodySmall.copy(fontFamily = MonoFontFamily)
private val BigCode = OctoType.title.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = 0.5.sp)

// Test tags, for checking what is on screen.
const val SHEET_CODE_TAG = "family-sheet-code"
const val SHEET_DONE_TAG = "family-sheet-done"
const val SHEET_MORE_TAG = "family-sheet-more"

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
        GlassSheet(visible = true, onDismiss = model::dismissAdded, tall = true, scrolls = false) {
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
    GlassSheet(visible = true, onDismiss = model::closeInvite, tall = true, scrolls = false) {
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
    Header(avatarName, sheet.title, subtitle, if (warn) OctoColors.SignalOrange else OctoColors.TextMuted, clock = sheet.view == DeviceSheetView.Code, close = actions.done)
    if (sheet.view == DeviceSheetView.Code && code != null && !sheet.stale) Announce(countdownAnnouncement(seconds))
    val target = remember { BringIntoViewRequester() }
    ScrollArea(target) {
        if (sheet.view == DeviceSheetView.Code && options?.choosable == true) {
            ReachChooser(reachQuestion(own = sheet.forName == null), reach, options, owner, server, actions) { picked = it }
        }
        val shown = sheet.shown
        when {
            shown == null && sheet.error != null -> Problem(sheet.error!!, actions.retry)
            shown == null -> Skeleton(sheet.view)
            sheet.view == DeviceSheetView.Code -> CodeBody(sheet, shown, options!!, reach, sheet.server(server), sheet.username(username), owner, server, actions, target)
            else -> AppsBody(shown, sheet.server(server), sheet.username(username), actions.copy, target)
        }
    }
    Footer(actions.done) {
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
    Header(invite.name, invite.title, INVITE_SUBTITLE, OctoColors.TextMuted, clock = true, close = actions.done)
    val target = remember { BringIntoViewRequester() }
    ScrollArea(target) {
        if (options.choosable) ReachChooser(reachQuestion(own = false), reach, options, owner, server, actions) { picked = it }
        if (url != null || invite.loading || invite.url == null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = qrSide(maxWidth)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (url == null) SkeletonBlock(side + 16.dp, side + 16.dp, 20.dp)
                    else QrWell(url, "QR code for ${invite.name}'s invite link", side, dim = false)
                    if (url != null) Caption(inviteCaption(invite.name), center = true)
                }
            }
        }
        if (url != null) Section("Open the link") { LinkRow(url, actions) }
        Section("Then") {
            Text(inviteNext(invite.name), style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.bringIntoViewRequester(target))
            invite.error?.let { Text(it, style = OctoType.caption, color = OctoColors.SignalOrange) }
        }
    }
    Footer(actions.done) { QuietAction(SEND_NEW_LINK, OctoIcons.Share, newLink, enabled = !invite.loading) }
}

// The QR code: about 55% of the sheet's width, never under 200 dp.
private fun qrSide(width: Dp): Dp = (width * 0.55f).coerceAtLeast(200.dp).coerceAtMost(width - 40.dp)

// The part of the sheet that scrolls when it can't all fit: a soft fade
// at its bottom edge and a "More below" button that brings `target` (the
// code) into view, both gone once the bottom is reached.
@Composable
private fun ColumnScope.ScrollArea(target: BringIntoViewRequester, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        MoreBelow(scroll, target, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MoreBelow(scroll: ScrollState, target: BringIntoViewRequester, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(octoTween(motion, OctoDuration.Card)), exit = fadeOut(octoTween(motion, OctoDuration.Card))) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).background(Brush.verticalGradient(listOf(Color.Transparent, SheetFill))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .padding(bottom = 6.dp)
                    .background(OctoColors.AccentSelected, PillShape)
                    .border(1.dp, Edge, PillShape)
                    .clickable(role = Role.Button, onClickLabel = MORE_BELOW) {
                        scope.launch {
                            target.bringIntoView()
                            if (scroll.canScrollForward) scroll.animateScrollTo(scroll.maxValue)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(SHEET_MORE_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(MORE_BELOW, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                Icon(painterResource(OctoIcons.Collapse), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Header(name: String, title: String, subtitle: String, subtitleColor: Color, clock: Boolean, close: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(Modifier.size(40.dp).background(OctoColors.AccentSelected, CircleShape).border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, style = OctoType.body.copy(fontWeight = FontWeight.Bold), color = OctoColors.TextPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = OctoType.headline.copy(fontSize = 22.sp), color = OctoColors.TextPrimary, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (clock) Icon(painterResource(OctoIcons.Timer), contentDescription = null, tint = subtitleColor, modifier = Modifier.size(13.dp))
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

// Where the link will be used: a question, two equal choices (a radio
// group), and a line saying which address the chosen one goes through.
// Anywhere before the server has an outside address shows a lock, and
// choosing it explains what to do, right under the choices.
@Composable
fun ReachChooser(question: String, reach: LinkReach, options: LinkOptions, owner: Boolean, server: String, actions: PhoneSheetActions, pick: (LinkReach) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextSecondary)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReachOption(LinkReach.Anywhere, OctoIcons.Globe, reach == LinkReach.Anywhere, locked = !options.anywhereAvailable, Modifier.weight(1f).fillMaxHeight()) { pick(LinkReach.Anywhere) }
            ReachOption(LinkReach.Home, OctoIcons.Home, reach == LinkReach.Home, locked = false, Modifier.weight(1f).fillMaxHeight()) { pick(LinkReach.Home) }
        }
        val line = reachLine(reach, options)
        if (line != null) {
            Text(line, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Column(
                Modifier.fillMaxWidth().background(Fill, CardShape).border(1.dp, Edge, CardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(OctoIcons.Lock), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(16.dp))
                    Text(SET_OUTSIDE_FIRST, style = OctoType.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                }
                if (owner) GlazeButton(OPEN_STATUS, { actions.open(dashboardStatusPage(server)) }, size = ButtonSize.Small)
                else Text(ASK_OWNER_OUTSIDE, style = OctoType.caption, color = OctoColors.TextMuted)
            }
        }
    }
}

// One choice: its icon and word, filled in the accent with a check when
// chosen, outlined when not.
@Composable
private fun ReachOption(reach: LinkReach, icon: Int, chosen: Boolean, locked: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val motion = motionScale()
    val fill by animateColorAsState(if (chosen) OctoColors.Accent else Color.Transparent, octoTween(motion, OctoDuration.Fill), label = "reach fill")
    val ink by animateColorAsState(if (chosen) OnAccent else OctoColors.TextSecondary, octoTween(motion, OctoDuration.Fill), label = "reach ink")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, if (chosen) Color.Transparent else Edge, shape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .testTag("reach-${reach.name}"),
    ) {
        Row(
            // Room at the end for the check in the corner.
            Modifier.align(Alignment.Center).padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(painterResource(if (locked && !chosen) OctoIcons.Lock else icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            // 15 sp, smaller only when a narrow phone at a large text size
            // leaves no room, so the word is never cut.
            BasicText(
                reach.label,
                style = ChoiceType.copy(color = ink),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = ChoiceType.fontSize),
                modifier = Modifier.testTag("reach-${reach.name}-label"),
            )
        }
        // The check sits in the corner, taking no room from the word.
        if (chosen) Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = ink, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp))
    }
}

private val ChoiceType = OctoType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

// Words on the accent fill.
private val OnAccent = Color(0xFF0C0C0D)

@Composable
private fun QrWell(link: String, label: String, side: Dp, dim: Boolean, overlay: @Composable () -> Unit = {}) {
    val motion = motionScale()
    Box(Modifier.size(side + 16.dp).background(Fill, WellShape).border(1.dp, Edge, WellShape), contentAlignment = Alignment.Center) {
        Crossfade(link, animationSpec = octoTween(motion, OctoDuration.Neutral), label = "qr") {
            QrTile(it, label, side, Modifier.alpha(if (dim) 0.12f else 1f))
        }
        overlay()
    }
}

// The QR code on white, with a generous quiet edge.
@Composable
private fun QrTile(text: String, label: String, side: Dp, modifier: Modifier) {
    val code = remember(text) { qrCode(text) }
    val quiet = side / 13
    Box(modifier.size(side).background(Color.White, RoundedCornerShape(14.dp)).padding(quiet).semantics { contentDescription = label }) {
        Canvas(Modifier.size(side - quiet * 2)) {
            val cell = size.width / code.size
            for (y in 0 until code.size) for (x in 0 until code.size) {
                if (code.isDark(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun Section(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        content()
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
    owner: Boolean,
    ownServer: String,
    actions: PhoneSheetActions,
    target: BringIntoViewRequester,
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = qrSide(maxWidth)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (link != null) {
                QrWell(link, CODE_QR_LABEL, side, dim = sheet.stale) {
                    if (sheet.stale) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(STILL_THERE, style = OctoType.body, color = OctoColors.TextPrimary)
                            AccentButton(MAKE_NEW_CODE, onClick = actions.newCode, size = ButtonSize.Small, fill = OctoColors.Accent)
                        }
                    }
                }
            }
            if (notice) Announce(NEW_CODE)
            if (link != null) {
                Crossfade(notice, Modifier.fillMaxWidth().heightIn(min = 18.dp), animationSpec = octoTween(motion, OctoDuration.Card), label = "notice") { showing ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (showing) {
                            Row(Modifier.background(OctoColors.AccentSelected, PillShape).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(13.dp))
                                Text(NEW_CODE, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextPrimary)
                            }
                        } else {
                            Caption(CODE_QR_CAPTION, center = true)
                        }
                    }
                }
            }
        }
    }
    if (link != null) Section("Open the link", grey) { LinkRow(link, actions) }
    Section("Or type it in Octo", grey.bringIntoViewRequester(target)) {
        // One card: the code, and who it is for on which server.
        Column(
            Modifier.fillMaxWidth().background(Fill, CardShape).border(1.dp, Edge, CardShape).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 10.dp).testTag(SHEET_CODE_TAG),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(code.pairCode.orEmpty(), Modifier.weight(1f), animationSpec = octoTween(motion, OctoDuration.Neutral), label = "digits") {
                    Text(groupedCode(it), style = BigCode, color = OctoColors.TextPrimary)
                }
                CopyButton(code.pairCode.orEmpty(), "Copy code", actions.copy)
            }
            Text(codeForLine(username, typed), style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (sheet.refreshFailed) Text(RENEW_FAILED, style = OctoType.caption, color = OctoColors.SignalOrange)
}

@Composable
private fun AppsBody(added: FamilyDeviceAdded, server: String, username: String, copy: (String) -> Unit, target: BringIntoViewRequester) {
    val password = added.appPassword.orEmpty()
    Section("Sign in with these") {
        Card {
            CardRow("Server", first = true) {
                Value(server, Modifier.weight(1f))
                CopyButton(server, "Copy server", copy)
            }
            CardRow("Username") {
                Value(username, Modifier.weight(1f))
                CopyButton(username, "Copy username", copy)
            }
            Hairline()
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    Section("Steps for your app") {
        var openApp by remember { mutableStateOf<String?>("Symfonium") }
        Card(Modifier.bringIntoViewRequester(target)) {
            otherAppSteps(server, username).forEachIndexed { index, app ->
                val expanded = openApp == app.app
                if (index > 0) Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide steps" else "Show steps") { openApp = if (expanded) null else app.app }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
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
}

@Composable
private fun Label(text: String) = Text(text, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold), color = OctoColors.TextSecondary)

@Composable
private fun Caption(text: String, center: Boolean = false) =
    Text(text, style = OctoType.caption, color = OctoColors.TextMuted, textAlign = if (center) TextAlign.Center else null, modifier = if (center) Modifier.fillMaxWidth() else Modifier)

@Composable
private fun Value(text: String, modifier: Modifier = Modifier) = BasicText(
    text,
    style = Mono.copy(color = OctoColors.TextPrimary),
    maxLines = 1,
    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = Mono.fontSize),
    modifier = modifier,
)

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
        Icon(painterResource(OctoIcons.Link), contentDescription = null, tint = OctoColors.AccentHover, modifier = Modifier.size(16.dp))
        // Never cut: on a narrow phone at a large text size it takes a
        // second line.
        BasicText(
            shownLink(url, 32),
            style = OctoType.bodySmall.copy(color = OctoColors.AccentHover),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = OctoType.bodySmall.fontSize),
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open the link") { actions.open(url) }
                .padding(vertical = 10.dp)
                .semantics { contentDescription = url },
        )
        CopyButton(url, "Copy link", actions.copy)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Fill, CardShape).border(1.dp, Edge, CardShape), content = content)
}

@Composable
private fun CardRow(label: String, first: Boolean = false, value: @Composable RowScope.() -> Unit) {
    if (!first) Hairline()
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Modifier.fillMaxWidth().background(Fill, CardShape).border(1.dp, Edge, CardShape).padding(16.dp),
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
private fun Skeleton(view: DeviceSheetView) {
    if (view == DeviceSheetView.OtherApps) {
        SkeletonBlock(120.dp, 12.dp, 6.dp)
        SkeletonBlock(Dp.Unspecified, 150.dp, 14.dp, Modifier.semantics { contentDescription = "Making an app password" })
        SkeletonBlock(Dp.Unspecified, 140.dp, 14.dp)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = qrSide(maxWidth) + 16.dp
        SkeletonBlock(side, side, 20.dp, Modifier.semantics { contentDescription = "Making a code" })
    }
    SkeletonBlock(96.dp, 12.dp, 6.dp)
    SkeletonBlock(Dp.Unspecified, 44.dp, 22.dp)
    SkeletonBlock(Dp.Unspecified, 80.dp, 14.dp)
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

// The quiet action beside Done: a link in the accent.
@Composable
private fun QuietAction(text: String, icon: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.AccentHover, modifier = Modifier.size(16.dp))
        Text(text, style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = OctoColors.AccentHover)
    }
}

// Pinned under the part that scrolls, always in sight: a hairline, the
// quiet action, and the one primary Done in the accent.
@Composable
private fun Footer(close: () -> Unit, start: @Composable RowScope.() -> Unit) {
    Hairline()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { Row(content = start) }
        AccentButton("Done", onClick = close, modifier = Modifier.width(108.dp).testTag(SHEET_DONE_TAG), size = ButtonSize.Medium, fill = OctoColors.Accent)
    }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Octo", text))
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
