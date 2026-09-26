package app.winters.octo.sort

import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.offline.DownloadRow
import app.winters.octo.offline.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortListsTest {
    private fun playlist(id: String, name: String, songs: Int, created: Long, updated: Long) =
        PlaylistSummary(id, name, songCount = songs, durationMs = 0, covers = emptyList(), createdAt = created, updatedAt = updated)

    private val playlists = listOf(
        playlist("1", "Road trip", songs = 12, created = 10, updated = 50),
        playlist("2", "The Gym", songs = 30, created = 30, updated = 20),
        playlist("3", "above all", songs = 12, created = 20, updated = 40),
    )

    private fun ids(list: List<PlaylistSummary>) = list.map { it.id }

    @Test
    fun playlistsByEachOrder() {
        assertEquals(listOf("1", "3", "2"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.RecentlyChanged, descending = true))))
        assertEquals(listOf("2", "3", "1"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.RecentlyCreated, descending = true))))
        // "The Gym" files under G; case does not matter.
        assertEquals(listOf("3", "2", "1"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.Name, descending = false))))
        assertEquals(listOf("1", "2", "3"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.Name, descending = true))))
        // Equal counts go by name, whichever way the count runs.
        assertEquals(listOf("2", "3", "1"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.SongCount, descending = true))))
        assertEquals(listOf("3", "1", "2"), ids(sortPlaylists(playlists, SortOrder(PlaylistSort.SongCount, descending = false))))
    }

    private fun download(id: String, title: String, state: DownloadStatus, size: Long, added: Long) = DownloadRow(
        trackId = id, sourceId = "server:1", serverId = id, title = title, artist = "", artwork = null, sizeBytes = size,
        state = state, progress = 0f, reason = "", addedAt = added,
    )

    @Test
    fun downloadsOnTheirWayStayOnTop() {
        val rows = listOf(
            download("going", "Zed", DownloadStatus.Downloading, size = 0, added = 1),
            download("waiting", "Alpha", DownloadStatus.Queued, size = 0, added = 2),
            download("big", "Middle", DownloadStatus.Done, size = 900, added = 3),
            download("new", "beta", DownloadStatus.Done, size = 100, added = 9),
            download("old", "Omega", DownloadStatus.Done, size = 500, added = 1),
        )
        val active = listOf("going", "waiting")
        assertEquals(active + listOf("new", "big", "old"), sortDownloads(rows, SortOrder(DownloadSort.RecentlyDownloaded, true)).map { it.trackId })
        assertEquals(active + listOf("new", "big", "old"), sortDownloads(rows, SortOrder(DownloadSort.Title, false)).map { it.trackId })
        assertEquals(active + listOf("big", "old", "new"), sortDownloads(rows, SortOrder(DownloadSort.Size, true)).map { it.trackId })
        assertEquals(active + listOf("new", "old", "big"), sortDownloads(rows, SortOrder(DownloadSort.Size, false)).map { it.trackId })
    }

    private fun song(id: String, title: String, artist: String, album: String, order: Int, year: Int?, added: Long, length: Long) =
        TrackEntity(
            id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(),
            sortKey = app.winters.octo.catalog.sortKey(title), artist = artist, artistId = artist, album = album, albumId = album,
            trackNo = null, discNo = null, year = year, durationMs = length, addedAt = added, mimeType = null, sizeBytes = null,
            artwork = null, uri = null, albumOrder = order,
        )

    // As the folder lists them.
    private val folder = listOf(
        song("1", "Intro", "The Band", "Second", order = 0, year = 2001, added = 5, length = 60),
        song("2", "Anthem", "The Band", "Second", order = 1, year = 2001, added = 5, length = 300),
        song("3", "Coda", "Another", "First", order = 0, year = null, added = 9, length = 200),
    )

    private fun folderIds(by: SongSort, descending: Boolean) = sortFolderSongs(folder, SortOrder(by, descending)).map { it.id }

    @Test
    fun folderSongsKeepTheFolderOrderOrSortInMemory() {
        assertEquals(listOf("1", "2", "3"), folderIds(SongSort.FolderOrder, false))
        assertEquals(listOf("3", "2", "1"), folderIds(SongSort.FolderOrder, true))
        assertEquals(listOf("2", "3", "1"), folderIds(SongSort.Title, false))
        // "The Band" files under B, after "Another"; an album keeps its order.
        assertEquals(listOf("3", "1", "2"), folderIds(SongSort.Artist, false))
        assertEquals(listOf("1", "2", "3"), folderIds(SongSort.Artist, true))
        assertEquals(listOf("3", "1", "2"), folderIds(SongSort.Album, false))
        assertEquals(listOf("3", "1", "2"), folderIds(SongSort.RecentlyAdded, true))
        // A song with no year goes last either way.
        assertEquals(listOf("1", "2", "3"), folderIds(SongSort.Year, true))
        assertEquals(listOf("1", "2", "3"), folderIds(SongSort.Year, false))
        assertEquals(listOf("2", "3", "1"), folderIds(SongSort.Length, true))
    }
}
