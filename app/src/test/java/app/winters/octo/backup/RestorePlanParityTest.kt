package app.winters.octo.backup

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.PinKind
import app.winters.octo.listening.timed
import app.winters.octo.playlists.inLibraryOrder
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import kotlin.random.Random

// A labeled pin. A backup's plan counted from its job equals today's
// planRestore over the whole library (Backup.kt at c2208eaa; ties go to
// the first song in library order, declared): the same describePlan lines,
// favorites, pins, kept playlists and lyrics timing. Songs by relink key,
// by title, artist and album with the closest length, by title and artist
// alone; repeats, blanks, ratings out of range, playlists already here.
@RunWith(RobolectricTestRunner::class)
class RestorePlanParityTest {
    private val rig = BackupRig(openTestCatalog())

    @After
    fun close() = rig.close()

    @Test
    fun aPlanIsTodays() = timed(120) {
        val r = Random(13)
        val tracks = (0 until 500).map { backupTrack("t$it", "Song ${it % 60}", "Artist ${it % 7}", "Album ${it % 11}", 180_000L + (it % 9) * 3_000, relinkKey = "rk${it % 450}") }
        rig.library(tracks)
        runBlocking {
            rig.db.dao().insertAlbums((0 until 11).map { AlbumEntity("al$it", "device", "al$it", "Album $it", "album $it", "album $it", "Artist ${it % 7}", "ar", null, 1, 1, 0, null) })
            rig.db.dao().insertArtists((0 until 7).map { ArtistEntity("ar$it", "device", "Artist $it", "artist $it", "artist $it", 1, 1, null) })
        }
        fun key(): SongKey {
            val t = tracks[r.nextInt(tracks.size)]
            return when (r.nextInt(5)) {
                0 -> SongKey(relinkKey = t.relinkKey)
                1 -> SongKey("", t.title, t.artist, t.album, t.durationMs + r.nextInt(-5, 5) * 1_000L)
                2 -> SongKey("", t.title, t.artist, "Elsewhere", if (r.nextBoolean()) 0 else t.durationMs)
                3 -> SongKey("", if (r.nextBoolean()) "" else "Nowhere", t.artist)
                else -> SongKey("no-such-key", "Song 999", "Nobody")
            }
        }
        val backup = Backup(
            playlists = listOf(
                PlaylistBackup("Road trip", List(300) { key() }),
                PlaylistBackup(" old ONE ", List(20) { key() }),
                PlaylistBackup("Nothing", emptyList()),
            ),
            likes = List(700) { key() },
            ratings = List(400) { RatedSong(key(), r.nextInt(0, 8)) },
            favouriteAlbums = listOf(HeldKey("album 3", "x"), HeldKey("", "Album 4", "Artist 4"), HeldKey("", "Gone", "Nobody")),
            favouriteArtists = listOf(HeldKey("artist 2"), HeldKey("", "Artist 5")),
            pins = listOf(PinBackup(PinKind.Album.id, HeldKey("album 1")), PinBackup(PinKind.Playlist.id, HeldKey(name = "road TRIP")), PinBackup(PinKind.Playlist.id, HeldKey(name = "absent"))),
            lyrics = LyricsBackup(offsets = List(30) { LyricsOffsetBackup(key(), r.nextLong(-2_000, 2_000)) }),
            offline = OfflineBackup(keptPlaylists = listOf("Road trip", "absent", "ROAD TRIP")),
        )
        val existing = listOf("old one", "Mine")
        val albums = rig.db.query("SELECT id, searchKey, title, artist FROM album", null).use { c -> buildList { while (c.moveToNext()) add(Named(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
        val artists = rig.db.query("SELECT id, searchKey, name FROM artist", null).use { c -> buildList { while (c.moveToNext()) add(Named(c.getString(0), c.getString(1), c.getString(2))) } }
        val today = planRestore(backup, tracks.inLibraryOrder(), existing, albums, artists)

        val opened = runBlocking { rig.songs.read { ByteArrayInputStream(encodeBackup(backup).toByteArray()) } } as BackupOpened.Opened
        val plan = runBlocking { rig.songs.plan(opened.file, existing) }
        assertEquals(describePlan(today), describePlan(plan))
        assertEquals(describeBackup(backup), describeBackup(opened.file.header, opened.file.sizes))
        assertEquals(today.favouriteAlbums, plan.favouriteAlbums)
        assertEquals(today.favouriteArtists, plan.favouriteArtists)
        assertEquals(today.pins, plan.pins)
        assertEquals(today.keptPlaylists, plan.keptPlaylists)
        assertEquals(today.lyricsOffsets, plan.lyricsOffsets)
        assertEquals(today.playlists.map { Triple(it.name, it.trackIds.size, it.total) }, plan.playlists.map { Triple(it.name, it.found, it.total) })
        runBlocking { rig.songs.close(opened.file) }
    }
}
