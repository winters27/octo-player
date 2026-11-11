package app.winters.octo.catalog

import javax.inject.Inject

// A save part way (SaveApply) has renamed some library ids already and
// not yet moved every row on them: save_rename holds each old id with the
// id it became. A user's write that names a library id reads the id it is
// now and writes there, inside one Read hold of the merge lock, so no group
// lands between its read and its write; a delete or a change by id also
// reaches every old id renamed to it whose rename reaches the row (a rename
// by follow reaches every row, one by key only a row with its key). Once
// the hold ends, the write's follow-ons (the server's copy, the play
// totals) go with the id it wrote.
//
// A row keyed by a server's copy (a copy's rating, a download) follows its
// song by follow only: a rename by key names another song's copies.

// The id a library id of `kind` is now: the one a rename in force made of
// it, or itself.
internal const val CURRENT_ID_SQL =
    "SELECT COALESCE((SELECT r.`new` FROM save_rename r WHERE r.kind = :kind AND r.`old` = :id), :id)"

// The same for a row that carries the relink key `key`: a rename by key
// reaches it only when the key is the rename's.
internal const val CURRENT_ROW_ID_SQL =
    "SELECT COALESCE((SELECT r.`new` FROM save_rename r WHERE r.kind = :kind AND r.`old` = :id AND (r.byKey = 0 OR r.`key` = :key)), :id)"

// The renames in force that made `id` of `kind`, by the index on the new
// id.
internal const val RENAMED_TO_SQL = "SELECT * FROM save_rename WHERE kind = :kind AND `new` = :id"

// The renames in force of some songs, by the renames' key, and those that
// made some songs, by the index on the new id: at most 900 ids a call, the
// kind written in so the ids are all a statement binds.
internal const val SONG_RENAMES_OF_SQL = "SELECT * FROM save_rename WHERE kind = 'track' AND `old` IN (:ids)"
internal const val SONGS_RENAMED_TO_SQL = "SELECT * FROM save_rename WHERE kind = 'track' AND `new` IN (:ids)"

// How many ids one rename lookup asks after, under the database's limit.
private const val RENAME_CHUNK = 900

// Runs a block inside one Read hold of the merge lock.
interface ReadHold {
    suspend fun <T> read(block: suspend () -> T): T
}

// A point a test can stop a writer at: an id read, inside the writer's
// hold, before its write. It does nothing in the app.
internal open class SongIdHooks {
    open suspend fun read(kind: String, id: String, now: String) {}
}

// Library ids as they are now, for the writers that take one.
class SongIds internal constructor(private val user: UserDao, private val hold: ReadHold) {
    @Inject constructor(db: CatalogDatabase, merge: CatalogMerge) : this(
        db.userDao(),
        object : ReadHold {
            override suspend fun <T> read(block: suspend () -> T): T = merge.betweenRebuilds(HoldKind.Read, block)
        },
    )

    internal var hooks = SongIdHooks()

    // Runs a writer's id reads and its write together, between groups of
    // a save.
    suspend fun <T> write(block: suspend () -> T): T = hold.read(block)

    // The song, album or artist an id names now.
    suspend fun song(id: String): String = current(RENAME_SONG, id)

    suspend fun album(id: String): String = current(RENAME_ALBUM, id)

    suspend fun artist(id: String): String = current(RENAME_ARTIST, id)

    private suspend fun current(kind: String, id: String): String = user.currentId(kind, id).also { hooks.read(kind, id, it) }

    // The id a row of `kind` written with relink key `key` lands on now.
    suspend fun row(kind: String, id: String, key: String): String = user.currentRowId(kind, id, key).also { hooks.read(kind, id, it) }

    // Songs as they are now, in the order given.
    suspend fun songs(ids: List<String>): List<String> {
        val renamed = HashMap<String, String>()
        ids.distinct().chunked(RENAME_CHUNK).forEach { chunk -> user.songRenamesOf(chunk).forEach { renamed[it.old] = it.new } }
        return ids.map { renamed[it] ?: it }.also { now -> if (ids.isNotEmpty()) hooks.read(RENAME_SONG, ids.first(), now.first()) }
    }

    // Rows given with their relink keys, each on the song it lands on now.
    suspend fun songRows(rows: List<SongToAdd>): List<SongToAdd> {
        val renamed = HashMap<String, SaveRenameEntity>()
        rows.map { it.trackId }.distinct().chunked(RENAME_CHUNK).forEach { chunk -> user.songRenamesOf(chunk).forEach { renamed[it.old] = it } }
        return rows.map { row -> renamed[row.trackId]?.takeIf { it.reaches(row.relinkKey) }?.let { row.copy(trackId = it.new) } ?: row }
            .also { now -> if (rows.isNotEmpty()) hooks.read(RENAME_SONG, rows.first().trackId, now.first().trackId) }
    }

    // Songs as rows keyed by a server's copy land on them now (a download
    // put back): renamed by follow only, in the order given.
    suspend fun followed(ids: List<String>): List<String> {
        val renamed = HashMap<String, String>()
        ids.distinct().chunked(RENAME_CHUNK).forEach { chunk -> user.songRenamesOf(chunk).filter { !it.byKey }.forEach { renamed[it.old] = it.new } }
        return ids.map { renamed[it] ?: it }.also { now -> if (ids.isNotEmpty()) hooks.read(RENAME_SONG, ids.first(), now.first()) }
    }

    // The old ids of `kind` renamed to `id`, with what each reaches.
    suspend fun renamedTo(kind: String, id: String): List<SaveRenameEntity> = user.renamedTo(kind, id)

    // `id` and the old ids a copy of it may still name: those renamed to it
    // by follow.
    suspend fun copyIds(id: String): List<String> = listOf(id) + user.renamedTo(RENAME_SONG, id).filter { !it.byKey }.map { it.old }

    // The same for many songs, by song.
    suspend fun copyIdsOf(ids: List<String>): Map<String, List<String>> {
        val olds = HashMap<String, MutableList<String>>()
        ids.distinct().chunked(RENAME_CHUNK).forEach { chunk ->
            user.songsRenamedTo(chunk).filter { !it.byKey }.forEach { olds.getOrPut(it.new, ::ArrayList) += it.old }
        }
        return ids.associateWith { listOf(it) + olds[it].orEmpty() }
    }
}

// Whether a rename reaches a row with relink key `key`.
fun SaveRenameEntity.reaches(key: String?): Boolean = !byKey || this.key == key
