package app.winters.octo.ui.library.health

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.read
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlOrder
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import app.winters.octo.ui.library.health.today.TodayHealth
import app.winters.octo.ui.library.health.today.phoneHealth as todayPhoneHealth

// Library health over a test library.
internal class HealthRig(
    val db: CatalogDatabase,
    val merge: CatalogMerge = db.testMerge(null),
    page: Int = HEALTH_PAGE,
    space: FreeSpace = FreeSpace { Long.MAX_VALUE },
    cache: Int = HEALTH_FIND_CACHE,
) {
    val owners = WorkOwners()
    val build = HealthBuild(db, merge, owners, space, page, cache)
    val plans = FixAllPlans(db, build)

    // A check's page in the published report, every line, read a few at a
    // time, with what the server whose source is `source` can fix.
    suspend fun page(check: HealthCheck, source: String?, size: Int = 7): List<Keyed<HealthRow>> {
        val loader = build.lines(check, source)
        val all = ArrayList<Keyed<HealthRow>>()
        var after: RowKey? = null
        while (true) {
            val page = loader.after(after, size)
            all += page
            if (page.size < size) return all
            after = page.last().key
        }
    }

    // Every step a plan sends, each page of sets checked by `same`.
    suspend fun steps(plan: FixAllPlan, same: suspend (List<FixSet>) -> List<Boolean> = { sets -> sets.map { true } }): List<FixStep> {
        val source = plan.steps(same)
        val all = ArrayList<FixStep>()
        var after: Long? = null
        try {
            while (true) {
                val page = source.next(after)
                if (page.isEmpty()) return all
                all += page.map { it.step }
                after = page.last().ord
            }
        } finally {
            source.close()
        }
    }

    // Works out the copies' song keys, as the app does at start.
    suspend fun keys(): HealthRig = apply {
        merge.start()
        merge.keysReady.first { it }
    }

    suspend fun built(): HealthShown.Shown = build.buildNow() as HealthShown.Shown

    // Today's report over the whole library, as the view model read it.
    suspend fun today(): TodayHealth {
        val tracks = db.dao().tracks().first().sortedWith { a, b -> SqlOrder.text(a.sortKey, b.sortKey).takeIf { it != 0 } ?: SqlOrder.text(a.id, b.id) }
        val albums = db.dao().albums().first()
        val copies = db.sourceDao().sourceIds().flatMap { db.sourceDao().tracks(it) }.filter { it.mergedId.isNotEmpty() }
            .sortedWith { a, b -> SqlOrder.text(a.sourceId, b.sourceId).takeIf { it != 0 } ?: a.seq.compareTo(b.seq) }
        return todayPhoneHealth(tracks, albums, copies)
    }

    // A check's songs in the published report, read a page at a time.
    suspend fun songs(check: HealthCheck, into: String = WorkOwners.marked("queue:9")): List<String> {
        val loader = build.loader(check, into)
        val all = ArrayList<String>()
        var after: RowKey? = null
        while (true) {
            val page = loader.after(after, 7)
            all += page.map { it.row }
            if (page.size < 7) return all
            after = page.last().key
        }
    }

    // The published report's sets of copies, in order: each one's copy ids
    // best first, why its copies are one and why its best is best.
    fun groups(gen: Long): List<Triple<List<String>, String, String>> =
        db.read("SELECT root, basis, reason FROM health_group WHERE owner = '${HealthBuild.OWNER}' AND gen = ? AND songs >= 2 ORDER BY k1, k2, bestId", gen) {
            Triple(it.getLong(0), it.getString(1), it.getString(2))
        }.map { (root, basis, reason) ->
            Triple(
                db.read("SELECT copyId FROM health_member WHERE owner = '${HealthBuild.OWNER}' AND gen = ? AND root = ? ORDER BY sound DESC, tags DESC, copyId", gen, root) { it.getString(0) },
                basis, reason,
            )
        }
}

private val ALBUM_TITLES = listOf("Midnight Sun", "Midnight Sun", "Blue", "Greatest Hits", "Ünïcode 😀", "Live at X", "Let's Get Lost Remixes", "Private")
private val ALBUM_ARTISTS = listOf("Alpha", "Beta", "", "Gamma", "Alpha & Beta", "  ")
private val TITLES = listOf("Intro", "Song A", "Song A (Live)", "Nightcall", "Ünïcode 😀", "Song B", "Let's Get Lost")
private val ARTISTS = listOf("Alpha", "Beta", "", "Gamma feat. Delta", "Gamma", " ")

// A library for the checks: albums of one title by one artist and by
// several, blank album artists, songs missing every tag, songs with no
// length, copies on the phone and a server, second copies of a song that
// sound better or worse, copies of two songs that read the same, copies
// sharing a recording id at close and far lengths, release ids and credits
// that join split albums, and names with an emoji and a private-use letter.
internal fun CatalogDatabase.healthLibrary(albums: Int = 40, seed: Int = 4): List<TrackEntity> {
    val r = Random(seed)
    val tracks = ArrayList<TrackEntity>()
    val copies = ArrayList<SourceTrackEntity>()
    val albumRows = ArrayList<AlbumEntity>()
    var seq = 0L
    fun <T> pick(list: List<T>) = list[r.nextInt(list.size)]
    for (a in 0 until albums) {
        val albumId = "al$a"
        val title = pick(ALBUM_TITLES)
        albumRows += AlbumEntity(albumId, "device", albumId, title, title.lowercase(), title.lowercase(), pick(ALBUM_ARTISTS), "ar${a % 5}", null, 0, 0, 0, null)
        val release = if (a % 4 < 2) "rel-${a / 4}" else null
        repeat(1 + r.nextInt(7)) { s ->
            val id = if ((a + s) % 23 == 0) "t😀$a-$s" else "t$a-$s"
            val songTitle = pick(TITLES)
            val artist = pick(ARTISTS)
            val ms = listOf(0L, 500L, 180_000L + r.nextInt(5) * 1_000L, 240_000L)[r.nextInt(4)]
            val track = TrackEntity(
                id = id, sourceId = "device", nativeId = id, title = songTitle, searchKey = songTitle.lowercase(), sortKey = "k%03d".format(r.nextInt(60)),
                artist = artist, artistId = "ar${a % 5}", album = title, albumId = albumId, trackNo = listOf(null, 0, s + 1, s + 1)[r.nextInt(4)],
                discNo = listOf(null, 1, 2)[r.nextInt(3)], year = listOf(null, 0, 1999, 2001)[r.nextInt(4)], durationMs = ms, addedAt = 0,
                mimeType = null, sizeBytes = null, artwork = listOf(null, "", "cover")[r.nextInt(3)], uri = null, albumOrder = s,
                genre = listOf("Rock", "", "Jazz")[r.nextInt(3)],
            )
            tracks += track
            fun copy(copyId: String, source: String, msShift: Long, mime: String?, depth: Int?, recording: String?) = SourceTrackEntity(
                id = copyId, sourceId = source, nativeId = copyId.substringAfterLast(':'), title = songTitle, searchKey = songTitle.lowercase(),
                sortKey = track.sortKey, artist = artist, artistId = track.artistId, album = title, albumId = albumId, trackNo = track.trackNo,
                discNo = track.discNo, year = track.year, durationMs = ms + msShift, addedAt = 0, mimeType = mime, sizeBytes = null, artwork = track.artwork,
                uri = null, albumOrder = s, relinkKey = "", genre = track.genre, bitrate = listOf(null, 128_000, 320_000)[r.nextInt(3)], bitDepth = depth,
                sampleRate = listOf(null, 44_100, 96_000)[r.nextInt(3)], mergedId = id, mbRecordingId = recording,
                mbAlbumId = release, artists = if (r.nextInt(5) == 0) "Alpha\nBeta" else "", seq = seq++,
            )
            val recording = if (r.nextInt(6) == 0) "rec-${r.nextInt(4)}" else null
            copies += copy("device:c$a-$s", "device", 0, listOf("audio/flac", "audio/mpeg", null)[r.nextInt(3)], listOf(null, 16, 24)[r.nextInt(3)], recording)
            if (r.nextInt(3) == 0) copies += copy("device:d$a-$s", "device", r.nextLong(-2_000, 2_000), "audio/mpeg", null, recording?.takeIf { r.nextBoolean() })
            if (r.nextInt(4) == 0) copies += copy("server:h:$a-$s", "server:h", 0, "audio/flac", 16, if (r.nextInt(3) == 0) "rec-far" else null)
        }
    }
    putHealth(albumRows, tracks, copies)
    return tracks
}

// Puts albums, songs and their copies in the library as written, the
// copies' song keys left for the start's refill to work out.
internal fun CatalogDatabase.putHealth(albumRows: List<AlbumEntity>, tracks: List<TrackEntity>, copies: List<SourceTrackEntity>) {
    val sql = openHelper.writableDatabase
    sql.beginTransaction()
    try {
        for (al in albumRows) {
            sql.execSQL(
                "INSERT INTO album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) VALUES (?, 'device', ?, ?, ?, ?, ?, ?, 0, 0, 0)",
                arrayOf<Any>(al.id, al.id, al.title, al.searchKey, al.sortKey, al.artist, al.artistId),
            )
        }
        for (t in tracks) {
            sql.execSQL(
                "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, trackNo, discNo, year, durationMs, addedAt, artwork, albumOrder, genre, relinkKey, mimeType, " +
                    "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, '', ?, ?, ?)",
                arrayOf<Any?>(
                    t.id, t.id, t.title, t.searchKey, t.sortKey, t.artist, t.artistId, t.album, t.albumId, t.trackNo, t.discNo, t.year, t.durationMs, t.artwork, t.albumOrder, t.genre, t.mimeType,
                    t.lookupKey, t.looseKey,
                ),
            )
        }
        for (c in copies) {
            sql.execSQL(
                "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, trackNo, discNo, year, durationMs, addedAt, " +
                    "mimeType, artwork, albumOrder, relinkKey, genre, bitrate, sampleRate, bitDepth, mergedId, mbRecordingId, mbAlbumId, mbReleaseGroupId, artists, seq, keysVersion) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                arrayOf<Any?>(
                    c.id, c.sourceId, c.nativeId, c.title, c.searchKey, c.sortKey, c.artist, c.artistId, c.album, c.albumId, c.trackNo, c.discNo, c.year,
                    c.durationMs, c.mimeType, c.artwork, c.albumOrder, c.genre, c.bitrate, c.sampleRate, c.bitDepth, c.mergedId, c.mbRecordingId, c.mbAlbumId,
                    c.mbReleaseGroupId, c.artists, c.seq,
                ),
            )
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
    }
}
