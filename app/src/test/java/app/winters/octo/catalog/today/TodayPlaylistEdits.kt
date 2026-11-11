package app.winters.octo.catalog.today

import app.winters.octo.catalog.ORD_STEP
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.TrackEntity

// A labeled pin: today's playlist edits, as catalog/Playlists.kt had them
// at c2208eaa (every edit renumbering the whole playlist), frozen as the
// measure the one-row edits on a sparse order must match. Never edited to
// follow the app.

// A row's place in its playlist, 0 for the first, from its ord.
val PlaylistItemEntity.place: Int get() = (ord / ORD_STEP).toInt()

// The ord of a playlist row at a place.
fun ordAt(place: Int): Long = place * ORD_STEP

// New rows for songs added to the end of a playlist, placed on from `start`.
fun appendedItems(playlistId: String, start: Int, tracks: List<TrackEntity>): List<PlaylistItemEntity> =
    tracks.mapIndexed { offset, track ->
        PlaylistItemEntity(playlistId = playlistId, trackId = track.id, relinkKey = track.relinkKey, ord = ordAt(start + offset))
    }

// The rows placed 0, 1, 2... in the order given, closing any gaps.
fun renumbered(items: List<PlaylistItemEntity>): List<PlaylistItemEntity> =
    items.mapIndexed { index, item -> if (item.ord == ordAt(index)) item else item.copy(ord = ordAt(index)) }

// Puts a row taken out back at the place it had, `item.place`, and
// renumbers. A place past the end puts it last. A row already there
// changes nothing.
fun restoredItems(items: List<PlaylistItemEntity>, item: PlaylistItemEntity): List<PlaylistItemEntity> {
    if (items.any { it.id == item.id }) return items
    val at = item.place.coerceIn(0, items.size)
    return renumbered(items.toMutableList().apply { add(at, item) })
}

// Moves one row into the place of another, the one it was dropped on, and
// renumbers. Rows are expected in play order. Unknown ids change nothing.
fun movedItem(items: List<PlaylistItemEntity>, itemId: Long, targetId: Long): List<PlaylistItemEntity> {
    val from = items.indexOfFirst { it.id == itemId }
    val to = items.indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0) return items
    return renumbered(items.toMutableList().apply { add(to, removeAt(from)) })
}
