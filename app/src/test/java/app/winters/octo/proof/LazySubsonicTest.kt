package app.winters.octo.proof

import app.winters.octo.server.NewMusic
import app.winters.octo.server.newMusic
import app.winters.octo.subsonic.AlbumListType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The proof server's fixtures, at a size that runs in a moment.
class LazySubsonicTest {
    // A library half of whose songs are in one "Unknown album", untagged,
    // answered whole by the album's own page.
    @Test
    fun theUnknownAlbumHoldsHalfTheLibrary() = runBlocking {
        val library = LazyLibrary(songs = 2_000, albums = 101, artists = 10, unknownAlbumSongs = 1_000)
        val fake = LazySubsonic(library, starredSongs = 0)
        val client = fake.client()
        val unknown = client.albumList(AlbumListType.ALPHABETICAL, 500, 0).single { it.name == "Unknown album" }
        assertEquals(1_000, unknown.songCount)
        val songs = client.album(unknown.id).song
        assertEquals((1_000 until 2_000).map { "s$it" }, songs.map { it.id })
        assertTrue(songs.all { it.track == null && it.albumId == unknown.id })
        // The other albums share the other songs evenly.
        assertEquals(10, client.album("al0").song.size)
        assertEquals(2_000, (0 until 4).flatMap { client.songPage(500, it * 500) }.map { it.id }.toSet().size)
        fake.close()
    }

    // Playlists come entry by entry: the long one with every kind of entry,
    // the family one with repeats.
    @Test
    fun playlistsCarryEveryKindOfEntry() = runBlocking {
        val library = LazyLibrary(songs = 1_000, albums = 100, artists = 10)
        val fake = LazySubsonic(
            library,
            starredSongs = 0,
            playlists = listOf(LazyPlaylist.mixed(library, "big", "Big", 2_000), LazyPlaylist.family(library, "fam", "Family", 1_000)),
        )
        val client = fake.client()
        assertEquals(listOf("big" to 2_000, "fam" to 1_000), client.playlists().map { it.id to it.songCount })
        assertEquals(true, client.playlists().single { it.id == "fam" }.octoFamily)
        val big = client.playlist("big").entry
        assertEquals(2_000, big.size)
        assertEquals(1_000, big.filterIndexed { k, s -> k % 20 < 10 && s.id == "s1" }.size)
        assertEquals(300, big.count { it.isExternal })
        assertEquals(200, big.count { it.id.startsWith("pt") })
        val family = client.playlist("fam").entry
        assertEquals(1_000, family.size)
        assertEquals(700, family.map { it.id }.toSet().size)
        // A new playlist is answered in full, under its own id.
        val made = client.createPlaylist("New", listOf("s1", "s2"))
        assertEquals("created-1", made?.id)
        assertEquals(2_000, made?.entry?.size)
        fake.close()
    }

    // A saved queue in both forms, and the album found online.
    @Test
    fun theSavedQueueAndTheOnlineAlbum() = runBlocking {
        val library = LazyLibrary(songs = 1_000, albums = 100, artists = 10)
        val fake = LazySubsonic(library, starredSongs = 0, playQueue = LazyPlayQueue.of(library, size = 700, current = 600), onlineAlbumSongs = 40)
        val client = fake.client()
        val queue = client.playQueue()!!
        assertEquals(700, queue.entry.size)
        assertEquals(queue.entry[600].id, queue.current)
        assertEquals(61_000L, queue.position)
        assertEquals("Feishin", queue.changedBy)
        assertEquals(600, client.playQueueByIndex()!!.currentIndex)
        val found = client.search("Found online", artists = 0, albums = 20, songs = 0).album.single()
        assertEquals(ONLINE_ALBUM_ID, found.id)
        assertTrue(found.isExternal)
        val online = client.album(ONLINE_ALBUM_ID).song
        assertEquals(40, online.size)
        assertTrue(online.all { it.isExternal })
        fake.close()
    }

    // After the change, the server says it read its folders again, lists
    // 100 songs fewer and 100 more in 10 new albums ahead of every other
    // album in newest order, and 2,000 titles differ. A look at the newest
    // 30 albums takes in exactly the 10 new ones.
    @Test
    fun theChangeIsWhatALookAndARecopyFind() = runBlocking {
        val library = LazyLibrary(songs = 20_000, albums = 2_000, artists = 500)
        val fake = LazySubsonic(library, starredSongs = 100)
        val client = fake.client()
        suspend fun everySong() = (0 until 41).flatMap { client.songPage(500, it * 500) }
        val before = everySong()
        val newestBefore = client.albumList(AlbumListType.NEWEST, 30)
        val kept = newestBefore.associate { it.id to it.songCount } + (0 until 2_000).associate { "al$it" to 10 }
        assertEquals("2026-11-01T00:00:00Z", client.scanStatus().lastScan)

        library.changed = true
        assertEquals("2026-11-02T00:00:00Z", client.scanStatus().lastScan)
        val after = everySong()
        assertEquals(before.size, after.size)
        val beforeIds = before.map { it.id }.toSet()
        val afterIds = after.map { it.id }.toSet()
        assertEquals((19_900 until 20_000).map { "s$it" }.toSet(), beforeIds - afterIds)
        assertEquals((20_000 until 20_100).map { "s$it" }.toSet(), afterIds - beforeIds)
        val titles = before.associate { it.id to it.title }
        assertEquals(2_000, after.count { it.id in titles && titles[it.id] != it.title })
        assertNull(after.firstOrNull { it.id == "s19950" })

        val newest = client.albumList(AlbumListType.NEWEST, 30)
        assertEquals((2_000 until 2_010).map { "al$it" }, newest.take(10).map { it.id })
        val look = newMusic(newest, kept)
        assertTrue(look is NewMusic.TakeIn)
        assertEquals(newest.take(10), (look as NewMusic.TakeIn).albums)
        assertEquals(10, client.album("al2003").song.size)
        // The album the last songs came out of answers only those left.
        assertEquals(0, client.album("al1999").song.size)
        assertEquals(100, client.starred().song.size)
        fake.close()
    }
}
