package app.winters.octo.catalog

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement

// Plain statements for the library's walks, for SQL Room's own checks do
// not read (row values like `(a, b) > (?, ?)`), with every argument bound.
// Inside a transaction they run on its connection.

// Reads `sql` and turns each row into a T.
internal fun <T> RoomDatabase.read(sql: String, vararg args: Any?, row: (Cursor) -> T): List<T> =
    query(SimpleSQLiteQuery(sql, args)).use { c ->
        val out = ArrayList<T>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) out += row(c)
        out
    }

// The first column of the first row as a number, or 0 with no row.
internal fun RoomDatabase.readLong(sql: String, vararg args: Any?): Long =
    query(SimpleSQLiteQuery(sql, args)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0 }

// Runs a write and answers how many rows it changed.
internal fun RoomDatabase.write(sql: String, vararg args: Any?): Int =
    openHelper.writableDatabase.compileStatement(sql).use { st ->
        st.bindAll(args)
        st.executeUpdateDelete()
    }

internal fun SupportSQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        when (a) {
            null -> bindNull(i + 1)
            is String -> bindString(i + 1, a)
            is ByteArray -> bindBlob(i + 1, a)
            is Boolean -> bindLong(i + 1, if (a) 1 else 0)
            is Double -> bindDouble(i + 1, a)
            is Float -> bindDouble(i + 1, a.toDouble())
            is Number -> bindLong(i + 1, a.toLong())
            else -> throw IllegalArgumentException("cannot bind ${a::class.simpleName}")
        }
    }
}

internal fun Cursor.stringOrNull(column: Int): String? = if (isNull(column)) null else getString(column)

internal fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)

internal fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

// `?, ?, ?` for `n` arguments.
internal fun marks(n: Int): String = List(n) { "?" }.joinToString(", ")

// The most ids one statement binds, below the database's 999.
internal const val ID_CHUNK = 900

// Which of these ids are songs of the library now.
internal fun RoomDatabase.knownIds(ids: Collection<String>): Set<String> {
    val known = HashSet<String>()
    ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
        known += read("SELECT id FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) }
    }
    return known
}

// Ids the library no longer has as songs, each followed to the song it
// became: a swap that renamed a song keeps the old id as one of its
// copies, merged into the new one. An id with no such copy, or whose copy
// is merged into no song, is left out.
internal fun RoomDatabase.followGone(gone: Collection<String>): Map<String, String> {
    val to = HashMap<String, String>()
    gone.distinct().chunked(ID_CHUNK).forEach { chunk ->
        read(
            "SELECT s.id, s.mergedId FROM source_track s WHERE s.id IN (${marks(chunk.size)}) AND s.mergedId != '' " +
                "AND EXISTS (SELECT 1 FROM track t WHERE t.id = s.mergedId)",
            *chunk.toTypedArray(),
        ) { it.getString(0) to it.getString(1) }.forEach { (from, into) -> to[from] = into }
    }
    return to
}

// Every copy of these songs, from every source, read ID_CHUNK songs at a
// time: a selection or a fix can name any number of songs.
suspend fun SourceDao.copiesInChunks(trackIds: List<String>): List<SourceTrackEntity> =
    trackIds.distinct().chunked(ID_CHUNK).flatMap { copiesOf(it) }

// Each id as the library has it now: itself when it is a song, the song it
// was followed to, or null when it is gone.
internal fun RoomDatabase.followAll(ids: Collection<String>): Map<String, String?> {
    val known = knownIds(ids)
    val moved = followGone(ids.filter { it !in known })
    return ids.associateWith { if (it in known) it else moved[it] }
}
