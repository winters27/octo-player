package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.ordAt
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.data.Drains
import app.winters.octo.playback.tracksByIds
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import java.io.Closeable
import java.nio.file.Files
import kotlin.random.Random

internal const val SOURCE = "server:h"
internal const val NOW = 1_800_000_000_000L

// A library and a server's songs, generated from a seed, holding every
// kind of entry a playlist sync meets: songs the library has from the
// server, songs the server marks as outside the library (some with a stale
// link), songs found by title only, finds, one song a playlist holds many
// times, ids holding a line break or empty, and titles and ids with
// characters past the basic plane (U+1F600) and U+FFFD, so an order
// compared in UTF-16 would differ from SQLite's.
internal class PlaylistWorld(seed: Long, songs: Int = 60) {
    private val random = Random(seed)
    val tracks = ArrayList<TrackEntity>()
    val copies = ArrayList<SourceTrackEntity>()
    val serverSongs = LinkedHashMap<String, Song>()
    val linked = ArrayList<String>()
    val outside = ArrayList<String>()
    val byTitle = ArrayList<String>()
    val finds = ArrayList<String>()
    val odd = listOf("odd\nid", "")
    val phoneOnly = ArrayList<String>()
    val hot: String

    init {
        for (i in 0 until songs) {
            val id = if (i % 13 == 0) "t😀$i" else if (i % 11 == 0) "t�$i" else "t$i"
            val title = when (i % 9) {
                0 -> "Café $i 😀"
                1 -> "Song $i (Live)"
                else -> "Song ${i / 2}"
            }
            val artist = "Artist ${i % 5}"
            val seconds = 100 + (i % 7) * 30
            tracks += track(id, title, artist, seconds)
            if (i % 6 == 5) {
                phoneOnly += id
                continue
            }
            val native = if (i % 13 == 0) "s😀$i" else "s$i"
            copies += copy(native, id, title, artist, seconds)
            if (i % 4 == 3) copies += copy("${native}b", id, title, artist, seconds)
            linked += native
            serverSongs[native] = Song(native, title, album = "Album ${i % 3}", albumId = "al${i % 3}", artist = artist, duration = seconds, suffix = "flac")
        }
        for (j in 0 until songs / 4) {
            val like = tracks[random.nextInt(tracks.size)]
            val seconds = (like.durationMs / 1000).toInt()
            // Outside the library, a third of them with a link an older copy left.
            val x = "x$j"
            serverSongs[x] = Song(x, like.title, artist = like.artist, duration = seconds, isExternal = true, coverArt = "cx$j")
            if (j % 3 == 0) copies += copy(x, like.id, like.title, like.artist, seconds)
            outside += x
            // Found by title only, sometimes with a length a minute off, or none.
            val u = "u$j"
            val length = when (j % 4) {
                0 -> seconds + 60
                1 -> 180
                else -> seconds
            }
            serverSongs[u] = Song(u, like.title, artist = like.artist, duration = length)
            byTitle += u
            val f = "f$j"
            serverSongs[f] = Song(f, "Lost $j", album = "Lost", albumId = "lostAl", artist = "Nobody", duration = 200 + j, coverArt = "cf$j")
            finds += f
        }
        odd.forEach { serverSongs[it] = Song(it, "Odd ${it.length}", artist = "Odd", duration = 99) }
        hot = linked.firstOrNull() ?: finds.first()
        for (k in 0 until songs / 6) {
            val id = "d$k"
            tracks += track(id, "Mine $k", "Me", 150)
            phoneOnly += id
        }
    }

    private fun track(id: String, title: String, artist: String, seconds: Int) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = searchKey(title), sortKey = sortKey(title),
        artist = artist, artistId = "a:$artist", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = seconds * 1000L, addedAt = 0, mimeType = null, sizeBytes = null, artwork = "art:$id", uri = null,
        relinkKey = "k:$id",
    )

    private fun copy(native: String, trackId: String, title: String, artist: String, seconds: Int) = SourceTrackEntity(
        id = "$SOURCE:$native", sourceId = SOURCE, nativeId = native, title = title, searchKey = searchKey(title), sortKey = sortKey(title),
        artist = artist, artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null, durationMs = seconds * 1000L,
        addedAt = 0, mimeType = "audio/flac", sizeBytes = 10, artwork = null, uri = null, albumOrder = 0, relinkKey = "k:$trackId",
        genre = "", mergedId = trackId,
    )

    // A server playlist's songs: every kind, one song many times.
    fun entries(n: Int): List<String> = List(n) {
        when (random.nextInt(20)) {
            in 0..8 -> linked.ifEmpty { finds }.random(random)
            9, 10 -> outside.random(random)
            11, 12, 13 -> byTitle.random(random)
            14, 15 -> finds.random(random)
            16, 17 -> hot
            else -> odd.random(random)
        }
    }

    // A phone playlist's rows, as (song, the server song it came from):
    // songs from the server, songs only on the phone, finds, songs with a
    // copy the playlist never had, and a song the library lost.
    fun phoneRows(n: Int): List<Pair<String, String?>> = List(n) {
        when (random.nextInt(10)) {
            in 0..3 -> {
                val native = linked.ifEmpty { finds }.random(random)
                copies.firstOrNull { it.nativeId == native }?.let { it.mergedId to native } ?: ("find:$native" to native)
            }
            4 -> copies.random(random).mergedId to null
            5, 6 -> phoneOnly.random(random) to null
            7 -> finds.random(random).let { "find:$it" to it }
            8 -> "gone${random.nextInt(5)}" to null
            else -> copies.random(random).let { it.mergedId to it.nativeId }
        }
    }

    // A family base near `from`: some of its songs, some others.
    fun base(from: List<String>): List<String> = from.filter { random.nextInt(4) != 0 } + List(from.size / 5 + 1) { (linked + finds).random(random) }

    fun pick(n: Int): Int = random.nextInt(n)
}

// Two catalogs seeded alike, one run by today's steps (the oracle) and one
// by the streamed steps, each with its own server holding the same songs
// and playlists, so every step's writes and calls can be compared.
internal class SyncRig(
    val world: PlaylistWorld,
    val page: Int = 500,
    val formPost: Boolean = false,
    val familyRules: Boolean = false,
    val me: FamilyMe? = null,
    val playlistSongs: Boolean = false,
) : Closeable {
    private val dir = Files.createTempDirectory("plsync").toFile()
    val oracleDb = openTestCatalog()
    val db = openTestCatalog()
    val oracleServer = PlaylistServer(world.serverSongs)
    val server = PlaylistServer(world.serverSongs)
    private var made = 0
    val oracle = PlaylistOracle(oracleDb, { SOURCE }, { me }, { NOW }, newId = { "oracle-${++made}" })
    val sets = PlaylistSets(db)
    val drains = Drains(dir)
    val reads = PlaylistReads(db, drains, { Long.MAX_VALUE }, { SOURCE }, { NOW }, page)
    val walks = PlaylistWalks(db, sets, page)
    val merge = db.testMerge(SOURCE)
    val steps = PlaylistSteps(db, sets, merge, reads, walks, { me }, { _, _ -> }, { NOW })

    // Both libraries.
    suspend fun library() {
        for (each in listOf(oracleDb, db)) {
            each.withTransaction {
                world.tracks.chunked(500).forEach { each.dao().insertTracks(it) }
                world.copies.chunked(500).forEach { each.sourceDao().putTracks(it) }
            }
        }
    }

    // A server playlist on both servers.
    fun serverPlaylist(id: String, name: String, entries: List<String>, owner: String = "winters", octoFamily: Boolean? = null) {
        oracleServer.add(id, name, entries, owner, octoFamily)
        server.add(id, name, entries, owner, octoFamily)
    }

    // A phone playlist on both catalogs: its rows, and its base, kept as
    // the oracle keeps it (the text cell) and as the streamed steps do.
    suspend fun phonePlaylist(row: PlaylistEntity, rows: List<Pair<String, String?>>, base: List<String>?) {
        for (each in listOf(oracleDb, db)) {
            val oracleSide = each === oracleDb
            each.withTransaction {
                val stored = row.copy(
                    retiredSyncedSongs = if (oracleSide) base?.let(::syncedSongsText) else null,
                    syncedSongsKept = !oracleSide && base != null,
                )
                each.userDao().insertPlaylist(stored)
                val first = each.userDao().nextItemKey()
                each.userDao().raiseNextItemKey(rows.size.coerceAtLeast(1))
                val keys = each.dao().tracksByIdsMap(rows.map { it.first })
                rows.chunked(500).forEachIndexed { chunk, part ->
                    each.userDao().insertPlaylistItems(
                        part.mapIndexed { i, (trackId, serverSongId) ->
                            val at = chunk * 500 + i
                            PlaylistItemEntity(
                                playlistId = row.id, trackId = trackId, relinkKey = keys[trackId].orEmpty(), ord = ordAt(at),
                                itemKey = first + at, serverSongId = serverSongId,
                            )
                        },
                    )
                }
                if (!oracleSide && base != null) {
                    base.flatMap { id -> id.split('\n').filter(String::isNotEmpty) }.groupingBy { it }.eachCount().forEach { (songId, n) ->
                        each.openHelper.writableDatabase.execSQL(
                            "INSERT INTO playlist_synced_song (playlistId, baseSet, songId, copies) VALUES (?, ?, ?, ?)",
                            arrayOf<Any?>(row.id, row.baseSet, songId, n),
                        )
                    }
                }
            }
        }
    }

    fun serverOf(oracleSide: Boolean) = SyncServer(
        (if (oracleSide) oracleServer else server).client(),
        SOURCE,
        "account",
        formPost,
        familyRules,
        playlistSongs,
    )

    // Runs a step on both sides.
    suspend fun run(step: PlaylistStep) {
        oracle.run(serverOf(true), step, me)
        steps.run(serverOf(false), step, me)
        sets.idle()
    }

    // Runs a step on both sides, each side's failure kept as what was thrown.
    suspend fun runBoth(step: PlaylistStep): Pair<Throwable?, Throwable?> {
        val a = runCatching { oracle.run(serverOf(true), step, me) }.exceptionOrNull()
        val b = runCatching { steps.run(serverOf(false), step, me) }.exceptionOrNull()
        sets.idle()
        return a to b
    }

    override fun close() {
        oracleServer.close()
        server.close()
        oracleDb.close()
        db.close()
        dir.deleteRecursively()
    }
}

// Every library song's relink key among these, by song.
internal suspend fun app.winters.octo.catalog.CatalogDao.tracksByIdsMap(ids: List<String>): Map<String, String> =
    tracksByIds(ids).associate { it.id to it.relinkKey }
