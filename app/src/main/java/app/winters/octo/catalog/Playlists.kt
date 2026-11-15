package app.winters.octo.catalog

import androidx.room.Embedded

// A playlist as its list shows it: name, how many songs, how long, and the
// covers for its picture.
data class PlaylistSummary(
    val id: String,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    val covers: List<String>,
    // Kept in step with a copy on the server.
    val onServer: Boolean = false,
    // When it was made and last changed, for ordering the list.
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    // Which of Octo's notice lists it is, as the server marks it.
    val octoNotice: String? = null,
    // A family playlist (its order is the server's).
    val family: Boolean = false,
    val familyMayAdd: Boolean = false,
    val familyMayRemove: Boolean = false,
    val familyMayRename: Boolean = false,
    val familyMayDelete: Boolean = false,
    // Its id on the server, once it is there, which its folder placement goes by.
    val serverId: String? = null,
    // One of Octo's library upkeep lists, and an imported playlist's copy;
    // the upkeep list's kind as the server names it.
    val upkeep: Boolean = false,
    val octoImport: Boolean = false,
    val upkeepKind: String? = null,
)

// One song of a playlist, as much of it as the list of playlists needs.
data class PlaylistEntry(
    val playlistId: String,
    val albumId: String,
    val durationMs: Long,
    val artwork: String?,
)

// A song on a playlist page, with the row it came from so it can be moved
// or taken out.
data class PlaylistTrack(
    val itemId: Long,
    @Embedded val track: TrackEntity,
)

// Up to four covers for a playlist's picture, one per album in the order
// the songs come. Songs with no artwork are skipped.
fun mosaicCovers(albumArt: List<Pair<String, String?>>): List<String> =
    albumArt
        .mapNotNull { (album, art) -> art?.let { album to it } }
        .distinctBy { it.first }
        .take(4)
        .map { it.second }

// The same four covers, picked as the songs come one at a time, so a walk
// of a long list stops once it has them.
class MosaicPicker {
    private val byAlbum = LinkedHashMap<String, String>()

    val full: Boolean get() = byAlbum.size >= 4

    val covers: List<String> get() = byAlbum.values.toList()

    fun offer(albumId: String, artwork: String?) {
        if (artwork != null && !full && albumId !in byAlbum) byAlbum[albumId] = artwork
    }
}

// Whether a playlist shows while this server is in use (null for none):
// one only on the phone always, one kept with a server while it is in use.
fun PlaylistEntity.shownWith(serverInUse: String?): Boolean = sourceId == null || sourceId == serverInUse

fun PlaylistRow.shownWith(serverInUse: String?): Boolean = sourceId == null || sourceId == serverInUse

// A playlist as its list reads it: what the summary shows, and the item
// set its songs are in.
data class PlaylistRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceId: String?,
    val octoNotice: String?,
    val family: Boolean,
    val familyMayAdd: Boolean,
    val familyMayRemove: Boolean,
    val familyMayRename: Boolean,
    val familyMayDelete: Boolean,
    val itemSet: Long,
    val serverId: String? = null,
    val upkeep: Boolean = false,
    val octoImport: Boolean = false,
    val upkeepKind: String? = null,
)

// A playlist's song count and length, with the change and the item set
// they were counted at.
data class PlaylistTotals(val updatedAt: Long, val itemSet: Long, val songCount: Int, val durationMs: Long)

// One row of a playlist in play order, with the album and cover of the
// library song it names, and of the song found online it names.
data class PlaylistCoverRow(
    val ord: Long,
    val itemKey: Long,
    val inLibrary: Boolean,
    val albumId: String?,
    val artwork: String?,
    val found: Boolean,
    val findAlbumId: String,
    val findCover: String?,
)

// A liked song the catalog still has, with its album and cover.
data class LikedCover(val likedAt: Long, val trackId: String, val albumId: String, val artwork: String?)

internal const val PLAYLIST_ROW_COLUMNS = """
    SELECT id, name, createdAt, updatedAt, sourceId, octoNotice, family, familyMayAdd, familyMayRemove, familyMayRename,
        familyMayDelete, itemSet, serverId, upkeep, octoImport, upkeepKind
    FROM playlist
"""

internal const val PLAYLIST_ROWS_SQL = "$PLAYLIST_ROW_COLUMNS WHERE itemSet >= 0 ORDER BY updatedAt DESC, id"

// Library songs and songs found online, each counted over the playlist's
// current set in one statement, so both read one committed set.
internal const val PLAYLIST_TOTALS_SQL = """
    SELECT p.updatedAt AS updatedAt, p.itemSet AS itemSet, l.n + f.n AS songCount, l.ms + f.ms AS durationMs
    FROM playlist p,
        (SELECT COUNT(*) AS n, IFNULL(SUM(t.durationMs), 0) AS ms
         FROM playlist q CROSS JOIN playlist_item i ON i.playlistId = q.id AND i.itemSet = q.itemSet
             CROSS JOIN track t ON t.id = i.trackId
         WHERE q.id = :id) l,
        (SELECT COUNT(*) AS n, IFNULL(SUM(o.durationMs), 0) AS ms
         FROM playlist q CROSS JOIN playlist_item i ON i.playlistId = q.id AND i.itemSet = q.itemSet
             CROSS JOIN online_song o ON o.id = i.trackId
         WHERE q.id = :id) f
    WHERE p.id = :id AND p.itemSet >= 0
"""

// A find's cover is written the way onlineArtwork writes one.
internal const val PLAYLIST_COVER_PAGE_SQL = """
    SELECT i.ord AS ord, i.itemKey AS itemKey, t.id IS NOT NULL AS inLibrary, t.albumId AS albumId, t.artwork AS artwork,
        o.id IS NOT NULL AS found, COALESCE(o.albumId, '') AS findAlbumId,
        CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END AS findCover
    FROM playlist_item i LEFT JOIN track t ON t.id = i.trackId LEFT JOIN online_song o ON o.id = i.trackId
    WHERE i.playlistId = :id AND i.itemSet = :itemSet
      AND i.ord >= :afterOrd AND CASE WHEN i.ord = :afterOrd THEN i.itemKey > :afterKey ELSE 1 END
    ORDER BY i.ord, i.itemKey LIMIT :limit
"""

// Ids from "find:" up to, not including, "find;", the next character up.
internal const val PLAYLIST_HOLDS_FIND_SQL = """
    SELECT EXISTS(SELECT 1 FROM playlist_item
        WHERE playlistId = :id AND itemSet = :itemSet AND trackId >= 'find:' AND trackId < 'find;')
"""

internal const val LIKED_COVER_PAGE_SQL = """
    SELECT l.likedAt AS likedAt, l.trackId AS trackId, t.albumId AS albumId, t.artwork AS artwork
    FROM liked_track l CROSS JOIN track t ON t.id = l.trackId
    WHERE l.likedAt <= :beforeAt AND CASE WHEN l.likedAt = :beforeAt THEN l.trackId < :beforeId ELSE 1 END
    ORDER BY l.likedAt DESC, l.trackId DESC LIMIT :limit
"""

// A playlist's summary from its row, its totals and its covers.
fun summaryOf(row: PlaylistRow, songCount: Int, durationMs: Long, covers: List<String>): PlaylistSummary = PlaylistSummary(
    id = row.id,
    name = row.name,
    songCount = songCount,
    durationMs = durationMs,
    covers = covers,
    onServer = row.sourceId != null,
    createdAt = row.createdAt,
    updatedAt = row.updatedAt,
    octoNotice = row.octoNotice,
    family = row.family,
    familyMayAdd = row.familyMayAdd,
    familyMayRemove = row.familyMayRemove,
    familyMayRename = row.familyMayRename,
    familyMayDelete = row.familyMayDelete,
    serverId = row.serverId,
    upkeep = row.upkeep,
    octoImport = row.octoImport,
    upkeepKind = row.upkeepKind,
)

// How far apart two neighboring playlist rows' ord values are.
const val ORD_STEP = 1L shl 20

// The ord of a row at a place in a set written evenly spaced.
fun ordAt(place: Int): Long = place * ORD_STEP
