package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.server.isServerSource
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Everything one source has, as the old merge took it.
data class SourceCatalog(
    val sourceId: String,
    val onPhone: Boolean,
    val tracks: List<SourceTrackEntity>,
    val albums: List<SourceAlbumEntity>,
    val artists: List<SourceArtistEntity>,
)

// The library as the screens read it, and which library song each source
// copy became.
data class MergedCatalog(
    val tracks: List<TrackEntity>,
    val albums: List<AlbumEntity>,
    val artists: List<ArtistEntity>,
    val mergedIds: Map<String, String>,
)

// Writes the sources to a fresh database, merges them there and reads the
// library back. The server in use is the first server source given.
fun mergeThroughDatabase(sources: List<SourceCatalog>): MergedCatalog = runBlocking {
    val db = openTestCatalog()
    try {
        sources.forEach { db.put(it) }
        val server = sources.firstOrNull { isServerSource(it.sourceId) }?.sourceId
        db.testMerge(server).rebuild()
        db.library()
    } finally {
        db.close()
    }
}

internal suspend fun CatalogDatabase.put(source: SourceCatalog) =
    sourceDao().replaceSource(source.sourceId, source.tracks, source.albums, source.artists)

// A copy of a source waiting to be swapped in, as a server copy leaves it.
internal suspend fun CatalogDatabase.stage(source: SourceCatalog, generation: Long, owner: String? = null) {
    val work = workDao()
    work.insertStageTracks(source.tracks.mapIndexed { i, t -> StageTrackEntity(t.copy(seq = i.toLong()).withKeys()) })
    work.insertStageAlbums(source.albums.mapIndexed { i, a -> StageAlbumEntity(a.copy(seq = i.toLong())) })
    work.insertStageArtists(source.artists.mapIndexed { i, a -> StageArtistEntity(a.copy(seq = i.toLong())) })
    val stamp = work.metas().firstOrNull { it.sourceId == source.sourceId }?.stamp ?: 0
    work.putMeta(
        SourceMetaEntity(source.sourceId, null, owner, stamp, STAGE_READY, generation, source.tracks.size, source.albums.size, source.artists.size),
    )
}

internal suspend fun CatalogDatabase.library(): MergedCatalog {
    val catalog = dao()
    val ids = HashMap<String, String>()
    query("SELECT id, mergedId FROM source_track WHERE mergedId != ''", null).use { c ->
        while (c.moveToNext()) ids[c.getString(0)] = c.getString(1)
    }
    return MergedCatalog(catalog.tracks().first(), catalog.albums().first(), catalog.artists().first(), ids)
}

internal fun CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

internal fun CatalogDatabase.longOf(sql: String, vararg args: Any?): Long =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

internal fun CatalogDatabase.stringOf(sql: String, vararg args: Any?): String? =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

internal fun song(source: String, native: String, title: String, album: String, ms: Long = 200_000, no: Int? = null, order: Int = 0, rating: Int? = null) =
    SourceTrackEntity(
        id = "$source:$native", sourceId = source, nativeId = native, title = title, searchKey = title.lowercase(),
        sortKey = title.lowercase(), artist = "Drake", artistId = "$source:artist", album = "Views", albumId = "$source:$album",
        trackNo = no, discNo = null, year = null, durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null,
        artwork = null, uri = null, albumOrder = order, relinkKey = "drake|${title.lowercase()}", genre = "", rating = rating,
    )

internal fun albumRow(source: String, native: String, title: String = "Views") =
    SourceAlbumEntity("$source:$native", source, native, title, title.lowercase(), title.lowercase(), "Drake", "$source:artist", null, 0, 0, 0, null)

internal fun artistRow(source: String) = SourceArtistEntity("$source:artist", source, "Drake", "drake", "drake", 1, 1, null)

// A source of `songs` songs, ten an album, the same titles in every source
// so copies merge.
internal fun library(source: String, songs: Int, msShift: Long = 0) = SourceCatalog(
    source,
    source == DEVICE,
    (0 until songs).map { song(source, "s$it", "Song $it", "album${it / 10}", ms = 200_000L + it + msShift, no = it % 10 + 1, order = it % 10) },
    (0 until (songs + 9) / 10).map { albumRow(source, "album$it", "Album $it") },
    listOf(artistRow(source)),
)

// Stops a merge at one point until the test lets it go.
internal class Pause {
    val reached = CompletableDeferred<Unit>()
    private val go = CompletableDeferred<Unit>()

    suspend fun hit() {
        if (reached.complete(Unit)) go.await()
    }

    fun release() {
        go.complete(Unit)
    }
}

internal class TestHooks(
    private val atClaim: Pause? = null,
    private val atFirstOutput: Pause? = null,
    private val atSwap: Pause? = null,
    private val firstOutput: (() -> Unit)? = null,
) : MergeHooks() {
    val merges = AtomicInteger()
    val skips = AtomicInteger()
    val events = CopyOnWriteArrayList<String>()

    override suspend fun claimed() {
        atClaim?.hit()
    }

    override suspend fun firstOutputWritten() {
        firstOutput?.invoke()
        atFirstOutput?.hit()
    }

    override suspend fun inSwap() {
        events += "swap"
        atSwap?.hit()
    }

    override fun merged() {
        merges.incrementAndGet()
    }

    override fun skipped() {
        skips.incrementAndGet()
    }
}

// Notes the size of every page the merge reads.
internal class CountingWorkDao(private val inner: WorkDao) : WorkDao by inner {
    val pages = CopyOnWriteArrayList<Int>()

    override suspend fun trackPage(query: androidx.room.RoomRawQuery) = inner.trackPage(query).also { pages += it.size }

    override suspend fun albumPage(query: androidx.room.RoomRawQuery) = inner.albumPage(query).also { pages += it.size }

    override suspend fun artistPage(query: androidx.room.RoomRawQuery) = inner.artistPage(query).also { pages += it.size }
}

internal fun reopen(db: CatalogDatabase, dir: File): CatalogDatabase {
    db.close()
    return openTestCatalog(dir)
}
