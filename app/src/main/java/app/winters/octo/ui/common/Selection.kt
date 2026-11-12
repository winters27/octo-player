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
import app.winters.octo.playback.ListSpec
import app.winters.octo.ui.menu.LocalSongMenu
import app.winters.octo.ui.paging.RowKey

// A song in a list that can be picked: the key it is picked by, which is
// its id, or its row on a playlist page where a song can be there twice.
data class Pickable(val key: String, val track: TrackEntity, val rowKey: RowKey? = null)

// A picked row, as much of it as the action bar needs: the key it was
// picked by, its song and its row's key in the list, whether its song is
// on the phone, and what the player shows of it at once.
data class Picked(
    val key: String,
    val trackId: String,
    val rowKey: RowKey?,
    val onPhone: Boolean,
    val title: String,
    val artist: String,
    val artwork: String?,
)

fun Pickable.picked() = Picked(key, track.id, rowKey, track.onPhone, track.title, track.artist, track.artwork)

// Which rows are picked, in the order they were tapped. It grows with
// taps, never with the list. Nothing picked means the list is not
// selecting: taking the last one off ends it.
data class Picks(val rows: Map<String, Picked> = emptyMap()) {
    val active: Boolean get() = rows.isNotEmpty()
    val keys: Set<String> get() = rows.keys

    fun start(row: Picked) = Picks(linkedMapOf(row.key to row))

    fun toggle(row: Picked) = Picks(LinkedHashMap(rows).apply { if (remove(row.key) == null) put(row.key, row) })

    // Drops rows no longer in the list, such as one just removed.
    fun keepOnly(present: Set<String>) = if (rows.keys.all { it in present }) this else Picks(rows.filterKeys { it in present })
}

// A list's selection, kept by the screen that shows the list. A row is
// picked by its key; `resolve` finds what the bar needs of it among the
// rows the list has read.
@Stable
class SongSelection {
    var picks by mutableStateOf(Picks())
        private set

    var resolve: (String) -> Picked? = { null }

    val active: Boolean get() = picks.active
    val count: Int get() = picks.rows.size

    fun isPicked(key: String) = key in picks.rows

    fun start(key: String) {
        resolve(key)?.let { picks = picks.start(it) }
    }

    fun toggle(key: String) {
        val row = picks.rows[key] ?: resolve(key) ?: return
        picks = picks.toggle(row)
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

// A list selecting songs, as the action bar sees it: the picked rows, put
// in the list's order as it stands when an action runs; the list they were
// picked on (null for a list held whole, whose picks play as they are);
// and a way to take them off the page's playlist when it is one.
class SelectionTarget(
    val selection: SongSelection,
    private val order: suspend (List<Picked>) -> List<Picked>,
    private val remover: () -> ((List<Picked>) -> Unit)?,
    private val base: () -> ListSpec? = { null },
) {
    // The picked rows as tapped, for what the bar shows.
    val picks: List<Picked> get() = selection.picks.rows.values.toList()

    // The picked rows in the list's order now.
    suspend fun ordered(): List<Picked> = order(picks)

    val spec: ListSpec? get() = base()

    val remove: ((List<Picked>) -> Unit)? get() = remover()
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

// Lets the songs of a list held whole be picked: rows read the selection,
// and the action bar appears while any are picked. `songs` is the list as
// shown; `onRemove` takes picked songs off the page's playlist.
@Composable
fun SelectableSongs(
    songs: List<Pickable>,
    onRemove: ((List<Picked>) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val list by rememberUpdatedState(songs)
    val selection = rememberSelection(
        resolve = { key -> list.firstOrNull { it.key == key }?.picked() },
        order = { picked -> inListOrder(list, picked) },
        onRemove = onRemove,
        base = null,
    )
    LaunchedEffect(songs) { selection.keepOnly(songs.mapTo(HashSet()) { it.key }) }
    CompositionLocalProvider(LocalSongSelection provides selection, content = content)
}

// Picked rows in the order of a list held whole, leaving out any it no
// longer holds.
fun inListOrder(list: List<Pickable>, picked: List<Picked>): List<Picked> {
    val at = list.withIndex().associate { it.value.key to it.index }
    return picked.filter { it.key in at }.sortedBy { at.getValue(it.key) }
}

// Lets the rows of any list be picked, a list held in pages included: a
// tapped row is found among the rows read (`resolve`), and the picks are
// put in the list's order when an action runs (`order`). `base` is the
// list the rows were picked on, when the picks play as a part of it.
// `upkeep` hears the selection, to drop picks the list no longer holds.
@Composable
fun SelectableRows(
    resolve: (String) -> Picked?,
    order: suspend (List<Picked>) -> List<Picked>,
    onRemove: ((List<Picked>) -> Unit)? = null,
    base: ListSpec? = null,
    upkeep: @Composable (SongSelection) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val selection = rememberSelection(resolve, order, onRemove, base)
    upkeep(selection)
    CompositionLocalProvider(LocalSongSelection provides selection, content = content)
}

@Composable
private fun rememberSelection(
    resolve: (String) -> Picked?,
    order: suspend (List<Picked>) -> List<Picked>,
    onRemove: ((List<Picked>) -> Unit)?,
    base: ListSpec?,
): SongSelection {
    val selection = remember { SongSelection() }
    val bar = LocalSongMenu.current.selectionBar
    val find by rememberUpdatedState(resolve)
    val sort by rememberUpdatedState(order)
    val remove by rememberUpdatedState(onRemove)
    val spec by rememberUpdatedState(base)
    selection.resolve = { find(it) }
    val target = remember(selection) { SelectionTarget(selection, { sort(it) }, { remove }, { spec }) }
    DisposableEffect(target) {
        bar.attach(target)
        onDispose {
            bar.detach(target)
            selection.clear()
        }
    }
    return selection
}
