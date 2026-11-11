package app.winters.octo.offline

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.ordAt
import app.winters.octo.playback.compareQuality
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import app.winters.octo.offline.today.planDownloads as todayPlanDownloads
import app.winters.octo.offline.today.wantedByRules as todayWantedByRules

// The server in use in these tests, and another kept server.
internal const val IN_USE = "server:h"
internal const val ELSEWHERE = "server:o"

// The download store's walks over a test library, with the server in use
// and the page size given. Deleted downloads, pins let go of and held
// back downloads are noted.
internal class RulesRig(
    val db: CatalogDatabase,
    val merge: CatalogMerge = db.testMerge(IN_USE),
    page: Int = RULES_PAGE,
    server: String? = IN_USE,
) {
    val lock = Mutex()
    val book = DownloadBook(db.downloadDao())
    val dropped = CopyOnWriteArrayList<DownloadEntity>()
    val released = CopyOnWriteArrayList<Pair<String, String>>()
    val heldBack = AtomicInteger()
    val sets = PlaylistSets(db)
    val rules = OfflineRules(
        db, merge, lock, book, WorkOwners(), { server },
        dropped = { dropped += it },
        heldBack = { heldBack.incrementAndGet() },
        sets = sets,
        releasePin = { owner, id ->
            released += owner to id
            sets.releasePin(owner, id)
        },
        page = page,
    )
}

// A library song, only on a server unless `onPhone`.
internal fun librarySong(id: String, album: String = "al", order: Int = 0, onPhone: Boolean = false) = TrackEntity(
    id = id, sourceId = IN_USE, nativeId = id, title = id, searchKey = id, sortKey = id, artist = "", artistId = "",
    album = album, albumId = album, trackNo = null, discNo = null, year = null, durationMs = 1_000, addedAt = 0,
    mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = order, relinkKey = "key:$id", onPhone = onPhone,
)

// A server's copy of a library song, as the last sync saw it.
internal fun serverCopy(trackId: String, native: String, source: String = IN_USE, bitrate: Int? = 192) = SourceTrackEntity(
    id = "$source:$native", sourceId = source, nativeId = native, title = "Song $native", searchKey = native, sortKey = native,
    artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null, durationMs = 1_000,
    addedAt = 0, mimeType = "audio/flac", sizeBytes = 10, artwork = null, uri = null, albumOrder = 0, relinkKey = "key:$trackId",
    genre = "", bitrate = bitrate, mergedId = trackId,
)

// Writes library songs and server copies straight into their tables.
internal suspend fun CatalogDatabase.putLibrary(songs: List<TrackEntity>, copies: List<SourceTrackEntity>) = withTransaction {
    songs.chunked(500).forEach { dao().insertTracks(it) }
    copies.chunked(500).forEach { sourceDao().putTracks(it) }
}

// Library songs only on the server in use, one copy each there.
internal suspend fun CatalogDatabase.putServerSongs(ids: List<String>, album: String = "al", order: (Int) -> Int = { 0 }) =
    putLibrary(ids.mapIndexed { i, id -> librarySong(id, album, order(i)) }, ids.mapIndexed { i, id -> serverCopy(id, "n$i") })

internal suspend fun CatalogDatabase.likeAll(ids: List<String>) = withTransaction {
    ids.mapIndexed { i, id -> LikedTrackEntity(id, "key:$id", i.toLong()) }.chunked(500).forEach { userDao().likeAll(it) }
}

// A download kept for `reasons`.
internal fun keptRow(
    trackId: String,
    serverId: String,
    reasons: Set<String>,
    sourceId: String = IN_USE,
    state: DownloadStatus = DownloadStatus.Queued,
    path: String = "",
) = DownloadEntity(
    trackId = trackId, sourceId = sourceId, serverId = serverId, path = path, sizeBytes = 0, format = null, state = state,
    progress = 0f, addedAt = 1, reason = Reasons.join(reasons), title = trackId, artist = "",
)

internal suspend fun CatalogDatabase.putKept(rows: List<DownloadEntity>) = withTransaction {
    rows.chunked(500).forEach { downloadDao().upsert(it) }
}

// A playlist whose item set `itemSet` holds these songs in this order,
// each row with a key of its own from the playlists' key counter.
internal suspend fun CatalogDatabase.putPlaylist(id: String, trackIds: List<String>, itemSet: Long = 0) {
    userDao().insertPlaylist(PlaylistEntity(id, "List $id", createdAt = 1, updatedAt = 1, itemSet = itemSet))
    putSet(id, itemSet, trackIds)
}

internal suspend fun CatalogDatabase.putSet(id: String, itemSet: Long, trackIds: List<String>) = withTransaction {
    if (trackIds.isEmpty()) return@withTransaction
    val user = userDao()
    user.raiseNextItemKey(trackIds.size)
    val first = user.nextItemKey() - trackIds.size
    trackIds.mapIndexed { i, t -> PlaylistItemEntity(playlistId = id, itemSet = itemSet, trackId = t, relinkKey = "key:$t", ord = ordAt(i), itemKey = first + i) }
        .chunked(500).forEach { user.insertPlaylistItems(it) }
}

// What a download table holds, row by row, for comparing two: every
// column but when each was asked for.
internal fun CatalogDatabase.keptTable(): List<String> =
    query("SELECT sourceId, serverId, trackId, reason, state, path FROM download ORDER BY sourceId, serverId", null).use { c ->
        buildList { while (c.moveToNext()) add((0 until 6).joinToString("|") { c.getString(it) }) }
    }

// Each song the server in use's downloads hold, with their reasons.
internal suspend fun CatalogDatabase.heldNow(source: String = IN_USE): Map<String, Set<String>> =
    downloadDao().all().filter { it.sourceId == source }.groupBy { it.trackId }.mapValues { (_, rows) -> rows.flatMapTo(HashSet()) { it.reasons } }

// Today's rules over the library as it is now: whole reads, and the frozen
// copies of today's want and plan. The kept playlists' songs are their
// shown item sets'.
internal suspend fun CatalogDatabase.todayPlan(keepLiked: Boolean, playlists: Set<String>, source: String? = IN_USE): DownloadPlan {
    val held = if (source == null) emptyMap() else heldNow(source)
    val liked = if (keepLiked) userDao().likedIds().first() else emptyList()
    val members = playlists.flatMap { id -> userDao().playlistItems(id).map { PlaylistMember(id, it.trackId) } }
    val wanted = todayWantedByRules(keepLiked, liked, members)
    val fresh = wanted.keys.filter { it !in held }
    val downloadable = fresh.chunked(900).flatMap { downloadDao().downloadable(it) }.toSet()
    return todayPlanDownloads(held, wanted, downloadable)
}

// Today's whole walk of the rules, as base ran it: the relink, the plan,
// and its writes, a new download from the best copy on the server.
internal suspend fun CatalogDatabase.todayRules(keepLiked: Boolean, playlists: Set<String>, source: String? = IN_USE) {
    val dao = downloadDao()
    dao.relink()
    val plan = todayPlan(keepLiked, playlists, source)
    plan.remove.forEach { id -> dao.forTrack(id).forEach { dao.delete(it.sourceId, it.serverId) } }
    plan.change.forEach { (id, reasons) -> dao.forTrack(id).forEach { dao.setReason(it.sourceId, it.serverId, Reasons.join(reasons)) } }
    val downloadable = plan.add.keys.chunked(900).flatMap { dao.downloadable(it) }.toSet()
    val copies = downloadable.toList().chunked(900).flatMap { dao.serverCopies(it) }.groupBy { it.mergedId }
    val rows = plan.add.filterKeys { it in downloadable }.mapNotNull { (trackId, reasons) ->
        val copy = copies[trackId]?.reduce { best, next -> if (compareQuality(next, best) > 0) next else best } ?: return@mapNotNull null
        keptRow(trackId, copy.nativeId, reasons, copy.sourceId).copy(title = copy.title, artist = copy.artist)
    }
    rows.chunked(500).forEach { dao.upsert(it) }
}

// Notes the walks' openings and pages, in order.
internal open class NotedHooks : RulesHooks() {
    val events = CopyOnWriteArrayList<String>()

    override suspend fun opened(attempt: Int) {
        events += "opened $attempt"
    }

    override suspend fun applied(page: Int, written: Boolean) {
        events += if (written) "wrote $page" else "rolled back $page"
    }
}
