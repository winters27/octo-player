package app.winters.octo.catalog

// The statements a server list's read, resolution and pages run over the
// server_* tables, each reading or writing one owner's rows a page at a
// time. They are the playlist sync read's, with the owner in place of the
// read.

// The tables one owner's server list is kept in.
val SERVER_TABLES = listOf("server_entry", "server_key", "server_match", "server_lookup", "server_head")

internal const val SERVER_ENTRY_INSERT_SQL =
    "INSERT INTO server_entry (owner, position, songId, external, title, artist, lengthMs, album, albumId, artistId, coverId, " +
        "mimeType, bitrate, explicit, trackId, kind, groupId, firstFind, held, relinkKey) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, NULL, 0, 0, '')"

internal const val SERVER_HEADS_SQL =
    "SELECT position, songId, external, title, artist, lengthMs FROM server_entry WHERE owner = ? AND position >= ? ORDER BY position LIMIT ?"

internal const val SERVER_LINK_SQL = "UPDATE server_entry SET kind = 1, trackId = ?, relinkKey = ? WHERE owner = ? AND position = ?"

internal const val SERVER_WAIT_SQL = "UPDATE server_entry SET kind = 2, groupId = ? WHERE owner = ? AND position = ?"

internal const val SERVER_GROUP_SQL = "SELECT id FROM server_match WHERE owner = ? AND title = ? AND artist = ? AND lengthMs = ?"

internal const val SERVER_NEW_GROUP_SQL =
    "INSERT INTO server_match (owner, title, artist, lengthMs, trackId, relinkKey) VALUES (?, ?, ?, ?, NULL, '')"

internal const val SERVER_LOOKUP_SQL = "INSERT OR IGNORE INTO server_lookup (owner, lookupKey, groupId) VALUES (?, ?, ?)"

internal const val SERVER_KEY_SQL = "INSERT OR IGNORE INTO server_key (owner, `key`) VALUES (?, ?)"

// The pool: every library song filed under a key the read looks up, in key
// order, then by id, from just past (`lastKey`, `lastId`).
internal const val SERVER_POOL_SQL = """
    SELECT k.`key`, t.id, t.title, t.artist, t.durationMs, t.relinkKey
    FROM server_key k CROSS JOIN track t
    WHERE k.owner = ? AND k.`key` >= ? AND t.searchKey = k.`key`
      AND t.id > CASE WHEN k.`key` = ? THEN ? ELSE '' END
    ORDER BY k.`key`, t.id LIMIT ?
"""

// The open match groups a lookup key leads to, by group, from just past `after`.
internal const val SERVER_OPEN_GROUPS_SQL = """
    SELECT g.id, g.title, g.artist, g.lengthMs
    FROM server_lookup l CROSS JOIN server_match g ON g.id = l.groupId
    WHERE l.owner = ? AND l.lookupKey = ? AND l.groupId > ? AND g.trackId IS NULL
    ORDER BY l.groupId LIMIT ?
"""

internal const val SERVER_PLACE_SQL = "UPDATE server_match SET trackId = ?, relinkKey = ? WHERE id = ? AND trackId IS NULL"

internal const val SERVER_UNLOOK_SQL = "DELETE FROM server_lookup WHERE owner = ? AND lookupKey = ? AND groupId = ?"

// Each waiting entry from `from` up to `to` takes its group's song, or
// becomes a find.
internal const val SERVER_SETTLE_SQL = """
    UPDATE server_entry SET
        trackId = COALESCE((SELECT g.trackId FROM server_match g WHERE g.id = server_entry.groupId), 'find:' || songId),
        relinkKey = COALESCE((SELECT g.relinkKey FROM server_match g WHERE g.id = server_entry.groupId AND g.trackId IS NOT NULL), ''),
        kind = CASE WHEN (SELECT g.trackId FROM server_match g WHERE g.id = server_entry.groupId) IS NULL THEN 3 ELSE 1 END
    WHERE owner = ? AND position >= ? AND position < ? AND kind = 2
"""

internal const val SERVER_FINDS_BY_SONG_SQL = """
    SELECT songId, position FROM server_entry
    WHERE owner = ? AND kind = 3 AND songId >= ? AND CASE WHEN songId = ? THEN position > ? ELSE 1 END
    ORDER BY songId, position LIMIT ?
"""

internal const val SERVER_FIRST_FINDS_SQL =
    "SELECT position, songId, title, artist, lengthMs, album, albumId, artistId, coverId, mimeType, bitrate, explicit " +
        "FROM server_entry WHERE owner = ? AND position > ? AND firstFind = 1 ORDER BY position LIMIT ?"

// Whether a read carries Octo's marks: any entry marked as outside the library.
internal const val SERVER_MARKED_SQL = "SELECT EXISTS(SELECT 1 FROM server_entry WHERE owner = ? AND external = 1)"

// The finds of a read the server did not mark, each once, in order, up to a limit.
internal const val SERVER_OWNED_SQL =
    "SELECT songId, title, artist, lengthMs, album, albumId, artistId, coverId " +
        "FROM server_entry WHERE owner = ? AND firstFind = 1 AND external = 0 ORDER BY position LIMIT ?"

// A find the library has downloaded since shows as the song it became.
internal const val SERVER_ADOPT_SQL = "UPDATE server_entry SET trackId = ?, kind = 1 WHERE owner = ? AND trackId = ?"

internal const val SERVER_SUGGESTED_SQL = "SELECT songId, trackId FROM server_entry WHERE owner = ? AND songId = ? AND trackId IS NOT NULL LIMIT 1"

internal const val SERVER_HEAD_PUT_SQL =
    "INSERT OR REPLACE INTO server_head (owner, kind, listId, title, artist, artistId, year, songCount, cover, cut, complete, readSeq, " +
        "swapCount, currentAt, positionMs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

internal const val SERVER_HEAD_SQL =
    "SELECT owner, kind, listId, title, artist, artistId, year, songCount, cover, cut, complete, readSeq, swapCount, currentAt, positionMs " +
        "FROM server_head WHERE owner = ?"

// The newest whole read of one list, by any owner of this launch, on the
// heads' list index: an older launch's heads wait for the sweep, unread.
internal const val SERVER_NEWEST_SQL =
    "SELECT owner FROM server_head WHERE kind = ? AND listId = ? AND complete = 1 " +
        "AND +owner >= $LAUNCH_OWNERS_FROM AND +owner < $LAUNCH_OWNERS_UNTIL ORDER BY readSeq DESC LIMIT 1"

internal const val SERVER_COMPLETE_SQL = "UPDATE server_head SET complete = 1, swapCount = ?, currentAt = ?, positionMs = ? WHERE owner = ?"

// The rows a copy of a whole read takes, a page at a time.
internal const val SERVER_COPY_SQL =
    "INSERT INTO server_entry (owner, position, songId, external, title, artist, lengthMs, album, albumId, artistId, coverId, " +
        "mimeType, bitrate, explicit, trackId, kind, groupId, firstFind, held, relinkKey) " +
        "SELECT ?, position, songId, external, title, artist, lengthMs, album, albumId, artistId, coverId, mimeType, bitrate, " +
        "explicit, trackId, kind, NULL, firstFind, 0, relinkKey FROM server_entry WHERE owner = ? AND position > ? ORDER BY position LIMIT ?"

internal const val SERVER_LAST_POSITION_SQL = "SELECT MAX(position) FROM server_entry WHERE owner = ?"

// The first entry with one server song, for a play queue named by id.
internal const val SERVER_FIRST_OF_SONG_SQL = "SELECT MIN(position) FROM server_entry WHERE owner = ? AND songId = ?"

internal const val SERVER_TRACK_AT_SQL = "SELECT trackId FROM server_entry WHERE owner = ? AND position = ?"

// A row a list shows: its song resolved, still in the library or kept as
// a find, and the first row of the owner with that song. `kind = 1` keeps
// library songs alone.
internal fun serverShown(alias: String = "e"): List<String> = listOf(
    "$alias.owner = ?",
    "$alias.trackId IS NOT NULL",
    "(t.id IS NOT NULL OR o.id IS NOT NULL)",
    "NOT EXISTS (SELECT 1 FROM server_entry d WHERE d.owner = $alias.owner AND d.trackId = $alias.trackId AND d.position < $alias.position)",
)

internal const val SERVER_ROWS_FROM =
    "server_entry e LEFT JOIN track t ON t.id = e.trackId LEFT JOIN online_song o ON o.id = e.trackId"

// The filter that finds one song's row of those shown on the song index:
// `kind = 1` kept off the kind index, which would walk every library row.
internal fun serverFoundWhere(libraryOnly: Boolean): String =
    (serverShown() + if (libraryOnly) listOf("+e.kind = 1") else emptyList()).joinToString(" AND ")

// The same rows walked in place order on the entries' own key, for pages,
// places and counts: an older SQLite would read them on the song index,
// which holds what they ask of an entry, and sort them all.
internal const val SERVER_ROWS_WALK_FROM =
    "server_entry e INDEXED BY sqlite_autoindex_server_entry_1 LEFT JOIN track t ON t.id = e.trackId LEFT JOIN online_song o ON o.id = e.trackId"

// The facts a list's heading shows, over the rows it shows: how many, how
// long all of them play, how many have no known length, and how many are
// finds.
internal fun serverFactsSql(libraryOnly: Boolean): String =
    "SELECT COUNT(*), COALESCE(SUM(COALESCE(t.durationMs, o.durationMs, 0)), 0), " +
        "COALESCE(SUM(CASE WHEN COALESCE(t.durationMs, o.durationMs, 0) <= 0 THEN 1 ELSE 0 END), 0), " +
        "COALESCE(SUM(CASE WHEN t.id IS NULL THEN 1 ELSE 0 END), 0) FROM $SERVER_ROWS_FROM WHERE " +
        (serverShown() + if (libraryOnly) listOf("e.kind = 1") else emptyList()).joinToString(" AND ")
