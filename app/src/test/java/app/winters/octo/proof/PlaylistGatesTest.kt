package app.winters.octo.proof

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// Source scans that keep the playlist sets' rules: every read of playlists
// as playlists leaves out one being deleted; only PlaylistSets lets a pin
// go; every multi-page walk of a playlist's rows reads through the baseline
// statement, but for the two walks whose result an edit cannot leave stale;
// no playlist row is written whole outside a flip; every row written takes
// a key; and nothing writes the retired syncedSongs cell.
class PlaylistGatesTest {
    private val root = File(System.getProperty("octo.mainSources")!!)
    private val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    // Every string literal of a file: triple-quoted ones first, then the
    // rest, with templates left as written.
    private fun literals(text: String): List<String> {
        val triple = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val single = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        return triple.findAll(text).map { it.groupValues[1] }.toList() + single.findAll(triple.replace(text, "")).map { it.groupValues[1] }.toList()
    }

    private fun statements(filter: (String) -> Boolean): Map<String, List<String>> =
        sources.mapValues { (_, text) -> literals(text).filter(filter) }.filterValues { it.isNotEmpty() }

    private val readsPlaylists = Regex("""\bFROM\s+`?playlist`?(?![_\w])""")

    // The files that read playlist rows for what they are, not as playlists.
    private val setsAndUpgrade = setOf(
        "app/winters/octo/catalog/PlaylistSets.kt",
        "app/winters/octo/catalog/Migration18To19.kt",
        "app/winters/octo/catalog/UpgradeGuard.kt",
    )

    @Test
    fun everyListingLeavesOutADeletedPlaylist() {
        val missing = statements { "SELECT" in it.uppercase() && readsPlaylists.containsMatchIn(it) }
            .filterKeys { it !in setsAndUpgrade }
            // A column list ending in FROM playlist is PLAYLIST_ROW_COLUMNS, whose every use is checked below;
            // a playlist's own item set read to reach its rows reads none of one being deleted.
            .flatMap { (path, found) ->
                found.filter { "itemSet >= 0" !in it && !it.trim().endsWith("FROM playlist") && "(SELECT itemSet FROM playlist WHERE" !in it }
                    .map { "$path: ${it.trim().take(80)}" }
            }
        assertEquals(emptyList<String>(), missing)
        val columns = statements { "\$PLAYLIST_ROW_COLUMNS" in it }.flatMap { (path, found) -> found.filter { "itemSet >= 0" !in it }.map { "$path: $it" } }
        assertEquals(emptyList<String>(), columns)
    }

    @Test
    fun onlyReleasePinLetsAPinGo() {
        val found = statements { Regex("""DELETE\s+FROM\s+`?playlist_set_pin""").containsMatchIn(it) }
        assertEquals(setOf("app/winters/octo/catalog/PlaylistSets.kt"), found.keys)
        assertEquals(1, found.values.single().size)
    }

    // A walk of a playlist's rows a page at a time: its statement reads
    // playlist_item with a LIMIT. Each is the baseline statement, a page of
    // PlaylistSets' own deletes, or one of two walks an edit cannot leave
    // stale: a summary's cover walk (it only picks covers, and every edit
    // moves updatedAt, which reads the summary again) and the rules walk
    // (every change of playlist_item starts another walk, which reads it).
    @Test
    fun everyPagedWalkReadsThroughTheBaseline() {
        val walks = statements { Regex("""\bFROM\s+playlist_item\b""").containsMatchIn(it) && Regex("""\bLIMIT\b""").containsMatchIn(it) }
            .flatMap { (path, found) -> found.map { path to it } }
        val allowed = walks.filter { (path, sql) ->
            when (path) {
                "app/winters/octo/catalog/PlaylistSets.kt" -> "UNION ALL" in sql && "playlist_edit_log" in sql || sql.trim().startsWith("SELECT id FROM playlist_item")
                // The picker's count of a playlist's songs: the baseline statement in song order.
                "app/winters/octo/ui/playlist/AddSources.kt" -> "UNION ALL" in sql && "playlist_edit_log" in sql
                "app/winters/octo/catalog/Playlists.kt" -> "findCover" in sql
                "app/winters/octo/offline/DownloadDao.kt" -> "GROUP BY trackId" in sql
                else -> false
            }
        }
        assertEquals(emptyList<String>(), (walks - allowed.toSet()).map { (path, sql) -> "$path: ${sql.trim().take(100)}" })
        assertEquals(5, allowed.size)
    }

    @Test
    fun noPlaylistRowIsWrittenWhole() {
        val whole = sources.filter { (_, text) ->
            "updatePlaylistRow" in text || Regex("""@Update\s+(suspend\s+)?fun\s+\w+\(\s*\w+\s*:\s*PlaylistEntity\b""").containsMatchIn(text)
        }.keys
        assertEquals(emptySet<String>(), whole)
    }

    @Test
    fun everyRowWrittenTakesAKey() {
        val keyless = statements { Regex("""INSERT\s+(OR\s+\w+\s+)?INTO\s+`?playlist_item`?\b""").containsMatchIn(it) && "itemKey" !in it }
        assertEquals(emptyMap<String, List<String>>(), keyless)
    }

    @Test
    fun nothingWritesTheRetiredCell() {
        val writes = sources.filter { (_, text) -> Regex("""\bretiredSyncedSongs\s*=""").containsMatchIn(text) }.keys +
            statements { Regex("""\bsyncedSongs`?\s*=(?!\s*NULL)""").containsMatchIn(it) }.keys
        assertEquals(emptySet<String>(), writes)
    }
}
