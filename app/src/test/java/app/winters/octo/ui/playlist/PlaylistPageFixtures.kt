package app.winters.octo.ui.playlist

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SET_PAGE
import app.winters.octo.catalog.TrackEntity

// Library songs "song<i>" for i in `ids`.
internal suspend fun CatalogDatabase.songs(ids: IntRange) = dao().insertTracks(
    ids.map { i ->
        TrackEntity(
            id = "song$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song $i",
            artist = "Band", artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null, year = null, durationMs = 0,
            addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = "k$i",
        )
    },
)

// A sync's write-back: a whole new set of the playlist's rows, shown in one
// step under its set mutex, as a pull from the server leaves it. Rows that
// name a key keep it (a family pull); the rest take fresh keys.
internal suspend fun PlaylistSets.writeBack(db: CatalogDatabase, playlistId: String, rows: List<NewItem>) = withSet(playlistId) {
    val set = writeSet(playlistId) { emit -> rows.chunked(SET_PAGE).forEach { emit(it) } }!!
    db.openHelper.writableDatabase.execSQL("UPDATE playlist SET itemSet = ? WHERE id = ?", arrayOf<Any?>(set, playlistId))
    set
}

// The rows a playlist shows, in play order.
internal fun CatalogDatabase.shownRows(playlistId: String): List<PlaylistItemEntity> = query(
    "SELECT i.id, i.itemKey, i.ord, i.trackId, i.relinkKey, i.serverSongId, i.itemSet FROM playlist p " +
        "CROSS JOIN playlist_item i ON i.playlistId = p.id AND i.itemSet = p.itemSet WHERE p.id = ? ORDER BY i.ord, i.itemKey",
    arrayOf<Any?>(playlistId),
).use { c ->
    buildList {
        while (c.moveToNext()) {
            add(
                PlaylistItemEntity(
                    id = c.getLong(0), playlistId = playlistId, itemKey = c.getLong(1), ord = c.getLong(2), trackId = c.getString(3),
                    relinkKey = c.getString(4), serverSongId = c.getString(5), itemSet = c.getLong(6),
                ),
            )
        }
    }
}

// The same rows as a write-back keeping their keys would send them.
internal fun List<PlaylistItemEntity>.kept() = map { NewItem(it.trackId, it.relinkKey, it.serverSongId, it.itemKey) }

internal fun CatalogDatabase.rowsOfSet(playlistId: String, itemSet: Long): Long =
    query("SELECT COUNT(*) FROM playlist_item WHERE playlistId = ? AND itemSet = ?", arrayOf<Any?>(playlistId, itemSet)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
