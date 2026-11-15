package app.winters.octo.desktop.playlists

// Moving songs within a playlist, worked out apart from the table and the
// server so it can be checked on its own. A drag and the menu's Move rows
// both come down to the same thing: the picked rows go together, in their
// order, to one place in the list.

// The list after moving the picked rows together to `to`. `order` is the
// list as it stands, each item a row's place in the playlist (usually
// 0, 1, 2 ...); `picked` are the places that move; `to` is the index in
// `order` they land before, from 0 to order.size (the end). Rows not
// picked keep their order around them.
fun movedPositions(order: List<Int>, picked: Collection<Int>, to: Int): List<Int> {
    val moving = picked.toHashSet()
    val at = to.coerceIn(0, order.size)
    val before = order.subList(0, at).filter { it !in moving }
    val after = order.subList(at, order.size).filter { it !in moving }
    return before + order.filter { it in moving } + after
}

// The moves the song menu offers on a playlist the listener owns.
enum class PlaylistMove(val label: String) {
    Top("Move to top"),
    Up("Move up"),
    Down("Move down"),
    Bottom("Move to bottom"),
}

// A playlist of `count` songs after a menu move of the picked places: up
// and down go one row past the nearest picked row, top and bottom to the
// ends. Places outside the list are ignored.
fun movedBy(move: PlaylistMove, count: Int, picked: Collection<Int>): List<Int> {
    val order = List(count) { it }
    val places = picked.filter { it in 0 until count }
    if (places.isEmpty()) return order
    val to = when (move) {
        PlaylistMove.Top -> 0
        PlaylistMove.Up -> places.min() - 1
        PlaylistMove.Down -> places.max() + 2
        PlaylistMove.Bottom -> count
    }
    return movedPositions(order, places, to)
}

// Whether a move would change anything, for dimming the rows that would not.
fun movesAnything(move: PlaylistMove, count: Int, picked: Collection<Int>): Boolean =
    movedBy(move, count, picked) != List(count) { it }

// A move of picked songs, by their ids in the playlist's order, to land
// before the song `anchor` (null: at the end), so it can be made again over
// the playlist after another device changed it.
data class SongMove(val picked: List<String>, val anchor: String?)

// The move that takes the picked places of `ids` to `to`, as movedPositions
// takes them: the anchor is the first song at or after `to` not picked.
fun songMoveOf(ids: List<String>, picked: Collection<Int>, to: Int): SongMove {
    val moving = picked.toHashSet()
    val at = to.coerceIn(0, ids.size)
    val anchor = (at until ids.size).firstOrNull { it !in moving }?.let(ids::get)
    return SongMove(ids.indices.filter { it in moving }.map(ids::get), anchor)
}

// The move made over `ids`: each picked song taken by its id (a song picked
// twice takes its first two copies), landing before the first copy of the
// anchor not picked, or at the end when the anchor is gone. Null when a
// picked song is no longer there.
fun reapplied(move: SongMove, ids: List<String>): List<Int>? {
    val used = BooleanArray(ids.size)
    val places = ArrayList<Int>()
    for (id in move.picked) {
        val at = ids.indices.firstOrNull { !used[it] && ids[it] == id } ?: return null
        used[at] = true
        places += at
    }
    val to = move.anchor?.let { anchor -> ids.indices.firstOrNull { !used[it] && ids[it] == anchor } } ?: ids.size
    return movedPositions(ids.indices.toList(), places, to)
}
