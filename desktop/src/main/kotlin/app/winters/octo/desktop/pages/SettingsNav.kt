package app.winters.octo.desktop.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as pressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.CardEdge
import app.winters.octo.design.CardFill
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.Txt
import app.winters.octo.design.exactPainter
import app.winters.octo.design.hoverLift
import app.winters.octo.design.mix
import app.winters.octo.design.scrollFades
import app.winters.octo.design.scrollbar
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.discord.DiscordMark
import app.winters.octo.desktop.home.HomeData
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.SettingsPageInfo
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.server.troubleWords
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.system.hasDiscord
import app.winters.octo.desktop.ui.windowRect
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.ceil
import kotlin.math.roundToInt

// Settings' list, in the sidebar's place while Settings is open: the way
// back and the title on the first line, the account card, the settings
// search, the pages under their group names, and About Octo at the foot.

// A group of Settings' pages under its name.
internal data class SettingsNavGroup(val title: String, val pages: List<SettingsPageInfo>)

private val ListeningPages = setOf(SettingsKey.Playback, SettingsKey.Sound, SettingsKey.Radio, SettingsKey.Lyrics)
private val YourMusicPages = setOf(SettingsKey.Servers, SettingsKey.Quality, SettingsKey.Offline, SettingsKey.Listening)

// The pages in their groups, in the order listed; About Octo is the foot's.
internal fun settingsNavGroups(pages: List<SettingsPageInfo>): List<SettingsNavGroup> = listOf(
    SettingsNavGroup(LISTENING, pages.filter { it.key in ListeningPages }),
    SettingsNavGroup(YOUR_MUSIC, pages.filter { it.key in YourMusicPages }),
    SettingsNavGroup(THIS_COMPUTER, pages.filter { it.key !in ListeningPages && it.key !in YourMusicPages && it.key != SettingsKey.About }),
).filter { it.pages.isNotEmpty() }

// The name of the group a page is listed under; About Octo, at the foot,
// is Settings' own.
internal fun settingsGroupOf(page: String): String = when (page) {
    in ListeningPages -> LISTENING
    in YourMusicPages -> YOUR_MUSIC
    SettingsKey.About -> "Settings"
    else -> THIS_COMPUTER
}

private const val LISTENING = "Listening"
private const val YOUR_MUSIC = "Your music"
private const val THIS_COMPUTER = "This computer"

// Each page's icon in the list.
internal fun settingsPageIcon(key: String): ImageVector = when (key) {
    SettingsKey.Playback -> OctoIcons.Playback
    SettingsKey.Sound -> OctoIcons.VolumeUp
    SettingsKey.Radio -> OctoIcons.Radio
    SettingsKey.Lyrics -> OctoIcons.Lyrics
    SettingsKey.Servers -> OctoIcons.Servers
    SettingsKey.Quality -> OctoIcons.Lossless
    SettingsKey.Offline -> OctoIcons.Download
    SettingsKey.Listening -> OctoIcons.History
    SettingsKey.Look -> OctoIcons.Appearance
    SettingsKey.Home -> OctoIcons.Home
    SettingsKey.System -> OctoIcons.System
    SettingsKey.Keys -> OctoIcons.Keyboard
    SettingsKey.Discord -> DiscordMark
    else -> OctoIcons.Info
}

// The pages this window lists: "Window" off Windows, Discord only in a
// build with Discord.
@Composable
private fun shownPages(app: AppState): List<SettingsPageInfo> = settingsPages(app.os == DesktopOs.Windows, hasDiscord())

// A page item's parts across: its inset each side, the icon and the gaps
// between the icon, the name and what follows it.
private val ItemInset = Space.M
private val ItemGap = Space.L

// How far the list's items sit in from the slot's edges.
private val ListSide = Space.M

// How far the list's faded ends reach.
private val ListFade = Space.Page

// The room under the list's last page.
private val ListFoot = RowHeight.Nav + Space.M

// The least move worth scrolling the list for.
private val ListNudge = Space.L

// The account card's slot for its server's status, before the name.
private val StatusSlot = Space.M + Space.Xxs

// What the tests call a list item's icon.
internal fun itemIconTag(page: String) = "settings-item-icon:$page"

// Where the page shown sits in the list's scrolling content.
internal data class ItemSpot(val page: String, val top: Int, val height: Int)

// What the about line says after its name: the version.
private val AboutDetail: TextStyle get() = DesktopType.meta

// How wide the list must be for every page's name to sit on one line at
// the text size in use, measured as the items lay them out.
@Composable
internal fun settingsListNeeds(app: AppState): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pages = shownPages(app)
    val version = appVersion()
    return remember(measurer, density, pages, version) {
        with(density) {
            fun words(text: String, style: TextStyle) = ceil(measurer.measure(text, style).size.width.toFloat()).toInt()
            val dot = ItemGap.roundToPx() + FrameSize.NavMark.roundToPx()
            val names = pages.filter { it.key != SettingsKey.About }.maxOfOrNull { page ->
                words(page.name, DesktopType.body) + if (page.key == SettingsKey.Servers) dot else 0
            } ?: 0
            val about = pages.firstOrNull { it.key == SettingsKey.About }?.let { page ->
                // The update's dot always has its room, so the list keeps
                // its width when one comes or goes.
                words(page.name, DesktopType.body) + ItemGap.roundToPx() + words(version, AboutDetail) + dot
            } ?: 0
            val around = (ListSide.roundToPx() + ItemInset.roundToPx()) * 2 + settingsIconSize(fontScale).roundToPx() + ItemGap.roundToPx()
            // One pixel to spare, so a name measured to the pixel still fits.
            (around + maxOf(names, about) + 1).toDp()
        }
    }
}

// The list itself. `chosen` takes the keyboard to the page shown; `live`
// is false while the list slides away, so it lets go of the search box's
// anchor and the find shortcut.
@Composable
internal fun SettingsList(app: AppState, chosen: FocusRequester, live: Boolean, modifier: Modifier = Modifier) {
    val ui = app.settingsUi
    val pages = shownPages(app)
    val groups = settingsNavGroups(pages)
    fun pick(key: String) {
        ui.page = key
        ui.aim(null)
    }
    val here = settingsHere(app)
    val query = ui.query
    val hits = remember(query, here) { searchDesktopSettings(query, here) }
    // The result Enter opens, moved with Up and Down; the first while typing.
    var at by remember(query) { mutableIntStateOf(0) }
    fun open(index: Int) {
        val hit = hits.getOrNull(index) ?: return
        at = index
        app.openSettings(hit.entry.page, hit.entry.row)
    }
    Column(modifier.padding(horizontal = ListSide, vertical = Space.M)) {
        SettingsHeader(app, live)
        val scroll = rememberScrollState()
        var view by remember { mutableIntStateOf(0) }
        var spot by remember { mutableStateOf<ItemSpot?>(null) }
        val fade = with(LocalDensity.current) { ListFade.roundToPx() }
        val nudge = with(LocalDensity.current) { ListNudge.roundToPx() }
        val still = LocalReduceMotion.current
        // The page shown comes into view, opening Settings and on each pick,
        // with an item's room under it and clear of the faded top; again
        // whenever the list is laid out anew under it (as it settles in, or
        // the window or text size changes), never as it is scrolled. About
        // Octo lives in the foot and needs none, and a move smaller than a
        // nudge is not worth the motion.
        LaunchedEffect(ui.page, query.isBlank()) {
            if (query.isNotBlank() || ui.page == SettingsKey.About) return@LaunchedEffect
            // Its place, the view's height and how far the list can scroll,
            // so a list grown back (the search cleared) is heard too.
            snapshotFlow { spot?.takeIf { it.page == ui.page && view > 0 }?.let { Triple(it, view, scroll.maxValue) } }.filterNotNull().collect { (at, shown, _) ->
                val goal = when {
                    at.top + at.height * 2 > scroll.value + shown -> at.top + at.height * 2 - shown
                    at.top - fade < scroll.value -> at.top - fade
                    else -> return@collect
                }.coerceIn(0, scroll.maxValue)
                if (kotlin.math.abs(goal - scroll.value) < nudge) return@collect
                if (still) scroll.scrollTo(goal) else scroll.animateScrollTo(goal)
            }
        }
        // A clear band under the header before the faded top begins.
        Spacer(Modifier.height(Space.M))
        // Its ends fade while more of it is that way; while it does not fit,
        // a faint thumb says so at rest, brighter on hover. It ends at the
        // line over About Octo.
        Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { view = it.height }.scrollbar(scroll, resting = true).scrollFades(scroll, ListFade, eased = true).verticalScroll(scroll)) {
            AccountCard(app) { pick(SettingsKey.Servers) }
            Spacer(Modifier.height(Space.L))
            SettingsSearch(app, live, onMove = { step -> if (hits.isNotEmpty()) at = (at + step).coerceIn(0, hits.lastIndex) }, onOpen = { open(at) })
            if (query.isNotBlank()) SettingsResults(hits, at, ::open) else groups.forEach { group ->
                Txt(
                    group.title.uppercase(),
                    DesktopType.label,
                    OctoColors.TextMuted,
                    Modifier.padding(start = ItemInset, top = Space.Xl, bottom = Space.Xs).semantics {
                        heading()
                        contentDescription = group.title
                    },
                )
                group.pages.forEach { page ->
                    val mark = if (page.key == SettingsKey.Servers && serverUpdateWaits(app)) SERVER_UPDATE_MARK else null
                    SettingsItem(page, ui.page == page.key, chosen, mark = mark, placed = { spot = it }) { pick(page.key) }
                }
            }
            // Room under the last page, so it too can sit an item clear of
            // the list's foot.
            Spacer(Modifier.height(ListFoot))
        }
        pages.firstOrNull { it.key == SettingsKey.About }?.let { about ->
            Separator(Modifier.padding(bottom = Space.S))
            val mark = if (app.updates?.ready != null) "update ready" else null
            SettingsItem(about, ui.page == about.key, chosen, mark = mark, detail = appVersion()) { pick(about.key) }
        }
    }
}

// Line one: the title, on the group names' edge; the way back is the title
// bar's. The title marks where the search box floats out while Settings
// holds the sidebar's place.
@Composable
private fun SettingsHeader(app: AppState, live: Boolean) {
    val box = app.omnibox
    val mine = remember { arrayOf(IntRect.Zero) }
    fun letGo() {
        if (mine[0] != IntRect.Zero && box.trigger == mine[0]) box.trigger = IntRect.Zero
        mine[0] = IntRect.Zero
    }
    LaunchedEffect(live) { if (!live) letGo() }
    DisposableEffect(Unit) { onDispose { letGo() } }
    Row(Modifier.fillMaxWidth().height(ControlHeight.L).padding(start = ItemInset), verticalAlignment = Alignment.CenterVertically) {
        Txt(
            "Settings",
            OctoType.section,
            OctoColors.TextPrimary,
            Modifier.onGloballyPositioned {
                if (!app.inSettings) return@onGloballyPositioned
                val at = it.windowRect()
                mine[0] = at
                box.trigger = at
            },
        )
    }
}

// The covers on the account card: the server's recently played albums,
// then its recently added, four at most; none while a switch is under way.
internal fun accountCovers(data: HomeData?, switching: Boolean): List<String?> {
    if (switching || data == null) return emptyList()
    return (data.recentlyPlayed + data.recentlyAdded).distinctBy { it.id }.take(4).map { it.coverArt }
}

// What a screen reader and the tests call a cover tile on the account card.
internal fun accountTileTag(coverId: String?): String = "account-cover:" + (coverId ?: "none")

// The account in use: who, on which server, and how that server last
// answered, beside covers of what was played there. A click opens Servers.
@Composable
private fun AccountCard(app: AppState, onOpen: () -> Unit) {
    val connection = app.connection ?: return
    val switching = app.switching
    val server = switching ?: connection.server
    val inUse = connection.server.id
    val home = app.home
    // The covers come from Home's shelves, read here when Home was never
    // opened on this server; the check is asked each time Settings opens.
    LaunchedEffect(inUse, home) { home?.refresh() }
    LaunchedEffect(inUse) { app.serverFacts.refresh() }
    val check = app.serverFacts.checks[inUse]?.takeIf { switching == null }
    val trouble = check.troubleWords()
    val status = when {
        switching != null -> "Switching"
        check == null -> null
        else -> trouble ?: "Connected"
    }
    val covers = accountCovers(home?.data, switching != null)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Corner.PanelShape)
            .background(CardFill)
            .border(FrameSize.Hairline, CardEdge, Corner.PanelShape)
            .hoverLift(Corner.PanelShape, lifted = false)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = listOfNotNull(server.username, server.name, status).joinToString(", ") }
            .padding(Space.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        key(server.id) { CoverMosaic(covers) }
        Column(Modifier.weight(1f)) {
            Txt(server.username, DesktopType.emphasis, OctoColors.TextPrimary, maxLines = 2)
            Row(Modifier.padding(top = Space.Xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                // One slot for how it stands, kept in every state, so the
                // name never moves as a check answers or a switch starts.
                Box(Modifier.size(StatusSlot), contentAlignment = Alignment.Center) {
                    when {
                        switching != null -> Spinner(size = StatusSlot, color = OctoColors.TextSecondary)
                        check == null -> Unit
                        else -> Box(Modifier.size(FrameSize.NavMark).background(if (trouble != null) OctoColors.SignalOrange else OctoColors.SignalGreen, CircleShape))
                    }
                }
                Txt(server.name, DesktopType.meta, OctoColors.TextSecondary, Modifier.weight(1f, fill = false), maxLines = 2)
            }
            // On its own line, as a sign-in page's words are too long to sit
            // beside the name on this narrow card.
            if (trouble != null) Txt(trouble, DesktopType.meta, OctoColors.SignalOrange, Modifier.padding(top = Space.Xxs), maxLines = 2)
        }
    }
}

// Four covers in a square, two by two; empty glass where there are fewer
// (on a light theme a faint fill of the main word color, as the card
// itself is the card color there).
@Composable
private fun CoverMosaic(covers: List<String?>) {
    val tile = SettingsSize.AccountArt / 2
    val empty = if (LocalOctoSurfaces.current.light) OctoColors.TextPrimary.copy(alpha = 0.06f) else CardFill
    Column(Modifier.size(SettingsSize.AccountArt).clip(Corner.ArtLShape)) {
        for (row in 0 until 2) {
            Row {
                for (column in 0 until 2) {
                    val at = row * 2 + column
                    if (at < covers.size) {
                        Cover(covers[at], Modifier.size(tile).testTag(accountTileTag(covers[at])), shape = RectangleShape)
                    } else {
                        Box(Modifier.size(tile).background(empty).testTag(accountTileTag(null)))
                    }
                }
            }
        }
    }
}

// What this window has, for which settings the search finds.
@Composable
private fun settingsHere(app: AppState): SettingsHere {
    val system = LocalSystem.current
    return SettingsHere(
        os = app.os,
        tray = system?.trayAvailable == true,
        notifications = system?.notificationsAvailable == true,
        discord = hasDiscord(),
        engine = app.sound != null,
        family = app.connection?.family == true,
    )
}

// Settings' own search field. Its words are Settings' query; Ctrl+F (Cmd+F)
// comes here while the list is live. While it holds words, Up and Down move
// through the results under it and Enter opens the one marked.
@Composable
private fun SettingsSearch(app: AppState, live: Boolean, onMove: (Int) -> Unit, onOpen: () -> Unit) {
    val ui = app.settingsUi
    val focus = remember { FocusRequester() }
    DisposableEffect(focus, live) {
        if (live) app.pageFilterFocus = focus
        onDispose { if (app.pageFilterFocus === focus) app.pageFilterFocus = null }
    }
    DisposableEffect(Unit) { onDispose { ui.searchHasKeyboard = false } }
    GlassField(
        ui.query,
        { ui.query = it },
        Modifier
            .fillMaxWidth()
            .height(RowHeight.Nav)
            .onFocusChanged { ui.searchHasKeyboard = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || ui.query.isBlank()) return@onPreviewKeyEvent false
                when (event.pressed) {
                    Key.DirectionDown -> onMove(1)
                    Key.DirectionUp -> onMove(-1)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        placeholder = "Search settings",
        icon = OctoIcons.Search,
        focusRequester = focus,
        onSubmit = { if (ui.query.isNotBlank()) onOpen() },
        trailing = if (ui.query.isEmpty()) ({ Txt(if (app.mac) "⌘F" else "Ctrl+F", DesktopType.meta, OctoColors.TextMuted, Modifier.padding(end = Space.S)) }) else null,
    )
}

// What the search found, under its field in the groups' place: how many,
// said to a screen reader as it changes, then each setting by its title
// with its page (and card) under it. The one Enter opens is lit as the page
// shown is lit in the list.
@Composable
private fun SettingsResults(hits: List<SettingsHit>, marked: Int, onOpen: (Int) -> Unit) {
    val count = when (hits.size) {
        0 -> "No settings match"
        1 -> "1 result"
        else -> "${hits.size} results"
    }
    Txt(
        count,
        DesktopType.meta,
        OctoColors.TextMuted,
        Modifier.padding(start = ItemInset, top = Space.L, bottom = Space.Xs).semantics { liveRegion = LiveRegionMode.Polite },
    )
    hits.forEachIndexed { index, hit -> SettingsResult(hit, index == marked) { onOpen(index) } }
}

// One result: the page's icon, the setting's title, and where it is.
@Composable
private fun SettingsResult(hit: SettingsHit, marked: Boolean, onOpen: () -> Unit) {
    val accent = OctoColors.Accent
    val seen = remember { BringIntoViewRequester() }
    LaunchedEffect(marked) { if (marked) seen.bringIntoView() }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Nav)
            .bringIntoViewRequester(seen)
            .hoverLift(Corner.ControlShape, lifted = false)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = "${hit.entry.title}, ${hit.place}"
                selected = marked
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (marked) PlaceFill(Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ItemInset, vertical = Space.S),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ItemGap),
        ) {
            // The icon sits on the title's first line, however many follow.
            val icon = settingsPageIcon(hit.entry.page)
            val size = if (biggerIcons()) IconSize.Transport else IconSize.Toolbar
            Box(Modifier.height(firstLine(DesktopType.body)), contentAlignment = Alignment.Center) {
                if (hit.entry.page == SettingsKey.Discord) Image(exactPainter(icon), null, Modifier.size(size))
                else Glyph(icon, size = size, tint = if (marked) OctoColors.AccentHover else accent)
            }
            Column(Modifier.weight(1f)) {
                Txt(hit.entry.title, DesktopType.body, if (marked) OctoColors.TextPrimary else mix(OctoColors.TextSecondary, OctoColors.TextPrimary, 0.6f), maxLines = 2)
                Txt(hit.place, DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = 2)
            }
        }
    }
}

// How tall one line of `style` is at the text size in use.
@Composable
private fun firstLine(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style) { with(density) { measurer.measure("Ag", style).size.height.toDp() } }
}

// One page in the list: its icon and name, and a dot or a quiet word after
// it. The page shown sits on an accent-tinted fill with a fine accent edge,
// its icon brighter; the keyboard comes to it through `chosen`. Shown, it
// tells `placed` where it sits in the list.
@Composable
private fun SettingsItem(
    page: SettingsPageInfo,
    shown: Boolean,
    chosen: FocusRequester,
    mark: String? = null,
    detail: String? = null,
    placed: ((ItemSpot) -> Unit)? = null,
    onPick: () -> Unit,
) {
    val accent = OctoColors.Accent
    val words = if (shown) OctoColors.TextPrimary else mix(OctoColors.TextSecondary, OctoColors.TextPrimary, 0.6f)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Nav)
            .then(if (shown && placed != null) Modifier.onPlaced { placed(ItemSpot(page.key, it.positionInParent().y.roundToInt(), it.size.height)) } else Modifier)
            .then(if (shown) Modifier.focusRequester(chosen) else Modifier)
            .hoverLift(Corner.ControlShape, lifted = false)
            .clickable(role = Role.Tab, onClick = onPick)
            .semantics {
                contentDescription = listOfNotNull(page.name, detail, mark).joinToString(", ")
                selected = shown
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (shown) PlaceFill(Modifier.matchParentSize())
        // The icon and the dot sit on the name's first line, however many
        // lines the name and the word after it take. The line with a word
        // after its name keeps the dot's room when there is no dot, so the
        // word does not slide as one comes or goes.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ItemInset, vertical = Space.S),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ItemGap),
        ) {
            val line = firstLine(DesktopType.body)
            val icon = settingsPageIcon(page.key)
            val size = if (biggerIcons()) IconSize.Transport else IconSize.Toolbar
            Box(Modifier.height(line).testTag(itemIconTag(page.key)), contentAlignment = Alignment.Center) {
                // A brand's mark keeps its own shape and colors.
                if (page.key == SettingsKey.Discord) Image(exactPainter(icon), null, Modifier.size(size))
                else Glyph(icon, size = size, tint = if (shown) OctoColors.AccentHover else accent)
            }
            if (detail == null) Txt(page.name, DesktopType.body, words, Modifier.weight(1f), maxLines = 3)
            else NameAndDetail(Modifier.weight(1f), { Txt(page.name, DesktopType.body, words, maxLines = 3) }, { Txt(detail, AboutDetail, OctoColors.TextMuted) })
            if (mark != null || detail != null) {
                Box(Modifier.height(line).width(FrameSize.NavMark), contentAlignment = Alignment.Center) {
                    if (mark != null) Box(Modifier.size(FrameSize.NavMark).background(accent, CircleShape))
                }
            }
        }
    }
}

// A name with a quiet word at the line's end, both centered on the line;
// when the name on one line and the word do not fit across, the word goes
// under the name, so the name is never broken for it.
@Composable
private fun NameAndDetail(modifier: Modifier, name: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Layout({ name(); detail() }, modifier) { (nameIn, detailIn), constraints ->
        val gap = ItemGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val word = detailIn.measure(loose)
        val across = nameIn.maxIntrinsicWidth(constraints.maxHeight) + gap + word.width <= constraints.maxWidth
        val room = if (across) constraints.maxWidth - gap - word.width else constraints.maxWidth
        val words = nameIn.measure(loose.copy(minWidth = room, maxWidth = room))
        val width = constraints.maxWidth
        if (across) {
            val height = maxOf(words.height, word.height)
            layout(width, height) {
                words.place(0, (height - words.height) / 2)
                word.place(width - word.width, (height - word.height) / 2)
            }
        } else {
            layout(width, words.height + word.height) {
                words.place(0, 0)
                word.place(0, words.height)
            }
        }
    }
}
