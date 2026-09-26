package app.winters.octo.sort

import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.sortKey
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus

// Orders for lists that are short, or not in the database at all: the
// playlists, the downloads, and the songs of one folder. These sort in
// memory.

// Sorts by one key the chosen way, with items missing it last either way,
// then by `ties`, which always run the same way. Each key is worked out once.
private fun <T, K : Comparable<K>> sortedByKey(
    items: List<T>,
    descending: Boolean,
    key: (T) -> K?,
    ties: Comparator<T>,
): List<T> {
    val keys = items.map(key)
    return items.indices.sortedWith { a, b ->
        val ka = keys[a]
        val kb = keys[b]
        val byKey = when {
            ka == null && kb == null -> 0
            ka == null -> 1
            kb == null -> -1
            descending -> kb.compareTo(ka)
            else -> ka.compareTo(kb)
        }
        if (byKey != 0) byKey else ties.compare(items[a], items[b])
    }.map { items[it] }
}

fun sortPlaylists(playlists: List<PlaylistSummary>, order: SortOrder): List<PlaylistSummary> {
    val byName = compareBy<PlaylistSummary>({ sortKey(it.name) }, { it.id })
    val descending = order.descending
    return when (order.by) {
        PlaylistSort.RecentlyChanged -> sortedByKey(playlists, descending, { it.updatedAt }, byName)
        PlaylistSort.RecentlyCreated -> sortedByKey(playlists, descending, { it.createdAt }, byName)
        PlaylistSort.SongCount -> sortedByKey(playlists, descending, { it.songCount }, byName)
        else -> sortedByKey(playlists, descending, { sortKey(it.name) }, compareBy { it.id })
    }
}

// Downloads on their way stay at the top as they were (downloading, then
// waiting, then failed); the finished ones below go in the chosen order.
fun sortDownloads(rows: List<DownloadRow>, order: SortOrder): List<DownloadRow> {
    val (done, active) = rows.partition { it.state == DownloadStatus.Done }
    val byTitle = compareBy<DownloadRow>({ sortKey(it.title) }, { it.trackId })
    val descending = order.descending
    val sorted = when (order.by) {
        DownloadSort.Title -> sortedByKey(done, descending, { sortKey(it.title) }, compareBy { it.trackId })
        DownloadSort.Size -> sortedByKey(done, descending, { it.sizeBytes }, byTitle)
        else -> sortedByKey(done, descending, { it.addedAt }, byTitle)
    }
    return active + sorted
}

// A folder's songs. By artist here means the song's own artist, since a
// folder's songs do not carry their album artist's name for sorting.
fun sortFolderSongs(songs: List<TrackEntity>, order: SortOrder): List<TrackEntity> {
    val descending = order.descending
    val inAlbum = compareBy<TrackEntity>({ it.albumId }, { it.albumOrder }, { it.sortKey }, { it.id })
    return when (order.by) {
        SongSort.Title -> sortedByKey(songs, descending, { it.sortKey }, compareBy({ sortKey(it.artist) }, { it.id }))
        SongSort.Artist -> sortedByKey(songs, descending, { sortKey(it.artist) }, compareBy<TrackEntity> { sortKey(it.album) }.then(inAlbum))
        SongSort.Album -> sortedByKey(songs, descending, { sortKey(it.album) }, compareBy<TrackEntity> { sortKey(it.artist) }.then(inAlbum))
        SongSort.RecentlyAdded -> sortedByKey(songs, descending, { it.addedAt }, inAlbum)
        SongSort.Year -> sortedByKey(songs, descending, { it.year }, compareBy<TrackEntity> { sortKey(it.album) }.then(inAlbum))
        SongSort.Length -> sortedByKey(songs, descending, { it.durationMs }, compareBy({ it.sortKey }, { it.id }))
        // As the folder lists them, or that backwards.
        else -> if (descending) songs.asReversed().toList() else songs
    }
}
