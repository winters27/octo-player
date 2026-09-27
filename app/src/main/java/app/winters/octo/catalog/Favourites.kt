package app.winters.octo.catalog

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// Albums and artists the listener keeps as favourites, and what is pinned
// to Home. Like liked songs, none of these point at the catalog with a
// foreign key: the catalog is rebuilt on every scan. Each row keeps a
// relink key instead (the album's or artist's search key), so one whose id
// changed can be found again.

@Entity(tableName = "liked_album")
data class LikedAlbumEntity(
    @PrimaryKey val albumId: String,
    val relinkKey: String,
    val likedAt: Long,
)

@Entity(tableName = "liked_artist")
data class LikedArtistEntity(
    @PrimaryKey val artistId: String,
    val relinkKey: String,
    val likedAt: Long,
)

// Something pinned to the front of Home, at its place in the row (0 first).
@Entity(tableName = "pinned_item", primaryKeys = ["kind", "itemId"])
data class PinnedItemEntity(
    val kind: String,
    val itemId: String,
    val relinkKey: String,
    val position: Int,
)

// What can be pinned. The id is what gets saved, so it never changes.
enum class PinKind(val id: String) {
    Album("album"),
    Artist("artist"),
    Playlist("playlist"),
    ;

    companion object {
        fun of(id: String): PinKind? = entries.firstOrNull { it.id == id }
    }
}

// A favourite album or artist with when it became one, for the Favourites page.
data class LikedAlbum(@Embedded val album: AlbumEntity, val likedAt: Long)

data class LikedArtist(@Embedded val artist: ArtistEntity, val likedAt: Long)

// A library album or artist id and its search key, for finding one again.
data class KeyedId(val id: String, val searchKey: String)

// How many songs of one source album (or artist) row went into one library
// album (or artist) when the library was merged. The row is a server's for
// stars, any source's for following a merge.
data class CopyCount(val serverRowId: String, val libraryId: String, val songs: Int)

// Home holds at most this many pins.
const val PIN_LIMIT = 12

// A favourite or pin that points at something by id, with its relink key.
data class Held(val id: String, val relinkKey: String)

// A held id that moves to the library id it now lives under.
data class Relink(val from: String, val to: String)

// Which held ids should move after a rebuild, the way liked songs do: only
// ids the library no longer has move, and never onto an id already held
// (that one stays as it was). An album or artist that merged into another
// follows it (`merged`, from mergedInto), since that is certain whatever
// the two copies are called. Failing that, it moves to the id with the same
// relink key; when several share it, the first by id wins, so the choice is
// the same every time.
fun relinks(
    held: List<Held>,
    present: Set<String>,
    byKey: Map<String, List<String>>,
    merged: Map<String, String> = emptyMap(),
): List<Relink> {
    val taken = held.mapTo(HashSet()) { it.id }
    val moves = ArrayList<Relink>()
    for (row in held.sortedBy { it.id }) {
        if (row.id in present) continue
        val followed = merged[row.id]?.takeIf { it in present }
        val to = when {
            followed != null -> followed.takeIf { it !in taken }
            row.relinkKey.isEmpty() -> null
            else -> byKey[row.relinkKey].orEmpty().sorted().firstOrNull { it !in taken }
        } ?: continue
        taken += to
        moves += Relink(row.id, to)
    }
    return moves
}

// Where each album (or artist) the library no longer has under its own id
// went when the sources were merged: the library one that took most of its
// songs, the first by id on a tie. The counts come from the source rows.
fun mergedInto(counts: List<CopyCount>): Map<String, String> =
    counts.groupBy { it.serverRowId }.mapValues { (_, links) ->
        links.sortedWith(compareByDescending<CopyCount> { it.songs }.thenBy { it.libraryId }).first().libraryId
    }

// Pins in row order, numbered again from 0.
private fun List<PinnedItemEntity>.renumbered(): List<PinnedItemEntity> =
    mapIndexed { index, pin -> if (pin.position == index) pin else pin.copy(position = index) }

fun List<PinnedItemEntity>.inPinOrder(): List<PinnedItemEntity> = sortedWith(compareBy({ it.position }, { it.kind }, { it.itemId }))

// The pins with one more at the end. `showing` is how many pins Home can
// show now (pins whose album, artist or playlist is gone do not count).
// Null when Home is full. Pinning something already pinned changes nothing.
fun withPin(pins: List<PinnedItemEntity>, add: PinnedItemEntity, showing: Int): List<PinnedItemEntity>? {
    val ordered = pins.inPinOrder()
    if (ordered.any { it.kind == add.kind && it.itemId == add.itemId }) return ordered
    if (showing >= PIN_LIMIT) return null
    return (ordered + add).renumbered()
}

// The pins without one, the rest closed up.
fun withoutPin(pins: List<PinnedItemEntity>, kind: String, itemId: String): List<PinnedItemEntity> =
    pins.inPinOrder().filterNot { it.kind == kind && it.itemId == itemId }.renumbered()

// The pins with one moved to the front, the rest keeping their order.
fun movedToFront(pins: List<PinnedItemEntity>, kind: String, itemId: String): List<PinnedItemEntity> {
    val ordered = pins.inPinOrder()
    val moving = ordered.firstOrNull { it.kind == kind && it.itemId == itemId } ?: return ordered
    return (listOf(moving) + (ordered - moving)).renumbered()
}
