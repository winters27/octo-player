package app.winters.octo.catalog

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.device.DEVICE

// A labeled pin: today's swap and today's skipped merge, as
// catalog/CatalogMerge.kt (swap, skipped, SwapSql) ran them in big1a after
// merge 6, with every statement they ran copied as its text (UserDao's and
// FavouritesDao's relinks, followMergedQueue, the work DAO's bookkeeping),
// so the app's own may change. Only the hooks, the timing and the log line
// are left out. Frozen as the measure the save's find and apply must
// match; never edited to follow the app. The one thing a save does that
// the swap never did, follow an adopted find, is TodayAdoptions.kt.

// A claim as the swap took it: the sources in the library in rank order,
// each claimed copy by its source and generation, and the server in use
// ("" for none).
internal class TodayClaim(val sources: List<String>, val claimed: Map<String, Long>, val server: String)

// Today's swap, in one transaction. Answers the rows it wrote.
internal suspend fun todaySwap(db: CatalogDatabase, claim: TodayClaim): Long = db.withTransaction {
    val sql = db.openHelper.writableDatabase
    val before = todayTotalChanges(sql)
    val metas = todayMetas(sql).associateBy { it.sourceId }
    val failed = claim.claimed.filterNot { (source, generation) ->
        val meta = metas[source]
        meta != null && meta.stage == STAGE_CLAIMED && meta.generation == generation &&
            todayLong(sql, "SELECT COUNT(*) FROM stage_track WHERE sourceId = ?", source) == meta.stagedTracks.toLong() &&
            todayLong(sql, "SELECT COUNT(*) FROM stage_album WHERE sourceId = ?", source) == meta.stagedAlbums.toLong() &&
            todayLong(sql, "SELECT COUNT(*) FROM stage_artist WHERE sourceId = ?", source) == meta.stagedArtists.toLong()
    }
    check(failed.isEmpty()) { "today's swap refuses the claim of ${failed.keys}" }
    val statements = todaySwapSql(sql)
    val firstSources = claim.claimed.isNotEmpty() && todayIsEmpty(sql, "source_track")
    val sourceIndexes = if (firstSources) todayDropIndexes(sql, "source_track") else emptyList()
    for ((source, generation) in claim.claimed) {
        val meta = metas.getValue(source)
        val moved = mapOf("source_track" to meta.movedTracks, "source_album" to meta.movedAlbums, "source_artist" to meta.movedArtists)
        statements.applyStage.forEach { s ->
            val name = todaySwapStepName(s)
            if (name.startsWith(TODAY_SEQ_STEP) && moved[name.removePrefix(TODAY_SEQ_STEP)] == 0) return@forEach
            sql.execSQL(s, arrayOf<Any?>(source, generation))
        }
    }
    val firstCopy = todayIsEmpty(sql, "track") && !todayIsEmpty(sql, "merge_candidate")
    statements.applyLibrary.forEach { s ->
        if (firstCopy && s == statements.songsIn) {
            val indexes = todayDropIndexes(sql, "track")
            sql.execSQL(s)
            indexes.forEach(sql::execSQL)
        } else {
            sql.execSQL(s)
        }
    }
    for (source in claim.sources) sql.execSQL(statements.mergedIds, arrayOf(source))
    if (firstSources) sourceIndexes.forEach(sql::execSQL)
    sql.execSQL(
        "UPDATE source_track SET mergedId = '' WHERE mergedId != '' AND sourceId NOT IN (${claim.sources.joinToString { "?" }})",
        claim.sources.toTypedArray(),
    )
    todayRelinkAll(sql)
    todayFollowMergedQueue(sql)
    todayRelinkFavourites(sql)
    todayExec(sql, TODAY_SHOW_PHONE_RATINGS)
    sql.query("SELECT * FROM rating_note").use { c ->
        val row = c.getColumnIndexOrThrow("sourceRowId")
        val rating = c.getColumnIndexOrThrow("rating")
        val notes = buildList { while (c.moveToNext()) add(c.getString(row) to c.getInt(rating)) }
        for ((sourceRowId, r) in notes) {
            sql.execSQL("UPDATE track SET rating = ? WHERE id = (SELECT mergedId FROM source_track WHERE id = ?)", arrayOf<Any?>(r, sourceRowId))
        }
    }
    for ((source, generation) in claim.claimed) {
        sql.execSQL("UPDATE account_sync SET afterSyncDone = 0 WHERE account = (SELECT stageOwner FROM source_meta WHERE sourceId = ?)", arrayOf(source))
        sql.execSQL("UPDATE source_meta SET copyOf = stageOwner WHERE sourceId = ?", arrayOf(source))
        sql.execSQL(
            "UPDATE source_meta SET stage = 'none', stageOwner = NULL, stagedTracks = 0, stagedAlbums = 0, stagedArtists = 0, " +
                "movedTracks = 0, movedAlbums = 0, movedArtists = 0, stamp = stamp + 1 WHERE sourceId = ?",
            arrayOf(source),
        )
        sql.execSQL("UPDATE source_meta SET stageClearing = 1 WHERE sourceId = ?", arrayOf(source))
        sql.execSQL("DELETE FROM take_in_log WHERE generation = ?", arrayOf(generation))
    }
    if (claim.claimed.keys.any { it != DEVICE }) sql.execSQL("DELETE FROM rating_tap WHERE sourceRowId NOT IN (SELECT id FROM source_track)")
    todayEnsureMergeState(sql)
    sql.execSQL("UPDATE merge_state SET claimActive = ? WHERE id = 0", arrayOf<Any?>(false))
    todayEndRatingNotes(sql)
    val stamps = todayMetas(sql).associate { it.sourceId to it.stamp }
    todayEnsureMergeState(sql)
    sql.execSQL("UPDATE merge_state SET skipKey = ? WHERE id = 0", arrayOf(todaySkipKey(claim.sources, stamps, emptyMap())))
    todayEnsureMergeState(sql)
    sql.execSQL("UPDATE merge_state SET swapCount = swapCount + 1 WHERE id = 0")
    todayEnsureMergeState(sql)
    sql.execSQL("UPDATE merge_state SET server = ? WHERE id = 0", arrayOf(claim.server))
    todayTotalChanges(sql) - before
}

// Today's skipped merge: rows written with an id a merge took away still
// follow it, and songs no server rated show the phone's rating; when any
// row moved, the swap count rises.
internal suspend fun todaySkipped(db: CatalogDatabase): Int = db.withTransaction {
    val sql = db.openHelper.writableDatabase
    val changed = todayRelinkAll(sql) + todayRelinkFavourites(sql) + todayExec(sql, TODAY_SHOW_PHONE_RATINGS)
    if (changed > 0) {
        todayEnsureMergeState(sql)
        sql.execSQL("UPDATE merge_state SET swapCount = swapCount + 1 WHERE id = 0")
    }
    changed
}

// A source's record as the swap read it.
private class TodayMeta(
    val sourceId: String,
    val stamp: Long,
    val stage: String,
    val generation: Long,
    val stagedTracks: Int,
    val stagedAlbums: Int,
    val stagedArtists: Int,
    val movedTracks: Int,
    val movedAlbums: Int,
    val movedArtists: Int,
)

private fun todayMetas(sql: SupportSQLiteDatabase): List<TodayMeta> =
    sql.query(
        "SELECT sourceId, stamp, stage, generation, stagedTracks, stagedAlbums, stagedArtists, movedTracks, movedAlbums, movedArtists FROM source_meta",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(TodayMeta(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9)))
            }
        }
    }

private fun todayEnsureMergeState(sql: SupportSQLiteDatabase) = sql.execSQL("INSERT OR IGNORE INTO merge_state (id, skipKey, claimActive) VALUES (0, NULL, 0)")

private fun todayEndRatingNotes(sql: SupportSQLiteDatabase) {
    if (todayMetas(sql).none { it.sourceId != DEVICE && it.stage != STAGE_NONE }) sql.execSQL("DELETE FROM rating_note")
}

private fun todayLong(sql: SupportSQLiteDatabase, query: String, vararg args: Any?): Long =
    sql.query(query, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

private fun todayExec(sql: SupportSQLiteDatabase, statement: String): Int = sql.compileStatement(statement).use { it.executeUpdateDelete() }

private fun todayTotalChanges(sql: SupportSQLiteDatabase): Long = todayLong(sql, "SELECT total_changes()")

internal fun todaySkipKey(sources: List<String>, stamps: Map<String, Long>, stages: Map<String, Long>): String = buildString {
    append("rules ").append(MERGE_RULES_VERSION).append(" keys ").append(SONG_KEYS_VERSION)
    for (source in sources) append('|').append(source).append('=').append(stamps[source] ?: 0)
    for ((source, generation) in stages.toSortedMap()) append("|stage ").append(source).append('=').append(generation)
}

private fun todaySwapStepName(statement: String): String {
    val words = statement.trimStart().split(Regex("\\s+"), limit = 4)
    val table = if (words[0] == "UPDATE") words[1].trim('`') else words[2].trim('`')
    if (words[0] == "UPDATE" && words[3].startsWith("`seq` =")) return "$TODAY_SEQ_STEP$table"
    return "${words[0].lowercase()} $table"
}

private const val TODAY_SEQ_STEP = "update seq of "

private fun todayIsEmpty(sql: SupportSQLiteDatabase, table: String): Boolean =
    sql.query("SELECT NOT EXISTS (SELECT 1 FROM `$table`)").use { c -> c.moveToFirst() && c.getInt(0) == 1 }

private fun todayDropIndexes(sql: SupportSQLiteDatabase, table: String): List<String> {
    val indexes = sql.query(
        "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL ORDER BY name",
        arrayOf<Any?>(table),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    indexes.forEach { (name, _) -> sql.execSQL("DROP INDEX `$name`") }
    return indexes.map { it.second }
}

private fun todayColumnsOf(sql: SupportSQLiteDatabase, table: String): List<String> =
    sql.query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        buildList { while (c.moveToNext()) add(c.getString(name)) }
    }

private fun todaySwapSql(sql: SupportSQLiteDatabase) = TodaySwapSql(
    source = todayColumnsOf(sql, "source_track"),
    album = todayColumnsOf(sql, "source_album"),
    artist = todayColumnsOf(sql, "source_artist"),
    track = todayColumnsOf(sql, "track"),
    libraryAlbum = todayColumnsOf(sql, "album"),
    libraryArtist = todayColumnsOf(sql, "artist"),
)

private class TodaySwapSql(
    source: List<String>,
    album: List<String>,
    artist: List<String>,
    track: List<String>,
    libraryAlbum: List<String>,
    libraryArtist: List<String>,
) {
    val applyStage: List<String> = listOf(
        apply("source_track", "stage_track", "track", source - "mergedId"),
        apply("source_album", "stage_album", "album", album),
        apply("source_artist", "stage_artist", "artist", artist),
    ).flatten()

    val applyLibrary: List<String> = listOf(
        library("track", "merge_candidate", track) { column ->
            when (column) {
                "year" -> "COALESCE(c.originalYear, c.year)"
                "rating" -> "CASE WHEN c.rating = 0 THEN COALESCE((SELECT r.rating FROM track_rating r WHERE r.trackId = c.id), 0) ELSE c.rating END"
                else -> "c.`$column`"
            }
        },
        library("album", "album_next", libraryAlbum) { "c.`$it`" },
        library("artist", "artist_next", libraryArtist) { "c.`$it`" },
    ).flatten()

    val songsIn: String = applyLibrary.single { it.startsWith("INSERT INTO `track` ") }

    val mergedIds = """
        UPDATE source_track
        SET mergedId = COALESCE((SELECT n.mergedId FROM merged_id_next n WHERE n.sourceRowId = source_track.id), id)
        WHERE sourceId = ?1
          AND mergedId IS NOT COALESCE((SELECT n.mergedId FROM merged_id_next n WHERE n.sourceRowId = source_track.id), id)
    """.trimIndent()

    private fun apply(live: String, staged: String, kind: String, columns: List<String>): List<String> {
        val all = columns.joinToString { "`$it`" }
        val set = columns - "id" - "seq"
        return listOfNotNull(
            """
            DELETE FROM `$live` WHERE sourceId = ?1
              AND NOT EXISTS (SELECT 1 FROM `$staged` g WHERE g.id = `$live`.id)
              AND NOT EXISTS (SELECT 1 FROM take_in_log l WHERE l.generation = ?2 AND l.kind = '$kind' AND l.id = `$live`.id)
            """.trimIndent(),
            """
            UPDATE `$live` SET (${set.joinToString { "`$it`" }}) = (SELECT ${set.joinToString { "g.`$it`" }} FROM `$staged` g WHERE g.id = `$live`.id)
            WHERE sourceId = ?1 AND ?2 = ?2
              AND EXISTS (SELECT 1 FROM `$staged` g WHERE g.id = `$live`.id AND NOT (${set.joinToString(" AND ") { "g.`$it` IS `$live`.`$it` COLLATE BINARY" }}))
            """.trimIndent(),
            if ("seq" !in columns) {
                null
            } else {
                """
                UPDATE `$live` SET `seq` = (SELECT g.`seq` FROM `$staged` g WHERE g.id = `$live`.id)
                WHERE sourceId = ?1 AND ?2 = ?2
                  AND EXISTS (SELECT 1 FROM `$staged` g WHERE g.id = `$live`.id AND g.`seq` IS NOT `$live`.`seq`)
                """.trimIndent()
            },
            """
            INSERT INTO `$live` ($all) SELECT ${columns.joinToString { "g.`$it`" }} FROM `$staged` g
            WHERE g.sourceId = ?1 AND ?2 = ?2 AND NOT EXISTS (SELECT 1 FROM `$live` t WHERE t.id = g.id)
            """.trimIndent(),
        )
    }

    private fun library(table: String, from: String, columns: List<String>, value: (String) -> String): List<String> {
        val set = columns - "id"
        return listOf(
            "DELETE FROM `$table` WHERE NOT EXISTS (SELECT 1 FROM `$from` c WHERE c.id = `$table`.id)",
            """
            UPDATE `$table` SET (${set.joinToString { "`$it`" }}) = (SELECT ${set.joinToString { value(it) }} FROM `$from` c WHERE c.id = `$table`.id)
            WHERE EXISTS (SELECT 1 FROM `$from` c WHERE c.id = `$table`.id AND NOT (${set.joinToString(" AND ") { "${value(it)} IS `$table`.`$it` COLLATE BINARY" }}))
            """.trimIndent(),
            "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) SELECT ${columns.joinToString { value(it) }} FROM `$from` c " +
                "WHERE NOT EXISTS (SELECT 1 FROM `$table` t WHERE t.id = c.id)",
        )
    }
}

// UserDao.showPhoneRatings.
private const val TODAY_SHOW_PHONE_RATINGS = """
    UPDATE track SET rating = (SELECT r.rating FROM track_rating r WHERE r.trackId = track.id)
    WHERE rating = 0 AND id IN (SELECT trackId FROM track_rating)
"""

// UserDao.relinkAll: each table's rows whose song id vanished follow the
// song their source row became, then the lowest song with their relink key.
private fun todayRelinkAll(sql: SupportSQLiteDatabase): Int {
    fun follow(table: String, column: String = "trackId", orIgnore: Boolean = false, also: String = "") = """
        UPDATE${if (orIgnore) " OR IGNORE" else ""} $table SET $column = (SELECT s.mergedId FROM source_track s WHERE s.id = $table.$column)
        WHERE ${also}$column NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = $table.$column)
    """
    fun relink(table: String, orIgnore: Boolean = false, also: String = "") = """
        UPDATE${if (orIgnore) " OR IGNORE" else ""} $table
        SET trackId = (SELECT t.id FROM track t WHERE t.relinkKey = $table.relinkKey ORDER BY t.id LIMIT 1)
        WHERE ${also}trackId NOT IN (SELECT id FROM track)
          AND EXISTS (SELECT 1 FROM track t WHERE t.relinkKey = $table.relinkKey AND t.relinkKey != '')
    """
    val statements = listOf(
        follow("liked_track", orIgnore = true),
        follow("play_event"),
        follow("playlist_item"),
        follow("playlist_edit_log"),
        follow("track_rating", orIgnore = true),
        follow("playlist_entry"),
        follow("playlist_match"),
        follow("playlist_local"),
        follow("playlist_next"),
        """
        UPDATE OR IGNORE lists_album_entry SET shownId = (SELECT s.mergedId FROM source_track s WHERE s.id = lists_album_entry.shownId)
        WHERE shownId NOT IN (SELECT id FROM track) AND isFind = 0
          AND EXISTS (SELECT 1 FROM source_track s JOIN track t ON t.id = s.mergedId WHERE s.id = lists_album_entry.shownId)
        """,
        follow("server_entry"),
        follow("server_match"),
        follow("lists_server_dir", also = "kind = 1 AND "),
        relink("liked_track", orIgnore = true),
        relink("play_event"),
        relink("playlist_item"),
        relink("playlist_edit_log"),
        relink("track_rating", orIgnore = true),
        relink("playlist_entry"),
        relink("playlist_match"),
        relink("playlist_local"),
        relink("playlist_next"),
        relink("server_entry"),
        relink("server_match"),
        relink("lists_server_dir", also = "kind = 1 AND "),
    )
    return statements.sumOf { todayExec(sql, it) }
}

// followMergedQueue: the merge's renames that touch a queued song, a page
// at a time by the renames' key, one update a rename.
private fun todayFollowMergedQueue(sql: SupportSQLiteDatabase): Int {
    var after = ""
    var changed = 0
    sql.compileStatement(
        "UPDATE queue_entry SET trackId = ?2 WHERE trackId = ?1 AND NOT EXISTS (SELECT 1 FROM track WHERE id = ?1) " +
            "AND EXISTS (SELECT 1 FROM track WHERE id = ?2) " +
            "AND NOT EXISTS (SELECT 1 FROM queue_entry o WHERE o.trackId = ?2 AND +o.queueId = queue_entry.queueId AND +o.listId = queue_entry.listId)",
    ).use { rename ->
        while (true) {
            val page = sql.query(
                "SELECT n.sourceRowId, n.mergedId FROM merged_id_next n WHERE n.sourceRowId > ?1 AND n.sourceRowId <> n.mergedId " +
                    "AND EXISTS (SELECT 1 FROM queue_entry q WHERE q.trackId = n.sourceRowId) ORDER BY n.sourceRowId LIMIT ?2",
                arrayOf<Any?>(after, 500L),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            for ((from, to) in page) {
                rename.clearBindings()
                rename.bindString(1, from)
                rename.bindString(2, to)
                changed += rename.executeUpdateDelete()
            }
            if (page.size < 500) return changed
            after = page.last().first
        }
    }
}

// FavouritesDao.relinkAll, with Favourites.kt's relinks and mergedInto.
private fun todayRelinkFavourites(sql: SupportSQLiteDatabase): Int {
    fun pairs(query: String): List<Pair<String, String>> = sql.query(query).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    val pins = sql.query("SELECT kind, itemId, relinkKey FROM pinned_item ORDER BY position").use { c ->
        buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
    }
    val likedAlbums = pairs("SELECT albumId, relinkKey FROM liked_album")
    val likedArtists = pairs("SELECT artistId, relinkKey FROM liked_artist")
    if (pins.isEmpty() && likedAlbums.isEmpty() && likedArtists.isEmpty()) return 0
    val albums = pairs("SELECT id, searchKey FROM album")
    val albumIds = albums.mapTo(HashSet()) { it.first }
    val albumsByKey = albums.groupBy({ it.second }, { it.first })
    val artists = pairs("SELECT id, searchKey FROM artist")
    val artistIds = artists.mapTo(HashSet()) { it.first }
    val artistsByKey = artists.groupBy({ it.second }, { it.first })

    fun held(kind: String) = pins.filter { it.first == kind }.map { Held(it.second, it.third) }
    val heldAlbums = likedAlbums.map { Held(it.first, it.second) }
    val heldArtists = likedArtists.map { Held(it.first, it.second) }
    val pinnedAlbums = held("album")
    val pinnedArtists = held("artist")

    fun vanished(rows: List<Held>, present: Set<String>) = rows.map { it.id }.filter { it !in present }.distinct()
    fun copyCounts(column: String, ids: List<String>): List<TodayCopyCount> = ids.chunked(500).flatMap { chunk ->
        sql.query(
            "SELECT st.$column AS serverRowId, t.$column AS libraryId, COUNT(*) AS songs " +
                "FROM source_track st JOIN track t ON t.id = st.mergedId WHERE st.$column IN (${chunk.joinToString { "?" }}) GROUP BY st.$column, t.$column",
            chunk.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(TodayCopyCount(c.getString(0), c.getString(1), c.getInt(2))) } }
    }
    val albumsMerged = todayMergedInto(copyCounts("albumId", vanished(heldAlbums + pinnedAlbums, albumIds)))
    val artistsMerged = todayMergedInto(copyCounts("artistId", vanished(heldArtists + pinnedArtists, artistIds)))

    fun move(statement: String, vararg args: Any?): Int = sql.compileStatement(statement).use { s ->
        s.bindAll(*args)
        s.executeUpdateDelete()
    }
    return todayRelinks(heldAlbums, albumIds, albumsByKey, albumsMerged).sumOf { move("UPDATE OR IGNORE liked_album SET albumId = ?2 WHERE albumId = ?1", it.first, it.second) } +
        todayRelinks(heldArtists, artistIds, artistsByKey, artistsMerged).sumOf { move("UPDATE OR IGNORE liked_artist SET artistId = ?2 WHERE artistId = ?1", it.first, it.second) } +
        todayRelinks(pinnedAlbums, albumIds, albumsByKey, albumsMerged).sumOf {
            move("UPDATE OR IGNORE pinned_item SET itemId = ?3 WHERE kind = ?1 AND itemId = ?2", "album", it.first, it.second)
        } +
        todayRelinks(pinnedArtists, artistIds, artistsByKey, artistsMerged).sumOf {
            move("UPDATE OR IGNORE pinned_item SET itemId = ?3 WHERE kind = ?1 AND itemId = ?2", "artist", it.first, it.second)
        }
}

private class TodayCopyCount(val serverRowId: String, val libraryId: String, val songs: Int)

private fun todayRelinks(held: List<Held>, present: Set<String>, byKey: Map<String, List<String>>, merged: Map<String, String>): List<Pair<String, String>> {
    val taken = held.mapTo(HashSet()) { it.id }
    val moves = ArrayList<Pair<String, String>>()
    for (row in held.sortedBy { it.id }) {
        if (row.id in present) continue
        val followed = merged[row.id]?.takeIf { it in present }
        val to = when {
            followed != null -> followed.takeIf { it !in taken }
            row.relinkKey.isEmpty() -> null
            else -> byKey[row.relinkKey].orEmpty().sorted().firstOrNull { it !in taken }
        } ?: continue
        taken += to
        moves += row.id to to
    }
    return moves
}

private fun todayMergedInto(counts: List<TodayCopyCount>): Map<String, String> =
    counts.groupBy { it.serverRowId }.mapValues { (_, links) ->
        links.sortedWith(compareByDescending<TodayCopyCount> { it.songs }.thenBy { it.libraryId }).first().libraryId
    }
