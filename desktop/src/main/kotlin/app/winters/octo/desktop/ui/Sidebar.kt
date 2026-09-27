package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SidebarItem
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Separator
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import dev.chrisbanes.haze.HazeState

val SidebarWidth = 232.dp
val SidebarShape = RoundedCornerShape(20.dp)

private val topItems = listOf(
    Triple(Page.Home, "Home", OctoIcons.Home),
    Triple(Page.Search, "Search", OctoIcons.Search),
)

private val libraryItems = listOf(
    Triple(Page.Songs, "Songs", OctoIcons.Songs),
    Triple(Page.Albums, "Albums", OctoIcons.Album),
    Triple(Page.Artists, "Artists", OctoIcons.Artist),
    Triple(Page.Genres, "Genres", OctoIcons.Genres),
    Triple(Page.Folders, "Folders", OctoIcons.Folder),
    Triple(Page.Favourites, "Favourites", OctoIcons.Like),
    Triple(Page.History, "History", OctoIcons.History),
)

// The glass panel down the left: where to go, the playlists, and Settings
// at the foot. The item for the page shown is the darker pill set into the
// glass.
@Composable
fun Sidebar(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val lit = app.navigator.sidebarItem
    FloatingGlaze(backdrop, modifier, shape = SidebarShape) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp)) {
            topItems.forEach { (page, label, icon) -> NavRow(label, icon, lit == SidebarItem.Top(page)) { app.navigator.go(page) } }
            Heading("Library")
            libraryItems.forEach { (page, label, icon) -> NavRow(label, icon, lit == SidebarItem.Top(page)) { app.navigator.go(page) } }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Playlists", OctoType.caption, OctoColors.TextMuted, Modifier.weight(1f).padding(start = 12.dp))
                IconAction(OctoIcons.AddToLibrary, "New playlist", { newPlaylist(app) }, size = 28.dp, iconSize = 18.dp, tint = OctoColors.TextSecondary)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(app.playlists, key = { it.id }) { playlist ->
                    NavRow(playlist.name, null, lit == SidebarItem.PlaylistItem(playlist.id)) { app.navigator.go(Page.Playlist(playlist.id)) }
                }
            }
            Separator(Modifier.padding(vertical = 6.dp))
            NavRow("Settings", OctoIcons.Settings, lit == SidebarItem.Top(Page.Settings)) { app.navigator.go(Page.Settings) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Txt(text, OctoType.caption, OctoColors.TextMuted, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
}

// One place to go: an icon and its name, the chosen one on the darker pill.
@Composable
private fun NavRow(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .hoverLift(CircleShape, lifted = false)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (selected) GlazeSelected(Modifier.matchParentSize())
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Glyph(icon, size = 19.dp, tint = if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
            Txt(label, OctoType.label, if (selected) OctoColors.TextPrimary else OctoColors.TextSecondary)
        }
    }
}

// A small form for naming a new, empty playlist, in the middle of the window.
fun newPlaylist(app: AppState) {
    app.popups.showCentred { close ->
        var name by remember { mutableStateOf("") }
        MenuTitle("New playlist")
        PopupPadding {
            val create = {
                if (name.isNotBlank()) {
                    app.createPlaylist(name, emptyList()) { id -> id?.let { app.navigator.go(Page.Playlist(it)) } }
                    close()
                }
            }
            GlassField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Name", onSubmit = create, onEscape = close)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(null, "Create", create, lit = true, enabled = name.isNotBlank())
            }
        }
    }
}
