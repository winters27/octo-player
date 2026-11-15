package app.winters.octo.ui.playlist

import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import java.text.Collator
import java.util.Locale

// The kinds of group an Add to playlist list shows, in the order shown.
enum class PickerGroupKind { Recent, Unfiled, Folder, Upkeep }

// One group of an Add to playlist list: its kind, its heading (a folder's
// path, or the upkeep group's name; none for the recent and unfiled
// groups), and its playlists in the order shown.
data class PickerGroup<T>(val kind: PickerGroupKind, val title: String?, val items: List<T>)

// How an Add to playlist list is grouped on every app, under its "New
// playlist" row: up to RECENT playlists added to lately, then the playlists
// in no folder A to Z, then each folder's playlists under its path (folders
// in tree order), then the library upkeep playlists. A playlist added to
// lately shows in its own group too. Upkeep playlists never show inside a
// folder. For songs found online (`forFind`) the ones that refuse such
// songs (`refusesFind`) are left out everywhere. Empty groups are left
// out. Callers leave out playlists the listener can't add to (an imported
// playlist's copy, a family playlist they may only hear).
object PickerGroups {
    const val RECENT = 3

    fun <T> build(
        items: List<T>,
        id: (T) -> String,
        placeKey: (T) -> String,
        name: (T) -> String,
        upkeep: (T) -> Boolean,
        refusesFind: (T) -> Boolean,
        recentIds: List<String>,
        tree: PlaylistFolderTree?,
        forFind: Boolean,
        locale: Locale = Locale.getDefault(),
    ): List<PickerGroup<T>> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val byName = Comparator<T> { a, b -> collator.compare(name(a), name(b)).takeIf { it != 0 } ?: id(a).compareTo(id(b)) }
        val offered = if (forFind) items.filterNot(refusesFind) else items
        val byId = offered.associateBy(id)
        val groups = ArrayList<PickerGroup<T>>()
        val recent = recentIds.distinct().mapNotNull(byId::get).take(RECENT)
        if (recent.isNotEmpty()) groups += PickerGroup(PickerGroupKind.Recent, null, recent)
        val (own, upkept) = offered.partition { !upkeep(it) }
        val unfiled = ArrayList<T>()
        val filed = HashMap<String, MutableList<T>>()
        for (item in own) {
            val folder = tree?.folderOf(placeKey(item))
            if (folder == null) unfiled += item else filed.getOrPut(folder) { ArrayList() } += item
        }
        if (unfiled.isNotEmpty()) groups += PickerGroup(PickerGroupKind.Unfiled, null, unfiled.sortedWith(byName))
        if (tree != null) {
            for ((folder, _) in tree.inTreeOrder()) {
                val held = filed[folder.id] ?: continue
                groups += PickerGroup(PickerGroupKind.Folder, tree.pathName(folder.id), held.sortedWith(byName))
            }
        }
        if (upkept.isNotEmpty()) groups += PickerGroup(PickerGroupKind.Upkeep, PlaylistFolderWords.LIBRARY_UPKEEP, upkept.sortedWith(byName))
        return groups
    }
}
