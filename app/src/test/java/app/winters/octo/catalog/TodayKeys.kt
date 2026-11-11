package app.winters.octo.catalog

import androidx.room.withTransaction

// The second thing a save does that today's swap never did (TodaySwap.kt,
// TodayAdoptions.kt): the listener's rows keep a key what they name is
// filed under. Once the library is in, each like, rating, play, playlist
// row and playlist edit on a library song whose relink key neither the song
// nor any of its copies (the source rows merged into it) holds takes the
// song's key; each favorite album or artist and each pin on a library
// album or artist whose key neither its search key nor any of its copies'
// (the source albums or artists of its songs' copies) is takes its search
// key. Only where that key is not empty. A row on an id the library lacks
// keeps the key it had. Frozen with TodaySwap; never edited to follow the
// app.
internal val TODAY_KEYED_TABLES = listOf("liked_track", "track_rating", "play_event", "playlist_item", "playlist_edit_log", "liked_album", "liked_artist", "pinned_item")

internal val TODAY_KEYS_SQL: List<String> =
    TODAY_KEYED_TABLES.take(5).map { table ->
        """
        UPDATE $table SET relinkKey = (SELECT t.relinkKey FROM track t WHERE t.id = $table.trackId)
        WHERE EXISTS (SELECT 1 FROM track t WHERE t.id = $table.trackId AND t.relinkKey != '' AND t.relinkKey IS NOT $table.relinkKey)
          AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.mergedId = $table.trackId AND s.relinkKey = $table.relinkKey)
        """
    } + listOf(
        Triple("liked_album", "albumId", "album"),
        Triple("liked_artist", "artistId", "artist"),
        Triple("pinned_item", "itemId", "album"),
        Triple("pinned_item", "itemId", "artist"),
    ).map { (table, column, kind) ->
        val pin = if (table == "pinned_item") "kind = '$kind' AND " else ""
        """
        UPDATE $table SET relinkKey = (SELECT x.searchKey FROM $kind x WHERE x.id = $table.$column)
        WHERE $pin EXISTS (SELECT 1 FROM $kind x WHERE x.id = $table.$column AND x.searchKey != '' AND x.searchKey IS NOT $table.relinkKey)
          AND NOT EXISTS (
            SELECT 1 FROM track t JOIN source_track s ON s.mergedId = t.id JOIN source_$kind a ON a.id = s.${kind}Id
            WHERE t.${kind}Id = $table.$column AND a.searchKey = $table.relinkKey
          )
        """
    }

// After today's swap and its adoptions, or after today's skipped merge:
// answers how many rows took a key.
internal suspend fun todayKeys(db: CatalogDatabase): Int = db.withTransaction {
    val sql = db.openHelper.writableDatabase
    TODAY_KEYS_SQL.sumOf { statement -> sql.compileStatement(statement).use { it.executeUpdateDelete() } }
}
