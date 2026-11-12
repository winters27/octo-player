package app.winters.octo.listening

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.ServerPlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.home.QUIET_DAYS
import app.winters.octo.home.Rediscovery
import kotlin.math.abs

// How many songs or albums each shelf of what was played holds.
internal const val PLAY_SHELF = 20

private const val DAY_MS = 24L * 60 * 60 * 1000

// Home's and the car's shelves of what was played: the most played songs,
// the albums played last, and the albums worth going back to (not played
// in six months, never finished, never played).
data class PlayShelves(
    val mostPlayed: List<TrackEntity> = emptyList(),
    val recentlyPlayed: List<AlbumEntity> = emptyList(),
    val rediscovery: Rediscovery<AlbumEntity> = Rediscovery(),
)

// The shelves, from the stored totals: the most played songs in their
// index's order, then one walk of the albums' listening in album order
// into the shelves that rank albums, and one walk of the albums with no
// played song for Never played. Holds a page and the shelves.
//
// The orders are the shared shelves': Recently played by the latest play,
// then album id; Not played lately by plays, then the oldest last play,
// then id; Never finished by the latest play (an album with none last),
// then id, leaving out the albums Not played lately shows; Never played by
// when the album came in (unknown last), then id.
internal suspend fun shelvesOf(dao: PlayTotalsDao, now: Long, hooks: PlayWalkHooks, page: Int = PLAY_PAGE): PlayShelves {
    val most = dao.mostPlayed(PLAY_SHELF).map { it.track }
    val quietBefore = now - QUIET_DAYS * DAY_MS
    val recent = TopOf(PLAY_SHELF, compareByDescending<AlbumPlays> { it.recent }.thenBy { it.albumId })
    val quiet = TopOf(PLAY_SHELF, compareByDescending<AlbumPlays> { it.plays }.thenBy { lastOf(it) }.thenBy { it.albumId })
    // Twice a shelf, so a whole one is left once Not played lately's are out.
    val unfinished = TopOf(2 * PLAY_SHELF, compareByDescending<AlbumPlays> { lastOf(it) ?: Long.MIN_VALUE }.thenBy { it.albumId })
    var after = ""
    while (true) {
        val rows = dao.albumPlaysPage(after, page)
        hooks.page(PlayStream.Albums)
        for (album in rows) {
            if (album.recent != null) recent.offer(album)
            val last = lastOf(album)
            if (last != null && last < quietBefore) quiet.offer(album)
            if (album.playedSongs > 0 && album.playedSongs < album.songs) unfinished.offer(album)
        }
        if (rows.size < page) break
        after = rows.last().albumId
    }
    val never = TopOf(PLAY_SHELF, compareByDescending<UnplayedAlbum> { it.addedAt.takeIf { at -> at > 0 } ?: Long.MIN_VALUE }.thenBy { it.id })
    after = ""
    while (true) {
        val rows = dao.unplayedAlbumPage(after, page)
        hooks.page(PlayStream.Albums)
        rows.forEach(never::offer)
        if (rows.size < page) break
        after = rows.last().id
    }
    val quietIds = quiet.sorted().map { it.albumId }
    val shown = quietIds.toHashSet()
    val unfinishedIds = unfinished.sorted().map { it.albumId }.filterNot { it in shown }.take(PLAY_SHELF)
    val recentIds = recent.sorted().map { it.albumId }
    val neverIds = never.sorted().map { it.id }
    val albums = dao.albumsAmong((recentIds + quietIds + unfinishedIds + neverIds).distinct()).associateBy { it.id }
    fun albumsOf(ids: List<String>) = ids.mapNotNull(albums::get)
    return PlayShelves(
        mostPlayed = most,
        recentlyPlayed = albumsOf(recentIds),
        rediscovery = Rediscovery(albumsOf(quietIds), albumsOf(unfinishedIds), albumsOf(neverIds)),
    )
}

// An album's latest play of a played song, when it has a time.
private fun lastOf(album: AlbumPlays): Long? = album.lastPlayed?.takeIf { it > 0 }

// The latest plays, newest first, as History lists them: the phone's
// latest `limit` plays, and each server copy's last play, unless it is
// one of the phone's own (sent there) or older than the phone's plays
// reach. One walk of the server copies keeps the latest `limit` of those.
internal suspend fun recentHistoryOf(dao: PlayTotalsDao, limit: Int, hooks: PlayWalkHooks, page: Int = PLAY_PAGE): List<HistoryPlay> {
    val local = dao.recentPlays(limit)
    val mine = local.groupBy({ it.track.id }, { it.startedAt })
    val horizon = if (local.size >= limit) local.minOfOrNull { it.startedAt } ?: Long.MIN_VALUE else Long.MIN_VALUE
    val kept = TopOf(limit, compareByDescending<CopyPlays> { it.lastPlayedAt }.thenBy { it.trackId })
    // The times this song's copies already gave, so one time joins once.
    var song = ""
    val times = HashSet<Long>()
    val copies = copyStream(dao, hooks, account = null, size = page)
    while (true) {
        val copy = copies.peek() ?: break
        copies.take()
        if (copy.trackId != song) {
            song = copy.trackId
            times.clear()
        }
        val at = copy.lastPlayedAt ?: continue
        val window = copy.durationMs.coerceAtLeast(0) + OWN_PLAY_SLACK_MS
        val ours = mine[copy.trackId].orEmpty().any { abs(it - at) <= window }
        if (ours || at < horizon || !times.add(at)) continue
        kept.offer(copy)
    }
    val elsewhere = kept.sorted()
    val tracks = dao.tracksAmong(elsewhere.map { it.trackId }.distinct()).associateBy { it.id }
    val server = elsewhere.mapNotNull { copy -> tracks[copy.trackId]?.let { ServerPlayedTrack(it, copy.serverId, copy.plays, copy.lastPlayedAt) } }
    return recentHistory(local, server, limit)
}

// The most played songs since `since`, most first, then the one played
// last, then id: one walk of the phone's plays in the range and the server
// copies together, keeping the first `limit`.
internal suspend fun mostPlayedSince(
    dao: PlayTotalsDao,
    account: String?,
    since: Long,
    limit: Int,
    hooks: PlayWalkHooks,
    page: Int = PLAY_PAGE,
): List<PlayedTrack> {
    val top = TopOf(limit, compareByDescending<PlayCount> { it.plays }.thenByDescending { it.lastPlayedAt }.thenBy { it.trackId })
    walkPlays(phoneStream(dao, hooks, since, page), copyStream(dao, hooks, account, page), null) { song, _ ->
        playsSince(song, since)?.let { (plays, last) -> top.offer(PlayCount(song.trackId, plays, last)) }
    }
    val counted = top.sorted()
    val tracks = dao.tracksAmong(counted.map { it.trackId }).associateBy { it.id }
    return counted.mapNotNull { c -> tracks[c.trackId]?.let { PlayedTrack(it, c.plays, c.lastPlayedAt) } }
}
