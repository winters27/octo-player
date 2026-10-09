package app.winters.octo.desktop.family

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.LocalTabStops
import app.winters.octo.design.MonoFontFamily
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupHost
import app.winters.octo.design.Separator
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.openJoinLink
import app.winters.octo.desktop.system.openInBrowser
import app.winters.octo.desktop.ui.LocalWindowShown
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.ui.family.ASK_OWNER_OUTSIDE
import app.winters.octo.ui.family.COPIED
import app.winters.octo.ui.family.COPIED_MS
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.INVITE_SUBTITLE
import app.winters.octo.ui.family.InviteSheet
import app.winters.octo.ui.family.LinkOptions
import app.winters.octo.ui.family.LinkReach
import app.winters.octo.ui.family.MAKE_NEW_CODE
import app.winters.octo.ui.family.MORE_BELOW
import app.winters.octo.ui.family.NEW_CODE
import app.winters.octo.ui.family.NEW_CODE_MS
import app.winters.octo.ui.family.OPEN_STATUS
import app.winters.octo.ui.family.RENEW_FAILED
import app.winters.octo.ui.family.SEND_NEW_LINK
import app.winters.octo.ui.family.SET_OUTSIDE_FIRST
import app.winters.octo.ui.family.STILL_THERE
import app.winters.octo.ui.family.TRY_AGAIN
import app.winters.octo.ui.family.countdownAnnouncement
import app.winters.octo.ui.family.countdownLine
import app.winters.octo.ui.family.countdownWarns
import app.winters.octo.ui.family.dashboardStatusPage
import app.winters.octo.ui.family.inviteCaption
import app.winters.octo.ui.family.inviteNext
import app.winters.octo.ui.family.linkServer
import app.winters.octo.ui.family.reachLine
import app.winters.octo.ui.family.reachQuestion
import app.winters.octo.ui.family.secondsLeft
import app.winters.octo.ui.family.shownLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import app.winters.octo.subsonic.FamilySignInPending
import app.winters.octo.ui.family.ALLOW
import app.winters.octo.ui.family.DENY
import app.winters.octo.ui.family.HANDOVER_CAPTION
import app.winters.octo.ui.family.HANDOVER_TITLE
import app.winters.octo.ui.family.HandOverSheet
import app.winters.octo.ui.family.askingTitle

// A new member's invite: the QR code and link that let them choose a
// password and join.
fun showInvite(popups: PopupHost, app: AppState, model: FamilyModel, invite: () -> InviteSheet? = { model.invite }) {
    if (invite() == null) return
    invites += 1
    val id = invites
    popups.showFamilyDialog { close ->
        key(id) { InvitePopup(app, model, invite, close) }
    }
}

private var invites = 0

@Composable
private fun ColumnScope.InvitePopup(app: AppState, model: FamilyModel, invite: () -> InviteSheet?, close: () -> Unit) {
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

// "Sign in on another device": the QR code that hands this app's sign-in to
// another device of the same person, renewed while it shows, and the
// question when a device scans it. A dialog in the middle of the window,
// over the page dimmed and blurred.
fun showHandOver(popups: PopupHost, app: AppState, model: FamilyModel, now: () -> Instant = Instant::now) {
    val source = model.handOver
    if (source.sheet == null) source.open()
    val id = source.sheet?.id ?: return
    popups.showFamilyDialog { close ->
        key(id) { HandOverPopup(app, model, id, now, close) }
    }
}

@Composable
private fun ColumnScope.HandOverPopup(app: AppState, model: FamilyModel, id: Int, now: () -> Instant, close: () -> Unit) {
    val source = model.handOver
    DisposableEffect(Unit) { onDispose { if (source.sheet?.id == id) source.close() } }
    val shown = LocalWindowShown.current
    LaunchedEffect(shown) { source.sheetOnScreen(shown) }
    val sheet = source.sheet
    if (sheet == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val server = app.connection?.client?.primaryUrl?.toString()?.removeSuffix("/").orEmpty()
    val actions = SheetActions(
        newCode = source::newCode,
        retry = source::retry,
        done = close,
        open = app::openJoinLink,
        openPage = { openInBrowser(it, app.os) },
        owner = model.me?.role == FamilyRole.Owner,
    )
    val asking = sheet.asking
    if (asking != null) {
        AskingCard(asking, sending = sheet.sending, allow = source::allow, deny = source::deny)
        return
    }
    HandOverCard(
        sheet,
        awayAllowed = model.me?.abilities?.away != false,
        server = server,
        avatarName = model.me?.displayName?.ifBlank { null } ?: app.connection?.client?.username.orEmpty(),
        actions = actions,
        copy = ::copyText,
        now = now,
    )
}

@Composable
fun ColumnScope.HandOverCard(
    sheet: HandOverSheet,
    awayAllowed: Boolean,
    server: String,
    avatarName: String,
    actions: SheetActions,
    copy: (String) -> Unit,
    now: () -> Instant = Instant::now,
) {
    val done = remember { FocusRequester() }
    FocusOnDone(done)
    // A control that goes away with the change it makes first hands the
    // keyboard to Done.
    val actions = actions.handingFocusTo(done)
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000 - clock.toEpochMilli() % 1_000)
            clock = now()
        }
    }
    val start = sheet.start
    val seconds = secondsLeft(start?.expires, clock)
    val ended = sheet.done != null || sheet.failed != null
    val subtitle = when {
        sheet.done != null -> "Handed over"
        sheet.waiting -> "Making a code"
        sheet.stale -> "This code expired"
        else -> countdownLine(seconds)
    }
    val warn = start != null && !sheet.stale && !ended && countdownWarns(seconds)
    val options = sheet.options(awayAllowed)
    var picked by remember(sheet.id) { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options?.default ?: LinkReach.Anywhere
    Header(avatarName, HANDOVER_TITLE, subtitle, if (warn) OctoColors.SignalOrange else OctoColors.TextMuted, clock = !ended, close = actions.done)
    if (start != null && !sheet.stale && !ended) Announce(countdownAnnouncement(seconds))
    val target = remember { BringIntoViewRequester() }
    val finished = sheet.done
    val failed = sheet.failed
    val error = sheet.error
    ScrollArea(target) {
        when {
            finished != null -> Finished(finished)
            failed != null -> Problem(failed, actions.newCode)
            error != null && start == null -> Problem(error, actions.retry)
            options == null -> Columns(
                left = { SkeletonBlock(QrWellSide, QrWellSide, 20.dp, Modifier.semantics { contentDescription = "Making a code" }) },
                right = {
                    SkeletonBlock(96.dp, 12.dp, 6.dp)
                    SkeletonBlock(Dp.Unspecified, 40.dp, 20.dp)
                },
            )
            else -> {
                if (options.choosable) ReachChooser(reachQuestion(own = true), reach, options, server, actions) { picked = it }
                HandOverBody(sheet, options, reach, actions, copy, target)
            }
        }
    }
    Footer(done, actions.done)
}

@Composable
private fun HandOverBody(sheet: HandOverSheet, options: LinkOptions, reach: LinkReach, actions: SheetActions, copy: (String) -> Unit, target: BringIntoViewRequester) {
    val link = options.linkFor(reach)
    var notice by remember { mutableStateOf(false) }
    LaunchedEffect(sheet.renewed) {
        if (sheet.renewed > 0) {
            notice = true
            delay(NEW_CODE_MS)
            notice = false
        }
    }
    val motion = motionScale()
    Columns(
        left = if (link == null) {
            null
        } else {
            {
                Label("Scan")
                Box(Modifier.testTag(CARD_QR_TAG)) {
                    QrWell(link, "QR code to sign in on another device", dim = sheet.stale) {
                        if (sheet.stale) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Txt(STILL_THERE, DesktopType.emphasis, OctoColors.TextPrimary)
                                AccentButton(MAKE_NEW_CODE, actions.newCode, size = ButtonSize.Small, fill = OctoColors.Accent)
                            }
                        }
                    }
                }
                if (notice) Announce(NEW_CODE)
                Crossfade(notice, Modifier.width(QrWellSide).heightIn(min = 22.dp), animationSpec = octoTween(motion, OctoDuration.Card), label = "notice") { showing ->
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
                        } else {
                            Caption(HANDOVER_CAPTION, center = true)
                        }
                    }
                }
            }
        },
        right = {
            if (link != null) {
                Label("Or open this link on it")
                LinkRow(link, "Copy link", copy, actions.open)
                Spacer(Modifier.height(10.dp))
            }
            Label("What happens")
            Column(Modifier.bringIntoViewRequester(target), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "The new device scans this code or opens the link.",
                    "This device asks you to allow it.",
                    "Your sign-in goes over sealed. The server can't read it.",
                ).forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(20.dp).background(OctoColors.AccentTonal, CircleShape), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", DesktopType.label, OctoColors.TextPrimary)
                        }
                        Txt(step, DesktopType.meta, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
            if (sheet.refreshFailed) Txt(RENEW_FAILED, DesktopType.meta, OctoColors.SignalOrange, maxLines = 2)
        },
    )
}

// The hand-over went: a check and what happens next.
@Composable
private fun Finished(words: String) {
    Row(
        Modifier.fillMaxWidth().background(CardFill, CardShape).border(1.dp, CardEdge, CardShape).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).background(OctoColors.SignalGreen.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(OctoIcons.Check, size = 18.dp, tint = OctoColors.SignalGreen)
        }
        Txt(words, DesktopType.body, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 3)
    }
}

// A device scanned the code: its name large, its platform, and Allow (the
// one primary button) or Deny.
@Composable
fun ColumnScope.AskingCard(asking: FamilySignInPending, sending: Boolean, allow: () -> Unit, deny: () -> Unit) {
    val focus = remember { FocusRequester() }
    FocusOnDone(focus)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(OctoColors.AccentSelected, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(OctoIcons.Device, size = 28.dp)
        }
        Txt("Sign in on", DesktopType.meta, OctoColors.TextMuted)
        Txt(asking.deviceName.ifBlank { "A new device" }, OctoType.title, OctoColors.TextPrimary, maxLines = 2, align = TextAlign.Center)
        if (asking.platform.isNotBlank()) Txt(asking.platform, DesktopType.body, OctoColors.TextSecondary)
        Txt("It gets your sign-in for this server. Allow it only if this device is yours.", DesktopType.meta, OctoColors.TextMuted, maxLines = 3, align = TextAlign.Center)
        Announce(askingTitle(asking))
    }
    Separator()
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        GlazeCapsule(null, DENY, deny, enabled = !sending)
        AccentButton(ALLOW, allow, Modifier.width(120.dp).focusRequester(focus), size = ButtonSize.Medium, fill = OctoColors.Accent, loading = sending)
    }
}

// A family dialog as the app shows it: in the middle of the window, over
// the page dimmed and blurred, its middle scrolling when the window is
// small while its footer stays in sight.
fun PopupHost.showFamilyDialog(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) =
    showCentred(width = SheetWidth, maxHeight = 2000.dp, scrim = true, scrollsItself = true, content = content)

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

// Test tags, for checking what is in sight.
const val CARD_QR_TAG = "family-card-qr"
const val CARD_DONE_TAG = "family-card-done"
const val CARD_MORE_TAG = "family-card-more"

private fun SheetActions.handingFocusTo(done: FocusRequester): SheetActions {
    fun (() -> Unit).first(): () -> Unit = {
        runCatching { done.requestFocus() }
        this()
    }
    return SheetActions(otherApps.first(), backToCode.first(), newCode.first(), retry.first(), this.done, open, openPage, owner)
}

private val SheetWidth = 640.dp
private val QrSide = 188.dp
private val QrWellSide = QrSide + 28.dp
internal val Mono = DesktopType.table.copy(fontFamily = MonoFontFamily)
internal val BigCode = DesktopType.table.copy(fontFamily = MonoFontFamily, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
private val CardShape = RoundedCornerShape(12.dp)
private val WellShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(50)
private val ChoiceShape = RoundedCornerShape(12.dp)
private val ChoiceType = DesktopType.emphasis.copy(fontSize = 15.sp)

// Words on the accent fill.
private val OnAccent = Color(0xFF0C0C0D)

@Composable
fun ColumnScope.InviteCard(invite: InviteSheet, server: String, newLink: () -> Unit, actions: SheetActions, copy: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    FocusOnDone(focus)
    val options = invite.options
    var picked by remember(invite.username) { mutableStateOf<LinkReach?>(null) }
    val reach = picked ?: options.default
    val url = options.linkFor(reach)
    Header(invite.name, invite.title, INVITE_SUBTITLE, OctoColors.TextMuted, clock = true, close = actions.done)
    val target = remember { BringIntoViewRequester() }
    ScrollArea(target) {
        if (options.choosable) ReachChooser(reachQuestion(own = false), reach, options, server, actions) { picked = it }
        val waiting = invite.loading || invite.url == null
        Columns(
            left = if (url != null || waiting) {
                {
                    Label("Scan")
                    if (url == null) SkeletonBlock(QrWellSide, QrWellSide, 20.dp)
                    else QrWell(url, "QR code for ${invite.name}'s invite link", dim = false)
                    if (url != null) Caption(inviteCaption(invite.name), center = true)
                }
            } else {
                null
            },
            right = {
                if (url != null) {
                    Label("Open the link")
                    LinkRow(url, "Copy link", copy, actions.open)
                    Spacer(Modifier.height(10.dp))
                }
                Label("Then")
                Txt(inviteNext(invite.name), DesktopType.body, OctoColors.TextPrimary, Modifier.bringIntoViewRequester(target), maxLines = 3)
                invite.error?.let { Txt(it, DesktopType.meta, OctoColors.SignalOrange, maxLines = 2) }
            },
        )
    }
    Footer(focus, actions.done) {
        LinkAction(SEND_NEW_LINK, OctoIcons.Share, newLink, enabled = !invite.loading)
    }
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

// The part that scrolls when the window is too small for it all: a soft
// fade at its bottom edge and a "More below" button that brings `target`
// (the code) into view, both gone once the bottom is reached.
@Composable
private fun ColumnScope.ScrollArea(target: BringIntoViewRequester, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
            Modifier.fillMaxWidth().height(56.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0141416)))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .padding(bottom = 6.dp)
                    .background(OctoColors.AccentSelected, PillShape)
                    .border(1.dp, CardEdge, PillShape)
                    .hoverLift(PillShape)
                    .clickable(role = Role.Button, onClickLabel = MORE_BELOW) {
                        scope.launch {
                            target.bringIntoView()
                            if (scroll.canScrollForward) scroll.animateScrollTo(scroll.maxValue)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag(CARD_MORE_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Txt(MORE_BELOW, DesktopType.label, OctoColors.TextPrimary)
                Glyph(OctoIcons.Collapse, size = 14.dp)
            }
        }
    }
}

// Whose device it is (their initial), the title, and how long the code
// lasts beside a clock.
@Composable
private fun Header(name: String, title: String, subtitle: String, subtitleColor: Color, clock: Boolean, close: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            Modifier.size(42.dp).background(OctoColors.AccentSelected, CircleShape).border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Txt(initial, OctoType.section, OctoColors.TextPrimary)
        }
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

// Read out once, politely, when it changes; nothing to see.
@Composable
internal fun Announce(words: String?) {
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
private fun ReachChooser(question: String, reach: LinkReach, options: LinkOptions, server: String, actions: SheetActions, pick: (LinkReach) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(question, DesktopType.label, OctoColors.TextSecondary)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReachOption(LinkReach.Anywhere, OctoIcons.Globe, reach == LinkReach.Anywhere, locked = !options.anywhereAvailable, Modifier.weight(1f).fillMaxHeight()) { pick(LinkReach.Anywhere) }
            ReachOption(LinkReach.Home, OctoIcons.Home, reach == LinkReach.Home, locked = false, Modifier.weight(1f).fillMaxHeight()) { pick(LinkReach.Home) }
        }
        val line = reachLine(reach, options)
        if (line != null) {
            Txt(line, DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
        } else {
            Row(
                Modifier.fillMaxWidth().background(CardFill, CardShape).border(1.dp, CardEdge, CardShape).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glyph(OctoIcons.Lock, size = 18.dp, tint = OctoColors.TextSecondary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(SET_OUTSIDE_FIRST, DesktopType.emphasis, OctoColors.TextPrimary, maxLines = 2)
                    if (!actions.owner) Txt(ASK_OWNER_OUTSIDE, DesktopType.meta, OctoColors.TextMuted, maxLines = 2)
                }
                if (actions.owner) GlazeCapsule(null, OPEN_STATUS, { actions.openPage(dashboardStatusPage(server)) }, height = 34.dp)
            }
        }
    }
}

// One choice: its icon and word, filled in the accent with a check when
// chosen, outlined when not.
@Composable
private fun ReachOption(reach: LinkReach, icon: androidx.compose.ui.graphics.vector.ImageVector, chosen: Boolean, locked: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val motion = motionScale()
    val fill by animateColorAsState(if (chosen) OctoColors.Accent else Color.Transparent, octoTween(motion, OctoDuration.Fill), label = "reach fill")
    val ink by animateColorAsState(if (chosen) OnAccent else OctoColors.TextSecondary, octoTween(motion, OctoDuration.Fill), label = "reach ink")
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(ChoiceShape)
            .background(fill, ChoiceShape)
            .border(1.dp, if (chosen) Color.Transparent else CardEdge, ChoiceShape)
            .then(if (chosen) Modifier else Modifier.hoverLift(ChoiceShape))
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .testTag("reach-${reach.name}"),
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Glyph(if (locked && !chosen) OctoIcons.Lock else icon, size = 18.dp, tint = ink)
            // 15 sp, smaller only when there is no room, so the word is never cut.
            BasicText(
                reach.label,
                style = ChoiceType.copy(color = ink),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = ChoiceType.fontSize),
                modifier = Modifier.testTag("reach-${reach.name}-label"),
            )
        }
        // The check sits in the corner, taking no room from the word.
        if (chosen) Glyph(OctoIcons.Check, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 12.dp, tint = ink)
    }
}

// Two columns side by side, or one above the other when narrow; without a
// left column the right one takes the width.
@Composable
private fun Columns(left: (@Composable ColumnScope.() -> Unit)?, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (left != null && maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(QrWellSide), verticalArrangement = Arrangement.spacedBy(8.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (left != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = left)
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
        Modifier.size(QrWellSide).background(CardFill, WellShape).border(1.dp, CardEdge, WellShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(link, animationSpec = octoTween(motion, OctoDuration.Neutral), label = "qr") {
            QrImage(it, Modifier.alpha(if (dim) 0.12f else 1f), side = QrSide, label = label, quiet = 14.dp, corner = 14.dp)
        }
        overlay()
    }
}

@Composable
internal fun Label(text: String) = Txt(text, DesktopType.label, OctoColors.TextSecondary)

@Composable
internal fun Caption(text: String, center: Boolean = false) =
    Txt(text, DesktopType.meta, OctoColors.TextMuted, if (center) Modifier.fillMaxWidth() else Modifier, maxLines = 2, align = if (center) TextAlign.Center else null)

@Composable
internal fun Value(text: String, modifier: Modifier = Modifier) = Txt(text, Mono, OctoColors.TextPrimary, modifier)

// The link in a soft pill: its icon, the link to click in the accent (its
// middle left out, its telling end kept, all of it in the tooltip and the
// copy), and a copy button.
@Composable
internal fun LinkRow(url: String, copyName: String, copy: (String) -> Unit, open: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardFill, PillShape).border(1.dp, CardEdge, PillShape).padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(OctoIcons.Link, size = 16.dp, tint = OctoColors.AccentHover)
        OctoTooltip(url, Modifier.weight(1f)) {
            Txt(
                shownLink(url, 52),
                DesktopType.meta.copy(fontWeight = FontWeight.Medium),
                OctoColors.AccentHover,
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClickLabel = "Open the link") { open(url) }
                    .semantics { contentDescription = url },
                maxLines = 2,
            )
        }
        CopyButton(url, copyName, copy)
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(CardFill, CardShape).border(1.dp, CardEdge, CardShape), content = content)
}

@Composable
internal fun CardRow(label: String, first: Boolean = false, tall: Boolean = false, value: @Composable RowScope.() -> Unit) {
    if (!first) Separator()
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 42.dp).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, DesktopType.meta, OctoColors.TextMuted, Modifier.width(104.dp))
        value()
    }
}

// A copy button: the icon turns to a check, with "Copied" beside it, for two
// seconds.
@Composable
internal fun CopyButton(value: String, name: String, copy: (String) -> Unit) {
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
internal fun Problem(error: String, retry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardFill, CardShape).border(1.dp, CardEdge, CardShape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(OctoIcons.Info, size = 20.dp, tint = OctoColors.SignalOrange)
        Txt(error, DesktopType.body, OctoColors.TextPrimary, Modifier.weight(1f), maxLines = 2)
        GlazeCapsule(null, TRY_AGAIN, retry, height = 36.dp)
    }
}

@Composable
internal fun SkeletonBlock(width: Dp, height: Dp, corner: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(corner)),
    )
}

// The quiet action beside Done: a link in the accent.
@Composable
private fun LinkAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .hoverLift(PillShape, clickable = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(icon, size = 16.dp, tint = OctoColors.AccentHover)
        Txt(text, OctoType.label, OctoColors.AccentHover, maxLines = 2)
    }
}

// Pinned under the part that scrolls, always in sight: a hairline, the
// quiet action, and the one primary Done in the accent.
@Composable
private fun Footer(done: FocusRequester, close: () -> Unit, start: @Composable RowScope.() -> Unit = {}) {
    Separator()
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 24.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { Row(content = start) }
        AccentButton("Done", close, Modifier.width(120.dp).focusRequester(done).testTag(CARD_DONE_TAG), size = ButtonSize.Medium, fill = OctoColors.Accent)
    }
}

internal fun copyText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
