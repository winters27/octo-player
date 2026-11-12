package app.winters.octo.server

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.withKeys
import app.winters.octo.server.today.todayBuildServerCatalog
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.Starred
import app.winters.octo.subsonic.readLibrary
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val SOURCE = "server:music.test"
private const val ACCOUNT = "acc-1"

// The album-by-album copy builds exactly today's rows, in today's order,
// from what today's reader reads off the same server.
@RunWith(RobolectricTestRunner::class)
class ServerCatalogParityTest {
    private fun assertSameRows(library: Library) = runBlocking {
        val fake = FakeSubsonic(library, Starred())
        try {
            val today = todayBuildServerCatalog(SOURCE, fake.client().readLibrary())
            // The shared helpers give today's rows on the in-memory path too.
            val inMemory = buildServerCatalog(SOURCE, fake.client().readLibrary())
            assertEquals(today.tracks, inMemory.tracks)
            assertEquals(today.albums, inMemory.albums)
            assertEquals(today.artists, inMemory.artists)

            val db = openTestCatalog()
            try {
                val copy = StagedCopy(db, db.testMerge(SOURCE), FreeSpace { Long.MAX_VALUE })
                val generation = copy.start(ACCOUNT, SOURCE)
                val counts = copy.fill(ACCOUNT, SOURCE, generation, fake.client())
                val dao = db.copyDao()
                val tracks = dao.stageTracks(SOURCE).map { it.row }
                val albums = dao.stageAlbums(SOURCE).map { it.row }
                val artists = dao.stageArtists(SOURCE).map { it.row }

                // In today's order, their places rising and spaced for songs
                // that come between them later.
                assertTrue(tracks.zipWithNext().all { (a, b) -> a.seq < b.seq })
                assertEquals(today.tracks, tracks.map { it.copy(seq = 0, titleKey = "", matchKey = "", keysVersion = 0) })
                assertEquals(today.tracks.map { it.withKeys() }.map { Triple(it.titleKey, it.matchKey, it.keysVersion) }, tracks.map { Triple(it.titleKey, it.matchKey, it.keysVersion) })
                assertEquals(today.albums, albums.map { it.copy(seq = 0) })
                assertTrue(albums.zipWithNext().all { (a, b) -> a.seq < b.seq })
                assertEquals(today.artists, artists.map { it.copy(seq = 0) })
                assertTrue(artists.zipWithNext().all { (a, b) -> a.seq < b.seq })
                assertEquals(CopyCounts(today.tracks.size, today.albums.size, today.artists.size), counts)
                // Built songs leave the copy's pages as they go.
                assertEquals(0, dao.syncSongCount())
            } finally {
                db.close()
            }
        } finally {
            fake.server.close()
        }
    }

    private fun song(
        id: String,
        title: String = "Song $id",
        albumId: String? = "al1",
        album: String? = "Album One",
        track: Int? = null,
        disc: Int? = null,
        artist: String? = "Band",
        year: Int? = null,
        created: String? = null,
        cover: String? = "mf-$id",
        duration: Int = 200,
    ) = Song(
        id = id, title = title, albumId = albumId, album = album, track = track, discNumber = disc, artist = artist,
        year = year, created = created, coverArt = cover, duration = duration,
    )

    private fun album(id: String, name: String = "Album $id", artistId: String? = "ar1", artist: String = "Band", songCount: Int = 1) =
        Album(id = id, name = name, artist = artist, artistId = artistId, coverArt = "al-$id", songCount = songCount, duration = 200, year = 2020, created = "2026-01-0${id.length % 9 + 1}T00:00:00Z")

    @Test
    fun aGeneratedLibraryBuildsTodaysRows() = assertSameRows(LibraryGen.server(3, GenShape(songs = 3_000, albums = 200, artists = 50)))

    @Test
    fun aSecondCopyWithReorderedAndChangedRowsBuildsTodaysRows() =
        assertSameRows(LibraryGen.secondCopy(4, LibraryGen.server(3, GenShape(songs = 3_000, albums = 200, artists = 50))))

    @Test
    fun albumsSharingAKeySonglessListedAlbumsRepeatsAndAlbumlessSongs() = assertSameRows(
        Library(
            songs = listOf(
                song("s1", albumId = null, album = "Greatest Hits", artist = "A", year = 1999, created = "2026-02-01T00:00:00Z"),
                song("s2", albumId = null, album = "Greatest Hits", artist = "B", created = "2026-03-01T00:00:00Z"),
                song("s3", albumId = null, album = null, artist = "C"),
                song("s4", albumId = "al1", track = 2),
                song("s4", albumId = "al1", track = 2),
                song("s5", albumId = "al1", track = 1),
                song("s6", albumId = "unlisted", album = "Late", track = 1, year = 2001),
            ),
            albums = listOf(album("al1", songCount = 2), album("empty", songCount = 0), album("al1")),
            artists = listOf(Artist(id = "ar1", name = "The Band", musicBrainzId = "0a1b2c3d-0000-4000-8000-000000000009")),
        ),
    )

    @Test
    fun tiesKeepTheOrderTheSongsCameIn() = assertSameRows(
        Library(
            songs = listOf(
                song("t3", title = "Same", track = 1, disc = 1),
                song("t1", title = "Same", track = 1, disc = 1),
                song("t2", title = "same", track = 1, disc = 1),
                song("t4", title = "Other", track = null),
                song("t5", title = "Other", track = null),
            ),
            albums = listOf(album("al1", songCount = 5)),
            artists = emptyList(),
        ),
    )

    @Test
    fun unlistedArtistsAndAlbumsWithoutAnArtistId() = assertSameRows(
        Library(
            songs = listOf(song("u1", albumId = "x1"), song("u2", albumId = "x2"), song("u3", albumId = "x3")),
            albums = listOf(album("x1", artistId = "ar-missing"), album("x2", artistId = null, artist = "Nobody Listed"), album("x3", artistId = "", artist = "")),
            artists = listOf(Artist(id = "ar1", name = "Listed")),
        ),
    )

    @Test
    fun giantAlbumsListedAndUnlistedWithEmojiTitles() {
        val emoji = listOf("🎵", "😀", "Ω", "é", "a", "Z")
        val listed = (1..5_000).map { i ->
            song(
                "g$i",
                title = "${emoji[i % emoji.size]} Track ${i % 37}",
                albumId = "big",
                album = "Big Box",
                track = if (i % 5 == 0) null else i % 300,
                disc = if (i % 7 == 0) null else i % 3 + 1,
            )
        }
        val unlisted = (1..2_500).map { i ->
            song("h$i", title = "${emoji[i % emoji.size]} Piece", albumId = "big-unlisted", album = "Loose", track = i % 2_000, year = if (i < 10) null else 1990 + i % 20, created = "2026-0${i % 9 + 1}-01T00:00:00Z")
        }
        assertSameRows(
            Library(
                songs = (listed + unlisted + song("small1", albumId = "al1", track = 1)).shuffled(java.util.Random(7)),
                albums = listOf(album("big", songCount = 5_000), album("al1")),
                artists = emptyList(),
            ),
        )
    }
}
