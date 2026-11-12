package app.winters.octo.playback

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistEntry
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.ordAt
import app.winters.octo.catalog.shownWith
import app.winters.octo.catalog.today.summarize as todaySummarize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// A library song on its album, with or without a cover.
internal fun songRow(id: String, album: String, art: String?, ms: Long = 1_000) = TrackEntity(
    id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
    artist = "", artistId = "", album = album, albumId = album, trackNo = null, discNo = null, year = null,
    durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = art, uri = null, relinkKey = "key:$id",
)

// A song found online, with or without an album and a cover.
internal fun findRow(id: String, album: String?, cover: String?, ms: Long = 2_000) = OnlineSongEntity(
    id = id, sourceId = "server:h", nativeId = id.removePrefix("find:"), title = id, artist = "", album = "", albumId = album,
    artistId = null, durationMs = ms, coverId = cover, mimeType = null, bitrate = null, seenAt = 0,
)

// A playlist with its songs in the order given, written straight into its
// item set.
internal suspend fun CatalogDatabase.playlist(id: String, trackIds: List<String>, updatedAt: Long = 1, sourceId: String? = null, itemSet: Long = 0) {
    userDao().insertPlaylist(PlaylistEntity(id, "List $id", createdAt = updatedAt, updatedAt = updatedAt, sourceId = sourceId, itemSet = itemSet))
    putItems(id, itemSet, trackIds)
}

// Rows of one item set, each with a key of its own from the playlists'
// key counter, as the app's writers take them.
internal suspend fun CatalogDatabase.putItems(id: String, itemSet: Long, trackIds: List<String>) = withTransaction {
    if (trackIds.isEmpty()) return@withTransaction
    val user = userDao()
    user.raiseNextItemKey(trackIds.size)
    val first = user.nextItemKey() - trackIds.size
    trackIds.mapIndexed { i, t -> PlaylistItemEntity(playlistId = id, itemSet = itemSet, trackId = t, relinkKey = "key:$t", ord = ordAt(i), itemKey = first + i) }
        .chunked(500).forEach { user.insertPlaylistItems(it) }
}

// Counts the reads of each playlist's totals and covers.
internal class CountedReads : SummaryHooks() {
    val reads = ConcurrentHashMap<String, AtomicInteger>()

    fun of(id: String) = reads[id]?.get() ?: 0

    val total get() = reads.values.sumOf { it.get() }

    override suspend fun reading(playlistId: String) {
        reads.getOrPut(playlistId) { AtomicInteger() }.incrementAndGet()
    }
}

// The summaries over a test library, the server in use fixed.
internal fun CatalogDatabase.summaries(
    server: String? = null,
    page: Int = COVER_PAGE,
    owners: WorkOwners = WorkOwners(),
    sets: PlaylistSets = PlaylistSets(this),
) = PlaylistSummaries(this, owners, MutableStateFlow(server), releasePin = sets::releasePin, page = page)

// Today's summaries of the same library: every playlist and every entry
// read whole, as base read them, then summarized by today's code.
internal suspend fun CatalogDatabase.todaySummaries(server: String?): List<PlaylistSummary> {
    val playlists = userDao().playlists().first()
    val entries = ArrayList<PlaylistEntry>()
    query(
        """
        SELECT playlistId, albumId, durationMs, artwork FROM (
            SELECT i.playlistId, i.ord, i.itemKey, t.albumId, t.durationMs, t.artwork
            FROM playlist_item i JOIN track t ON t.id = i.trackId
            UNION ALL
            SELECT i.playlistId, i.ord, i.itemKey, COALESCE(o.albumId, ''), o.durationMs,
                CASE WHEN o.coverId IS NULL THEN NULL ELSE 'online:' || o.sourceId || '|' || o.coverId END
            FROM playlist_item i JOIN online_song o ON o.id = i.trackId
        )
        ORDER BY playlistId, ord, itemKey
        """,
        null,
    ).use { c ->
        while (c.moveToNext()) entries += PlaylistEntry(c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3))
    }
    return todaySummarize(playlists.filter { it.shownWith(server) }, entries)
}
