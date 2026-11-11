package app.winters.octo.backup

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PinnedSet
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.longOrNull
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.playlists.BYTE_ORDER_MARK
import app.winters.octo.playlists.MATCH_BYTES_PER_ENTRY
import app.winters.octo.playlists.MatchJob
import app.winters.octo.playlists.MatchSong
import com.squareup.moshi.JsonReader
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

// The most bytes one value of a backup may take.
internal const val BACKUP_VALUE_MAX = 1024L * 1024

// How many songs a reader stages between asks for room.
private const val ROOM_EVERY = 5_000

// The top-level lines the three song lists take in a backup written with
// none, which the export writes in their place a page at a time.
private const val PLAYLISTS_MARK = "\n    \"playlists\": []"
private const val LIKES_MARK = "\n    \"likes\": []"
private const val RATINGS_MARK = "\n    \"ratings\": []"

private fun indent(depth: Int) = "    ".repeat(depth)

// A value as encodeBackup writes it, moved in to `depth`.
private fun indented(text: String, depth: Int): String {
    val pad = indent(depth)
    return text.lineSequence().joinToString("\n") { pad + it }
}

// Writes a backup's text exactly as encodeBackup writes the whole backup:
// its small values encoded together, and the playlists, likes and ratings
// a page at a time, each song encoded on its own and moved in to its depth.
// Likes and ratings come by when they were made, then by song; playlists by
// when they were made, then by id, each as it stood when it was pinned.
internal class BackupWriter(private val db: CatalogDatabase, private val sets: PlaylistSets, private val out: Appendable) {
    suspend fun write(header: Backup, playlists: List<Pair<PinnedSet, String>>) {
        val text = encodeBackup(header.copy(playlists = emptyList(), likes = emptyList(), ratings = emptyList()))
        var from = 0
        listOf(PLAYLISTS_MARK, LIKES_MARK, RATINGS_MARK).forEachIndexed { i, mark ->
            val at = text.indexOf(mark, from)
            check(at >= 0) { "a backup's text always has its song lists" }
            out.append(text, from, at)
            out.append(mark.removeSuffix("[]"))
            when (i) {
                0 -> playlists(playlists)
                1 -> likes()
                else -> ratings()
            }
            from = at + mark.length
        }
        out.append(text, from, text.length)
    }

    // An array's items written as they come, at `depth`, the array closed
    // at `depth - 1`.
    private inner class Items(private val depth: Int) {
        private var any = false

        fun item(text: String) {
            out.append(if (any) ",\n" else "[\n")
            any = true
            out.append(indented(text, depth))
        }

        fun end() {
            if (any) out.append("\n").append(indent(depth - 1)).append("]") else out.append("[]")
        }
    }

    private fun songText(key: SongKey) = BackupJson.encodeToString(SongKey.serializer(), key)

    private fun likes() {
        val items = Items(2)
        var afterAt = Long.MIN_VALUE
        var afterId = ""
        while (true) {
            val page = db.read(LIKED_KEYS_SQL, afterAt, afterId, BACKUP_PAGE) { c ->
                Triple(c.getLong(0), c.getString(1), keyOf(c.getString(2), c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5), c.longOrNull(6)))
            }
            page.forEach { items.item(songText(it.third)) }
            if (page.size < BACKUP_PAGE) break
            afterAt = page.last().first
            afterId = page.last().second
        }
        items.end()
    }

    private fun ratings() {
        val items = Items(2)
        var afterAt = Long.MIN_VALUE
        var afterId = ""
        while (true) {
            val page = db.read(RATED_KEYS_SQL, afterAt, afterId, BACKUP_PAGE) { c ->
                Triple(c.getLong(0), c.getString(1), RatedSong(keyOf(c.getString(2), c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5), c.longOrNull(6)), c.getInt(7)))
            }
            page.forEach { items.item(BackupJson.encodeToString(RatedSong.serializer(), it.third)) }
            if (page.size < BACKUP_PAGE) break
            afterAt = page.last().first
            afterId = page.last().second
        }
        items.end()
    }

    // Each playlist as PlaylistBackup encodes it: its name, then its songs
    // a page at a time from its pinned set.
    private suspend fun playlists(playlists: List<Pair<PinnedSet, String>>) {
        val items = Items(2)
        for ((set, name) in playlists) {
            val head = "{\n" + indent(1) + "\"name\": " + BackupJson.encodeToString(String.serializer(), name) + ",\n" + indent(1) + "\"songs\": "
            items.item(head)
            val songs = Items(4)
            sets.walk(set, BACKUP_PAGE) { page ->
                val known = songsOf(page.map { it.trackId })
                page.forEach { row ->
                    val song = known[row.trackId]
                    songs.item(songText(keyOf(row.relinkKey, song?.title, song?.artist, song?.album, song?.durationMs)))
                }
            }
            songs.end()
            out.append("\n").append(indent(2)).append("}")
        }
        items.end()
    }

    private class Known(val title: String, val artist: String, val album: String, val durationMs: Long)

    // What the library knows of a page's songs: a library song's own
    // words, else a find's.
    private fun songsOf(ids: List<String>): Map<String, Known> {
        val found = HashMap<String, Known>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, title, artist, album, durationMs FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) {
                it.getString(0) to Known(it.getString(1), it.getString(2), it.getString(3), it.getLong(4))
            }.forEach { (id, known) -> found[id] = known }
            val missing = chunk.filter { it !in found }
            if (missing.isNotEmpty()) {
                db.read("SELECT id, title, artist, album, durationMs FROM online_song WHERE id IN (${marks(missing.size)})", *missing.toTypedArray()) {
                    it.getString(0) to Known(it.getString(1), it.getString(2), it.getString(3), it.getLong(4))
                }.forEach { (id, known) -> found[id] = known }
            }
        }
        return found
    }

    private fun keyOf(relinkKey: String, title: String?, artist: String?, album: String?, durationMs: Long?) =
        SongKey(relinkKey = relinkKey, title = title.orEmpty(), artist = artist.orEmpty(), album = album.orEmpty(), durationMs = durationMs ?: 0)
}

// A page of the likes with what is known of each song, by when each was
// liked, then by song, on the likes' date index.
internal const val LIKED_KEYS_SQL =
    "SELECT l.likedAt, l.trackId, l.relinkKey, t.title, t.artist, t.album, t.durationMs " +
        "FROM liked_track l LEFT JOIN track t ON t.id = l.trackId WHERE (l.likedAt, l.trackId) > (?, ?) ORDER BY l.likedAt, l.trackId LIMIT ?"

// The same for the ratings, on the ratings' date index.
internal const val RATED_KEYS_SQL =
    "SELECT r.ratedAt, r.trackId, r.relinkKey, t.title, t.artist, t.album, t.durationMs, r.rating " +
        "FROM track_rating r LEFT JOIN track t ON t.id = r.trackId WHERE (r.ratedAt, r.trackId) > (?, ?) ORDER BY r.ratedAt, r.trackId LIMIT ?"

// Reads a backup's text one value at a time, as decodeBackup reads it whole:
// each small value decoded with its own serializer by the same rules (a
// value of the wrong kind refuses the file, an unknown key is passed by, a
// null where a value has a default gives the default, a key given twice
// keeps its last value), the playlists, likes and ratings staged in `job` a
// page at a time, a list given twice replacing the first. A file that is
// not one JSON object, holds a value over BACKUP_VALUE_MAX bytes or a
// playlist with no name is not a backup.
internal class BackupReader(private val job: MatchJob, private val room: (Long) -> Unit = {}) {
    private val small = LinkedHashMap<String, JsonElement>()
    private var likes = 0
    private var ratings = 0
    private var staged = 0L
    private val playlists = ArrayList<FilePlaylist>()
    private var lists = Backups.PLAYLISTS

    suspend fun read(source: BufferedSource): BackupOpened {
        if (source.rangeEquals(0, BYTE_ORDER_MARK.encodeUtf8())) source.skip(BYTE_ORDER_MARK.encodeUtf8().size.toLong())
        val reader = JsonReader.of(source)
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return BackupOpened.NotABackup
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "likes" -> likes = songList(reader, Backups.LIKES, name)
                "ratings" -> ratings = ratedList(reader, name)
                "playlists" -> playlistList(reader, name)
                else -> small[name] = valueOf(reader)
            }
        }
        reader.endObject()
        if (reader.peek() != JsonReader.Token.END_DOCUMENT) return BackupOpened.NotABackup
        val header = BackupJson.decodeFromJsonElement(Backup.serializer(), JsonObject(small))
        if (header.kind != BACKUP_KIND) return BackupOpened.NotABackup
        if (header.version > BACKUP_VERSION) return BackupOpened.TooNew
        header.lyrics?.offsets?.chunked(BACKUP_PAGE)?.forEach { page ->
            stage(Backups.OFFSETS, page.map { it.song.toMatch(it.offsetMs.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()) })
        }
        return BackupOpened.Opened(BackupFile(header, likes, ratings, playlists.toList(), job))
    }

    // The next value whole, as its own text, at most BACKUP_VALUE_MAX bytes.
    private fun valueOf(reader: JsonReader): JsonElement {
        val text = reader.nextSource().use { it.readUtf8Limited() }
        return BackupJson.parseToJsonElement(text)
    }

    private fun BufferedSource.readUtf8Limited(): String {
        val buffer = Buffer()
        while (true) {
            val read = read(buffer, 64 * 1024)
            if (read < 0) break
            if (buffer.size > BACKUP_VALUE_MAX) throw IllegalArgumentException("a backup value over ${BACKUP_VALUE_MAX} bytes")
        }
        return buffer.readUtf8()
    }

    private suspend fun stage(list: Int, songs: List<MatchSong>) {
        if (songs.isEmpty()) return
        job.stageSongs(list, songs)
        val before = staged / ROOM_EVERY
        staged += songs.size
        if (staged / ROOM_EVERY != before) room(staged)
    }

    // A list of songs: each staged as it comes, null for none; a list given
    // again replaces the first. Answers how many it has.
    private suspend fun songList(reader: JsonReader, list: Int, name: String): Int {
        job.dropList(list)
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return 0
        }
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) throw IllegalArgumentException("$name is not a list")
        var count = 0
        val page = ArrayList<MatchSong>(BACKUP_PAGE)
        reader.beginArray()
        while (reader.hasNext()) {
            page += BackupJson.decodeFromJsonElement(SongKey.serializer(), valueOf(reader)).toMatch()
            count++
            if (page.size == BACKUP_PAGE) {
                stage(list, page.toList())
                page.clear()
            }
        }
        reader.endArray()
        stage(list, page)
        return count
    }

    private suspend fun ratedList(reader: JsonReader, name: String): Int {
        job.dropList(Backups.RATINGS)
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return 0
        }
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) throw IllegalArgumentException("$name is not a list")
        var count = 0
        val page = ArrayList<MatchSong>(BACKUP_PAGE)
        reader.beginArray()
        while (reader.hasNext()) {
            val rated = BackupJson.decodeFromJsonElement(RatedSong.serializer(), valueOf(reader))
            page += rated.song.toMatch(rated.rating)
            count++
            if (page.size == BACKUP_PAGE) {
                stage(Backups.RATINGS, page.toList())
                page.clear()
            }
        }
        reader.endArray()
        stage(Backups.RATINGS, page)
        return count
    }

    // The playlists, each read a member at a time: its name and, staged in
    // a list of its own, its songs. A playlist's other members are decoded
    // as PlaylistBackup would decode them.
    private suspend fun playlistList(reader: JsonReader, name: String) {
        playlists.forEach { job.dropList(it.list) }
        playlists.clear()
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return
        }
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) throw IllegalArgumentException("$name is not a list")
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) throw IllegalArgumentException("a playlist is not an object")
            val list = lists++
            val rest = LinkedHashMap<String, JsonElement>()
            var songs = 0
            reader.beginObject()
            while (reader.hasNext()) {
                when (val member = reader.nextName()) {
                    "songs" -> songs = songList(reader, list, member)
                    else -> rest[member] = valueOf(reader)
                }
            }
            reader.endObject()
            // Its name and every other member as the whole file's decode
            // takes them; a playlist with no name refuses the file.
            val decoded = BackupJson.decodeFromJsonElement(PlaylistBackup.serializer(), JsonObject(rest + ("songs" to JsonArray(emptyList()))))
            playlists += FilePlaylist(decoded.name, list, songs)
        }
        reader.endArray()
    }
}

// Asks for room for `staged` songs of a backup being read.
internal fun stagingRoom(free: () -> Long): (Long) -> Unit = { staged ->
    val need = staged * MATCH_BYTES_PER_ENTRY * 2 * 11 / 10
    if (free() < need) throw NotEnoughSpace(need, SpaceFor.Restore)
}
