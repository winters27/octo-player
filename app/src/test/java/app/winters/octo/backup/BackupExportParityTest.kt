package app.winters.octo.backup

import app.winters.octo.catalog.SongKeyRow
import app.winters.octo.listening.timed
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A labeled pin. A backup written a page of songs at a time is the same
// file, byte for byte, as today's encodeBackup of the whole backup (make()
// at c2208eaa: likes, ratings and phone playlists read whole by the DAO's
// key reads, playlists by when they were made). Empty lists, small values
// left null, emoji, a song gone from the library, a find in a playlist, a
// playlist with no songs. Likes made at the same moment come by song now,
// declared (today's order for them was the database's).
@RunWith(RobolectricTestRunner::class)
class BackupExportParityTest {
    private val rig = BackupRig(openTestCatalog())

    @After
    fun close() = rig.close()

    // Today's whole backup, over the same header.
    private fun today(header: Backup): String = runBlocking {
        val user = rig.db.userDao()
        val byPlaylist = user.phonePlaylistSongKeys().groupBy { it.playlistId }
        fun SongKeyRow.toKey() = SongKey(relinkKey, title.orEmpty(), artist.orEmpty(), album.orEmpty(), durationMs ?: 0)
        encodeBackup(
            header.copy(
                playlists = user.phonePlaylists().sortedBy { it.createdAt }.map { p -> PlaylistBackup(p.name, byPlaylist[p.id].orEmpty().map { it.toKey() }) },
                likes = user.likedSongKeys().map { it.toKey() },
                ratings = user.ratedSongKeys().mapNotNull { row -> row.rating?.let { RatedSong(row.toKey(), it) } },
            ),
        )
    }

    private fun header() = Backup(
        createdAt = 1_700_000_000_000,
        player = PlayerBackup(PlayerPrefs(), theme = "Octo"),
        presets = listOf(PresetBackup("Bass 🎸", listOf(1f, 2f, 3f))),
        favouriteAlbums = listOf(HeldKey("rk", "Album \"quoted\"", "Artist")),
        sortOrders = mapOf("likes" to "Title:asc"),
    )

    @Test
    fun anEmptyLibraryIsTodaysFile() {
        val (bytes, answer) = rig.export(header())
        assertEquals(BackupSave.Saved, answer)
        assertEquals(today(header()), String(bytes!!, Charsets.UTF_8))
    }

    @Test
    fun aBackupWrittenInPagesIsTodaysFile() = timed(60) {
        val tracks = (0 until 1_300).map { backupTrack("t$it", if (it % 50 == 0) "Song $it 🎵 \\ \"x\"" else "Song $it", "Artist ${it % 9}", "Album ${it % 13}") }
        rig.library(tracks)
        (0 until 1_100).forEach { rig.like("t$it", 10_000L + it) }
        // Two likes made at the same moment come by song.
        rig.like("t1200", 50_000)
        rig.like("t1199", 50_000)
        // A like whose song is gone.
        rig.like("gone", 60_000, relinkKey = "rk-gone")
        (0 until 700).forEach { rig.rate("t$it", it % 5 + 1, 20_000L + it) }
        rig.find("find:9", "Online song")
        rig.playlist("p2", "Second ✨", 200, (0 until 1_150).map { "t${it % 300}" } + listOf("find:9", "gone"))
        rig.playlist("p1", "First", 100, (0 until 40).map { "t${1_000 + it}" })
        rig.playlist("p3", "Empty", 300, emptyList())
        val expected = today(header()).toByteArray(Charsets.UTF_8)
        rig.db.counted.reset()
        val (bytes, answer) = rig.export(header())
        assertEquals(BackupSave.Saved, answer)
        assertTrue("${rig.db.counted.maxRead}", rig.db.counted.maxRead!!.rowsRead <= 600)
        assertArrayEquals(expected, bytes)
        // The picked file was written outside every lock.
        assertEquals(false, rig.copiedInLock)
        // The export let its playlists go.
        assertEquals(0, rig.db.query("SELECT COUNT(*) FROM playlist_set_pin", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(emptyList<String>(), rig.exportDir.listFiles().orEmpty().map { it.name })
    }

    // Short of room, the export says so in the backup's words, writing
    // nothing.
    @Test
    fun anExportShortOfRoomSaysSo() {
        val short = BackupRig(openTestCatalog(), space = { 1_000 })
        try {
            short.library((0 until 100).map { backupTrack("t$it", "Song $it") })
            (0 until 100).forEach { short.like("t$it", it.toLong()) }
            val (bytes, answer) = short.export(header())
            assertEquals(null, bytes)
            val words = (answer as BackupSave.Failed).words
            assertTrue(words, words.startsWith("Not enough space to save this backup. Free up "))
        } finally {
            short.close()
        }
    }
}
