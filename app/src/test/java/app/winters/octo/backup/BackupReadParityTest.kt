package app.winters.octo.backup

import app.winters.octo.player.PlayerPrefs
import app.winters.octo.playlists.BYTE_ORDER_MARK
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

// A labeled pin. A backup read a value at a time answers what today's
// decodeBackup answers for the whole text (Backup.kt at c2208eaa): the same
// small values, the same likes, ratings and playlists in order (staged in
// the job), or the same refusal. Missing kind, unknown and repeated keys,
// nulls, values of the wrong kind, too new, a byte order mark, text after
// the object, no JSON at all.
@RunWith(RobolectricTestRunner::class)
class BackupReadParityTest {
    private val rig = BackupRig(openTestCatalog())

    @After
    fun close() = rig.close()

    private fun key(i: Int) = SongKey("rk$i", "Song $i 🎵", "Artist \"$i\"", "Album", 1_000L * i)

    private val full = Backup(
        createdAt = 5,
        player = PlayerBackup(PlayerPrefs(), theme = "Octo"),
        presets = listOf(PresetBackup("P", listOf(1f))),
        playlists = listOf(PlaylistBackup("Road trip", (0 until 7).map(::key)), PlaylistBackup("Empty")),
        likes = (0 until 9).map(::key),
        ratings = (0 until 4).map { RatedSong(key(it), it + 1) },
        sortOrders = mapOf("songs" to "Title:asc"),
    )

    // A read as the restore sees it: its header with its songs from the job.
    private fun read(text: String): Any {
        val opened = runBlocking { rig.songs.read { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) } }
        if (opened !is BackupOpened.Opened) return opened
        val file = opened.file
        val rows = rig.db.query("SELECT list, relinkKey, title, artist, album, durationMs, value FROM match_query WHERE owner = ? ORDER BY id", arrayOf(file.job.owner)).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getInt(0), SongKey(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5)), c.getInt(6))) }
        }
        fun list(n: Int) = rows.filter { it.first == n }
        val whole = file.header.copy(
            playlists = file.playlists.map { p -> PlaylistBackup(p.name, list(p.list).map { it.second }) },
            likes = list(Backups.LIKES).map { it.second },
            ratings = list(Backups.RATINGS).map { RatedSong(it.second, it.third) },
        )
        runBlocking { rig.songs.close(file) }
        return BackupRead.Read(whole)
    }

    private fun today(text: String): Any = when (val read = decodeBackup(text)) {
        is BackupRead.Read -> read
        BackupRead.NotABackup -> BackupOpened.NotABackup
        BackupRead.TooNew -> BackupOpened.TooNew
    }

    private val corpus: List<String> by lazy {
        val text = encodeBackup(full)
        listOf(
            text,
            BYTE_ORDER_MARK + text,
            text.replace("\"kind\": \"octo-settings\",", ""),
            text.replace("\"kind\": \"octo-settings\"", "\"kind\": \"other\""),
            text.replace("\"version\": 1", "\"version\": 99"),
            text.replaceFirst("{", "{\"unknown\": {\"a\": [1, 2]}, \"likes\": [{\"title\": \"first\"}], \"createdAt\": 1,"),
            text.replaceFirst("\"relinkKey\": \"rk0\",", "\"relinkKey\": \"rk0\", \"extra\": true,"),
            text.replaceFirst("\"name\": \"Road trip\",", "\"name\": \"Road trip\", \"songs\": [{\"title\": \"dropped\"}], \"more\": 1,"),
            """{"likes": null, "presets": null, "player": null, "playlists": null}""",
            """{"playlists": [{"name": "A", "songs": null}]}""",
            """{"createdAt": "x"}""",
            """{"likes": [1]}""",
            """{"likes": {}}""",
            """{"playlists": [null]}""",
            """{"playlists": [{"songs": []}]}""",
            """{"ratings": [{"song": {"title": "x"}}]}""",
            """{"ratings": [{"song": {"title": "x"}, "rating": 9}]}""",
            """{} x""",
            "[]",
            "",
            "not json",
            """{"sortOrders": {"a": "b"}, "sortOrders": {"c": "d"}}""",
        )
    }

    @Test
    fun everyFileReadsAsToday() {
        corpus.forEachIndexed { i, text ->
            assertEquals("file $i: ${text.take(80)}", today(text), read(text))
        }
        // No job is left behind.
        assertEquals(0, rig.db.query("SELECT COUNT(*) FROM match_query", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
