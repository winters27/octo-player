package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A save in groups leaves exactly the library today's swap leaves
// (TodaySwap.kt, with the one adoption TodayAdoptions.kt adds): from one
// start, copied twice, one copy is saved by the apply and the other by
// today's swap and its own totals rebuild, and every table compares equal
// by primary key (SaveOracle), the play totals included. Each case runs at
// the floor bound, where it takes many groups and runs, and at the default.
// The carve-outs each have their own check: the swap count rises once a
// group, no apply record is left, and the totals' account is the
// rebuild's.
@RunWith(RobolectricTestRunner::class)
class SaveOracleTest {
    private val server = "server:oracle.test"
    private val other = "server:oracle-other.test"
    private val account = "home"

    @Test
    fun anEverydayCopyWithARatingSinceTheClaimSavesAsTodaySwapsIt() = cases("everyday") { db ->
        db.put(library(DEVICE, 12))
        val copy = library(server, 30)
        db.put(copy)
        rebuildSettled(db, server)
        followingRows(db, Random(1), server)
        val next = copy.copy(
            tracks = copy.tracks.mapIndexedNotNull { i, t ->
                when {
                    i % 7 == 0 -> null
                    i % 5 == 0 -> t.copy(title = t.title + " (Live)", searchKey = t.searchKey + " live", relinkKey = t.relinkKey + " live")
                    i % 4 == 0 -> t.copy(rating = 4, playCount = 3, lastPlayedAt = 1_000L + i)
                    i % 6 == 0 -> t.copy(seq = 100L + i)
                    else -> t
                }
            } + (30 until 34).map { song(server, "s$it", "Song $it", "album3", no = it % 10 + 1) },
            albums = copy.albums.map { if (it.id.endsWith("album1")) it.copy(title = "Album One") else it },
        )
        db.stage(next, generation = 7)
        db.exec("INSERT INTO take_in_log (generation, kind, id) VALUES (7, 'track', ?)", "$server:s7")
        db.exec("INSERT INTO rating_note (sourceRowId, rating) VALUES (?, 5)", "$server:s3")
        db.exec("INSERT INTO rating_tap (sourceRowId, rating) VALUES (?, 2), (?, 3), (?, 4)", "$server:s14", "$server:s3", "$server:s7")
        Case(server, sinceClaim = { built ->
            // A rating tapped since the claim, on a song the copy changes.
            val song = built.stringOf("SELECT mergedId FROM source_track WHERE id = ?", "$server:s8")!!
            built.userDao().noteTap(song, 2, built.userDao().relinkKeyOf(song).orEmpty(), 5)
        })
    }

    @Test
    fun aServerSwitchSavesAsTodaySwapsIt() = cases("server switch") { db ->
        db.put(library(DEVICE, 4))
        val old = library(other, 20)
        db.put(old.copy(tracks = old.tracks + song(other, "dup", "Song 9", "album0", ms = 290_000)))
        rebuildSettled(db, other)
        for (id in listOf("$other:s9", "$other:dup")) {
            insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to "drake|song 9", "likedAt" to 1))
            insertRow(db, "track_rating", mapOf("trackId" to id, "relinkKey" to "drake|song 9", "rating" to 2, "ratedAt" to 1))
        }
        followingRows(db, Random(2), server)
        repeat(30) { db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, ?, ?, 1, 1)", "$other:s3", "drake|song 3", 500L + it) }
        db.stage(library(server, 22), generation = 3)
        Case(server)
    }

    // A liked song an everyday copy retitled, then a server switch whose
    // copy has the new title: the copy gave the like its song's new key, so
    // the switch finds the new server's song by it.
    @Test
    fun aLikedSongRetitledThenAServerSwitchKeepsTheLike() = cases("retitled, then a switch") { db ->
        db.put(library(DEVICE, 4))
        val old = library(other, 20)
        db.put(old)
        rebuildSettled(db, other)
        for (n in listOf(5, 6)) insertRow(db, "liked_track", mapOf("trackId" to "$other:s$n", "relinkKey" to "drake|song $n", "likedAt" to 1))
        fun retitled(source: SourceCatalog) = source.copy(
            tracks = source.tracks.map { if (it.nativeId == "s5") it.copy(title = "Song 5 (Remastered)", searchKey = "song 5 (remastered)", relinkKey = "drake|song 5 (remastered)") else it },
        )
        db.put(retitled(old))
        rebuildSettled(db, other)
        followingRows(db, Random(4), server)
        db.stage(retitled(library(server, 20)), generation = 3)
        Case(server) { saved, where ->
            assertEquals("$where: likes left on the old server's ids", 0L, saved.longOf("SELECT COUNT(*) FROM liked_track WHERE trackId IN (?, ?)", "$other:s5", "$other:s6"))
            assertEquals("$where: the retitled song's like", listOf("drake|song 5 (remastered)"), keysOf(saved, "liked_track", "$server:s5"))
            assertEquals("$where: the other song's like", listOf("drake|song 6"), keysOf(saved, "liked_track", "$server:s6"))
        }
    }

    // An everyday copy retitles a song the listener liked, rated, played
    // and put in a playlist: each of those rows takes the song's new key.
    @Test
    fun anEverydayCopyThatRetitlesASongGivesItsRowsTheNewKey() = cases("everyday retitle") { db ->
        db.put(library(DEVICE, 4))
        val copy = library(server, 20)
        db.put(copy)
        rebuildSettled(db, server)
        val song = "$server:s7"
        assertEquals("the song's id", song, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", song))
        listeningRows(db, song, "drake|song 7")
        followingRows(db, Random(5), server)
        val retitled = copy.copy(
            tracks = copy.tracks.map { if (it.nativeId == "s7") it.copy(title = "Song 7 (Live)", searchKey = "song 7 (live)", relinkKey = "drake|song 7 (live)") else it },
        )
        db.stage(retitled, generation = 3)
        Case(server) { saved, where ->
            for (table in LISTENING_TABLES) assertEquals("$where: $table", listOf("drake|song 7 (live)"), keysOf(saved, table, song))
        }
    }

    // A mass retag: every song filed under a new key, under a new album
    // and artist name. The listener's rows on the songs take the new keys.
    @Test
    fun aMassRetagGivesItsSongsRowsTheNewKeys() = cases("mass retag") { db ->
        val copy = library(server, 20)
        db.put(copy)
        rebuildSettled(db, server)
        val song = "$server:s12"
        assertEquals("the song's id", song, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", song))
        listeningRows(db, song, "drake|song 12")
        followingRows(db, Random(6), server)
        val retagged = copy.copy(
            tracks = copy.tracks.map { it.copy(album = "Views (Deluxe)", artist = "Drake Band", relinkKey = "drake band|views (deluxe)|${it.title.lowercase()}") },
            albums = copy.albums.map { it.copy(title = it.title + " (Deluxe)", searchKey = it.searchKey + " (deluxe)", sortKey = it.sortKey + " (deluxe)") },
            artists = copy.artists.map { it.copy(name = "Drake Band", searchKey = "drake band", sortKey = "drake band") },
        )
        db.stage(retagged, generation = 3)
        Case(server) { saved, where ->
            for (table in LISTENING_TABLES) assertEquals("$where: $table", listOf("drake band|views (deluxe)|song 12"), keysOf(saved, table, song))
        }
    }

    // A server switch: the phone's song keeps its id, but the old server's
    // copy, under whose key the listener's rows were kept, goes. No copy is
    // filed under that key any more, so the rows take the song's.
    @Test
    fun aServerSwitchGivesRowsKeptUnderTheOldServersKeyTheSongsKey() = cases("switch, the old copy's key") { db ->
        db.put(library(DEVICE, 4))
        val old = library(other, 20)
        db.put(old.copy(tracks = old.tracks.map { if (it.nativeId == "s2") it.copy(relinkKey = "drake|old|song 2") else it }))
        rebuildSettled(db, other)
        val phone = "$DEVICE:s2"
        assertEquals("the phone's song is its own", phone, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", phone))
        assertEquals("the old server's copy is the phone song's", phone, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", "$other:s2"))
        listeningRows(db, phone, "drake|old|song 2")
        followingRows(db, Random(7), server)
        db.stage(library(server, 20), generation = 3)
        Case(server) { saved, where ->
            for (table in LISTENING_TABLES) assertEquals("$where: $table", listOf("drake|song 2"), keysOf(saved, table, phone))
        }
    }

    // A server's song the listener liked joins a song the phone now has,
    // filed under the phone's key. While the server's copy is still filed
    // under the like's key the like keeps it, so it finds the server's copy
    // again if the phone's file goes. When the server's copy is retitled in
    // the same save, no copy holds the like's key and it takes the song's.
    @Test
    fun aLikeThatFollowsItsSongKeepsAKeyACopyHolds() = cases("a like follows, kept key") { db -> followed(db, retitled = false) }

    @Test
    fun aLikeThatFollowsItsSongTakesTheSongsKeyWhenNoCopyHoldsIts() = cases("a like follows, new key") { db -> followed(db, retitled = true) }

    private suspend fun followed(db: CatalogDatabase, retitled: Boolean): Case {
        val phone = library(DEVICE, 3)
        db.put(phone)
        val copy = library(server, 20)
        db.put(copy)
        rebuildSettled(db, server)
        val liked = "$server:s3"
        assertEquals("the server's song is its own", liked, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", liked))
        listeningRows(db, liked, "drake|song 3")
        followingRows(db, Random(if (retitled) 9 else 8), server)
        db.put(phone.copy(tracks = phone.tracks + song(DEVICE, "s3", "Song 3", "album0", ms = 200_003, no = 4, order = 3).copy(relinkKey = "drake|phone|song 3")))
        if (retitled) db.stage(copy.copy(tracks = copy.tracks.map { if (it.nativeId == "s3") it.copy(relinkKey = "drake|views|song 3") else it }), generation = 3)
        return Case(server) { saved, where ->
            assertEquals("$where: the server's copy joins the phone's song", "$DEVICE:s3", saved.stringOf("SELECT mergedId FROM source_track WHERE id = ?", liked))
            val key = if (retitled) "drake|phone|song 3" else "drake|song 3"
            for (table in LISTENING_TABLES) {
                assertEquals("$where: $table left on the server's id", emptyList<String>(), keysOf(saved, table, liked))
                assertEquals("$where: $table", listOf(key), keysOf(saved, table, "$DEVICE:s3"))
            }
        }
    }

    // A row on a kept song with a key neither the song nor a copy holds, as
    // rows were left before keys followed their songs: any save gives it the
    // song's.
    @Test
    fun aKeptSongsStaleKeyTakesTheSongsKey() = cases("stale key") { db ->
        db.put(library(DEVICE, 6))
        db.put(library(server, 6))
        rebuildSettled(db, server)
        val song = "$DEVICE:s4"
        assertEquals("the phone's song is its own", song, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", song))
        listeningRows(db, song, "drake|song 4 (old title)")
        val album = db.stringOf("SELECT albumId FROM track WHERE id = ?", song)!!
        val artist = db.stringOf("SELECT artistId FROM track WHERE id = ?", song)!!
        favourites(db, album, "album 0 (old title)", artist, "drake (old name)")
        Case(null, skipped = true) { saved, where ->
            for (table in LISTENING_TABLES) assertEquals("$where: $table", listOf("drake|song 4"), keysOf(saved, table, song))
            assertFavourites(saved, where, album, "album 0", artist, "drake")
        }
    }

    // A favorite album and artist, both pinned, an everyday copy retitled
    // (the album) and renamed (the artist), then a server switch whose copy
    // has the new names: the copy gave the favorites the new keys, so the
    // switch finds the new server's album and artist by them.
    @Test
    fun aFavouriteAlbumRetitledThenAServerSwitchKeepsIt() = cases("favorites retitled, then a switch") { db ->
        val old = library(other, 20)
        db.put(old)
        rebuildSettled(db, other)
        followingRows(db, Random(10), server)
        favourites(db, "$other:album1", "album 1", "$other:artist", "drake")
        db.put(renamed(old))
        rebuildSettled(db, other)
        assertFavourites(db, "after the everyday copy", "$other:album1", "album 1 (remastered)", "$other:artist", "drake band")
        db.stage(renamed(library(server, 20)), generation = 3)
        Case(server) { saved, where ->
            for (table in listOf("liked_album" to "albumId", "liked_artist" to "artistId", "pinned_item" to "itemId")) {
                assertEquals("$where: ${table.first} left on the old server's ids", 0L, saved.longOf("SELECT COUNT(*) FROM `${table.first}` WHERE `${table.second}` LIKE ?", "$other:%"))
            }
            assertFavourites(saved, where, "$server:album1", "album 1 (remastered)", "$server:artist", "drake band")
        }
    }

    // An everyday copy (staged) retitles a favorite album and renames a
    // favorite artist: the favorites and their pins take the new keys.
    @Test
    fun anEverydayCopyThatRenamesAnAlbumAndArtistGivesTheirFavouritesTheNewKeys() = cases("favorites, everyday rename") { db ->
        val copy = library(server, 20)
        db.put(copy)
        rebuildSettled(db, server)
        followingRows(db, Random(11), server)
        favourites(db, "$server:album1", "album 1", "$server:artist", "drake")
        db.stage(renamed(copy), generation = 3)
        Case(server) { saved, where -> assertFavourites(saved, where, "$server:album1", "album 1 (remastered)", "$server:artist", "drake band") }
    }

    // A server's album the listener liked and pinned joins an album the
    // phone now has under another name (its bracketed extra aside). While
    // the server's copy is still filed under the favorite's key the favorite
    // keeps it, so it finds the server's album again if the phone's files
    // go; when that copy is retitled in the same save, it takes the album's.
    @Test
    fun aFavouriteAlbumThatFollowsKeepsAKeyACopyHolds() = cases("a favorite follows, kept key") { db -> followedAlbum(db, retitled = false) }

    @Test
    fun aFavouriteAlbumThatFollowsTakesTheAlbumsKeyWhenNoCopyHoldsIts() = cases("a favorite follows, new key") { db -> followedAlbum(db, retitled = true) }

    private suspend fun followedAlbum(db: CatalogDatabase, retitled: Boolean): Case {
        val phone = library(DEVICE, 3)
        db.put(phone)
        val copy = library(server, 20)
        db.put(copy)
        rebuildSettled(db, server)
        val liked = "$server:album1"
        assertEquals("the server's album is its own", liked, db.stringOf("SELECT albumId FROM track WHERE id = ?", "$server:s10"))
        followingRows(db, Random(if (retitled) 13 else 12), server)
        favourites(db, liked, "album 1", null, null)
        db.put(
            phone.copy(
                tracks = phone.tracks + song(DEVICE, "s10", "Song 10", "album1", ms = 200_010, no = 1, order = 0),
                albums = phone.albums + albumRow(DEVICE, "album1", "Album 1 (Deluxe)"),
            ),
        )
        if (retitled) {
            db.stage(copy.copy(albums = copy.albums.map { if (it.nativeId == "album1") it.copy(title = "Album 1 (Remaster)", searchKey = "album 1 (remaster)", sortKey = "album 1 (remaster)") else it }), generation = 3)
        }
        return Case(server) { saved, where ->
            val album = "$DEVICE:album1"
            assertEquals("$where: the server's album joins the phone's", album, saved.stringOf("SELECT albumId FROM track WHERE id = ?", "$DEVICE:s10"))
            assertEquals("$where: the phone's album's key", "album 1 (deluxe)", saved.stringOf("SELECT searchKey FROM album WHERE id = ?", album))
            val key = if (retitled) "album 1 (deluxe)" else "album 1"
            assertEquals("$where: the liked album", key, saved.stringOf("SELECT relinkKey FROM liked_album WHERE albumId = ?", album))
            assertEquals("$where: the pinned album", key, saved.stringOf("SELECT relinkKey FROM pinned_item WHERE kind = 'album' AND itemId = ?", album))
        }
    }

    // A source with album1 retitled and its artist renamed.
    private fun renamed(source: SourceCatalog) = source.copy(
        tracks = source.tracks.map { it.copy(artist = "Drake Band", album = if (it.albumId.endsWith(":album1")) "Album 1 (Remastered)" else it.album) },
        albums = source.albums.map {
            val retitled = if (it.nativeId == "album1") it.copy(title = "Album 1 (Remastered)", searchKey = "album 1 (remastered)", sortKey = "album 1 (remastered)") else it
            retitled.copy(artist = "Drake Band")
        },
        artists = source.artists.map { it.copy(name = "Drake Band", searchKey = "drake band", sortKey = "drake band") },
    )

    // A favorite album and artist, each pinned too, under these keys, in
    // place of any rows of theirs already there.
    private fun favourites(db: CatalogDatabase, album: String, albumKey: String?, artist: String?, artistKey: String?) {
        db.exec("INSERT OR REPLACE INTO liked_album (albumId, relinkKey, likedAt) VALUES (?, ?, 1)", album, albumKey)
        db.exec("INSERT OR REPLACE INTO pinned_item (kind, itemId, relinkKey, position) VALUES ('album', ?, ?, 0)", album, albumKey)
        if (artist == null) return
        db.exec("INSERT OR REPLACE INTO liked_artist (artistId, relinkKey, likedAt) VALUES (?, ?, 1)", artist, artistKey)
        db.exec("INSERT OR REPLACE INTO pinned_item (kind, itemId, relinkKey, position) VALUES ('artist', ?, ?, 1)", artist, artistKey)
    }

    private fun assertFavourites(db: CatalogDatabase, where: String, album: String, albumKey: String, artist: String, artistKey: String) {
        assertEquals("$where: the liked album", albumKey, db.stringOf("SELECT relinkKey FROM liked_album WHERE albumId = ?", album))
        assertEquals("$where: the pinned album", albumKey, db.stringOf("SELECT relinkKey FROM pinned_item WHERE kind = 'album' AND itemId = ?", album))
        assertEquals("$where: the liked artist", artistKey, db.stringOf("SELECT relinkKey FROM liked_artist WHERE artistId = ?", artist))
        assertEquals("$where: the pinned artist", artistKey, db.stringOf("SELECT relinkKey FROM pinned_item WHERE kind = 'artist' AND itemId = ?", artist))
    }

    // A like, a rating, two plays, a playlist row and its edit, on `song`
    // under `key`.
    private fun listeningRows(db: CatalogDatabase, song: String, key: String) {
        insertRow(db, "liked_track", mapOf("trackId" to song, "relinkKey" to key, "likedAt" to 1))
        insertRow(db, "track_rating", mapOf("trackId" to song, "relinkKey" to key, "rating" to 4, "ratedAt" to 1))
        repeat(2) { insertRow(db, "play_event", mapOf("trackId" to song, "relinkKey" to key, "startedAt" to 900L + it, "playedMs" to 1, "durationMs" to 1)) }
        insertRow(db, "playlist", mapOf("id" to "mine", "name" to "Mine", "createdAt" to 1, "updatedAt" to 1))
        insertRow(db, "playlist_item", mapOf("playlistId" to "mine", "itemSet" to 0, "trackId" to song, "relinkKey" to key, "ord" to 1, "itemKey" to 900))
        insertRow(db, "playlist_edit_log", mapOf("playlistId" to "mine", "itemSet" to 0, "op" to 0, "itemKey" to 900, "ord" to 1, "trackId" to song, "relinkKey" to key))
    }

    // The distinct keys `table`'s rows on `song` hold.
    private fun keysOf(db: CatalogDatabase, table: String, song: String): List<String> =
        db.query("SELECT DISTINCT relinkKey FROM `$table` WHERE trackId = ? ORDER BY relinkKey", arrayOf(song)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun aPhoneScanSavesAsTodaySwapsIt() = cases("phone scan") { db ->
        val phone = library(DEVICE, 16)
        db.put(phone)
        db.put(library(server, 16))
        rebuildSettled(db, server)
        followingRows(db, Random(3), server)
        db.put(
            phone.copy(
                tracks = phone.tracks.filterIndexed { i, _ -> i % 3 != 0 }
                    .map { if (it.id == "$DEVICE:s4") it.copy(title = "Song Four", relinkKey = "drake|song four") else it },
            ),
        )
        Case(server)
    }

    @Test
    fun rowsThatMeetOnOneSongSaveAsTodaySwapsThem() = cases("rows that meet") { db ->
        fun source(id: String, ms: Long, extra: Int) = SourceCatalog(
            id, id == DEVICE,
            listOf(song(id, "s0", "Song 0", "album0", ms = ms)) + (1..extra).map { song(id, "s$it", "Song ${it + 10 * extra}", "album1", ms = 230_000L + it) },
            listOf(albumRow(id, "album0", "Album 0"), albumRow(id, "album1", "Album ${extra + 1}")),
            listOf(artistRow(id)),
        )
        val phone = source(DEVICE, 200_000, 1)
        db.put(phone.copy(tracks = phone.tracks.drop(1), albums = phone.albums.drop(1)))
        db.put(source(server, 198_000, 2))
        db.put(source("usb", 202_000, 3))
        rebuildSettled(db, server)
        val x1 = "$server:s0"
        val x2 = "usb:s0"
        for (id in listOf(x2, x1)) {
            insertRow(db, "liked_track", mapOf("trackId" to id, "relinkKey" to "drake|song 0", "likedAt" to 1))
            insertRow(db, "track_rating", mapOf("trackId" to id, "relinkKey" to "drake|song 0", "rating" to if (id == x1) 3 else 4, "ratedAt" to 1))
            insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to id.length.toLong(), "trackId" to id, "listId" to 1))
        }
        insertRow(db, "queue_entry", mapOf("queueId" to 1, "entry" to 99, "trackId" to x2, "listId" to 2))
        for (album in listOf("usb:album0", "$server:album0")) {
            insertRow(db, "liked_album", mapOf("albumId" to album, "relinkKey" to "album 0", "likedAt" to 1))
            insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to album, "relinkKey" to "album 0", "position" to album.length))
        }
        insertRow(db, "online_song", mapOf("id" to "find:1", "sourceId" to server, "nativeId" to "1", "title" to "F", "artist" to "A", "album" to "", "durationMs" to 1, "seenAt" to 1, "adoptedId" to x1))
        db.put(phone)
        Case(server, adoptions = true)
    }

    @Test
    fun aSkippedMergeSavesAsTodaySkipsIt() = cases("skipped") { db ->
        db.put(library(DEVICE, 6))
        db.put(library(server, 6))
        rebuildSettled(db, server)
        insertRow(db, "liked_track", mapOf("trackId" to "$server:s2", "relinkKey" to "drake|song 2", "likedAt" to 1))
        insertRow(db, "play_event", mapOf("trackId" to "gone:9", "relinkKey" to "drake|song 4", "startedAt" to 5, "playedMs" to 1, "durationMs" to 1))
        insertRow(db, "track_rating", mapOf("trackId" to "$server:s5", "relinkKey" to "drake|song 5", "rating" to 4, "ratedAt" to 1))
        db.exec("UPDATE track SET rating = 0 WHERE id = ?", "$DEVICE:s5")
        insertRow(db, "pinned_item", mapOf("kind" to "album", "itemId" to "gone:album", "relinkKey" to "album 0", "position" to 0))
        Case(null, skipped = true)
    }

    @Test
    fun seededLibrariesSaveAsTodaySwapsThem() = guarded(300) {
        for (seed in listOf(41L, 42L, 43L)) {
            println("SaveOracleTest seed $seed")
            run("seed $seed") { db ->
                val (phone, copy) = MergeRowsGen.sources(seed, 240)
                db.put(phone)
                db.put(copy)
                rebuildSettled(db, MergeRowsGen.SERVER)
                followingRows(db, Random(seed), MergeRowsGen.SERVER)
                db.stage(MergeRowsGen.secondCopy(seed + 100, copy), generation = 9)
                Case(MergeRowsGen.SERVER)
            }
        }
    }

    // How a case saves: the server in use, whether the merge is a skipped
    // one, whether today's adoptions differ from today's swap there (a named
    // case makes that difference real), what the case writes once the claim
    // is built, on both copies alike, and what it holds the saved library to.
    private class Case(
        val server: String?,
        val skipped: Boolean = false,
        val adoptions: Boolean = false,
        val sinceClaim: suspend (CatalogDatabase) -> Unit = {},
        val expect: suspend (CatalogDatabase, String) -> Unit = { _, _ -> },
    )

    private companion object {
        // The listener's song rows whose keys follow their songs.
        val LISTENING_TABLES = listOf("liked_track", "track_rating", "play_event", "playlist_item", "playlist_edit_log")
    }

    private fun cases(label: String, start: suspend (CatalogDatabase) -> Case) = guarded { run(label, start) }

    private suspend fun run(label: String, start: suspend (CatalogDatabase) -> Case) {
        val db = openTestCatalog()
        try {
            val case = start(db)
            db.settleTotals(account)
            for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) check(db, case, bound, "$label, bound $bound")
        } finally {
            db.close()
        }
    }

    private suspend fun check(start: CatalogDatabase, case: Case, bound: Long, where: String) {
        val ours = copyCatalog(start)
        val today = copyCatalog(start)
        try {
            val hooks = GroupHooks(ours)
            val merge = ours.boundMerge(case.server, bound, hooks)
            val countBefore = ours.workDao().swapCount()
            if (case.skipped) {
                merge.saveSkipped()
                todaySkipped(today)
            } else {
                val (_, claim) = claimAndBuild(ours, case.server)
                case.sinceClaim(ours)
                merge.saveBuilt(claim.sources, claim.staged, case.server.orEmpty())
                val (todayClaim, _) = claimAndBuild(today, case.server)
                case.sinceClaim(today)
                todaySwap(today, todayClaim)
                val moved = todayAdoptions(today)
                if (case.adoptions) assertTrue("$where: the adoption case follows no adoption", moved > 0)
            }
            todayKeys(today)
            case.expect(ours, where)
            // Today's own rebuild of the totals after its swap.
            today.settleTotals(account)
            val ourImage = SaveOracle.image(ours.openHelper.writableDatabase)
            val todayImage = SaveOracle.image(today.openHelper.writableDatabase)
            val differences = SaveOracle.differences(todayImage, ourImage)
            val named = differences.take(3).flatMap { line -> Regex("""[\w.-]+:[\w.:-]+""").findAll(line).map { it.value }.toList() }
            val queued = { db: CatalogDatabase ->
                named.flatMap { id ->
                    db.query("SELECT rowid, queueId, entry, listId, trackId FROM queue_entry WHERE trackId = ?", arrayOf(id)).use { c ->
                        buildList { while (c.moveToNext()) add((0 until 5).joinToString("/") { c.getString(it) ?: "-" }) }
                    }
                }
            }
            assertTrue(
                "$where: ${differences.joinToString("\n")}\nplanned ${hooks.plannedFor(named).take(30)}\nqueued, ours ${queued(ours)}, today ${queued(today)}",
                differences.isEmpty(),
            )
            assertTrue("$where: nothing was compared", ourImage.tables.getValue("track").rows.isNotEmpty())
            // The carve-outs.
            val turns = hooks.saves.single()
            assertEquals("$where: the swap count rises once a group", countBefore + turns, ours.workDao().swapCount())
            assertNull("$where: an apply record is left", ours.stringOf("SELECT applyClaim FROM merge_state WHERE id = 0"))
            assertEquals("$where: the totals' account", account, ours.stringOf("SELECT totalsAccount FROM merge_state WHERE id = 0"))
            assertEquals("$where: found rows left", 0L, ours.longOf("SELECT COUNT(*) FROM save_found") + ours.longOf("SELECT COUNT(*) FROM save_ref"))
            if (bound == SAVE_GROUP_FLOOR && !case.skipped) assertTrue("$where: $turns groups at the floor", turns > 1)
        } finally {
            ours.close()
            today.close()
        }
    }
}
