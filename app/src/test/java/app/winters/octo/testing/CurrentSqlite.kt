package app.winters.octo.testing

import android.database.sqlite.SQLiteDatabase
import app.winters.octo.catalog.CATALOG_VERSION

// The SQLite Robolectric runs, a current one as newer phones have, with the
// library's schema: a query's plan is checked here as well as on 3.21, since
// a newer planner can choose differently. Robolectric tests only.
object CurrentSqlite {
    // The plan's detail lines. Arguments are written into the statement as
    // literals (a missing one as 1), which plans the same as binding them.
    fun plan(sql: String, args: List<Any?> = emptyList()): List<String> {
        val db = SQLiteDatabase.create(null)
        try {
            OldSqlite.schemaStatements(CATALOG_VERSION).forEach(db::execSQL)
            return db.rawQuery("EXPLAIN QUERY PLAN ${withLiterals(sql, args)}", null).use { c ->
                val detail = c.getColumnIndexOrThrow("detail")
                buildList { while (c.moveToNext()) add(c.getString(detail)) }
            }
        } finally {
            db.close()
        }
    }

    private fun withLiterals(sql: String, args: List<Any?>): String {
        var next = 0
        return Regex("""\?\d*""").replace(sql) {
            when (val arg = args.getOrNull(next++)) {
                null -> "1"
                is String -> "'" + arg.replace("'", "''") + "'"
                is Boolean -> if (arg) "1" else "0"
                else -> arg.toString()
            }
        }
    }
}
