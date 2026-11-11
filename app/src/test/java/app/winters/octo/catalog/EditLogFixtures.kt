package app.winters.octo.catalog

// Single edits as the playlist page makes them, through PlaylistSets'
// writer: each one transaction under the playlist's set mutex, its edit log
// rows written before it changes the rows.
internal class Edits(private val db: CatalogDatabase, private val sets: PlaylistSets, private val playlistId: String) {
    // The row at a place in the set the playlist shows.
    fun rowAt(rank: Int): PlaylistItemEntity = rows()[rank]

    fun rows(): List<PlaylistItemEntity> = db.query(
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

    suspend fun move(key: Long, toOrd: Long) {
        sets.edit(playlistId) { set ->
            sets.logRows(EditOp.MOVE, playlistId, set, listOf(key))
            db.exec("UPDATE playlist_item SET ord = ? WHERE playlistId = ? AND itemSet = ? AND itemKey = ?", toOrd, playlistId, set, key)
            db.exec("UPDATE playlist SET updatedAt = updatedAt + 1 WHERE id = ?", playlistId)
        }
    }

    // Answers the row as it was.
    suspend fun remove(key: Long): PlaylistItemEntity {
        val row = rows().first { it.itemKey == key }
        sets.edit(playlistId) { set ->
            sets.logRows(EditOp.REMOVE, playlistId, set, listOf(key))
            db.exec("DELETE FROM playlist_item WHERE playlistId = ? AND itemSet = ? AND itemKey = ?", playlistId, set, key)
            db.exec("UPDATE playlist SET updatedAt = updatedAt + 1 WHERE id = ?", playlistId)
        }
        return row
    }

    suspend fun restore(row: PlaylistItemEntity) {
        sets.edit(playlistId) { set ->
            sets.logRestore(playlistId, set, row)
            db.exec(
                "INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey, serverSongId) VALUES (?, ?, ?, ?, ?, ?, ?)",
                playlistId, set, row.trackId, row.relinkKey, row.ord, row.itemKey, row.serverSongId,
            )
            db.exec("UPDATE playlist SET updatedAt = updatedAt + 1 WHERE id = ?", playlistId)
        }
    }

    // A new song at the end: a fresh key, nothing logged.
    suspend fun add(trackId: String) {
        sets.edit(playlistId) { set ->
            val last = rows().lastOrNull()?.ord ?: -ORD_STEP
            val key = db.longOf("SELECT nextItemKey FROM playlist_meta WHERE id = 0")
            db.exec("UPDATE playlist_meta SET nextItemKey = nextItemKey + 1 WHERE id = 0")
            db.exec(
                "INSERT INTO playlist_item (playlistId, itemSet, trackId, relinkKey, ord, itemKey) VALUES (?, ?, ?, '', ?, ?)",
                playlistId, set, trackId, last + ORD_STEP, key,
            )
        }
    }
}

// A playlist of `n` songs ("song0" on), made through the bulk writer.
internal suspend fun PlaylistSets.playlistOf(id: String, n: Int, prefix: String = "song") {
    create(PlaylistEntity(id, id.uppercase(), 1, 1)) { emit -> List(n) { NewItem("$prefix$it", "k$it") }.chunked(SET_PAGE).forEach { emit(it) } }
}

// A pinned set read whole through the baseline statement, `pageSize` rows
// at a time, with `between` run after each page.
internal suspend fun PlaylistSets.walked(set: PinnedSet, pageSize: Int, between: suspend (Int) -> Unit = {}): List<String> {
    val out = ArrayList<String>()
    var page = 0
    walk(set, pageSize) { rows ->
        out += rows.map { it.trackId }
        between(++page)
    }
    return out
}
