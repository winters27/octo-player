package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.Glyph
import app.winters.octo.design.HoverFill
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuFilm
import app.winters.octo.design.MenuFrost
import app.winters.octo.design.MenuShape
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.artistSongs
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.playlistSongs
import app.winters.octo.desktop.search.OmniItem
import app.winters.octo.desktop.search.OmniSection
import app.winters.octo.desktop.search.SearchState
import app.winters.octo.desktop.search.commandsFor
import app.winters.octo.desktop.search.omniSections
import app.winters.octo.subsonic.Song
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

// How a line was chosen: Enter or a click (the main thing), with Shift (add
// to the queue), with Ctrl or Cmd (play next), or with Alt (go to its album
// or artist).
enum class OmniHow { Main, Queue, Next, GoTo }

// The search field at the top of the sidebar. Focusing it opens the list
// under it; the arrows move through the list and Enter chooses, with
// Shift, Ctrl or Alt for other ways; Escape closes the list, and again
// clears the field. Empty, it shows the shortcut that reaches it.
@Composable
fun OmniField(app: AppState, modifier: Modifier = Modifier) {
    val model = app.search ?: return
    val box = app.omnibox
    val focus = LocalFocusManager.current
    val lines = rememberOmniLines(app)
    Box(
        modifier
            .onGloballyPositioned { box.field = it.windowRect() }
            .onFocusChanged { if (it.hasFocus) box.open = true }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !box.open) return@onPreviewKeyEvent false
                val command = if (app.mac) event.isMetaPressed else event.isCtrlPressed
                when (event.key) {
                    Key.DirectionDown -> box.move(1, lines.size)
                    Key.DirectionUp -> box.move(-1, lines.size)
                    Key.Enter -> {
                        val how = when {
                            event.isShiftPressed -> OmniHow.Queue
                            command -> OmniHow.Next
                            event.isAltPressed -> OmniHow.GoTo
                            else -> OmniHow.Main
                        }
                        lines.getOrNull(box.highlight)?.let { choose(app, it, how) { focus.clearFocus() } }
                            ?: if (model.text.isNotBlank()) choose(app, OmniItem.SeeAll(model.text.trim()), OmniHow.Main) { focus.clearFocus() } else Unit
                    }
                    Key.Escape -> {
                        box.open = false
                        focus.clearFocus()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        GlassField(
            model.text,
            { text ->
                model.type(text)
                box.open = true
                box.highlight = 0
            },
            Modifier.fillMaxWidth().height(RowHeight.Nav),
            placeholder = "Search",
            icon = OctoIcons.Search,
            focusRequester = app.searchFocus,
            onEscape = { model.type("") },
            trailing = if (model.text.isEmpty()) ({ Txt(if (app.mac) "⌘K" else "Ctrl+K", DesktopType.meta, OctoColors.TextMuted, Modifier.padding(end = Space.S)) }) else null,
        )
    }
}

// Every line of the list, in order, for the keyboard.
@Composable
private fun rememberOmniLines(app: AppState): List<OmniItem> = rememberOmniSections(app).flatMap { it.items }

@Composable
private fun rememberOmniSections(app: AppState): List<OmniSection> {
    val model = app.search ?: return emptyList()
    val settings by app.settings.state.collectAsState()
    val box = app.omnibox
    val state = model.state
    // A new answer replaces the kept one; while one is on its way, the last
    // stays, so the list does not blink as each letter is typed.
    LaunchedEffect(state) {
        when (state) {
            is SearchState.Done -> box.lastFound = state.found
            SearchState.Idle -> box.lastFound = null
            else -> Unit
        }
    }
    val player by app.player.state.collectAsState()
    val commands = remember(player.current, player.playing, player.shuffle, player.repeat, player.outputs, app.sidePanel, settings.frame.sidebarRail) { commandsFor(app) }
    return remember(model.text, box.lastFound, commands, settings.recentSearches) {
        omniSections(model.text, box.lastFound, commands, settings.recentSearches)
    }
}

// The list under the search field, over the page: headings, lines with
// the one under the keyboard lit, and a line of hints at the foot.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OmniPanel(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val model = app.search ?: return
    val box = app.omnibox
    val sections = rememberOmniSections(app)
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Keeps the keyboard's line in view.
    LaunchedEffect(box.highlight, sections) {
        var row = 0
        var at = 0
        for (section in sections) {
            if (section.title.isNotEmpty()) row++
            if (box.highlight < at + section.items.size) {
                row += box.highlight - at
                break
            }
            row += section.items.size
            at += section.items.size
        }
        val seen = list.layoutInfo.visibleItemsInfo
        if (seen.none { it.index == row } || seen.lastOrNull()?.index == row) scope.launch { list.scrollToItem((row - 2).coerceAtLeast(0)) }
    }
    // The menus' floating glass: the window's colours frosted behind a dark
    // film, so the page under it never shows through.
    FloatingGlaze(backdrop, modifier, shape = MenuShape, film = MenuFilm, frost = MenuFrost, halo = true) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.S)) {
        val loading = model.state is SearchState.Looking && box.lastFound == null
        when {
            sections.isEmpty() && model.text.isBlank() ->
                Txt("Search your music, or type > for things Octo can do.", DesktopType.body, OctoColors.TextMuted, Modifier.padding(Space.L), maxLines = 2)
            sections.isEmpty() && loading -> Txt("Searching", DesktopType.body, OctoColors.TextMuted, Modifier.padding(Space.L))
            sections.isEmpty() && model.state is SearchState.Failed ->
                Txt((model.state as SearchState.Failed).message, DesktopType.body, OctoColors.TextMuted, Modifier.padding(Space.L), maxLines = 3)
            // Nothing but the way to the Search page: say so above it.
            sections.all { section -> section.items.all { it is OmniItem.SeeAll } } ->
                Txt("Nothing found. Try other words, or fewer of them.", DesktopType.body, OctoColors.TextMuted, Modifier.padding(Space.L), maxLines = 2)
        }
        LazyColumn(Modifier.heightIn(max = FrameSize.OmniHeight), state = list) {
            var at = 0
            sections.forEach { section ->
                val first = at
                if (section.title.isNotEmpty()) {
                    item(key = "h:${section.title}") {
                        Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.S, top = Space.M, bottom = Space.Xxs), verticalAlignment = Alignment.CenterVertically) {
                            Txt(section.title.uppercase(), DesktopType.label, OctoColors.TextMuted, Modifier.weight(1f))
                            if (section.title == "Recent searches") TextAction("Clear", { app.forgetSearches() })
                        }
                    }
                }
                section.items.forEachIndexed { i, item ->
                    val index = first + i
                    item(key = "i:$index:${keyOf(item)}") {
                        OmniLine(app, item, lit = index == box.highlight, onHover = { box.highlight = index }) {
                            choose(app, item, OmniHow.Main) { focus.clearFocus() }
                        }
                    }
                }
                at += section.items.size
            }
        }
        if (sections.isNotEmpty()) {
            Separator(Modifier.padding(top = Space.S))
            Txt(
                if (model.text.trim().startsWith(">")) "Enter runs it" else "Enter plays or opens · Shift+Enter adds to the queue · Ctrl+Enter plays next · Alt+Enter opens its album",
                DesktopType.meta,
                OctoColors.TextMuted,
                Modifier.padding(start = Space.L, end = Space.L, top = Space.S),
                maxLines = 2,
            )
        }
    }
    }
}

private fun keyOf(item: OmniItem): String = when (item) {
    is OmniItem.Recent -> "r:${item.text}"
    is OmniItem.Run -> "c:${item.command.title}"
    is OmniItem.SongHit -> "s:${item.song.id}:${item.outside}"
    is OmniItem.AlbumHit -> "a:${item.album.id}"
    is OmniItem.ArtistHit -> "t:${item.artist.id}"
    is OmniItem.PlaylistHit -> "p:${item.playlist.id}"
    is OmniItem.SeeAll -> "all"
}

// One line: a picture or an icon, the name, what it is, and on the lit
// line what Enter does.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OmniLine(app: AppState, item: OmniItem, lit: Boolean, onHover: () -> Unit, onChoose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.S)
            .height(OmniLineHeight)
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .onPointerEvent(PointerEventType.Press) { onChoose() }
            .background(if (lit) OctoColors.AccentSelected else if (hovered) HoverFill else androidx.compose.ui.graphics.Color.Transparent, Corner.ControlShape)
            .padding(horizontal = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M + Space.Xxs),
    ) {
        val (title, detail, verb) = wordsFor(item)
        when (item) {
            is OmniItem.SongHit -> Cover(item.song.coverArt, Modifier.size(OmniArt), shape = Corner.ArtSShape, placeholder = OctoIcons.Songs, online = item.outside)
            is OmniItem.AlbumHit -> Cover(item.album.coverArt, Modifier.size(OmniArt), shape = Corner.ArtSShape, online = item.outside)
            is OmniItem.ArtistHit -> Cover(item.artist.coverArt, Modifier.size(OmniArt), shape = CircleShape, placeholder = OctoIcons.Artist, online = item.outside)
            is OmniItem.PlaylistHit -> Cover(item.playlist.coverArt, Modifier.size(OmniArt), shape = Corner.ArtSShape, placeholder = OctoIcons.Playlists)
            is OmniItem.Recent -> IconBox(OctoIcons.History)
            is OmniItem.Run -> IconBox(OctoIcons.Chevron)
            is OmniItem.SeeAll -> IconBox(OctoIcons.Search)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, DesktopType.emphasis)
            if (detail.isNotEmpty()) Txt(detail, DesktopType.meta, OctoColors.TextSecondary)
        }
        if (lit && verb.isNotEmpty()) Txt(verb, DesktopType.meta, OctoColors.TextMuted)
    }
}

@Composable
private fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(OmniArt), contentAlignment = Alignment.Center) { Glyph(icon, size = IconSize.Toolbar, tint = OctoColors.TextSecondary) }
}

private val OmniLineHeight = FrameSize.OmniLine
private val OmniArt = FrameSize.OmniArt

// The line's name, what it is, and what Enter does to it.
private fun wordsFor(item: OmniItem): Triple<String, String, String> = when (item) {
    is OmniItem.Recent -> Triple(item.text, "Search again", "Search")
    is OmniItem.Run -> Triple(item.command.title, item.command.group, "Run")
    is OmniItem.SongHit -> Triple(item.song.title, listOfNotNull(item.song.displayArtist ?: item.song.artist, item.song.album, lengthText(item.song.duration).ifEmpty { null }).joinToString(" · "), "Play")
    is OmniItem.AlbumHit -> Triple(item.album.name, listOfNotNull("Album", item.album.displayArtist ?: item.album.artist, item.album.year?.takeIf { it > 0 }?.toString()).joinToString(" · "), "Open")
    is OmniItem.ArtistHit -> Triple(item.artist.name, "Artist", "Open")
    is OmniItem.PlaylistHit -> Triple(item.playlist.name, "Playlist · ${item.playlist.songCount} songs", "Open")
    is OmniItem.SeeAll -> Triple("See all results for \"${item.text}\"", "On the Search page", "Open")
}

// Does what a line is for, then closes the box. A result that led
// somewhere is kept as a recent search.
fun choose(app: AppState, item: OmniItem, how: OmniHow, done: () -> Unit) {
    val model = app.search ?: return
    val typed = model.text.trim()
    fun close(remember: Boolean = true) {
        if (remember && typed.isNotEmpty() && !typed.startsWith(">")) app.rememberSearch(typed)
        app.omnibox.open = false
        done()
    }
    fun withSongs(load: suspend () -> List<Song>, then: (List<Song>) -> Unit) {
        app.scope.launch { runCatching { load() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(then) }
    }
    when (item) {
        is OmniItem.Recent -> {
            model.type(item.text)
            app.omnibox.highlight = 0
            return
        }
        is OmniItem.Run -> {
            item.command.run()
            if (typed.startsWith(">")) model.type("")
            close(remember = false)
        }
        is OmniItem.SongHit -> {
            when (how) {
                OmniHow.Main -> if (item.outside) app.play(listOf(item.song)) else app.play(item.list, item.index)
                OmniHow.Queue -> app.addToQueue(listOf(item.song))
                OmniHow.Next -> app.playNext(listOf(item.song))
                OmniHow.GoTo -> item.song.albumId?.takeIf(String::isNotBlank)?.let { app.navigator.go(Page.Album(it)) }
            }
            close()
        }
        is OmniItem.AlbumHit -> {
            val id = item.album.id
            when (how) {
                OmniHow.Main -> app.navigator.go(Page.Album(id))
                OmniHow.Queue -> withSongs({ app.albumSongs(id) }, app::addToQueue)
                OmniHow.Next -> withSongs({ app.albumSongs(id) }, app::playNext)
                OmniHow.GoTo -> item.album.artistId?.takeIf(String::isNotBlank)?.let { app.navigator.go(Page.Artist(it, item.album.artist)) }
            }
            close()
        }
        is OmniItem.ArtistHit -> {
            val artist = item.artist
            when (how) {
                OmniHow.Main, OmniHow.GoTo -> app.navigator.go(Page.Artist(artist.id, artist.name))
                OmniHow.Queue -> withSongs({ app.artistSongs(artist.id) }, app::addToQueue)
                OmniHow.Next -> withSongs({ app.artistSongs(artist.id) }, app::playNext)
            }
            close()
        }
        is OmniItem.PlaylistHit -> {
            val id = item.playlist.id
            when (how) {
                OmniHow.Main, OmniHow.GoTo -> app.navigator.go(Page.Playlist(id))
                OmniHow.Queue -> withSongs({ app.playlistSongs(id) }, app::addToQueue)
                OmniHow.Next -> withSongs({ app.playlistSongs(id) }, app::playNext)
            }
            close()
        }
        is OmniItem.SeeAll -> {
            app.navigator.go(Page.Search)
            close()
        }
    }
}
