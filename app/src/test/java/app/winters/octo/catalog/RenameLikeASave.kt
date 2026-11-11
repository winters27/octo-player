package app.winters.octo.catalog

import androidx.room.withTransaction

// A library id renamed as one group of a save renames it, in one
// transaction: the rename is put in force (by follow, or by key for
// `key`), every reference table's rows on the old id that it reaches move
// to the new one under the table's guard, as the group's catch-up moves
// them (SaveApply), and with `raise` the swap count moves once. The
// library's own rows are the caller's to change. RenameLikeASaveTest holds
// it to a real save.
internal suspend fun CatalogDatabase.renameLikeASave(from: String, to: String, kind: String = RENAME_SONG, key: String? = null, raise: Boolean = true) =
    withTransaction {
        val sql = openHelper.writableDatabase
        sql.execSQL(RENAME_IN_SQL, arrayOf<Any?>(kind, from, to, if (key != null) 1 else 0, key.orEmpty()))
        for (t in SAVE_RENAME_TARGETS.filter { it.kind == kind || it.kind == null }) {
            val row = { column: String -> "`${t.table}`.`$column`" }
            val pin = if (t.kind == null) " AND `kind` = '$kind'" else ""
            sql.execSQL(
                "UPDATE `${t.table}` SET `${t.column}` = (SELECT r.`new` FROM save_rename r WHERE r.kind = '$kind' AND r.`old` = `${t.table}`.`${t.column}`) " +
                    "WHERE `${t.column}` = ?1$pin AND ${t.condition(row)}",
                arrayOf<Any?>(from),
            )
        }
        if (raise) workDao().raiseSwapCount()
    }
