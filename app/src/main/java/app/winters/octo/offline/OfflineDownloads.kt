package app.winters.octo.offline

import app.winters.octo.ui.family.offlineCopiesAllowed
import app.winters.octo.ui.family.OFFLINE_COPIES_OFF
import app.winters.octo.ui.common.Feedback
import app.winters.octo.family.family
import app.winters.octo.family.FamilyHub
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Environment
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.StreamUnavailable
import app.winters.octo.playback.Streams
import app.winters.octo.playback.bitrateOf
import app.winters.octo.playback.downloadRequest
import app.winters.octo.playback.expectedBytes
import app.winters.octo.playback.signInPageIn
import app.winters.octo.playback.streamUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// How often a running download writes how far it has got.
private const val PROGRESS_EVERY_MS = 500L

// How long rule changes settle before the downloads follow them.
private const val RULES_SETTLE_MS = 1_500L

// How many times a download that broke off with a connection is tried again
// before it is marked failed, and how long between tries.
private const val TRIES = 3
private const val RETRY_AFTER_MS = 5_000L

// How long the file of a download removed by hand is set aside, so the
// removal can be taken back. A little longer than the undo line stays up.
private const val UNDO_KEEP_MS = 10_000L

// How many of a download's first bytes are kept to tell what it is: enough
// for an ID3 tag of a few kilobytes before the audio.
private const val HEAD_BYTES = 4096

// A download let go of, with its file set aside while it can still come back.
private data class SetAside(val row: DownloadEntity, val file: File?)

// Whether a removal can be taken back: every finished download kept its
// file. Unfinished ones only need to wait in line again.
fun canRestore(removed: List<Pair<DownloadStatus, Boolean>>): Boolean =
    removed.isNotEmpty() && removed.none { (state, fileKept) -> state == DownloadStatus.Done && !fileKept }

// Server songs kept on the phone as real files, one at a time, in the order
// asked for. Asked for by hand, or kept in step with rules: Liked songs, and
// chosen playlists. The queue lives in the database, so it carries on when
// the app starts again. No foreground service: downloads run while the app
// is alive, and pick up where they were next time.
@OptIn(UnstableApi::class, FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class OfflineDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val db: CatalogDatabase,
    merge: CatalogMerge,
    owners: WorkOwners,
    sets: PlaylistSets,
    private val sessions: SessionRepository,
    private val streams: Streams,
    private val settings: OfflineSettings,
    private val saved: StreamCache,
    // A family member whose account has offline copies off gets none from
    // that server; files already on the phone stay.
    private val family: FamilyHub,
    private val feedback: Feedback,
    // The animated art of albums downloaded whole, kept beside their songs.
    private val loops: LoopKeeper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    // The download running now, so removing it can stop it.
    @Volatile private var running: Pair<String, Job>? = null
    private val tries = ConcurrentHashMap<String, Int>()

    // The "Download on Wi-Fi only" setting as it is now.
    @Volatile private var wifiOnly = OfflinePrefs().wifiOnly

    // Every write of the download table goes through here.
    private val book = DownloadBook(dao)

    // The rules, and every walk of the download table a page at a time.
    private val rules = OfflineRules(
        db, merge, lock, book, owners, ::serverInUse,
        dropped = ::dropped,
        heldBack = { feedback.show(OFFLINE_COPIES_OFF) },
        sets = sets,
    )

    // One rules walk at a time: the walks the rules' changes ask for and the
    // one after each sync. Taken before the merge lock, never inside it.
    private val walks = Mutex()

    // Moves whenever the set of downloaded songs can change: the download
    // marks on song rows and the car follow it.
    val doneVersion: StateFlow<Long> get() = book.doneVersion

    // Moves on every write of a download but a progress tick: readers of a
    // download's state follow it.
    val version: StateFlow<Long> get() = book.version

    // Hears each requeue of downloads whose files had gone, once written,
    // with how many it moved.
    internal var requeued: (Int) -> Unit = {}

    // Whether a library song has a finished download.
    suspend fun isDone(trackId: String): Boolean = book.isDone(trackId)

    // Which of these library songs have a finished download.
    suspend fun doneAmong(trackIds: Collection<String>): Set<String> = book.doneAmong(trackIds)

    // The downloads of these library songs, by song, read again on each
    // write but a progress tick.
    fun keptAmong(trackIds: Collection<String>): Flow<Map<String, DownloadEntity>> = book.keptAmong(trackIds)

    // The download of one library song, likewise.
    fun keptRow(trackId: String): Flow<DownloadEntity?> = book.keptRow(trackId)

    // How far one download has got, as it goes.
    fun progressOf(sourceId: String, serverId: String): Flow<Float> = book.progressOf(sourceId, serverId)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Files set aside by a removal the app closed on before it was final.
            asideDir().listFiles()?.forEach { it.delete() }
            book.requeueInterrupted()
            // The saved songs open in the background, not when a song first plays.
            saved.refreshUsed()
            watchRules()
        }
        scope.launch {
            // Parts of downloads the app closed on go before any download runs.
            leftoverParts(downloadDir()).forEach { it.delete() }
            runQueue()
        }
        // A new connection, a changed setting or a sign-in may let waiting downloads go.
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = poke()
                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = poke()
            },
        )
        scope.launch {
            settings.prefs.map { it.wifiOnly }.distinctUntilChanged().collect {
                wifiOnly = it
                poke()
            }
        }
        scope.launch { sessions.state.collect { poke() } }
        loops.start()
    }

    // Downloads these library songs, the ones that can be: on a server and
    // not on the phone. Each goes on the song its id names now (OfflineRules).
    fun download(trackIds: List<String>) {
        scope.launch {
            rules.download(trackIds, refusedSource())
            poke()
        }
    }

    // An album's songs, a page at a time.
    fun downloadAlbum(albumId: String) {
        scope.launch {
            rules.downloadAlbum(albumId, refusedSource())
            poke()
        }
    }

    // A playlist's songs as it shows them now, a page at a time.
    fun downloadPlaylist(playlistId: String) {
        scope.launch {
            rules.downloadPlaylist(playlistId, refusedSource())
            poke()
        }
    }

    // Lets go of a download asked for by hand. One a rule also keeps stays.
    // When the download itself goes, `gone` hears a way to bring it back,
    // as long as its file could be set aside for a moment.
    fun remove(trackId: String, gone: (restore: () -> Unit) -> Unit = {}) {
        scope.launch {
            // Under every id the song's downloads are kept under now.
            val aside = rules.unkeep(trackId) { held -> held.flatMap { setAside(it) } } ?: return@launch
            // The set aside files go for good once the undo has passed.
            scope.launch {
                delay(UNDO_KEEP_MS)
                lock.withLock { aside.forEach { it.file?.delete() } }
            }
            loops.poke()
            if (canRestore(aside.map { it.row.state to (it.file != null) })) gone { restore(aside) }
        }
    }

    // Takes back a removal: finished files move back, anything unfinished
    // waits to download again, each on the song its id names now. One asked
    // for again meanwhile just stays wanted by hand.
    private fun restore(aside: List<SetAside>) {
        scope.launch {
            val files = aside.associate { key(it.row) to it.file }
            rules.putBack(aside.map { it.row }) { row ->
                val file = files[key(row)]
                when {
                    row.state == DownloadStatus.Done && file != null && file.renameTo(File(row.path)) -> row
                    row.state == DownloadStatus.Failed -> row
                    else -> row.copy(state = DownloadStatus.Queued, progress = 0f, path = "", sizeBytes = 0)
                }
            }
            poke()
        }
    }

    // Removes a song's downloads, moving each finished file aside rather
    // than deleting it. A file that cannot be moved is deleted.
    private suspend fun setAside(trackId: String): List<SetAside> = dao.forTrack(trackId).map { row ->
        running?.let { (key, job) -> if (key == key(row)) job.cancel() }
        book.delete(row)
        val file = File(row.path).takeIf { row.path.isNotEmpty() && it.exists() }
        val kept = file?.let { File(asideDir(), "${System.nanoTime()}-${it.name}") }?.takeIf { file.renameTo(it) }
        if (file != null && kept == null) file.delete()
        SetAside(row, kept)
    }

    // Stops keeping anything: the rules are switched off and every file goes.
    fun removeAll() {
        scope.launch {
            settings.clearRules()
            rules.removeAll()
        }
    }

    // Tries a failed download again.
    fun retry(trackId: String) {
        scope.launch {
            dao.forTrack(trackId).filter { it.state == DownloadStatus.Failed }.forEach {
                tries.remove(key(it))
                book.setState(it.sourceId, it.serverId, DownloadStatus.Queued)
            }
            poke()
        }
    }

    // Deletes the downloads of a server taken off the list, files and all.
    suspend fun forgetSource(sourceId: String) {
        rules.forgetSource(sourceId)
    }

    fun setKeepLiked(on: Boolean) {
        scope.launch { settings.setKeepLiked(on) }
    }

    fun setPlaylistKept(playlistId: String, kept: Boolean) {
        scope.launch { settings.setPlaylistKept(playlistId, kept) }
    }

    // After each copy of the server's library: a download whose server file
    // is now another one downloads again (until it has, the song streams,
    // so the old file never plays), downloads follow their songs to new
    // ids, and songs a rule wants that have just arrived download.
    suspend fun afterSync() {
        rules.refreshSwapped()
        val prefs = settings.prefs.first()
        followRules(prefs.keepLiked, prefs.keptPlaylists)
        poke()
    }

    // The finished downloads of these library songs whose files are there.
    // One whose file has gone waits to download again, written on the
    // store's own scope, so a play never waits for a write; the song streams
    // meanwhile.
    suspend fun playableCopies(trackIds: List<String>): Map<String, DownloadEntity> =
        book.playableCopies(trackIds) { requeue ->
            scope.launch {
                val moved = requeue()
                if (moved > 0) poke()
                requeued(moved)
            }
        }

    // Whether a queue address is a file in the downloads folder.
    fun isDownloadFile(uri: String): Boolean {
        val path = uri.toUri().takeIf { it.scheme == "file" }?.path ?: uri.takeIf { it.startsWith("/") } ?: return false
        val dir = downloadDir().canonicalPath + File.separator
        return runCatching { File(path).canonicalPath.startsWith(dir) }.getOrDefault(false)
    }

    // The server copy a downloaded file of `trackId` was made from, for a
    // device that does not play the file's type.
    suspend fun serverCopyOfFile(trackId: String, uri: String): app.winters.octo.catalog.SourceTrackEntity? {
        val path = uri.toUri().takeIf { it.scheme == "file" }?.path ?: uri
        val row = dao.forTrack(trackId).firstOrNull { it.state == DownloadStatus.Done && it.path == path } ?: return null
        return dao.serverCopy(row.sourceId, row.serverId)
    }

    // A downloaded file that turns out to be an HTML page (a sign-in page's
    // answer kept before such answers were refused) is deleted, and the
    // downloads of `trackId` kept in it download again. A download kept in it
    // under another id since is requeued once its file is found gone, as any
    // is. True when it was one.
    suspend fun dropStoredHtml(uri: String, trackId: String?): Boolean = withContext(Dispatchers.IO) {
        val path = uri.toUri().takeIf { it.scheme == "file" }?.path ?: return@withContext false
        if (!isHtmlFile(File(path))) return@withContext false
        lock.withLock {
            File(path).delete()
            if (trackId != null) book.requeueFile(trackId, path)
        }
        poke()
        true
    }

    private fun poke() {
        wake.trySend(Unit)
        loops.poke()
    }

    // Rules

    // The rules follow their settings and the songs they name: a like, a
    // playlist's songs and a playlist's new list of songs each ask for a
    // walk, once changes have settled. Asks made while a walk runs become
    // one more walk.
    @kotlin.OptIn(FlowPreview::class)
    private suspend fun watchRules() {
        val songs = flow {
            var changes = 0L
            db.invalidationTracker.createFlow("liked_track", "playlist_item", "playlist").collect { emit(++changes) }
        }
        combine(settings.prefs.map { it.keepLiked to it.keptPlaylists }.distinctUntilChanged(), songs) { setting, _ -> setting }
            .debounce(RULES_SETTLE_MS)
            .conflate()
            .collect { (keepLiked, playlists) ->
                followRules(keepLiked, playlists)
                poke()
            }
    }

    // One walk of the rules. Whether new downloads may come from the server
    // in use is asked first, outside every lock: it may ask the server.
    private suspend fun followRules(keepLiked: Boolean, playlists: Set<String>) {
        val refused = refusedSource()
        walks.withLock { rules.walk(keepLiked, playlists, refused) }
    }

    // The downloads of the server in use are the ones the rules look at.
    // Another kept server's files stay as they are until it is in use
    // again: its songs are not in the library meanwhile, and would look
    // unwanted.
    private fun serverInUse(): String? = (sessions.state.value as? SessionState.SignedIn)?.session?.sourceId

    // The server in use, when its family plan has offline copies off for
    // this account; null when new downloads may go.
    private suspend fun refusedSource(): String? {
        val session = (sessions.state.value as? SessionState.SignedIn)?.session ?: return null
        if (!session.family) return null
        return session.sourceId.takeUnless { offlineCopiesAllowed(family.plan()) }
    }

    // A download deleted: one running stops, its file goes, and the
    // animated art kept beside it is looked at again.
    private fun dropped(row: DownloadEntity) {
        running?.let { (key, job) -> if (key == key(row)) job.cancel() }
        if (row.path.isNotEmpty()) File(row.path).delete()
        loops.poke()
    }

    // The queue

    private suspend fun runQueue() {
        while (true) {
            val next = if (canDownloadNow()) serverInUse()?.let { dao.nextQueued(it) } else null
            if (next == null) {
                wake.receive()
                continue
            }
            val job = scope.launch { fetch(next) }
            running = key(next) to job
            job.join()
            running = null
        }
    }

    private suspend fun canDownloadNow(): Boolean {
        if (sessions.state.value !is SessionState.SignedIn) return false
        return if (settings.prefs.first().wifiOnly) streams.onWifi() else streams.online()
    }

    private suspend fun fetch(row: DownloadEntity) {
        val key = key(row)
        val copy = dao.serverCopy(row.sourceId, row.serverId)
        if (copy == null) {
            book.setState(row.sourceId, row.serverId, DownloadStatus.Failed)
            return
        }
        book.markDownloading(row.sourceId, row.serverId)
        val prefs = settings.prefs.first()
        val request = downloadRequest(copy.mimeType, bitrateOf(copy), prefs.downloadQuality, prefs.downloadFormat)
        val ref = streams.refFor(copy).pin(request)
        val dir = downloadDir()
        val asked = request.mimeType(copy.mimeType)
        val part = File(dir, downloadFileName(copy.artist, copy.title, row.sourceId, row.serverId, asked, madeSmaller = request.maxKbps != null) + ".part")
        try {
            val got = copyInto(streamUri(ref), part, expectedBytes(copy.durationMs, request.maxKbps)) { progress ->
                book.setProgress(row.sourceId, row.serverId, progress)
            }
            if (dao.get(row.sourceId, row.serverId) == null) {
                part.delete()
                return
            }
            val arrival = arrivalOf(got.head, got.headCount, got.type, copy.mimeType, request)
            val target = File(dir, downloadFileName(copy.artist, copy.title, row.sourceId, row.serverId, arrival.label, arrival.madeSmaller))
            val finished = finishArrival(part, target, arrival.rewrap) {
                dao.get(row.sourceId, row.serverId)?.state == DownloadStatus.Downloading
            }
            if (!finished) return
            book.finish(row.sourceId, row.serverId, target.absolutePath, target.length(), arrival.label, copy.sizeBytes?.takeIf { it > 0 })
            // A song downloaded again under a new name lets its old file go.
            if (row.path.isNotEmpty() && row.path != target.absolutePath) File(row.path).delete()
            tries.remove(key)
            loops.poke()
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            Log.w("Octo", "download failed: ${e.javaClass.simpleName}")
            val tried = (tries[key] ?: 0) + 1
            tries[key] = tried
            when {
                // No connection: it waits for one, however long that takes.
                e is StreamUnavailable || !streams.online() -> {
                    tries.remove(key)
                    book.setState(row.sourceId, row.serverId, DownloadStatus.Queued)
                }
                // A sign-in page answered in place of the song: nothing is
                // kept, a saved copy that is one goes, and the download
                // waits to be tried again by hand.
                signInPageIn(e) -> {
                    streams.dropStoredHtml(streamUri(ref))
                    book.setState(row.sourceId, row.serverId, DownloadStatus.Failed)
                }
                // The server said no; asking again will not change that.
                e is HttpDataSource.InvalidResponseCodeException || tried >= TRIES ->
                    book.setState(row.sourceId, row.serverId, DownloadStatus.Failed)
                else -> {
                    book.setState(row.sourceId, row.serverId, DownloadStatus.Queued)
                    delay(RETRY_AFTER_MS)
                }
            }
        }
    }

    // What a download read: how many bytes, its first bytes (up to
    // HEAD_BYTES) for telling what it is, and the type the server said.
    private class Read(val bytes: Long, val head: ByteArray, val headCount: Int, val type: String?)

    // Reads a server song into a file, telling how far it has got, against
    // the server's length or, when it sends none, `expected`.
    private suspend fun copyInto(uri: String, file: File, expected: Long, onProgress: suspend (Float) -> Unit): Read = withContext(Dispatchers.IO) {
        val source = streams.downloadSource()
        try {
            val length = source.open(DataSpec(uri.toUri())).takeIf { it > 0 } ?: expected
            val type = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
            var written = 0L
            var reported = 0L
            val buffer = ByteArray(64 * 1024)
            val head = ByteArray(HEAD_BYTES)
            var headCount = 0
            FileOutputStream(file).use { out ->
                while (true) {
                    ensureActive()
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    // A sign-in page saved as the song before such answers
                    // were refused is never downloaded as it.
                    if (written == 0L) refuseHtmlStart(buffer, read)
                    if (headCount < HEAD_BYTES) {
                        val take = minOf(read, HEAD_BYTES - headCount)
                        System.arraycopy(buffer, 0, head, headCount, take)
                        headCount += take
                    }
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (now - reported >= PROGRESS_EVERY_MS) {
                        reported = now
                        // Leaving Wi-Fi pauses a download that waits for it.
                        if (wifiOnly && !streams.onWifi()) throw StreamUnavailable("Off Wi-Fi")
                        if (length > 0) onProgress((written.toFloat() / length).coerceIn(0f, 0.99f))
                    }
                }
            }
            Read(written, head, headCount, type)
        } finally {
            source.close()
        }
    }

    // App storage on the phone's music drive, which other apps and the
    // phone's music library do not look in, or inside the app if there is
    // no such drive.
    private fun downloadDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")
        val dir = File(base, "Octo")
        dir.mkdirs()
        File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        return dir
    }

    // Where removed files wait out their undo.
    private fun asideDir(): File = File(downloadDir(), ".removed").apply { mkdirs() }

    private fun key(row: DownloadEntity) = "${row.sourceId}|${row.serverId}"
}

// How many songs one lookup asks the database about, under its limit.
internal const val LOOKUP_CHUNK = 900

// The download table as OfflineDownloads keeps it: every write, each
// moving the counters it can change, and the lookups by library song. Both
// counters live in memory and start from 0. doneVersion moves when the set
// of downloaded songs can change: a finish, a delete or a change of a
// finished download, a restore of one, a relink. version moves on every
// write but a progress tick. Each moves after its write returns, or, for a
// write inside one of the book's transactions, once that has committed. A
// running download's progress reaches its readers through progressOf,
// never a counter, so a tick re-reads nothing.
internal class DownloadBook(private val dao: DownloadDao) {
    private val done = MutableStateFlow(0L)
    private val any = MutableStateFlow(0L)

    val doneVersion: StateFlow<Long> = done.asStateFlow()
    val version: StateFlow<Long> = any.asStateFlow()

    // The counter moves of one transaction's writes, held until it commits.
    private class Pending(val book: DownloadBook) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Pending>

        @Volatile var any = false
        @Volatile var done = false
    }

    private suspend fun wrote(doneMayChange: Boolean) {
        val pending = currentCoroutineContext()[Pending]?.takeIf { it.book === this }
        if (pending != null) {
            pending.any = true
            if (doneMayChange) pending.done = true
            return
        }
        any.update { it + 1 }
        if (doneMayChange) done.update { it + 1 }
    }

    // Runs `block` as one transaction of `db`. The counters move for its
    // writes once it has committed, never before, so a reader a counter
    // wakes reads what was written; one rolled back moves neither. Inside
    // another of the book's transactions it is part of that one.
    suspend fun <T> transaction(db: RoomDatabase, block: suspend () -> T): T {
        if (currentCoroutineContext()[Pending]?.book === this) return db.withTransaction(block)
        val pending = Pending(this)
        var committed = false
        try {
            return withContext(pending) { db.withTransaction(block).also { committed = true } }
        } finally {
            if (committed && pending.any) {
                any.update { it + 1 }
                if (pending.done) done.update { it + 1 }
            }
        }
    }

    // New downloads, or rows put back as they were. A new download that
    // takes the place of a finished one under the same key (another
    // server's song kept from before, say) changes the downloaded set too.
    suspend fun upsert(rows: List<DownloadEntity>) {
        if (rows.isEmpty()) return
        val replacesDone = rows.any { dao.keyDone(it.sourceId, it.serverId) }
        dao.upsert(rows)
        wrote(doneMayChange = replacesDone || rows.any { it.state == DownloadStatus.Done })
    }

    suspend fun setReason(sourceId: String, serverId: String, reason: String) {
        dao.setReason(sourceId, serverId, reason)
        wrote(doneMayChange = false)
    }

    // A download not finished waits again or has failed.
    suspend fun setState(sourceId: String, serverId: String, state: DownloadStatus) {
        dao.setState(sourceId, serverId, state, 0f)
        wrote(doneMayChange = false)
    }

    // A finished download downloads again.
    suspend fun refetch(sourceId: String, serverId: String) {
        dao.setState(sourceId, serverId, DownloadStatus.Queued, 0f)
        wrote(doneMayChange = true)
    }

    suspend fun markDownloading(sourceId: String, serverId: String) {
        dao.markDownloading(sourceId, serverId)
        wrote(doneMayChange = false)
    }

    suspend fun setProgress(sourceId: String, serverId: String, progress: Float) {
        dao.setProgress(sourceId, serverId, progress)
    }

    suspend fun finish(sourceId: String, serverId: String, path: String, size: Long, format: String?, fileSize: Long?) {
        dao.finish(sourceId, serverId, path, size, format, fileSize)
        wrote(doneMayChange = true)
    }

    suspend fun noteFileSize(sourceId: String, serverId: String, fileSize: Long) {
        dao.noteFileSize(sourceId, serverId, fileSize)
        wrote(doneMayChange = false)
    }

    suspend fun requeueInterrupted() {
        dao.requeueInterrupted()
        wrote(doneMayChange = false)
    }

    // A row read while it downloaded may have finished since.
    suspend fun delete(row: DownloadEntity) {
        dao.delete(row.sourceId, row.serverId)
        wrote(doneMayChange = row.state == DownloadStatus.Done || row.state == DownloadStatus.Downloading)
    }

    suspend fun relink() {
        if (dao.relink() > 0) wrote(doneMayChange = true)
    }

    // Finished downloads whose files have gone wait to download again, each
    // only while it is still finished with that file. Answers how many did.
    suspend fun requeueGone(rows: List<DownloadEntity>): Int {
        val moved = rows.sumOf { dao.requeueGone(it.sourceId, it.serverId, it.path) }
        if (moved > 0) wrote(doneMayChange = true)
        return moved
    }

    // The finished downloads of `trackId` kept in the file at `path` wait to
    // download again. Answers how many did.
    suspend fun requeueFile(trackId: String, path: String): Int = requeueGone(dao.forTrack(trackId).filter { it.path == path })

    // The finished downloads of these library songs whose files are there,
    // by song. It writes nothing: the ones whose files have gone are handed
    // to `later` as a requeue to run, which answers how many it moved.
    suspend fun playableCopies(trackIds: List<String>, later: (suspend () -> Int) -> Unit): Map<String, DownloadEntity> =
        withContext(Dispatchers.IO) {
            val rows = trackIds.distinct().chunked(LOOKUP_CHUNK).flatMap { dao.doneFor(it) }
            val (present, gone) = rows.partition { it.path.isNotEmpty() && File(it.path).isFile }
            if (gone.isNotEmpty()) later { requeueGone(gone) }
            present.associateBy { it.trackId }
        }

    suspend fun isDone(trackId: String): Boolean = dao.isDone(trackId)

    suspend fun doneAmong(trackIds: Collection<String>): Set<String> =
        trackIds.distinct().chunked(LOOKUP_CHUNK).flatMapTo(HashSet()) { dao.doneAmong(it) }

    // A song with downloads from more than one server keeps the one written
    // last, as a map over every row in the order written would.
    fun keptAmong(trackIds: Collection<String>): Flow<Map<String, DownloadEntity>> {
        val chunks = trackIds.distinct().chunked(LOOKUP_CHUNK)
        return version.map { chunks.flatMap { dao.keptAmong(it) }.associateBy { it.trackId } }.distinctUntilChanged()
    }

    fun keptRow(trackId: String): Flow<DownloadEntity?> = version.map { dao.keptRow(trackId) }.distinctUntilChanged()

    fun progressOf(sourceId: String, serverId: String): Flow<Float> =
        dao.progressOf(sourceId, serverId).map { it ?: 0f }.distinctUntilChanged()
}
