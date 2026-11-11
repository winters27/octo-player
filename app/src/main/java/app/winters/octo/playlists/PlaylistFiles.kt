package app.winters.octo.playlists

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.OpenableColumns
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NewItem
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.catalog.readLong
import app.winters.octo.catalog.stableRead
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.data.Exports
import app.winters.octo.data.copyExport
import app.winters.octo.data.spaceWords
import app.winters.octo.device.relativeFile
import app.winters.octo.playback.PlaylistStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

// How many entries of a playlist file are staged, or lines of one written,
// at a time.
private const val FILE_PAGE = 500

// The most missed lines an import's report lists by name.
internal const val MISSED_SHOWN = 1_000

// The disk one line of an exported playlist takes, measured on long titles
// and paths, with its #EXTINF line.
internal const val M3U_BYTES_PER_LINE = 400L

// The words a playlist file the picked file could not take shows.
const val PLAYLIST_NOT_WRITTEN = "Could not export the playlist"

// Reads playlist files the listener picks into new playlists, and writes
// playlists out as files other players can open, never holding a file or a
// playlist whole: a file is read twice, once to learn its text's encoding
// and once a line at a time into a matching job; a playlist is written a
// page at a time from the set it showed when the export began.
@Singleton
class PlaylistFiles internal constructor(
    private val context: Context,
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val sets: PlaylistSets,
    private val owners: WorkOwners,
    private val jobs: MatchJobs,
    private val exports: Exports,
    private val freeSpace: FreeSpace,
    // Makes a playlist from pages of rows, as PlaylistStore.createFrom does.
    private val create: suspend (String, suspend (suspend (List<NewItem>) -> Unit) -> Unit) -> String,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        db: CatalogDatabase,
        merge: CatalogMerge,
        sets: PlaylistSets,
        owners: WorkOwners,
        jobs: MatchJobs,
        exports: Exports,
        freeSpace: FreeSpace,
        store: PlaylistStore,
    ) : this(context, db, merge, sets, owners, jobs, exports, freeSpace, store::createFrom)

    // How many lines an export writes at a time; tests make it small.
    internal var page = FILE_PAGE

    // Told after each page an export writes; a test steps in there.
    internal var wrote: suspend (pages: Int) -> Unit = {}

    // Copies an export's file to the picked file; false when it could not.
    internal var copy: suspend (File, Uri) -> Boolean = { file, uri ->
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { copyExport(file, it) } != null }.getOrDefault(false)
    }

    // Makes a playlist from a picked M3U or M3U8 file, named after the file,
    // with the songs the library has. Null when the file cannot be read.
    suspend fun import(uri: Uri): ImportReport? = withContext(Dispatchers.IO) {
        val name = playlistNameOf(displayName(uri))
        val charset = runCatching { open(uri)?.use(::charsetOf) }.getOrNull() ?: return@withContext null
        try {
            jobs.run(MatchKind.Playlist, SpaceFor.KeepList, 0) { job ->
                var total = 0
                val page = ArrayList<M3uEntry>(FILE_PAGE)
                val opened = open(uri) ?: return@run null
                opened.use { input ->
                    m3uEntries(input, charset) { entry ->
                        page += entry
                        total++
                        if (page.size == FILE_PAGE) {
                            job.stageEntries(page.toList())
                            page.clear()
                            room(total.toLong())
                        }
                    }
                }
                if (page.isNotEmpty()) job.stageEntries(page)
                if (total == 0) return@run ImportReport(name, 0, 0, emptyList())
                job.walk()
                job.finish()
                var matched = 0
                if (job.counts(0).first > 0) {
                    create(name) { emit ->
                        var after = -1L
                        while (true) {
                            val rows = job.rows(0, after, FILE_PAGE)
                            if (rows.rows.isNotEmpty()) {
                                val keys = relinkKeysOf(rows.rows.map { it.trackId })
                                matched += rows.rows.size
                                emit(rows.rows.map { NewItem(it.trackId, keys[it.trackId].orEmpty()) })
                            }
                            after = rows.next ?: break
                        }
                    }
                }
                ImportReport(name, matched, total, missedLines(job))
            }
        } catch (e: IOException) {
            null
        }
    }

    // The entries not found, by the words each is shown by, at most
    // MISSED_SHOWN of them, and how many more.
    private fun missedLines(job: MatchJob): List<String> {
        val shown = ArrayList<String>()
        var after = -1L
        while (shown.size < MISSED_SHOWN) {
            val rows = job.missedShown(after, minOf(FILE_PAGE, MISSED_SHOWN - shown.size))
            shown += rows.map { it.second }
            if (rows.isEmpty()) break
            after = rows.last().first
        }
        val more = db.readLong(
            "SELECT COUNT(*) FROM match_query WHERE owner = ? AND kind = ? AND list = 0 AND id > ? AND foundId IS NULL",
            job.owner, job.kind.code, after,
        )
        if (more > 0) shown += "and %,d more not found".format(more)
        return shown
    }

    private fun room(entries: Long) {
        val need = entries * MATCH_BYTES_PER_ENTRY * 2 * 11 / 10
        if (freeSpace.bytes() < need) throw NotEnoughSpace(need, SpaceFor.KeepList)
    }

    // Writes a playlist to a picked file: each song's length, artist and
    // title, and where it is on the phone, or "Artist - Title" for a song
    // with no phone path known. A page of songs at a time from the set the
    // playlist showed when the export began, read as it stood then, to a
    // file of the app's own as one state of the library, then copied to the
    // picked file outside every lock. Answers the words for why it could not
    // be written, or null.
    suspend fun export(playlistId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val rows = db.readLong(
            "SELECT COUNT(*) FROM playlist_item i JOIN playlist p ON p.id = i.playlistId AND i.itemSet = p.itemSet WHERE p.id = ?",
            playlistId,
        )
        val need = (rows + 1) * M3U_BYTES_PER_LINE * 11 / 10
        if (freeSpace.bytes() < need) return@withContext spaceWords(SpaceFor.SavePlaylist, need)
        val owner = owners.export()
        val pinned = sets.pin(owner, playlistId) ?: return@withContext PLAYLIST_NOT_WRITTEN
        try {
            exports.use("playlist-${owners.next()}.m3u") { file ->
                try {
                    stableRead(merge, HoldKind.Walk) {
                        file.parentFile?.mkdirs()
                        file.bufferedWriter(Charsets.UTF_8).use { out ->
                            out.write(writeM3u(emptyList()))
                            var pages = 0
                            sets.walk(pinned, page) { rows ->
                                val songs = songsOf(rows.map { it.trackId })
                                val paths = phonePathsOf(rows.map { it.trackId })
                                val lines = rows.mapNotNull { row ->
                                    val song = songs[row.trackId] ?: return@mapNotNull null
                                    M3uLine(
                                        seconds = (song.durationMs / 1000).toInt(),
                                        artist = song.artist,
                                        title = song.title,
                                        location = paths[row.trackId] ?: fallbackLocation(song.artist, song.title),
                                    )
                                }
                                out.write(writeM3u(lines).removePrefix(M3U_HEADER))
                                wrote(++pages)
                            }
                        }
                    }
                } catch (e: IOException) {
                    return@use spaceWords(SpaceFor.SavePlaylist, null)
                } catch (e: SQLiteFullException) {
                    return@use spaceWords(SpaceFor.SavePlaylist, null)
                }
                if (copy(file, uri)) null else PLAYLIST_NOT_WRITTEN
            }
        } finally {
            withContext(NonCancellable) { sets.releasePin(owner, playlistId) }
        }
    }

    private class Song(val title: String, val artist: String, val durationMs: Long)

    // A page's songs as a playlist file names them: a library song's own
    // words, else a find's; a song gone from both is left out.
    private fun songsOf(ids: List<String>): Map<String, Song> {
        val found = HashMap<String, Song>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, title, artist, durationMs FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) {
                it.getString(0) to Song(it.getString(1), it.getString(2), it.getLong(3))
            }.forEach { (id, song) -> found[id] = song }
            val finds = chunk.filter { it !in found }
            if (finds.isNotEmpty()) {
                db.read("SELECT id, title, artist, durationMs FROM online_song WHERE id IN (${marks(finds.size)})", *finds.toTypedArray()) {
                    it.getString(0) to Song(it.getString(1), it.getString(2), it.getLong(3))
                }.forEach { (id, song) -> found[id] = song }
            }
        }
        return found
    }

    // Where each of a page's songs sits on the phone: of its phone copies,
    // the one the scan found first.
    private fun phonePathsOf(ids: List<String>): Map<String, String> {
        val first = HashMap<String, Pair<Long, String>>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            db.read(PHONE_PATHS_SQL.replace("%s", marks(chunk.size)), *chunk.toTypedArray()) {
                it.getString(0) to (it.getLong(3) to relativeFile(it.stringOrNull(1), it.getString(2)))
            }.forEach { (id, at) -> if ((first[id]?.first ?: Long.MAX_VALUE) > at.first) first[id] = at }
        }
        return first.mapValues { it.value.second }
    }

    private fun relinkKeysOf(ids: List<String>): Map<String, String> {
        val keys = HashMap<String, String>()
        ids.distinct().chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, relinkKey FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                .forEach { (id, key) -> keys[id] = key }
        }
        return keys
    }

    private fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}

// The line every playlist file starts with.
private const val M3U_HEADER = "#EXTM3U\n"

// Each phone copy of these songs with its place and when the scan found it.
internal const val PHONE_PATHS_SQL =
    "SELECT mergedId, folder, fileName, seq FROM source_track WHERE mergedId IN (%s) AND +sourceId = 'device' AND fileName IS NOT NULL"

// The encoding a playlist file's first PLAYLIST_FILE_LIMIT bytes are read
// with, as playlistText chooses it: UTF-8 when they read as UTF-8, which
// M3U8 always is, otherwise Windows' Western encoding. Read a block at a
// time, keeping nothing, so a character cut at the limit falls back as a
// whole read would.
internal fun charsetOf(input: InputStream): Charset {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val bytes = ByteBuffer.allocate(16 * 1024)
    val chars = CharBuffer.allocate(16 * 1024)
    var left = PLAYLIST_FILE_LIMIT
    while (true) {
        val read = if (left == 0) -1 else input.read(bytes.array(), bytes.position(), minOf(bytes.remaining(), left))
        val end = read < 0
        if (!end) {
            bytes.position(bytes.position() + read)
            left -= read
        }
        bytes.flip()
        while (true) {
            val result: CoderResult = decoder.decode(bytes, chars, end)
            if (result.isError) return Charset.forName("windows-1252")
            chars.clear()
            if (result.isUnderflow) break
        }
        bytes.compact()
        if (end) {
            chars.clear()
            if (decoder.flush(chars).isError) return Charset.forName("windows-1252")
            return Charsets.UTF_8
        }
    }
}

// A playlist file's entries in order, as parseM3u finds them in the text of
// its first PLAYLIST_FILE_LIMIT bytes in `charset`: read a line at a time
// (a line ends at "\n", "\r\n" or "\r"), the file's byte order mark gone.
internal suspend fun m3uEntries(input: InputStream, charset: Charset, each: suspend (M3uEntry) -> Unit) {
    val reader: Reader = LimitedStream(input, PLAYLIST_FILE_LIMIT.toLong()).reader(charset)
    val lines = M3uLines()
    val line = StringBuilder()
    var number = 0
    var afterCr = false
    var first = true
    suspend fun end() {
        number++
        var text = line.toString()
        if (first) {
            text = text.removePrefix(BYTE_ORDER_MARK)
            first = false
        }
        lines.line(text, number)?.let { each(it) }
        line.setLength(0)
    }
    val buffer = CharArray(8 * 1024)
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        for (i in 0 until read) {
            when (val c = buffer[i]) {
                '\r' -> {
                    end()
                    afterCr = true
                }
                '\n' -> {
                    if (!afterCr) end()
                    afterCr = false
                }
                else -> {
                    afterCr = false
                    line.append(c)
                }
            }
        }
    }
    // The text's last line, which no line ending closes (an empty one
    // after a closing ending, as lineSequence gives it).
    end()
}

// The first `limit` bytes of a stream.
private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = inner.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val read = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (read > 0) left -= read
        return read
    }
}
