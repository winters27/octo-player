package app.winters.octo.playback

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.RENAME_SONG
import app.winters.octo.catalog.RunHooks
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.data.Drains
import app.winters.octo.device.DEVICE
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.asTrack
import app.winters.octo.discovery.serverFolders
import app.winters.octo.discovery.serverReads
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LivePicks
import app.winters.octo.output.OutputSwitch
import app.winters.octo.output.QueueState
import app.winters.octo.output.RemotePlayer
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.Counted
import app.winters.octo.testing.CountedTransaction
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

// The queue as the service holds it, playing lists a fake server answers
// for, with the find map: the window over Room, its builders and first
// reads, the server's lists read and drained as the app reads them. The
// player's items are made as the app makes them, a find from the map until
// its row is written. Each start's hand-off is marked in the counting log
// (QueueWindow.handing), so a test reads what ran before it.
@OptIn(UnstableApi::class)
internal class ServerStartRig(
    songs: Map<String, Song>,
    readPage: Int = 50,
    buildPage: Int = 100,
    val db: CatalogDatabase = openTestCatalog(),
    val server: ListServer = ListServer(songs),
    // The library's merge: a save run through it holds this queue's builds
    // as it holds the app's.
    val merge: CatalogMerge = db.testMerge(SERVER_SOURCE),
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val scope = MainScope()
    val owners = WorkOwners()
    val drains: File = Files.createTempDirectory("drain").toFile()
    val real = RealLengths()
    val finds = FindMap(db.onlineDao(), real, scope)
    private val totals = PlayTotals(db, merge, { Long.MAX_VALUE }, MutableStateFlow("home"), {}, 500)
    val folders = db.serverFolders(server, owners, page = readPage)
    val reads = db.serverReads(server, page = readPage, merge = merge, owners = owners, drains = drains, levels = folders)
    private val live = LivePicks(db, merge, totals, owners, { Long.MAX_VALUE }, { System.currentTimeMillis() }, 500)
    private val health = HealthBuild(db, merge, owners, { Long.MAX_VALUE }, 500, 100)
    private val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, PlaylistSets(db), owners, Drains(Files.createTempDirectory("drain").toFile()), totals, { null },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { SERVER_SOURCE }, servers = { reads },
    )
    val store = QueueStore(db)
    val builds = QueueBuilds(store, { loaders }, { live }, { health }, merge, totals, { Long.MAX_VALUE }, owners, { error("no radio") })
        .also { it.pageSize = buildPage }
    val starts = QueueStarts(db, { loaders }, { live }, { health }, owners, { reads })
    private val local = OctoPlayer(context, ExoPlayer.Builder(context).build(), ExoPlayer.Builder(context).build())
    private val remote = RemotePlayer(QuietDevice(), AnyMedia(), scope) {}
    lateinit var window: QueueWindow
    val player = OutputSwitch(local) { if (::window.isInitialized) window else null }
    val words = ArrayList<String>()

    // Where in the counting log each start handed over, by list id.
    val handed = HashMap<Long, Long>()

    init {
        remote.start(QueueState(emptyList(), 0, 0, playing = false))
        player.useRemote(remote)
        window = QueueWindow(
            player, { player.active }, store, builds, starts, ::items, { "home" }, PlayGuard({ player.replacements }, { "home" }), scope,
            WindowSizes(window = 20, ahead = 12, topUpAt = 6, behind = 4, trimAt = 8), { words += it }, finds = finds,
        )
        window.handing = { listId -> synchronized(handed) { handed[listId] = db.counted.mark() } }
        // The ids the queue hands out are read first, so no start reserves a run.
        runBlocking { store.claimList(); store.claimQueue() }
    }

    // Songs the player can play: library songs, and finds from the map
    // until their rows are written, then from their rows.
    suspend fun items(ids: List<String>): List<MediaItem?> = ids.map { id ->
        val track: TrackEntity? = db.queueDao().track(id) ?: (finds[id] ?: db.onlineDao().byIds(listOf(id)).firstOrNull())?.asTrack()
        track?.let { t ->
            MediaItem.Builder().setMediaId(t.id).setUri("http://server/${t.id}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setDurationMs(t.durationMs).build()).build()
        }
    }

    fun settle() = repeat(4) {
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { store.inTurn { } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Waits on the work, settling the main looper and the worker, until
    // `done`; fails after a few seconds.
    fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            settle()
            if (System.nanoTime() > end) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    fun play(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        val job = scope.launch { answer = window.play(request) }
        until("the play answers") { answer != null || job.isCompleted }
        return answer!!
    }

    // A play while another writer holds the write lock: the main looper
    // turns, but nothing waits on the queue's worker, which waits on the lock.
    fun playWhileParked(request: PlayRequest): PlayAnswer {
        var answer: PlayAnswer? = null
        val job = scope.launch { answer = window.play(request) }
        val end = System.nanoTime() + 15_000_000_000L
        while (answer == null && !job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > end) throw AssertionError("the play waited on the held lock")
            Thread.sleep(5)
        }
        return answer!!
    }

    fun playForMedia3(request: PlayRequest, emptyWords: String = "nothing"): CarStarted {
        var answer: CarStarted? = null
        val guard = PlayGuard({ player.replacements }, { "home" })
        val job = scope.launch { answer = window.playForMedia3(request, guard, guard.mark(), emptyWords) }
        until("the car's play answers") { answer != null || job.isCompleted }
        return answer!!
    }

    fun add(spec: ListSpec, next: Boolean, shownOverWindow: Boolean = false): Added? {
        var answer: Added? = null
        var done = false
        val job = scope.launch {
            answer = window.addList(spec, next, null, shownOverWindow)
            done = true
        }
        until("the add answers") { done || job.isCompleted }
        return answer
    }

    fun current(): String? = player.currentMediaItem?.mediaId

    fun held(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    // Every list of the queue in use joined.
    fun joined() = until("every list joined") {
        runBlocking { store.inTurn { store.dao.listsOf(window.queueId) } }.all { it.fillState == QueueBuilds.JOINED } && window.building.value.isEmpty()
    }

    // The whole queue's songs in play order.
    fun songs(order: QueueOrder = QueueOrder.Play): List<String> =
        runBlocking { store.inTurn { store.page(window.queueId, order, null, true, Int.MAX_VALUE, Floors.NONE) } }.map { it.trackId }

    fun count(sql: String, vararg args: Any?): Int = db.query(sql, args).use { it.moveToFirst(); it.getInt(0) }

    // A rename in force, written by hand as a save's group would publish it.
    fun rename(old: String, new: String, byKey: Boolean = false, key: String = "") {
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO save_rename (kind, old, new, byKey, `key`) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(RENAME_SONG, old, new, if (byKey) 1 else 0, key),
        )
    }

    fun files(): List<String> = drains.listFiles().orEmpty().map { it.name }

    override fun close() {
        window.release()
        player.release()
        scope.cancel()
        store.scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        server.close()
        db.close()
    }
}

// A word of letters alone for `i`, so titles told apart by it do not share
// one lookup key, as titles told apart by a number do ("Song 1", "Song 2").
// Four letters, one for each place of `i` in base 26 (i under 456,976).
internal fun wordOf(i: Int): String {
    var n = i
    val out = StringBuilder()
    repeat(4) {
        out.append('a' + n % 26)
        n /= 26
    }
    return out.toString()
}

// What a start ran up to its hand-off: every write statement and every
// transaction begun before it (Room's own change tracking left out: a
// transaction of its runs only its bookkeeping), and the rows read.
internal class StartCount(val writes: List<Counted>, val begins: List<CountedTransaction>, val rowsRead: Long, val statements: Int) {
    override fun toString() = "writes $writes, begins ${begins.map { "${it.kind} ${it.label}" }}"
}

// A write, or a transaction begun by its text: Android begins any BEGIN it is
// given as an EXCLUSIVE transaction (SQLiteSession.executeSpecial), whatever
// kind the text names, so such a BEGIN takes the write lock.
private val WRITE = Regex("""^\s*(INSERT|UPDATE|DELETE|REPLACE|BEGIN|SAVEPOINT)\b""", RegexOption.IGNORE_CASE)

// Runs `block` (one start) with the counting log recording, and answers
// what ran from its beginning to its hand-off. Fails when the start never
// handed over.
internal fun ServerStartRig.counted(block: () -> Unit): StartCount {
    settle()
    synchronized(handed) { handed.clear() }
    db.counted.reset()
    val from = db.counted.mark()
    db.counted.recording = true
    try {
        block()
        settle()
    } finally {
        db.counted.recording = false
    }
    val mark = synchronized(handed) { handed.values.maxOrNull() } ?: throw AssertionError("the start never handed over")
    val ran = db.counted.statements.filter { it.seq in (from + 1)..mark }
    val writes = ran.filter { !it.bookkeeping && WRITE.containsMatchIn(it.sql) }
    val begins = db.counted.begins.filter { it.beginSeq in (from + 1)..mark && (it.statements > 0 || it.rowsChanged > 0) }
    return StartCount(writes, begins, ran.filterNot { it.bookkeeping }.sumOf { it.rowsRead.toLong() }, ran.count { !it.bookkeeping })
}

// A write transaction held open on a thread of its own until released, as
// a long save holds SQLite's one write lock.
internal class ParkedWrite(private val db: CatalogDatabase) : AutoCloseable {
    private val holding = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val job = MainScope().async(Dispatchers.IO) {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE track SET title = title WHERE 0")
            holding.complete(Unit)
            release.await()
        }
    }

    init {
        runBlocking { withTimeout(10_000) { holding.await() } }
    }

    override fun close() {
        release.complete(Unit)
        runBlocking { withTimeout(10_000) { job.await() } }
    }
}

// The songs of a library the fake server and the phone both hold, "s0" on,
// titled and sung as the test library's songs (MergeHarness's `library`).
internal fun saveSongs(n: Int): Map<String, Song> =
    (0 until n).associate { "s$it" to Song("s$it", title = "Song $it", album = "Views", artist = "Drake", duration = 200 + it / 1_000) }

// A real save parked part way through a run, inside one of the run's pages
// and holding that page's transaction: the fake server's copies of the
// songs in the library, then the phone's copies of the same songs joining
// them, so the save renames every song, album and artist by follow (the
// server's ids to the phone's) in groups of `bound`, through the rig's own
// merge. It parks once an earlier page of the run has committed renames of
// songs, which a read then sees in force.
@OptIn(UnstableApi::class)
internal class ParkedSave(val bound: Long) : AutoCloseable {
    val songs = if (bound == SAVE_GROUP_FLOOR) 80 else 1_200
    val db: CatalogDatabase = openTestCatalog()
    val hooks = RunHooks(db)
    val rig = ServerStartRig(saveSongs(songs), db = db, merge = db.boundMerge(SERVER_SOURCE, bound, hooks))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parked = CompletableDeferred<Long>()
    private val go = CompletableDeferred<Unit>()
    private var saving: Deferred<*>? = null

    // The song renames in force at the park, old id to new, and the page
    // parked, by its number in the save.
    var renames: Map<String, String> = emptyMap()
        private set
    var turn = 0L
        private set

    // A song renamed at the park, by its id on the fake server: its old id
    // and the id it is now.
    fun renamedSong(): Triple<String, String, String> {
        val (old, new) = renames.entries.first { it.key.startsWith("$SERVER_SOURCE:") }
        return Triple(old.removePrefix("$SERVER_SOURCE:"), old, new)
    }

    // With `between`, it parks between two pages of the run instead, holding
    // no transaction and no lock but the source-write gate, once renames are
    // in force: where a library list's start, which reads as a stable read,
    // answers.
    fun park(between: Boolean = false) {
        runBlocking {
            db.put(library(SERVER_SOURCE, songs))
            db.boundMerge(SERVER_SOURCE, bound).rebuild()
            db.put(library(DEVICE, songs))
        }
        rig.settle()
        suspend fun parkHere(at: Long) {
            val now = db.query("SELECT `old`, `new` FROM save_rename WHERE kind = '$RENAME_SONG'", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
            if (now.keys.none { it.startsWith("$SERVER_SOURCE:") }) return
            renames = now
            turn = at
            parked.complete(at)
            go.await()
        }
        if (between) {
            hooks.between = between@{ at ->
                val groups = hooks.groups
                if (parked.isCompleted || at < 2 || groups.size < at || groups[(at - 1).toInt()] != groups[(at - 2).toInt()]) return@between
                parkHere(at)
            }
        } else {
            hooks.inside = inside@{ at ->
                if (parked.isCompleted || at < 2 || !hooks.inRun(at - 1)) return@inside
                parkHere(at)
            }
        }
        val save = scope.async { rig.merge.rebuild() }
        saving = save
        save.invokeOnCompletion { parked.completeExceptionally(AssertionError("the save ended without parking inside a run with renames", it)) }
        runBlocking { withTimeout(60_000) { parked.await() } }
    }

    // Lets the save go on to its end; answers how many groups it took.
    fun release(): Long {
        go.complete(Unit)
        runBlocking { withTimeout(60_000) { saving?.await() } }
        return hooks.saves.last()
    }

    // Whether a library song has this id now.
    fun inLibrary(id: String): Boolean = rig.count("SELECT COUNT(*) FROM track WHERE id = ?", id) == 1

    override fun close() {
        go.complete(Unit)
        runCatching { runBlocking { withTimeout(60_000) { saving?.await() } } }
        scope.cancel()
        rig.close()
    }
}

// Asserts a start wrote nothing and began no transaction before handing
// over, and prints what it ran for the proof.
internal fun StartCount.assertNoWrite(route: String) {
    println("RESULT t-route $route: ${writes.size} write statements, ${begins.size} transactions begun, $statements statements, $rowsRead rows read before the hand-off")
    assertTrue("$route wrote before the player had it: $writes", writes.isEmpty())
    assertTrue("$route began a transaction before the player had it: $this", begins.isEmpty())
}
