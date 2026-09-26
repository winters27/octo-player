package app.winters.octo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.ui.menu.LocalSongMenu

// A song in a list that can be picked: the key it is picked by, which is
// its id, or its row on a playlist page where a song can be there twice.
data class Pickable(val key: String, val track: TrackEntity)

// Which songs are picked. Nothing picked means the list is not selecting:
// taking the last one off ends it.
data class Picks(val keys: Set<String> = emptySet()) {
    val active: Boolean get() = keys.isNotEmpty()

    fun start(key: String) = Picks(setOf(key))
    fun toggle(key: String) = Picks(if (key in keys) keys - key else keys + key)

    // Drops songs no longer in the list, such as one just removed.
    fun keepOnly(present: Set<String>) = if (keys.all { it in present }) this else Picks(keys.intersect(present))

    // The picked songs in the list's own order.
    fun inOrder(list: List<Pickable>): List<Pickable> = list.filter { it.key in keys }
}

// A list's selection, kept by the screen that shows the list.
@Stable
class SongSelection {
    var picks by mutableStateOf(Picks())
        private set

    val active: Boolean get() = picks.active
    val count: Int get() = picks.keys.size

    fun isPicked(key: String) = key in picks.keys
    fun start(key: String) {
        picks = picks.start(key)
    }
    fun toggle(key: String) {
        picks = picks.toggle(key)
    }
    fun keepOnly(present: Set<String>) {
        picks = picks.keepOnly(present)
    }
    fun clear() {
        picks = Picks()
    }
}

// The selection of the list a row is in, or null in a list that cannot select.
val LocalSongSelection = staticCompositionLocalOf<SongSelection?> { null }

// A list selecting songs, as the action bar sees it: the picked songs, and
// a way to take them off the page's playlist when it is one.
class SelectionTarget(
    val selection: SongSelection,
    private val list: () -> List<Pickable>,
    private val remover: () -> ((List<Pickable>) -> Unit)?,
) {
    fun picked(): List<Pickable> = selection.picks.inOrder(list())
    val remove: ((List<Pickable>) -> Unit)? get() = remover()
}

// The lists that can select right now. Only the one on screen is ever
// selecting, since selecting starts from a row.
@Stable
class SelectionBarState {
    private var targets by mutableStateOf(emptyList<SelectionTarget>())

    val selecting: SelectionTarget? get() = targets.firstOrNull { it.selection.active }

    fun attach(target: SelectionTarget) {
        targets = targets + target
    }

    fun detach(target: SelectionTarget) {
        targets = targets - target
    }
}

// Lets the songs inside be picked: rows read the selection, and the action
// bar appears while any are picked. `songs` is the list as shown;
// `onRemove` takes picked songs off the page's playlist.
@Composable
fun SelectableSongs(
    songs: List<Pickable>,
    onRemove: ((List<Pickable>) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val selection = remember { SongSelection() }
    val bar = LocalSongMenu.current.selectionBar
    val list by rememberUpdatedState(songs)
    val remove by rememberUpdatedState(onRemove)
    val target = remember(selection) { SelectionTarget(selection, { list }, { remove }) }
    DisposableEffect(target) {
        bar.attach(target)
        onDispose {
            bar.detach(target)
            selection.clear()
        }
    }
    LaunchedEffect(songs) { selection.keepOnly(songs.mapTo(HashSet()) { it.key }) }
    CompositionLocalProvider(LocalSongSelection provides selection, content = content)
}
