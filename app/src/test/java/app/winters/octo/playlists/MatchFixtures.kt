package app.winters.octo.playlists

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.device.relativeFile
import app.winters.octo.playback.radioTrack
import app.winters.octo.ui.paging.SqlOrder
import kotlin.random.Random

// Titles, artists and albums that meet each other in a matching: case and
// accents, versions, guests, blanks, a common title.
internal val MATCH_TITLES = listOf("Intro", "Song A", "Song A (Live)", "song a", "Nightcall", "Nightcall (feat. Lovefoxxx)", "Untitled", "Höme", "home", "Track 01", "Ünïcode 😀")
internal val MATCH_ARTISTS = listOf("Kavinsky", "kavinsky", "", "Drake feat. Rihanna", "Drake", "The Band", "Band")
internal val MATCH_ALBUMS = listOf("Outrun", "OutRun", "", "Live at X", "Greatest Hits")

// A library of `songs` songs drawn from the lists above, some sharing a
// relink key or a sort key, and a phone copy of every third (two of some),
// with its folder and file name.
internal fun CatalogDatabase.matchLibrary(songs: Int, seed: Int = 3): List<TrackEntity> {
    val r = Random(seed)
    val tracks = (0 until songs).map { i ->
        val title = MATCH_TITLES[r.nextInt(MATCH_TITLES.size)]
        val artist = MATCH_ARTISTS[r.nextInt(MATCH_ARTISTS.size)]
        radioTrack(
            id = "t%04d".format(songs - i),
            sortKey = "k%03d".format(i / 2),
            title = title,
            artist = artist,
            artistId = "ar${i % 5}",
            albumId = "al${i % 30}",
            durationMs = 180_000L + (i % 13) * 3_000L,
        ).copy(album = MATCH_ALBUMS[r.nextInt(MATCH_ALBUMS.size)], relinkKey = if (i % 5 == 0) "rk${i % 37}" else "")
    }
    val sql = openHelper.writableDatabase
    sql.beginTransaction()
    try {
        tracks.forEach { t ->
            sql.execSQL(
                "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, genre, " +
                    "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, '', ?, ?)",
                arrayOf<Any>(t.id, t.id, t.title, t.searchKey, t.sortKey, t.artist, t.artistId, t.album, t.albumId, t.durationMs, t.relinkKey, t.lookupKey, t.looseKey),
            )
        }
        var seq = 0L
        tracks.forEachIndexed { i, t ->
            if (i % 3 != 0) return@forEachIndexed
            val copies = if (i % 9 == 0) 2 else 1
            repeat(copies) { c ->
                val folder = if (c == 0) "Music/${t.artist.ifBlank { "Unknown" }}/${t.album}" else "Other/${t.album}"
                val fileName = "%02d %s.mp3".format(i % 7, t.title)
                sql.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                        "albumOrder, relinkKey, genre, mergedId, seq, folder, fileName) VALUES (?, 'device', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, '', '', ?, ?, ?, ?)",
                    arrayOf<Any>("device:c$i-$c", "c$i-$c", t.title, t.searchKey, t.sortKey, t.artist, t.artistId, t.album, t.albumId, t.durationMs, t.id, seq++, folder, fileName),
                )
            }
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
    }
    return tracks
}

// The library in library order: sort key, then id.
internal fun List<TrackEntity>.inLibraryOrder() = sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }

// Song id to its phone path: the first phone copy with a file name.
internal fun CatalogDatabase.phonePaths(): Map<String, String> {
    val paths = LinkedHashMap<String, String>()
    query("SELECT mergedId, folder, fileName FROM source_track WHERE sourceId = 'device' AND fileName IS NOT NULL ORDER BY seq", null).use { c ->
        while (c.moveToNext()) paths.putIfAbsent(c.getString(0), relativeFile(c.getString(1), c.getString(2)))
    }
    return paths
}

internal fun matchJobs(db: CatalogDatabase, page: Int = 7, space: FreeSpace = FreeSpace { Long.MAX_VALUE }) = MatchJobs(db, WorkOwners(), space, page)

// Every row of a list a job matched, page by page.
internal suspend fun MatchJob.allRows(list: Int): List<MatchRow> {
    val all = ArrayList<MatchRow>()
    var after: Long? = -1L
    while (after != null) {
        val page = rows(list, after, 5)
        all += page.rows
        after = page.next
    }
    return all
}

internal val RESTORE = SpaceFor.Restore
