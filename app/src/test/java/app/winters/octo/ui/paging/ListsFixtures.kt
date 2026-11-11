package app.winters.octo.ui.paging

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.rawQuery
import app.winters.octo.catalog.sortKey
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.today.songQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

// Names full of what orders and letters trip on: case, accents, digits,
// signs, the dotless i and the long s, a ligature, text past the BMP and
// in the private use area, and nothing at all.
internal val EDGE_TITLES = listOf(
    "a", "A", "Ångström", "angstrom", "𝄞 clef", "", "Zed", "zed", "100 Days", "10 Days", "_under", "Éclair", "ıstanbul", "ſtar",
    "Ĳssel", "[bracket", "{brace", " private", "� sign", "Mango", "melon", "Night", "Ölberg", "Yes", "x-ray", "#hash", "Ńowhere",
)
internal val EDGE_NAMES = listOf("Abba", "ABBA", "Björk", "bjork", "", "🎵 Notes", "Zoë", "ıl", "Muse", "muse", "12 Bands")

// SQLite's LOWER, which turns only ASCII letters, as the swap writes the
// artist and album sort keys.
internal fun sqlLower(s: String) = buildString { s.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

// `n` songs on generated edge names, written straight into `track`, with
// every third one liked.
internal suspend fun CatalogDatabase.edgeSongs(n: Int, seed: Int = 11, titles: List<String> = EDGE_TITLES): List<TrackEntity> {
    val random = Random(seed)
    val tracks = (0 until n).map { i ->
        val title = titles[random.nextInt(titles.size)]
        val artist = EDGE_NAMES[random.nextInt(EDGE_NAMES.size)]
        val album = EDGE_NAMES[random.nextInt(EDGE_NAMES.size)] + " LP"
        TrackEntity(
            id = "device:${random.nextInt(1_000_000)}-$i", sourceId = "device", nativeId = "$i", title = title,
            searchKey = title.lowercase(), sortKey = sortKey(title), artist = artist, artistId = "ar:$artist",
            album = album, albumId = "al:$album", trackNo = random.nextInt(3), discNo = 1,
            year = if (random.nextInt(4) == 0) null else 1990 + random.nextInt(4), durationMs = 1_000L * random.nextInt(5),
            addedAt = random.nextLong(5), mimeType = null, sizeBytes = null, artwork = null, uri = null,
            albumOrder = random.nextInt(4), relinkKey = "k$i", genre = listOf("Rock", "ROCK", "Jazz", "")[random.nextInt(4)], rating = random.nextInt(6),
            artistSortKey = sqlLower(artist), albumSortKey = sqlLower(album),
        )
    }
    dao().insertTracks(tracks)
    tracks.filterIndexed { i, _ -> i % 3 == 0 }.forEachIndexed { i, t ->
        openHelper.writableDatabase.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES (?, ?, ?)", arrayOf<Any?>(t.id, t.relinkKey, i % 7))
    }
    return tracks
}

// A write through Room's own transaction, so its table change reaches the
// lists that follow it, as the app's writes do.
internal suspend fun CatalogDatabase.write(sql: String, vararg args: Any?) = withTransaction { openHelper.writableDatabase.execSQL(sql, args) }

// Keeps a screen where each new generation asks, as rememberListRows does:
// once the generation shows, its anchor row is read there and becomes the
// first visible row, so Paging's own anchor and the handle's hint follow.
internal fun <T : Any> CoroutineScope.followReanchors(
    handle: ListHandle<*, T>,
    shown: app.winters.octo.testing.Presented<ListItem<T>>,
    seen: MutableList<Reanchor>,
) = launch {
    handle.reanchors.collect { reanchor ->
        seen += reanchor
        withTimeoutOrNull(10_000) { while (handle.presented < reanchor.generation) delay(5) }
        if (handle.presented == reanchor.generation) {
            shown.get(reanchor.index)
            shown.peek(reanchor.index)?.let { handle.visible(it.key) }
        }
    }
}

// Songs as files of one phone folder: the library's songs and their own
// phone rows there. Answers the folder as the page's tree holds it.
internal suspend fun CatalogDatabase.phoneFolder(name: String, tracks: List<TrackEntity>): PhoneFolder {
    dao().insertTracks(tracks)
    val sql = openHelper.writableDatabase
    sql.beginTransaction()
    try {
        for (t in tracks) {
            sql.execSQL(
                "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, " +
                    "relinkKey, genre, mergedId, storageVolume, folder) VALUES (?, 'device', ?, ?, ?, ?, ?, '', ?, ?, 0, 0, 0, '', '', ?, 'primary', ?)",
                arrayOf<Any?>(t.id, t.nativeId, t.title, t.searchKey, t.sortKey, t.artist, t.album, t.albumId, t.id, "Music/$name"),
            )
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
    }
    return PhoneFolder(name, emptyList(), tracks.size, listOf(FolderPlace("primary", "Music/$name")), null)
}

// Today's rows of a song list, by its whole query: their ids, or with
// `select` another column of today's query in their place.
internal suspend fun CatalogDatabase.todaySongs(sort: SongSort, down: Boolean, scope: SongScope, select: String? = null): List<String> {
    val q = songQuery(sort, down, scope)
    val head = Regex("""^SELECT t\.\*, (.+?) AS heading FROM""").find(q.sql) ?: error("today's query changed shape")
    val sql = "SELECT ${(select ?: "t.id").replace("HEADING", head.groupValues[1])} FROM" + q.sql.substring(head.range.last + 1)
    return listsDao().ids(rawQuery(sql, q.args.map { SqlValue.Text(it) }))
}

// A loader's rows walked `page` at a time from the top.
internal suspend fun <T : Any> ListLoader<T>.walked(page: Int = 100, idOf: (T) -> String? = { trackIdOf(it) }): List<String> {
    val out = ArrayList<String>()
    var after: RowKey? = null
    while (true) {
        val rows = after(after, page)
        out += rows.map { idOf(it.row) ?: error("a row with no song") }
        if (rows.size < page) return out
        after = rows.last().key
    }
}
