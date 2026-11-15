package app.winters.octo.desktop.playlists

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.Song

// The sidebar's playlists as rows, which playlists take songs found online,
// and the playlists picked with ctrl and shift clicks, worked out apart from
// how they are drawn so they can be checked without a window.

// The kinds of library upkeep list a server names (octoUpkeepKind): its
// library actions, its notices, and a family member's request lists.
const val UPKEEP_ACTION = "action"
const val UPKEEP_NOTICE = "notice"
const val UPKEEP_REQUEST = "request"

// Whether songs found online never go in this playlist: one of Octo's
// library action or notice lists (a server that names no kind means an
// action list). A family member's request lists take them, as requests.
fun refusesFinds(playlist: Playlist): Boolean =
    playlist.octoUpkeep && (playlist.octoUpkeepKind ?: UPKEEP_ACTION) != UPKEEP_REQUEST

// The songs of `songs` that can go in `playlist`: songs found online only on
// a server listing octoPlaylistSongs (`outsideAllowed`; an older one would
// drop them) and never in an upkeep list that refuses them.
fun songsFor(playlist: Playlist, songs: List<Song>, outsideAllowed: Boolean): List<Song> =
    if (outsideAllowed && !refusesFinds(playlist)) songs else songs.filterNot { it.isExternal }

// One row of the sidebar's Playlists group.
sealed interface SidebarPlaylistRow {
    val key: String

    // A playlist `depth` folders in (0 at the top). `first` marks a pinned
    // playlist in the pinned rows at the top, which a filed one is shown
    // beside its folder too.
    data class Item(val playlist: Playlist, val depth: Int, val pinned: Boolean, val first: Boolean = false) : SidebarPlaylistRow {
        override val key: String get() = (if (first) "pin:" else "pl:") + playlist.id
    }

    // A folder at `depth` (1 at the top), open or not, with how many
    // playlists and folders sit right in it.
    data class Folder(val folder: PlaylistFolder, val depth: Int, val open: Boolean, val playlists: Int, val folders: Int) : SidebarPlaylistRow {
        override val key: String get() = "pf:" + folder.id
    }
}

// The rows, top to bottom: the pinned playlists in the order they were
// pinned; Octo's own lists the group holds (Liked Songs), never filed; then,
// on a server that keeps folders (`tree`), the folders each level A to Z, an
// open one (in `open`) followed by its folders and then its playlists; then
// the playlists in no folder, in the server's order. A pinned playlist in a
// folder shows in both places, one in no folder only at the top. On the
// rail (`rail`) only the top level shows, every folder closed.
fun sidebarPlaylistRows(
    playlists: List<Playlist>,
    pinned: List<String>,
    tree: PlaylistFolderTree?,
    open: Set<String>,
    rail: Boolean,
): List<SidebarPlaylistRow> {
    val out = ArrayList<SidebarPlaylistRow>()
    val byId = playlists.associateBy { it.id }
    val pinnedIds = pinned.toSet()
    pinned.distinct().mapNotNull(byId::get).forEach { out += SidebarPlaylistRow.Item(it, 0, pinned = true, first = true) }
    playlists.filter { it.id !in pinnedIds && !PlaylistFolderTree.fileable(it.id) }.forEach { out += SidebarPlaylistRow.Item(it, 0, pinned = false) }
    val fileable = playlists.filter { PlaylistFolderTree.fileable(it.id) }
    if (tree == null || tree.isEmpty) {
        fileable.filter { it.id !in pinnedIds }.forEach { out += SidebarPlaylistRow.Item(it, 0, pinned = false) }
        return out
    }
    val inFolder = fileable.groupBy { tree.folderOf(it.id) }
    fun walk(parent: String?, depth: Int) {
        for (folder in tree.children(parent)) {
            val shown = !rail && folder.id in open
            out += SidebarPlaylistRow.Folder(folder, depth, shown, inFolder[folder.id].orEmpty().size, tree.children(folder.id).size)
            if (!shown) continue
            walk(folder.id, depth + 1)
            inFolder[folder.id].orEmpty().forEach { out += SidebarPlaylistRow.Item(it, depth, pinned = it.id in pinnedIds) }
        }
    }
    walk(null, 1)
    inFolder[null].orEmpty().filter { it.id !in pinnedIds }.forEach { out += SidebarPlaylistRow.Item(it, 0, pinned = false) }
    return out
}

// Playlists picked in the sidebar with ctrl (command on a Mac) and shift
// clicks, to drag or move several at once. A plain click clears them.
@Stable
class SidebarPicks {
    var picked by mutableStateOf<Set<String>>(emptySet())
        private set

    // The row clicked last, where a shift click's run starts.
    private var anchor: String? = null

    // A click with `toggle` puts the playlist in or out of the picks; with
    // `range` every row from the last one clicked to this one is picked, by
    // the rows' `order` (a toggle when there is no last one); with neither,
    // the picks are this playlist alone.
    fun click(id: String, toggle: Boolean, range: Boolean, order: List<String>) {
        val from = anchor
        picked = when {
            range && from != null && from in order && id in order -> {
                val a = order.indexOf(from)
                val b = order.indexOf(id)
                picked + order.subList(minOf(a, b), maxOf(a, b) + 1)
            }
            toggle || range -> if (id in picked) picked - id else picked + id
            else -> setOf(id)
        }
        if (!range || from == null) anchor = id
    }

    fun clear() {
        picked = emptySet()
        anchor = null
    }

    // What a drag or a menu from the row `id` takes: every pick, in the rows'
    // `order`, when the row is one of several picked; otherwise itself.
    fun group(id: String, order: List<String>): List<String> =
        if (id in picked && picked.size > 1) order.filter { it in picked } else listOf(id)
}
