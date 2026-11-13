package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import app.winters.octo.catalog.QUEUE_HOLDING_SQL
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.catalog.queueQuery
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

// How many songs either side of the current one the server's copy of the
// queue is chosen from.
const val SERVER_VIEW_SPAN = 2_000

// How many times a rebuild reads the window again while edits keep coming.
private const val REBUILD_TRIES = 5

// How big the window is and when it moves. Tests make it small.
data class WindowSizes(
    // Most items the player holds.
    val window: Int = QUEUE_WINDOW,
    // How many it keeps ahead after a top-up, and below how many it tops up.
    val ahead: Int = 300,
    val topUpAt: Int = 150,
    // How many it keeps behind after a trim, and above how many it trims.
    val behind: Int = 100,
    val trimAt: Int = 199,
)

// A play of a list as asked: what, from where, shuffled or not, under what
// name, and the token its tap took (the play's list id), if any.
data class PlayRequest(
    val spec: ListSpec,
    val start: ListStart? = null,
    val shuffle: Boolean = false,
    val source: String? = null,
    val playWhenReady: Boolean = true,
    val pageOwner: String? = null,
    val shownOverWindow: Boolean = false,
    val token: Long? = null,
    // The queue's heading word in full, where a route has its own (a
    // station's, so its refill goes on).
    val word: String? = null,
)

// What a play came to: whether it started, and the words it was refused
// with, if it was.
data class PlayAnswer(val started: Boolean, val cut: Boolean = false, val refusal: String? = null)

// What an add put in: its list, how many songs (none known yet while a big
// one is written), and whether it is still being written.
data class Added(val listId: Long, val count: Int, val building: Boolean)

// The part of Up next past the window: how many songs and how long they
// play, and the quiet rows of lists still being written.
data class QueueSummary(val tailCount: Int = 0, val tailMs: Long = 0, val building: List<BuildingRow> = emptyList())

// A list still being written, shown as one quiet row where it goes: a
// play's rest or a big add, after the entry it follows (null: the start).
data class BuildingRow(val listId: Long, val play: Boolean, val afterEntry: Long?)

// What a player edit tells the queue, so Room follows it, given the player
// that made it (read for the edit's final places). The queue's own window
// moves are made on that player directly and not told.
interface QueueMirror {
    // Entry ids for items that come without any (another app's), so every
    // item the player holds names its row.
    fun stamp(items: List<MediaItem>, set: Boolean): List<MediaItem>

    fun setItems(items: List<MediaItem>, player: Player)

    fun added(items: List<MediaItem>, player: Player)

    fun removing(items: List<MediaItem>)

    fun removed(player: Player)

    fun moved(items: List<MediaItem>, player: Player)

    fun shuffleChanged(on: Boolean)

    fun repeatChanged(mode: Int)

    // The repeat the player itself runs for the listener's.
    fun innerRepeat(): Int

    // Whether the player holds a window of a longer queue, and the repeat
    // the listener chose.
    val windowed: Boolean

    val listenerRepeat: Int

    // Whether the queue ends with the window's last item, or starts with
    // its first, in the order that plays.
    val atLogicalEnd: Boolean

    val atLogicalStart: Boolean

    // Next or Previous at the window's edge: a future that completes once
    // the player is on the next song, or null when the player can do it.
    fun edge(forward: Boolean): ListenableFuture<*>?

    // An item still in the queue, by its entry id.
    fun holds(key: String): Boolean

    // Whether the player's last move was the window stepping on because a
    // song ended at its edge, once: such a move is a song ending, not a skip.
    fun takeAutoStep(): Boolean
}

// Where a list still being written holds the window: a play at its start
// entry, a big add after an entry (null: at the very start).
private class Place(val listId: Long, val play: Boolean, val after: Long?)

// The queue the player plays, kept whole in Room while the player holds a
// window of it: at most `sizes.window` items around the current one. Plays
// and adds of lists go through here; every other edit of the player is
// told to the mirror and written to Room after it. Runs on the main thread;
// Room's work runs on the store's worker, and big lists are written by
// builders off it.
@OptIn(UnstableApi::class)
class QueueWindow(
    // The session's player, for reads and for a play's items.
    private val player: EditableQueue,
    // The player playing now, which the window moves on directly.
    private val inner: () -> EditableQueue,
    private val store: QueueStore,
    // How a list too big to play whole is written in and read back.
    private val fill: QueueFill,
    private val starts: QueueStarts,
    // Songs the player can play, one for each id, null for any gone.
    private val items: suspend (List<String>) -> List<MediaItem?>,
    // The account whose queue is in use.
    private val account: () -> String,
    private val guard: PlayGuard,
    private val scope: CoroutineScope,
    private val sizes: WindowSizes = WindowSizes(),
    // Words for the listener.
    private val say: (String) -> Unit = {},
    // Where each list's shuffle seed comes from: a new one for every play
    // and add, never one derived from the list.
    private val random: java.util.Random = SecureRandom(),
    // A play's starting state, told the start song once it is read.
    private val startingPlays: StartingPlays? = null,
    // The finds a start plays before their rows are written.
    private val finds: FindMap? = null,
) : QueueMirror {
    private val dao = store.dao
    private val apply = QueueApply(store)

    // Told a start's list id once it has every item and row from reads,
    // before it posts a write or hands the player anything. Does nothing in
    // the app.
    internal var handing: (Long) -> Unit = {}

    // The generation in use, 0 for none, and its floors.
    @Volatile var queueId = 0L
        private set(value) {
            field = value
            store.inUse.value = value
        }
    private var floors = Floors.NONE

    // Lists of the generation in use still being written, by id.
    private val places = LinkedHashMap<Long, Place>()

    // Generations handed to the player and not yet in use, by the list
    // whose items start them, and those another app's items started.
    private val starting = HashMap<Long, Long>()
    private val strangers = HashMap<Long, Long>()

    // Moves on every change of the window, so a read made before one is
    // read again.
    private var version = 0L
    private var _windowed = false
    private var _listenerRepeat = Player.REPEAT_MODE_OFF
    private val _logicalCount = MutableStateFlow(0)
    val logicalCount: StateFlow<Int> = _logicalCount
    private val _atLogicalEnd = MutableStateFlow(true)
    val atLogicalEndFlow: StateFlow<Boolean> = _atLogicalEnd
    private val _summary = MutableStateFlow(QueueSummary())
    val summary: StateFlow<QueueSummary> = _summary
    private var topUp: Job? = null

    // Lists of the generation in use still being written, for Up next.
    private val _building = MutableStateFlow<List<BuildingRow>>(emptyList())
    val building: StateFlow<List<BuildingRow>> = _building

    override val windowed: Boolean get() = _windowed || places.isNotEmpty()
    override val listenerRepeat: Int get() = _listenerRepeat
    override val atLogicalEnd: Boolean get() = _atLogicalEnd.value && places.isEmpty()
    override var atLogicalStart: Boolean = true
        private set

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshEnds()
            topUpSoon()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (inner().playbackState == Player.STATE_ENDED && windowed && !atLogicalEnd) endedAtEdge()
        }
    }

    init {
        scope.launch { fill.joins.collect { joined(it) } }
        scope.launch { fill.radioWords.collect { say(it) } }
        scope.launch { store.doubled.collect { dropDoubled(it) } }
        player.addListener(listener)
    }

    // A rename left a list that plays each song once holding a song twice:
    // the row later in the order that plays leaves the player and Room,
    // unless it is the song playing, when the other one does.
    // It is the app's own upkeep: while a blend is under way it waits, and
    // decides again with the song on then.
    private fun dropDoubled(pairs: List<Doubled>) {
        val queueId = queueId
        inner().upkeep {
            if (queueId != this.queueId) return@upkeep
            val current = entryOfKey(inner().currentMediaItem?.entryId)
            val out = pairs.filter { it.queueId == queueId }.map { if (it.drop == current) it.keep else it.drop }.distinct()
            if (out.isNotEmpty()) removeEntries(out)
        }
    }

    fun release() {
        player.removeListener(listener)
    }

    private val order: QueueOrder get() = if (player.shuffleModeEnabled) QueueOrder.Shuffle else QueueOrder.Play

    private fun publishPlaces() {
        _building.value = places.values.map { BuildingRow(it.listId, it.play, it.after) }
        store.building.value = _building.value
    }

    // ---- Playing a list ----

    // Plays a list in place of the queue: its start read with no write, the
    // player set from reads, then the worker writes. Answers once it plays,
    // or was refused with the queue as it was.
    suspend fun play(request: PlayRequest, explicit: Boolean = true): PlayAnswer {
        val mark = guard.mark()
        val listId = request.token ?: store.claimList()
        val newQueue = store.claimQueue()
        // The old song waits, kept, so a refusal leaves it ready to go on.
        if (explicit && player.playWhenReady) player.pause()
        val stopped = stopBuilders(queueId)
        var answer: PlayAnswer? = null
        try {
            val read = withContext(Dispatchers.IO) {
                starts.read(request.spec, request.start, request.shuffle, request.shownOverWindow, request.pageOwner, listId)
            }
            hold(listId, read)
            answer = when (read) {
                is StartRead.Refused -> {
                    resume(stopped)
                    PlayAnswer(false, refusal = read.words)
                }
                is StartRead.Whole -> playWhole(request, read, listId, newQueue, mark, stopped)
                is StartRead.Big -> playBig(request, read, listId, newQueue, mark, stopped)
            }
            return answer
        } finally {
            // A big list's answer goes with its build; one no build took
            // over goes now, and so do the finds the play held.
            if (answer?.started != true) {
                finds?.release(listId)
                if (request.spec.fromServer) runCatching { starts.dropCopy(listId) }
            }
        }
    }

    // Holds the finds a start plays, whose rows are not written yet, so
    // their items are made from them.
    private fun hold(listId: Long, read: StartRead) {
        val finds = finds ?: return
        when (read) {
            is StartRead.Whole -> finds.hold(listId, read.finds)
            is StartRead.Big -> read.find?.let { finds.hold(listId, listOf(it)) }
            is StartRead.Refused -> Unit
        }
    }

    // The queue's first rows, posted once the start hands over: the finds
    // the play holds are kept first, and a find a keep could not write
    // before is kept again.
    private fun postFirstRows(listId: Long, write: suspend () -> Unit) {
        store.post {
            finds?.let { f ->
                f.keep(listId)
                f.retry()
            }
            write()
        }
    }

    // The items and rows of a list played whole, from reads.
    class Whole(val played: List<MediaItem>, val rows: List<QueueEntryEntity>, val start: Int, val list: QueueListEntity)

    suspend fun readWhole(request: PlayRequest, read: StartRead.Whole, listId: Long, queueId: Long): Whole? {
        val seed = random.nextLong()
        val (ids, found, lengths) = withContext(Dispatchers.IO) {
            val followed = renamedSongs(store.db, read.ids.map { store.followed(it) })
            Triple(followed, items(followed), songLengths(store, followed))
        }
        val start = read.start.coerceIn(0, ids.size - 1)
        val rows = ArrayList<QueueEntryEntity>()
        val played = ArrayList<MediaItem>()
        var startAt = 0
        val word = sourceWord(request)
        // A shuffled play's start plays first, whether it was picked at
        // random or tapped, then every other song in the seed's order.
        val first = read.firstShuffled || request.shuffle
        ids.forEachIndexed { i, id ->
            val item = found[i] ?: return@forEachIndexed
            val n = playNumber(i, start)
            val qk = playKey(n)
            val sk = playShuffledKey(n, seed, first)
            if (i == start) startAt = played.size
            rows += QueueEntryEntity(queueId, entryOf(listId, n), id, listId, qk, sk, lengths[id] ?: item.mediaMetadata.durationMs ?: 0, firstKeyBefore(n, seed, first))
            played += item.withEntry(entryKey(entryOf(listId, n))).withSource(word)
        }
        if (played.isEmpty()) return null
        return Whole(played, rows, startAt, listRow(listId, queueId, request, seed, QueueBuilds.JOINED).copy(songsOnce = read.songsOnce))
    }

    private suspend fun playWhole(request: PlayRequest, read: StartRead.Whole, listId: Long, queueId: Long, mark: PlayMark, stopped: List<Pair<Long, Job>>): PlayAnswer {
        val whole = readWhole(request, read, listId, queueId)
        if (whole == null || !guard.stillWanted(mark)) {
            resume(stopped)
            return PlayAnswer(false, refusal = if (whole == null) NOTHING_TO_PLAY else null)
        }
        handing(listId)
        postFirstRows(listId) { store.writeList(whole.list, whole.rows) }
        starting[listId] = queueId
        learned(listId, whole.played[whole.start])
        setPlayer(whole.played, whole.rows, whole.start, read.positionMs, request.shuffle, request.playWhenReady)
        return PlayAnswer(true, cut = read.cut)
    }

    // The start item and rows of a big list, from reads.
    class Big(val item: MediaItem, val row: QueueEntryEntity, val list: QueueListEntity)

    suspend fun readBig(request: PlayRequest, read: StartRead.Big, listId: Long, queueId: Long): Big? {
        val seed = random.nextLong()
        val (startId, item) = withContext(Dispatchers.IO) {
            val id = renamedSongs(store.db, listOf(store.followed(read.trackId))).single()
            id to items(listOf(id)).firstOrNull()
        }
        if (item == null) return null
        val entry = entryOf(listId, 0)
        val first = read.firstShuffled || request.shuffle
        val (qk, sk) = fill.startKeys(seed, first)
        val row = QueueEntryEntity(queueId, entry, startId, listId, qk, sk, item.mediaMetadata.durationMs ?: 0, firstKeyBefore(0, seed, first))
        val list = listRow(listId, queueId, request, seed, QueueBuilds.BUILDING).copy(
            anchor = read.key?.token,
            cursor = if (read.noAnchor) QueueBuilds.NO_ANCHOR else null,
            expected = read.count?.toLong(),
        )
        return Big(item.withEntry(entryKey(entry)).withSource(sourceWord(request)), row, list)
    }

    private suspend fun playBig(request: PlayRequest, read: StartRead.Big, listId: Long, queueId: Long, mark: PlayMark, stopped: List<Pair<Long, Job>>): PlayAnswer {
        val big = readBig(request, read, listId, queueId) ?: run {
            // A start gone from the library: the list's first row stands in.
            val first = withContext(Dispatchers.IO) { starts.read(request.spec, null, false, true, request.pageOwner, listId) }
            hold(listId, first)
            if (first is StartRead.Big && first.trackId != read.trackId) readBig(request, first, listId, queueId) else null
        }
        if (big == null || !guard.stillWanted(mark)) {
            resume(stopped)
            return PlayAnswer(false, refusal = if (big == null) NOTHING_TO_PLAY else null)
        }
        handing(listId)
        openBig(big, request.spec.fromServer)
        learned(listId, big.item)
        setPlayer(listOf(big.item), listOf(big.row), 0, read.positionMs, request.shuffle, request.playWhenReady)
        return PlayAnswer(true, cut = read.cut)
    }

    // The starting state shows the song a play starts with once it is read.
    private fun learned(listId: Long, item: MediaItem) {
        val meta = item.mediaMetadata
        startingPlays?.learned(listId, meta.title?.toString(), meta.artist?.toString(), meta.artworkRef())
    }

    // Posts a big list's opening and starts its builder, which waits for
    // it; the start entry plays meanwhile. A server list's builder owns the
    // answer its start drained: once it ends, however it ends, that goes.
    private fun openBig(big: Big, fromServer: Boolean) {
        val opened = CompletableDeferred<Unit>()
        postFirstRows(big.list.listId) {
            try {
                store.writeList(big.list, listOf(big.row))
            } finally {
                opened.complete(Unit)
            }
        }
        val listId = big.list.listId
        val job = fill.start(listId, big.list.queueId, opened)
        if (fromServer) job.invokeOnCompletion { runCatching { starts.dropCopy(listId) } }
        starting[listId] = big.list.queueId
        pendingPlaces[listId] = Place(listId, play = true, after = big.row.entry)
    }

    // Big plays' places, by list, each taken once its generation is in use.
    private val pendingPlaces = HashMap<Long, Place>()

    // A play Media3 applies (a car's choice, a spoken request): the shuffle
    // mode it plays with, and its rows, whose keys give the player's order.
    private class Expected(val shuffle: Boolean, val rows: List<QueueEntryEntity>)

    private val expected = HashMap<Long, Expected>()

    // Media3 sets the items itself, and the generation is taken up when it
    // does, with the play's shuffle mode and the order its keys give.
    fun expect(listId: Long, queueId: Long, shuffle: Boolean, rows: List<QueueEntryEntity>) {
        starting[listId] = queueId
        expected[listId] = Expected(shuffle, rows)
    }

    // Whether the player plays shuffled now.
    val shuffleOn: Boolean get() = player.shuffleModeEnabled

    // A play Media3 applies, read as a phone play is: the old queue's
    // builders stop before its read and go on when it is refused or a newer
    // choice overtook it, and the old queue stays in use until Media3 sets
    // the new one's items. Answers those items in queue order, where to
    // start and how far in. A server list's copy no build took over goes.
    suspend fun playForMedia3(request: PlayRequest, guard: PlayGuard, mark: PlayMark, emptyWords: String): CarStarted {
        val listId = store.claimList()
        val newQueue = store.claimQueue()
        val stopped = stopBuilders(queueId)
        var handed = false
        try {
            val read = withContext(Dispatchers.IO) { starts.read(request.spec, request.start, request.shuffle, request.shownOverWindow, request.pageOwner, listId) }
            hold(listId, read)
            return when (read) {
                is StartRead.Refused -> CarStarted.Refused(if (read.words == NOTHING_TO_PLAY) emptyWords else read.words)
                is StartRead.Whole -> {
                    val whole = readWhole(request, read, listId, newQueue) ?: return CarStarted.Refused(emptyWords)
                    if (!guard.stillWanted(mark)) return CarStarted.Superseded
                    handing(listId)
                    postFirstRows(listId) { store.writeList(whole.list, whole.rows) }
                    val byQk = whole.rows.sortedWith(compareBy({ it.qk }, { it.entry }))
                    expect(listId, newQueue, request.shuffle, byQk)
                    handed = true
                    val startEntry = whole.rows[whole.start.coerceIn(whole.rows.indices)].entry
                    val itemByEntry = whole.played.associateBy { entryOfKey(it.entryId) }
                    CarStarted.Items(byQk.mapNotNull { itemByEntry[it.entry] }, byQk.indexOfFirst { it.entry == startEntry }.coerceAtLeast(0), read.positionMs)
                }
                is StartRead.Big -> {
                    val big = readBig(request, read, listId, newQueue) ?: return CarStarted.Refused(emptyWords)
                    if (!guard.stillWanted(mark)) return CarStarted.Superseded
                    handing(listId)
                    openBig(big, request.spec.fromServer)
                    expected[listId] = Expected(request.shuffle, listOf(big.row))
                    handed = true
                    CarStarted.Items(listOf(big.item), 0, read.positionMs)
                }
            }
        } finally {
            if (!handed) {
                resume(stopped)
                finds?.release(listId)
                if (request.spec.fromServer) runCatching { starts.dropCopy(listId) }
            }
        }
    }

    private fun sourceWord(request: PlayRequest): String {
        request.word?.let { return it }
        val name = request.source ?: (request.spec as? ListSpec.Radio)?.source ?: (request.spec as? ListSpec.Ids)?.source
        return QueueSource.Played(name).encoded()
    }

    private fun listRow(listId: Long, queueId: Long, request: PlayRequest, seed: Long, fillState: Int) = QueueListEntity(
        listId = listId,
        queueId = queueId,
        source = sourceWord(request),
        autoplay = false,
        spec = request.spec.toJson(),
        seed = seed,
        fillState = fillState,
        phase = null,
        cursor = null,
        anchor = null,
        phase0End = null,
        expected = null,
        swapCount = null,
        qBase = null,
        qStep = null,
        sBase = null,
        sStep = null,
        copyOwner = null,
    )

    // Sets the player to a new queue's items: queue order by play key, the
    // shuffled order by shuffled key, and starts it. The mirror sees the
    // items' generation and makes it the one in use.
    private fun setPlayer(played: List<MediaItem>, rows: List<QueueEntryEntity>, startAt: Int, positionMs: Long, shuffle: Boolean, play: Boolean) {
        val byQk = rows.sortedWith(compareBy({ it.qk }, { it.entry }))
        val startEntry = rows[startAt.coerceIn(rows.indices)].entry
        val itemByEntry = played.associateBy { entryOfKey(it.entryId) }
        val laid = byQk.mapNotNull { itemByEntry[it.entry] }
        player.setMediaItems(laid, byQk.indexOfFirst { it.entry == startEntry }.coerceAtLeast(0), positionMs)
        val now = inner()
        now.setPlayOrder(shuffleOrder(byQk))
        now.shuffleModeEnabled = shuffle
        now.repeatMode = innerRepeat()
        player.prepare()
        if (play) player.play()
        val listId = listOfEntry(startEntry)
        fill.hooks.startPlaying(listId)
        watched = Watched(listId)
        watchUpNext()
    }

    // How far Up next has filled for the last play, told to the fill's
    // hooks once at 10 songs after the current one and once when full.
    private class Watched(val listId: Long, var ten: Boolean = false, var full: Boolean = false)

    private var watched: Watched? = null

    private fun watchUpNext() {
        val watch = watched ?: return
        val ahead = upcoming(inner()).size
        if (!watch.ten && ahead >= 10) {
            watch.ten = true
            fill.hooks.upNextHolds(watch.listId, ahead)
        }
        if (!watch.full && (ahead >= sizes.ahead || (!windowed && queueId != 0L && starting.isEmpty()))) {
            watch.full = true
            fill.hooks.upNextHolds(watch.listId, ahead)
        }
        if (watch.ten && watch.full) watched = null
    }

    // Where each of `byQk` sits in shuffled order, as the player's order.
    // `byQk` is every item the player holds: the window keeps their keys
    // and no others.
    private fun shuffleOrder(byQk: List<QueueEntryEntity>): IntArray {
        heldKeys.keys.retainAll(byQk.mapTo(HashSet()) { it.entry })
        know(byQk)
        val index = byQk.withIndex().associate { it.value.entry to it.index }
        return byQk.sortedWith(compareBy({ it.sk }, { it.entry })).map { index.getValue(it.entry) }.toIntArray()
    }

    // Stops the builders of a generation and answers them, so a play that
    // does not replace it lets them go on.
    private fun stopBuilders(queueId: Long): List<Pair<Long, Job>> {
        if (queueId == 0L) return emptyList()
        val stopped = store.buildersOf(queueId).mapNotNull { id -> store.builderOf(id)?.let { id to it } }
        stopped.forEach { it.second.cancel() }
        return stopped
    }

    private fun resume(stopped: List<Pair<Long, Job>>) {
        if (stopped.isEmpty()) return
        val queueId = queueId
        store.scope.launch {
            stopped.forEach { it.second.join() }
            stopped.forEach { (listId, _) -> fill.start(listId, queueId) }
        }
    }

    // ---- Adding a list ----

    // Puts a list next, or at the end of the listener's songs (before
    // Autoplay's still to come). Up to QUEUE_WINDOW songs go in at once; a
    // bigger list is written by its builder and joins after.
    suspend fun addList(spec: ListSpec, next: Boolean, source: String?, shownOverWindow: Boolean = false): Added? {
        if (queueId == 0L || player.mediaItemCount == 0) {
            val answer = play(PlayRequest(spec, null, false, source, playWhenReady = false, shownOverWindow = shownOverWindow), explicit = false)
            return if (answer.started) Added(0, player.mediaItemCount, false) else null
        }
        val listId = store.claimList()
        val read = withContext(Dispatchers.IO) { starts.read(spec, null, false, shownOverWindow, null, listId, adding = true) }
        hold(listId, read)
        val word = (if (source == null && (spec as? ListSpec.Ids)?.source == null) QueueSource.You else QueueSource.Played(source ?: (spec as? ListSpec.Ids)?.source)).encoded()
        var added = false
        try {
            return when (read) {
                is StartRead.Refused -> null
                is StartRead.Whole -> {
                    val (ids, found, lengths) = withContext(Dispatchers.IO) {
                        val followed = renamedSongs(store.db, read.ids.map { store.followed(it) })
                        Triple(followed, items(followed), songLengths(store, followed))
                    }
                    val kept = ids.indices.filter { found[it] != null }
                    if (kept.isEmpty()) return null
                    val rows = kept.mapIndexed { n, i -> NewRow(entryOf(listId, n.toLong()), ids[i], listId, lengths[ids[i]] ?: 0) }
                    val played = kept.mapIndexed { n, i -> found[i]!!.withEntry(entryKey(rows[n].entry)).withSource(word) }
                    handing(listId)
                    // The finds the add holds are kept before its rows go in.
                    if (read.finds.isNotEmpty()) postFirstRows(listId) {}
                    insertRun(rows, played, next, StoredList(listId, word, autoplay = false, songsOnce = read.songsOnce))
                    added = true
                    Added(listId, rows.size, false)
                }
                is StartRead.Big -> {
                    handing(listId)
                    added = true
                    addBig(spec, listId, next, word)
                }
            }
        } finally {
            if (!added) {
                finds?.release(listId)
                if (spec.fromServer) runCatching { starts.dropCopy(listId) }
            }
        }
    }

    // A run of songs placed after the current song (next) or at the end of
    // the listener's songs: the part inside the window goes into the player
    // now; Room follows in its turn, or at the join of a list being written.
    private fun insertRun(rows: List<NewRow>, played: List<MediaItem>, next: Boolean, list: StoredList, autoplay: Boolean = false) {
        val now = inner()
        val current = now.currentMediaItemIndex
        val currentEntry = entryOfKey(now.currentMediaItem?.entryId)
        if (next) {
            record(QueueEdit.Insert(rows, Spot(after = currentEntry, behind = behind(now, current, false)), Spot(after = currentEntry, behind = behind(now, current, true)), list))
            now.addNext(played)
        } else {
            val (spot, at) = endSpot(now, autoplay)
            record(QueueEdit.Insert(rows, spot, spot, list))
            if (at != null) now.addMediaItems(at, played)
        }
        afterEdit()
    }

    // Where an add at the end goes: before the first Autoplay song still to
    // come, else after the last song, in both orders; and the player's
    // place for it when that is inside the window, else null.
    private fun endSpot(now: Player, autoplay: Boolean): Pair<Spot, Int?> {
        val firstAutoplay = if (autoplay) null else upcoming(now).firstOrNull { now.getMediaItemAt(it).isAutoplay }
        if (firstAutoplay != null) return Spot(before = entryOfKey(now.getMediaItemAt(firstAutoplay).entryId)) to firstAutoplay
        return Spot.END to if (atLogicalEnd) now.mediaItemCount else null
    }

    // The entries before the item at `index` in an order, nearest first,
    // for a run whose neighbor goes before it lands.
    private fun behind(now: Player, index: Int, shuffled: Boolean): List<Long> {
        val out = ArrayList<Long>()
        val timeline = now.currentTimeline
        if (timeline.isEmpty || index !in 0 until now.mediaItemCount) return out
        var i = timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, shuffled)
        while (i != C.INDEX_UNSET && out.size < sizes.window) {
            entryOfKey(now.getMediaItemAt(i).entryId)?.let(out::add)
            i = timeline.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, shuffled)
        }
        return out
    }

    // A big add: its list opened with its keys in its gap and written by
    // its builder; while another list of the queue is written it waits for
    // that one's join. The window's items past its place leave the player
    // until it joins.
    private suspend fun addBig(spec: ListSpec, listId: Long, next: Boolean, word: String): Added {
        val queueId = queueId
        val now = inner()
        val currentEntry = entryOfKey(now.currentMediaItem?.entryId)
        val spot = if (next) Spot(after = currentEntry) else endSpot(now, false).first
        val building = places.keys.firstOrNull()
        val list = QueueListEntity(
            listId, queueId, word, false, spec.toJson(), random.nextLong(), if (building != null) QueueBuilds.WAITING else QueueBuilds.BUILDING,
            null, QueueBuilds.NO_ANCHOR, null, 0, 0, null, null, null, 0, null, null, null, null, null,
        )
        val opened = CompletableDeferred<Unit>()
        store.post {
            try {
                if (building != null) {
                    store.writeList(list, emptyList())
                    store.putPending(building, QueueEdit.AddList(listId, spot, spot).toJson())
                } else {
                    val (qBase, qStep) = store.addKeys(queueId, QueueOrder.Play, spot)
                    val (sBase, sStep) = store.addKeys(queueId, QueueOrder.Shuffle, spot)
                    store.writeList(list.copy(qBase = qBase, qStep = qStep, sBase = sBase, sStep = sStep), emptyList())
                }
            } finally {
                opened.complete(Unit)
            }
        }
        if (building == null) {
            val job = fill.start(listId, queueId, opened)
            if (spec.fromServer) job.invokeOnCompletion { runCatching { starts.dropCopy(listId) } }
        }
        // Nothing past the add's place plays before it joins. At the
        // queue's end its own rows are the place, and the rest of the queue
        // still plays up to them.
        val after = when {
            next -> currentEntry
            spot == Spot.END -> null
            else -> lastEntryOf(now)
        }
        places[listId] = Place(listId, play = false, after = after)
        publishPlaces()
        if (after != null) cutPast(now, after)
        refreshCount()
        return Added(listId, 0, true)
    }

    private fun lastEntryOf(now: Player): Long? {
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return null
        return entryOfKey(now.getMediaItemAt(timeline.getLastWindowIndex(now.shuffleModeEnabled)).entryId)
    }

    private fun firstEntryOf(now: Player): Long? {
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return null
        return entryOfKey(now.getMediaItemAt(timeline.getFirstWindowIndex(now.shuffleModeEnabled)).entryId)
    }

    // Takes out of the player, and only there, every item after `after` in
    // the order that plays.
    private fun cutPast(now: EditableQueue, after: Long?) {
        val ahead = upcoming(now)
        val currentEntry = entryOfKey(now.currentMediaItem?.entryId)
        val from = when (after) {
            null, currentEntry -> 0
            else -> ahead.indexOfFirst { entryOfKey(now.getMediaItemAt(it).entryId) == after } + 1
        }
        val gone = ahead.drop(from.coerceAtLeast(0))
        if (gone.isEmpty()) return
        now.removeRuns(removalRuns(gone))
        version++
    }

    // Takes a list back out: an add's Undo. A list still being written
    // stops and its rows go a page at a time.
    fun removeList(listId: Long) {
        val queueId = queueId
        if (places.remove(listId) != null) {
            publishPlaces()
            store.cancelBuilder(listId)
            store.deleteList(queueId, listId)
            rebuild()
            return
        }
        val now = inner()
        val held = (0 until now.mediaItemCount).filter { entryOfKey(now.getMediaItemAt(it).entryId)?.let(::listOfEntry) == listId }
        if (held.isNotEmpty()) now.removeRuns(removalRuns(held))
        store.deleteList(queueId, listId)
        afterEdit()
    }

    // ---- Entry commands from Up next ----

    // Plays an entry anywhere in the queue: one in the window is a seek; a
    // head or tail entry rebuilds the window around it.
    fun playEntry(entry: Long) {
        val at = indexOf(entry)
        if (at != null) {
            player.seekToDefaultPosition(at)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            return
        }
        scope.launch {
            val window = readAround(entry) ?: return@launch
            val placed = itemsOf(window) ?: return@launch
            val rows = placed.first
            val start = rows.indexOfFirst { it.entry == entry }.coerceAtLeast(0)
            val byQk = rows.sortedWith(compareBy({ it.qk }, { it.entry }))
            val itemByEntry = placed.second
            val now = inner()
            now.setMediaItems(byQk.mapNotNull { itemByEntry[it.entry] }, byQk.indexOfFirst { it.entry == rows[start].entry }.coerceAtLeast(0), 0)
            now.setPlayOrder(shuffleOrder(byQk))
            version++
            player.prepare()
            player.play()
            refreshEnds()
        }
    }

    // Moves an entry before `beforeEntry` in queue order (null: last).
    fun moveEntry(entry: Long, beforeEntry: Long?) {
        val from = indexOf(entry)
        val to = beforeEntry?.let(::indexOf)
        if (from != null && (to != null || beforeEntry == null)) {
            player.moveMediaItem(from, (to ?: player.mediaItemCount).let { if (it > from) it - 1 else it })
            return
        }
        record(QueueEdit.Move(entry, if (beforeEntry != null) Spot(before = beforeEntry) else Spot.END))
        if (from != null) inner().removeRuns(listOf(from..from))
        afterEdit()
    }

    // Takes entries out and answers their Undo, which puts each back with
    // its keys, where it was.
    suspend fun takeOut(entries: List<Long>): (() -> Unit)? {
        val queueId = queueId
        if (queueId == 0L) return null
        val rows = store.inTurn { entries.chunked(900).flatMap { dao.entries(queueId, it) } }
        if (rows.isEmpty() && entries.none { indexOf(it) != null }) return null
        removeEntries(entries)
        return {
            if (this.queueId == queueId) {
                record(QueueEdit.PutBack(rows.map(StoredRow::of)))
                rebuild()
            }
        }
    }

    // Takes entries out, in the window and past it.
    fun removeEntries(entries: List<Long>) {
        val inWindow = entries.mapNotNull(::indexOf)
        record(QueueEdit.Remove(entries))
        if (inWindow.isNotEmpty()) inner().removeRuns(removalRuns(inWindow))
        afterEdit()
    }

    fun indexOf(entry: Long): Int? = (0 until player.mediaItemCount).firstOrNull { entryOfKey(player.getMediaItemAt(it).entryId) == entry }

    // ---- The mirror ----

    override fun stamp(items: List<MediaItem>, set: Boolean): List<MediaItem> {
        if (items.all { entryOfKey(it.entryId) != null }) return items
        val listId = store.claimListNow()
        if (set) strangers[listId] = store.claimQueueNow()
        return items.mapIndexed { i, item ->
            if (entryOfKey(item.entryId) != null) item else item.withEntry(entryKey(entryOf(listId, i.toLong())))
        }
    }

    override fun setItems(items: List<MediaItem>, player: Player) {
        val first = items.firstOrNull()?.let { entryOfKey(it.entryId) }?.let(::listOfEntry) ?: return
        starting.remove(first)?.let { generation ->
            // A play Media3 applied takes up its own order and shuffle mode,
            // set on the player directly: no shuffle change of the queue
            // being replaced.
            expected.remove(first)?.let { play ->
                val now = inner()
                val byEntry = play.rows.associateBy { it.entry }
                val laid = (0 until now.mediaItemCount).mapNotNull { byEntry[entryOfKey(now.getMediaItemAt(it).entryId)] }
                if (laid.size == now.mediaItemCount) now.setPlayOrder(shuffleOrder(laid))
                if (now.shuffleModeEnabled != play.shuffle) now.shuffleModeEnabled = play.shuffle
            }
            // A play Media3 never applied keeps nothing.
            expected.keys.retainAll(starting.keys)
            adopt(generation, player, pendingPlaces.remove(first))
            // Finds an older play's keep could not write go with its queue.
            finds?.replaced(first)
            return
        }
        strangers.remove(first)?.let { generation ->
            // A queue another app set: it becomes a generation of its own.
            val seed = random.nextLong()
            val rows = items.mapIndexedNotNull { i, item ->
                val e = entryOfKey(item.entryId) ?: return@mapIndexedNotNull null
                val qk = KEY_MID + i * KEY_STEP
                QueueEntryEntity(generation, e, item.mediaId, listOfEntry(e), qk, shuffledKey(seed, qk), item.mediaMetadata.durationMs ?: 0)
            }
            val list = QueueListEntity(first, generation, QueueSource.You.encoded(), false, null, seed, QueueBuilds.JOINED, null, null, null, 0, 0, null, null, null, 0, null, null, null, null, null)
            store.post { store.writeList(list, rows) }
            adopt(generation, player)
            inner().setPlayOrder(shuffleOrder(rows))
            return
        }
        // The queue's own items set again (a resumption): the shuffled
        // order is Room's again.
        realign()
    }

    // Sets the player's shuffled order from Room's keys of what it holds.
    private fun realign() {
        scope.launch { realignNow() }
    }

    private suspend fun realignNow() {
        val queueId = queueId
        if (queueId == 0L) return
        val now = inner()
        val held = (0 until now.mediaItemCount).mapNotNull { entryOfKey(now.getMediaItemAt(it).entryId) }
        val rows = withContext(Dispatchers.IO) { store.inTurn { held.chunked(900).flatMap { dao.entries(queueId, it) } } }
        if (queueId != this@QueueWindow.queueId) return
        val byEntry = rows.associateBy { it.entry }
        val laid = (0 until now.mediaItemCount).mapNotNull { byEntry[entryOfKey(now.getMediaItemAt(it).entryId)] }
        if (laid.size == now.mediaItemCount) now.setPlayOrder(shuffleOrder(laid))
    }

    // The queue as a server keeps it: up to 2,000 songs either side of the
    // current one in the order that plays, read from Room.
    suspend fun serverView(): QueueSnapshot {
        val queueId = queueId
        val now = inner()
        val current = entryOfKey(now.currentMediaItem?.entryId)
        val position = now.currentPosition
        val order = order
        val floors = floors
        if (queueId == 0L || current == null) return QueueSnapshot(emptyList(), emptyList(), 0, 0, _listenerRepeat, false)
        val rows = store.inTurn {
            val row = dao.entry(queueId, current) ?: return@inTurn emptyList<QueueEntryEntity>() to 0
            val back = fill.page(queueId, order, row, forward = false, SERVER_VIEW_SPAN, floors).reversed()
            val ahead = fill.page(queueId, order, row, forward = true, SERVER_VIEW_SPAN, floors)
            (back + row + ahead) to back.size
        }
        return QueueSnapshot(rows.first.map { it.trackId }, emptyList(), rows.second, position, _listenerRepeat, false)
    }

    private fun adopt(generation: Long, now: Player, place: Place? = null) {
        val old = queueId
        queueId = generation
        floors = Floors.NONE
        version++
        places.clear()
        place?.let { places[it.listId] = it }
        // A new queue holds what the player holds until its count says more.
        _windowed = false
        publishPlaces()
        val account = account()
        val current = entryOfKey(now.currentMediaItem?.entryId)
        val position = now.currentPosition
        val repeat = _listenerRepeat
        val shuffle = now.shuffleModeEnabled
        store.post { store.adopt(account, generation, current, position, repeat, shuffle) }
        if (old != 0L && old != generation) {
            dropHeld()
            if (store.retire(old)) store.deleteGeneration(old)
        }
        refreshCount()
    }

    // The items being removed, before the player takes them out.
    private var removingEntries: List<Long> = emptyList()

    override fun removing(items: List<MediaItem>) {
        removingEntries = items.mapNotNull { entryOfKey(it.entryId) }
    }

    override fun removed(player: Player) {
        val gone = removingEntries
        removingEntries = emptyList()
        if (gone.isEmpty()) return
        record(QueueEdit.Remove(gone))
        afterEdit()
    }

    override fun added(items: List<MediaItem>, player: Player) {
        if (queueId == 0L) return
        val entries = items.mapNotNull { entryOfKey(it.entryId) }
        if (entries.isEmpty()) return
        val at = (0 until player.mediaItemCount).associateBy { entryOfKey(player.getMediaItemAt(it).entryId) }
        val indexes = entries.mapNotNull { at[it] }
        if (indexes.isEmpty()) return
        val firstIndex = indexes.min()
        val lastIndex = indexes.max()
        val timeline = player.currentTimeline
        val beforeQ = if (firstIndex > 0) entryOfKey(player.getMediaItemAt(firstIndex - 1).entryId) else null
        val nextQ = (lastIndex + 1).takeIf { it < player.mediaItemCount }?.let { entryOfKey(player.getMediaItemAt(it).entryId) }
        val firstInShuffle = indexes.minByOrNull { rankInShuffle(it, timeline) } ?: firstIndex
        val prevS = timeline.getPreviousWindowIndex(firstInShuffle, Player.REPEAT_MODE_OFF, true).takeIf { it != C.INDEX_UNSET }
        val beforeS = prevS?.let { entryOfKey(player.getMediaItemAt(it).entryId) }
        val listId = listOfEntry(entries.first())
        val rows = items.mapNotNull { item ->
            val e = entryOfKey(item.entryId) ?: return@mapNotNull null
            NewRow(e, item.mediaId, listOfEntry(e), item.mediaMetadata.durationMs ?: 0)
        }
        val play = if (beforeQ != null) Spot(after = beforeQ, behind = behind(player, firstIndex - 1, false)) else Spot(before = nextQ)
        val shuffle = if (beforeS != null) Spot(after = beforeS) else Spot(before = nextQ)
        val autoplay = items.all { it.isAutoplay }
        record(QueueEdit.Insert(rows, play, shuffle, StoredList(listId, items.first().queueSource.encoded(), autoplay)))
        afterEdit()
    }

    private fun rankInShuffle(index: Int, timeline: Timeline): Int {
        var rank = 0
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && i != index) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            rank++
        }
        return rank
    }

    override fun moved(items: List<MediaItem>, player: Player) {
        if (queueId == 0L) return
        for (item in items) {
            val e = entryOfKey(item.entryId) ?: continue
            val at = (0 until player.mediaItemCount).firstOrNull { entryOfKey(player.getMediaItemAt(it).entryId) == e } ?: continue
            val before = if (at > 0) entryOfKey(player.getMediaItemAt(at - 1).entryId) else null
            val after = if (at + 1 < player.mediaItemCount) entryOfKey(player.getMediaItemAt(at + 1).entryId) else null
            record(QueueEdit.Move(e, if (before != null) Spot(after = before) else Spot(before = after)))
        }
        afterEdit()
    }

    // Turning shuffle on keeps the current song first, then every other
    // song once in the play's own shuffled order, played ones included: the
    // current row takes the first shuffled key. Turning shuffle off goes on
    // in play order from the current song; rows that held the first key take
    // their own back. The player's order follows from the keys the window
    // holds at once, as Room's will be, so nothing plays out of order before
    // Room's are written.
    override fun shuffleChanged(on: Boolean) {
        saveState()
        val queueId = queueId
        val now = inner()
        val current = entryOfKey(now.currentMediaItem?.entryId)
        if (queueId != 0L && current != null) {
            val floors = floors
            keyedOrder(now, on)?.let(now::setPlayOrder)
            if (on) {
                store.post { store.keyFirst(queueId, current, floors) }
            } else {
                store.post { store.unkeyFirst(queueId, floors, keep = null) }
            }
        }
        if (windowed) {
            rebuild()
        } else {
            afterEdit()
            reordering = scope.launch { realignNow() }
        }
    }

    // The keys of the items the player holds, by entry, as Room last gave
    // them (bounded by the window).
    private class HeldKeys(val sk: Long, val skBefore: Long?)

    private val heldKeys = HashMap<Long, HeldKeys>()

    // Rows the player holds, read from Room or made by the window.
    private fun know(rows: List<QueueEntryEntity>) {
        rows.forEach { heldKeys[it.entry] = HeldKeys(it.sk, it.skBefore) }
    }

    // The player's shuffled order from the keys of what it holds, after a
    // toggle: turning shuffle on puts the current item first and gives every
    // other row that held the first key its own back; turning it off gives
    // them all back. An item whose keys the window has not heard yet (an add
    // Room has not placed) keeps its place after the item before it. Null
    // when the player holds nothing.
    private fun keyedOrder(now: Player, on: Boolean): IntArray? {
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return null
        val count = now.mediaItemCount
        val current = now.currentMediaItemIndex
        val entries = (0 until count).map { entryOfKey(now.getMediaItemAt(it).entryId) }
        val currentEntry = entries.getOrNull(current)
        for (e in entries) {
            val k = e?.let(heldKeys::get) ?: continue
            heldKeys[e] = when {
                on && e == currentEntry -> HeldKeys(Long.MIN_VALUE, k.skBefore ?: k.sk)
                k.skBefore != null -> HeldKeys(k.skBefore, null)
                else -> k
            }
        }
        val old = ArrayList<Int>(count)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) {
            old += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        if (old.size != count) return null
        fun keyOf(index: Int): HeldKeys? = entries[index]?.let(heldKeys::get)
        val known = old.filter { keyOf(it) != null }.sortedWith(compareBy({ keyOf(it)!!.sk }, { entries[it] }))
        val front = ArrayList<Int>()
        val after = HashMap<Int, MutableList<Int>>()
        var anchor: Int? = null
        for (index in old) {
            if (keyOf(index) != null) {
                anchor = index
            } else {
                (anchor?.let { a -> after.getOrPut(a) { ArrayList() } } ?: front) += index
            }
        }
        val out = ArrayList<Int>(count)
        out += front
        for (index in known) {
            out += index
            after[index]?.let(out::addAll)
        }
        if (on) {
            out.remove(current)
            out.add(0, current)
        }
        return out.toIntArray()
    }

    // The player's order set again from Room's keys after a shuffle change,
    // which a Next or Previous waits for.
    private var reordering: Job? = null

    override fun repeatChanged(mode: Int) {
        _listenerRepeat = mode
        saveState()
        refreshEnds()
    }

    // The player runs the listener's repeat while it holds the whole
    // queue; holding a window it runs none, except repeat-one, which plays
    // the same song either way.
    override fun innerRepeat(): Int = if (windowed && _listenerRepeat == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else _listenerRepeat

    // From the count's read (`upkeep`), the write waits while a blend is
    // under way.
    private fun applyRepeat(upkeep: Boolean = false) {
        val now = inner()
        if (upkeep) {
            if (now.repeatMode != innerRepeat()) now.upkeep { if (inner() === now) applyRepeat() }
            return
        }
        if (now.repeatMode != innerRepeat()) now.repeatMode = innerRepeat()
    }

    override fun holds(key: String): Boolean = queueId != 0L && (indexOf(entryOfKey(key) ?: return false) != null || queueRows.contains(entryOfKey(key)))

    // Entries the queue holds past the window, as last seen, for the sleep
    // timer's "after this song": an entry a trim took out is still queued.
    private val queueRows = HashSet<Long>()

    // ---- Room following the player ----

    // An edit goes to Room now, or, while a list of the queue is being
    // written, waits for that list's join.
    private fun record(edit: QueueEdit) {
        val queueId = queueId
        if (queueId == 0L) return
        val building = places.keys.firstOrNull()
        if (building != null) {
            val json = edit.toJson()
            store.post {
                finds?.retry()
                store.putPending(building, json)
            }
        } else {
            store.post {
                // A find a keep could not write before is kept again first.
                finds?.retry()
                apply.apply(queueId, edit)
                // The keys Room gave the new rows, for the window's order.
                if (edit is QueueEdit.Insert) {
                    val rows = edit.rows.map { it.entry }.chunked(900).flatMap { dao.entries(queueId, it) }
                    scope.launch { if (this@QueueWindow.queueId == queueId) know(rows.filter { indexOf(it.entry) != null }) }
                }
            }
        }
    }

    // After an edit: the count and ends again, and the window topped up.
    private fun afterEdit() {
        version++
        refreshCount()
    }

    // ---- The window ----

    // Reads the generation's size, and from it whether the player holds
    // all of it or a window.
    fun refreshCount() {
        val queueId = queueId
        if (queueId == 0L) return
        val floors = floors
        val building = places.keys.toList()
        scope.launch {
            // A list still being written counts the rows it has yet to
            // write: those it wrote are among the generation's already.
            val (count, expected) = withContext(Dispatchers.IO) {
                store.inTurn {
                    store.count(queueId, floors) to building.sumOf { id ->
                        dao.list(id)?.let { list -> ((list.expected ?: 0L) - list.filled0 - list.filled1 - (if (list.qStep == null) 1 else 0)).coerceAtLeast(0) } ?: 0L
                    }
                }
            }
            if (queueId != this@QueueWindow.queueId) return@launch
            _logicalCount.value = count + expected.toInt()
            _windowed = count > sizes.window
            applyRepeat(upkeep = true)
            refreshEnds()
            topUpSoon()
        }
    }

    // Whether the window holds the queue's last song (or first) in the
    // order that plays: always while the queue fits; never past a list
    // still being written.
    fun refreshEnds() {
        val queueId = queueId
        if (!windowed) {
            _atLogicalEnd.value = true
            atLogicalStart = true
            sessionHears()
            return
        }
        if (places.isNotEmpty()) {
            _atLogicalEnd.value = false
            atLogicalStart = places.values.none { it.play }
            sessionHears()
            return
        }
        val now = inner()
        val order = order
        val floors = floors
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return
        val last = lastEntryOf(now)
        val first = entryOfKey(now.getMediaItemAt(timeline.getFirstWindowIndex(now.shuffleModeEnabled)).entryId)
        scope.launch {
            val (after, before) = withContext(Dispatchers.IO) {
                store.inTurn {
                    val a = last?.let { dao.entry(queueId, it) }?.let { fill.page(queueId, order, it, forward = true, 1, floors).isNotEmpty() } ?: false
                    val b = first?.let { dao.entry(queueId, it) }?.let { fill.page(queueId, order, it, forward = false, 1, floors).isNotEmpty() } ?: false
                    a to b
                }
            }
            if (queueId != this@QueueWindow.queueId) return@launch
            _atLogicalEnd.value = !after
            atLogicalStart = !before
            sessionHears()
        }
    }

    // The session's Next, Previous and repeat follow the queue's ends, which
    // change with no event of the player's: it reads them again.
    private fun sessionHears() {
        (player as? app.winters.octo.output.OutputSwitch)?.mirrorChanged()
    }

    // Tops the window up. It is the app's own upkeep: it reads at once,
    // and its writes wait while a blend is under way, so every song still
    // blends and the window still moves on when every song does.
    fun topUpSoon() {
        if (topUp?.isActive == true) topUpAgain = true
        if (!windowed || topUp?.isActive == true) return
        topUp = scope.launch {
            do {
                topUpAgain = false
                topUpNow()
            } while (topUpAgain && windowed)
        }
    }

    // A top-up asked for while one runs (a waiting write found the window
    // moved and reads again): it runs once more when that one ends.
    private var topUpAgain = false

    // Brings the window to `ahead` songs past the current one in the order
    // that plays, and trims it to `behind` before it.
    private suspend fun topUpNow(): Boolean {
        val queueId = queueId
        val now = inner()
        if (queueId == 0L || now.mediaItemCount == 0) return false
        var moved = false
        val ahead = upcoming(now).size
        if (ahead < sizes.topUpAt) moved = extend(queueId, forward = true, asked = sizes.ahead - ahead, upkeep = true)
        trim(now, upkeep = true)
        val behind = playedBefore(now).size
        if (behind < 50 && !atLogicalStart) moved = extend(queueId, forward = false, asked = sizes.behind - behind, upkeep = true) || moved
        return moved
    }

    private fun playedBefore(now: Player): List<Int> {
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Int>()
        var i = timeline.getPreviousWindowIndex(now.currentMediaItemIndex, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) {
            out += i
            i = timeline.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
        }
        return out
    }

    // Adds up to `want` rows past the window's edge in the order that
    // plays, stopping at a list still being written; under repeat-all the
    // queue's first rows follow its last. For the app's own top-up
    // (`upkeep`), each player write waits while a blend is under way and is
    // checked again when it runs; the read is kept unless the window moved.
    private suspend fun extend(queueId: Long, forward: Boolean, asked: Int, upkeep: Boolean = false): Boolean {
        val now = inner()
        // The player never holds more than the window: going forward what
        // was played goes, going back what is furthest ahead.
        trim(now, upkeep)
        if (!forward) {
            if (upkeep) {
                if (upcoming(now).size > sizes.ahead) {
                    now.upkeep {
                        if (queueId != this.queueId || inner() !== now) return@upkeep
                        if (dropFar(now)) topUpSoon()
                    }
                }
            } else {
                dropFar(now)
            }
        }
        val want = minOf(asked, sizes.window - now.mediaItemCount)
        if (want <= 0) return false
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return false
        val shuffled = now.shuffleModeEnabled
        val edgeIndex = if (forward) timeline.getLastWindowIndex(shuffled) else timeline.getFirstWindowIndex(shuffled)
        val edge = entryOfKey(now.getMediaItemAt(edgeIndex).entryId) ?: return false
        val held = (0 until now.mediaItemCount).mapNotNull { entryOfKey(now.getMediaItemAt(it).entryId) }
        val stamp = version
        val order = order
        val floors = floors
        // Repeating the queue, its first songs follow its last and its last
        // come before its first.
        val wrap = _listenerRepeat == Player.REPEAT_MODE_ALL
        val blockers = places.values.toList()
        val read = withContext(Dispatchers.IO) {
            store.inTurn {
                val edgeRow = dao.entry(queueId, edge) ?: return@inTurn null
                var page = fill.page(queueId, order, edgeRow, forward, want, floors)
                if (forward) page = upToPlaces(page, edge, blockers)
                if (wrap && page.size < want && blockers.isEmpty()) {
                    val heldSet = held.toHashSet()
                    page = page + fill.page(queueId, order, null, forward, want - page.size, floors).filter { it.entry !in heldSet }
                }
                val open = page.takeWhile { row -> blockers.none { blocks(it, row) } }
                open to held.chunked(900).flatMap { dao.entries(queueId, it) }
            }
        } ?: return false
        val (page, windowRows) = read
        if (page.isEmpty()) return false
        val placed = itemsOf(page) ?: return false
        if (stamp != version || queueId != this.queueId) return extend(queueId, forward, want, upkeep)
        if (!upkeep) {
            place(now, windowRows + placed.first, placed.second)
            return true
        }
        now.upkeep {
            if (queueId != this.queueId || inner() !== now) return@upkeep
            // The window moved while the write waited: read again.
            if (stamp != version) {
                topUpSoon()
                return@upkeep
            }
            place(now, windowRows + placed.first, placed.second)
        }
        return true
    }

    // Takes out what lies furthest ahead past `ahead`; true when it took any.
    private fun dropFar(now: EditableQueue): Boolean {
        val far = upcoming(now).drop(sizes.ahead)
        if (far.isEmpty()) return false
        far.forEach { i -> entryOfKey(now.getMediaItemAt(i).entryId)?.let(queueRows::add) }
        now.removeRuns(removalRuns(far))
        version++
        _atLogicalEnd.value = false
        return true
    }

    // The items of rows the player can play, with their lists' sources. A
    // row whose song a merge renamed before the heal reached it is followed
    // to the song it is now, and Room renames it; only a row whose song is
    // gone for good is dropped from Room as it is met.
    private suspend fun itemsOf(rows: List<QueueEntryEntity>): Pair<List<QueueEntryEntity>, Map<Long, MediaItem>>? {
        val queueId = queueId
        val (read, lists) = withContext(Dispatchers.IO) {
            items(rows.map { it.trackId }) to rows.map { it.listId }.distinct().mapNotNull { dao.list(it) }.associateBy { it.listId }
        }
        val found = read.toMutableList()
        val seen = rows.toMutableList()
        val missing = rows.indices.filter { found[it] == null }
        if (missing.isNotEmpty()) {
            val now = withContext(Dispatchers.IO) { missing.associateWith { store.followedOrGone(rows[it].trackId) }.filterValues { it != null } }
            val renamed = now.filter { (i, id) -> id != rows[i].trackId }
            val again = withContext(Dispatchers.IO) { items(renamed.values.map { it!! }) }
            val renames = ArrayList<Pair<QueueEntryEntity, String>>()
            renamed.keys.forEachIndexed { k, i ->
                val item = again[k] ?: return@forEachIndexed
                val id = renamed.getValue(i)!!
                found[i] = item
                seen[i] = rows[i].copy(trackId = id)
                renames += rows[i] to id
            }
            if (renames.isNotEmpty()) store.post { store.rename(queueId, renames) }
        }
        val gone = rows.indices.filter { found[it] == null }.map { rows[it].entry }
        if (gone.isNotEmpty()) store.post { gone.chunked(900).forEach { dao.deleteEntries(queueId, it) } }
        val kept = rows.indices.filter { found[it] != null }
        if (kept.isEmpty()) return null
        val byEntry = kept.associate { i ->
            val row = seen[i]
            val list = lists[row.listId]
            val word = if (list?.autoplay == true) QueueSource.Autoplay.encoded() else list?.source
            row.entry to withSavedSource(found[i]!!.withEntry(entryKey(row.entry)), word)
        }
        return kept.map { seen[it] } to byEntry
    }

    // Whether a row lies past a list's place: a list being written holds
    // every row of its own but a play's start entry.
    private fun blocks(place: Place, row: QueueEntryEntity): Boolean = fill.blocks(place.listId, place.play, row)

    // A read forward never passes the place of an add still being written:
    // the rows after `from` up to and including the entry it goes after.
    private fun upToPlaces(rows: List<QueueEntryEntity>, from: Long, blockers: List<Place>): List<QueueEntryEntity> {
        val stops = blockers.mapNotNullTo(HashSet()) { it.after }
        if (stops.isEmpty()) return rows
        if (from in stops) return emptyList()
        val at = rows.indexOfFirst { it.entry in stops }
        return if (at < 0) rows else rows.subList(0, at + 1)
    }

    // Puts the new items into the player at their places in play order,
    // then the shuffled order over everything it holds.
    private fun place(now: EditableQueue, rows: List<QueueEntryEntity>, offered: Map<Long, MediaItem>) {
        // What came in meanwhile by another way is not put in twice.
        val holding = (0 until now.mediaItemCount).mapNotNullTo(HashSet()) { entryOfKey(now.getMediaItemAt(it).entryId) }
        val adding = offered.filterKeys { it !in holding }
        val byQk = rows.distinctBy { it.entry }.sortedWith(compareBy({ it.qk }, { it.entry }))
        var at = 0
        var run = ArrayList<MediaItem>()
        var runAt = 0
        fun flush() {
            if (run.isNotEmpty()) now.addMediaItems(runAt, run)
            run = ArrayList()
        }
        for (row in byQk) {
            val item = adding[row.entry]
            if (item == null) {
                flush()
                val held = (0 until now.mediaItemCount).firstOrNull { entryOfKey(now.getMediaItemAt(it).entryId) == row.entry }
                if (held != null) at = held + 1
                continue
            }
            if (run.isEmpty()) runAt = at
            run += item
            at++
        }
        flush()
        val byEntry = byQk.associateBy { it.entry }
        val held = (0 until now.mediaItemCount).mapNotNull { i -> byEntry[entryOfKey(now.getMediaItemAt(i).entryId)] }
        if (held.size == now.mediaItemCount) now.setPlayOrder(shuffleOrder(held))
        version++
        refreshEnds()
        watchUpNext()
    }

    // Takes the oldest items behind the current one out of the player, down
    // to `behind`, once there are more than `trimAt`. For the app's own
    // top-up (`upkeep`) it waits while a blend is under way and counts again.
    private fun trim(now: EditableQueue, upkeep: Boolean = false) {
        if (upkeep) {
            if (playedBefore(now).size > sizes.trimAt) now.upkeep { if (inner() === now) trim(now) }
            return
        }
        val played = playedBefore(now)
        if (played.size <= sizes.trimAt) return
        played.drop(sizes.behind).forEach { i -> entryOfKey(now.getMediaItemAt(i).entryId)?.let(queueRows::add) }
        now.removeRuns(removalRuns(played.drop(sizes.behind)))
        version++
        atLogicalStart = false
    }

    // The window around the current entry again, after a shuffle change or
    // a join: rows that belong come in, the rest go, the current stays. After
    // a join it is the app's own upkeep (`upkeep`): its write waits while a
    // blend is under way, and reads again if the window moved meanwhile.
    fun rebuild(upkeep: Boolean = false) {
        val queueId = queueId
        val now = inner()
        if (now.currentMediaItem == null) return
        rebuilding = scope.launch {
            // An edit while the window is read (a Play next, say) is not in
            // what was read: the window is read again, so nothing the
            // listener just did is taken out.
            for (tries in 1..REBUILD_TRIES) {
                val stamp = version
                val current = entryOfKey(now.currentMediaItem?.entryId) ?: return@launch
                val window = readAround(current) ?: return@launch
                if (queueId != this@QueueWindow.queueId) return@launch
                val wanted = window.mapTo(HashSet()) { it.entry }
                val held = (0 until now.mediaItemCount).mapNotNull { entryOfKey(now.getMediaItemAt(it).entryId) }.toHashSet()
                val placed = itemsOf(window.filter { it.entry !in held }) ?: (emptyList<QueueEntryEntity>() to emptyMap())
                if (queueId != this@QueueWindow.queueId) return@launch
                if (stamp != version && tries < REBUILD_TRIES) continue
                // Still moving on the last try: nothing is taken out, and the
                // next top-up and trim tidy the window.
                val write = {
                    val out = if (stamp != version) emptyList() else (0 until now.mediaItemCount).filter { i ->
                        val e = entryOfKey(now.getMediaItemAt(i).entryId)
                        e != current && e !in wanted
                    }
                    if (out.isNotEmpty()) now.removeRuns(removalRuns(out))
                    place(now, window.filter { it.entry in held || it.entry in placed.second }, placed.second)
                }
                if (!upkeep) {
                    write()
                } else {
                    val stampNow = version
                    now.upkeep {
                        if (queueId != this@QueueWindow.queueId || inner() !== now) return@upkeep
                        if (version != stampNow || entryOfKey(now.currentMediaItem?.entryId) != current) rebuild(upkeep = true) else write()
                    }
                }
                break
            }
            refreshCount()
        }
    }

    // A rebuild under way, which a Next or Previous waits for.
    private var rebuilding: Job? = null

    // Whether a press or a song's end is still being stepped past the
    // window's edge, or the window is being made again.
    internal val stepping: Boolean get() = presses > 0 || edging || rebuilding?.isActive == true || reordering?.isActive == true

    // The window for `entry`: up to `behind` before it and `ahead` after it
    // in the order that plays, stopping at a list still being written. Taken
    // on the main thread, read on the worker.
    private suspend fun readAround(entry: Long): List<QueueEntryEntity>? {
        val queueId = queueId
        val order = order
        val floors = floors
        val blockers = places.values.toList()
        return withContext(Dispatchers.IO) {
            store.inTurn {
                val row = dao.entry(queueId, entry) ?: return@inTurn null
                val back = fill.page(queueId, order, row, forward = false, sizes.behind, floors).takeWhile { r -> blockers.none { blocks(it, r) } }.reversed()
                val ahead = upToPlaces(fill.page(queueId, order, row, forward = true, sizes.ahead, floors), entry, blockers).takeWhile { r -> blockers.none { blocks(it, r) } }
                back + row + ahead
            }
        }
    }

    // ---- The window's edge ----

    override fun edge(forward: Boolean): ListenableFuture<*>? {
        // While the window is being made again (a shuffle change, a join),
        // or the player's order set again from Room's keys after a shuffle
        // change, the song next is the one it brings: the press waits for it.
        val making = rebuilding?.takeIf { it.isActive && windowed } ?: reordering?.takeIf { it.isActive }
        if (!windowed && making == null) return null
        making?.let { job ->
            val moved = SettableFuture.create<Unit>()
            scope.launch {
                try {
                    job.join()
                    stepPastEdge(forward)
                } finally {
                    moved.set(Unit)
                }
            }
            return moved
        }
        val now = inner()
        val timeline = now.currentTimeline
        if (timeline.isEmpty) return null
        val next = if (forward) {
            timeline.getNextWindowIndex(now.currentMediaItemIndex, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
        } else {
            timeline.getPreviousWindowIndex(now.currentMediaItemIndex, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
        }
        if (next != C.INDEX_UNSET) return null
        if (forward && atLogicalEnd && _listenerRepeat != Player.REPEAT_MODE_ALL) return null
        if (!forward && atLogicalStart && _listenerRepeat != Player.REPEAT_MODE_ALL) return null
        val done = SettableFuture.create<Unit>()
        presses++
        if (presses == 1) {
            scope.launch {
                try {
                    while (presses > 0) {
                        val pressed = System.nanoTime()
                        if (!stepPastEdge(forward)) break
                        fill.hooks.nextWaited((System.nanoTime() - pressed) / 1_000_000)
                        presses--
                    }
                } finally {
                    presses = 0
                    edgeWaiters.forEach { it.set(Unit) }
                    edgeWaiters.clear()
                    done.set(Unit)
                }
            }
        } else {
            edgeWaiters += done
        }
        return done
    }

    // Next presses while one waits at the edge, applied in turn.
    private var presses = 0
    private val edgeWaiters = ArrayList<SettableFuture<Unit>>()

    // The song after the window's last (or before its first): a list in
    // the way joins first, then one page comes, then the seek.
    private suspend fun stepPastEdge(forward: Boolean): Boolean {
        repeat(4) {
            val now = inner()
            val timeline = now.currentTimeline
            val next = if (forward) {
                timeline.getNextWindowIndex(now.currentMediaItemIndex, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
            } else {
                timeline.getPreviousWindowIndex(now.currentMediaItemIndex, Player.REPEAT_MODE_OFF, now.shuffleModeEnabled)
            }
            if (next != C.INDEX_UNSET) {
                now.seekToDefaultPosition(next)
                return true
            }
            // Repeating the queue, its first song follows its last, and its
            // last comes before its first.
            if (_listenerRepeat == Player.REPEAT_MODE_ALL && places.isEmpty()) {
                val queueId = queueId
                val order = order
                val floors = floors
                val edgeEntry = if (forward) lastEntryOf(now) else firstEntryOf(now)
                val (other, more) = withContext(Dispatchers.IO) {
                    store.inTurn {
                        val row = edgeEntry?.let { dao.entry(queueId, it) }
                        val more = row != null && fill.page(queueId, order, row, forward, 1, floors).isNotEmpty()
                        fill.page(queueId, order, null, forward, 1, floors).firstOrNull() to more
                    }
                }
                // Read from the player itself: the session's view waits for this step.
                val at = other?.takeIf { !more }?.let { row -> (0 until now.mediaItemCount).firstOrNull { entryOfKey(now.getMediaItemAt(it).entryId) == row.entry } }
                if (at != null) {
                    now.seekToDefaultPosition(at)
                    return true
                }
            }
            // A list in the way joins first: its place goes when it does.
            places.keys.firstOrNull()?.let { building -> _building.first { rows -> rows.none { it.listId == building } } }
            if (!extend(queueId, forward, if (forward) sizes.ahead else sizes.behind) && places.isEmpty()) {
                // A top-up or rebuild meanwhile may have put the song there
                // itself; only with none is the queue's end reached.
                val later = inner()
                val held = later.currentTimeline
                if (held.isEmpty) return false
                val again = if (forward) {
                    held.getNextWindowIndex(later.currentMediaItemIndex, Player.REPEAT_MODE_OFF, later.shuffleModeEnabled)
                } else {
                    held.getPreviousWindowIndex(later.currentMediaItemIndex, Player.REPEAT_MODE_OFF, later.shuffleModeEnabled)
                }
                if (again == C.INDEX_UNSET) return false
            }
        }
        return false
    }

    // The player ended at the window's last song though the queue goes on:
    // the next song plays as if the player had moved on itself.
    internal fun endedAtEdge() {
        if (edging) return
        edging = true
        scope.launch {
            try {
                autoStep = true
                if (stepPastEdge(forward = true)) {
                    val now = inner()
                    if (now.playbackState == Player.STATE_IDLE || now.playbackState == Player.STATE_ENDED) now.prepare()
                    now.play()
                } else {
                    autoStep = false
                }
            } finally {
                edging = false
            }
        }
    }

    private var edging = false

    // Set while a song that ended at the edge steps on, until the session
    // reads the move.
    private var autoStep = false

    override fun takeAutoStep(): Boolean = autoStep.also { autoStep = false }

    // ---- A list's join ----

    private fun joined(joined: Joined) {
        joined.words?.let(say)
        if (joined.queueId != queueId || places.remove(joined.listId) == null) return
        // The player holds a window of the joined list until the count says
        // it holds the whole queue, and the queue goes on past it until its
        // ends are read.
        _windowed = true
        _atLogicalEnd.value = false
        publishPlaces()
        sessionHears()
        val queueId = queueId
        scope.launch {
            // A big add that waited for this list builds now.
            val waiting = withContext(Dispatchers.IO) { dao.listsOf(queueId).filter { it.fillState == QueueBuilds.BUILDING } }
            waiting.forEach { fill.start(it.listId, it.queueId) }
            refreshCount()
            rebuild(upkeep = true)
            rebuilding?.join()
            // The server's copy of the queue holds the joined list's songs
            // now, whether or not the window changed.
            if (queueId == this@QueueWindow.queueId) _rejoined.tryEmit(joined.listId)
        }
    }

    // A list joined and the window was made again around it, by list.
    private val _rejoined = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val rejoined: SharedFlow<Long> = _rejoined

    // ---- Saving and restoring ----

    // Where playback is, written for the generation it was read from.
    fun saveState() {
        val queueId = queueId
        if (queueId == 0L) return
        val account = account()
        val now = inner()
        val current = entryOfKey(now.currentMediaItem?.entryId)
        val position = now.currentPosition
        val repeat = _listenerRepeat
        val shuffle = now.shuffleModeEnabled
        store.post { dao.saveState(account, queueId, current, position, repeat, shuffle, System.currentTimeMillis()) }
    }

    // What a restore reads: the window around the saved entry.
    // `pending` are the edits made while a list was being written, in
    // order, with the songs their new rows need.
    class Saved(
        val queueId: Long,
        val rows: List<QueueEntryEntity>,
        val items: Map<Long, MediaItem>,
        val start: Long,
        val positionMs: Long,
        val account: QueueAccountEntity,
        val building: List<QueueListEntity>,
        val count: Int,
        val pending: List<QueueEdit> = emptyList(),
        val pendingItems: Map<String, MediaItem> = emptyMap(),
    )

    // Reads the account's saved queue: its window around the saved entry,
    // stopping at a list still being written. Reads only.
    suspend fun readSaved(name: String): Saved? {
        val held = setOfNotNull(heldForUndo)
        return withContext(Dispatchers.IO) { readSavedNow(name, held) }
    }

    private suspend fun readSavedNow(name: String, held: Set<Long>): Saved? = withContext(Dispatchers.IO) {
        store.seed()
        // What an earlier run left half deleted, or made and never took up.
        store.sweepOnce(held)
        val saved = dao.account(name) ?: dao.account("") ?: return@withContext null
        val generation = saved.queueId
        val savedFloors = Floors.of(saved)
        val lists = dao.listsOf(generation)
        val building = lists.filter { it.fillState != QueueBuilds.JOINED }
        val order = if (saved.shuffle) QueueOrder.Shuffle else QueueOrder.Play
        val blockers = building.map { Place(it.listId, it.qStep == null, null) }
        // The saved song, else, when it is gone, the queue's first in play
        // order, from its start.
        val current = saved.currentEntry?.let { dao.entry(generation, it) }?.takeIf { row -> items(listOf(row.trackId)).single() != null }
            ?: fill.page(generation, QueueOrder.Play, null, true, 1, savedFloors).firstOrNull()
            ?: return@withContext null
        val back = fill.page(generation, order, current, false, sizes.behind, savedFloors).takeWhile { r -> blockers.none { blocks(it, r) } }.reversed()
        val ahead = fill.page(generation, order, current, true, sizes.ahead, savedFloors).takeWhile { r -> blockers.none { blocks(it, r) } }
        val window = back + current + ahead
        val found = items(window.map { it.trackId })
        val byList = lists.associateBy { it.listId }
        val kept = window.indices.filter { found[it] != null }
        if (kept.isEmpty()) return@withContext null
        val byEntry = kept.associate { i ->
            val row = window[i]
            val list = byList[row.listId]
            row.entry to withSavedSource(found[i]!!.withEntry(entryKey(row.entry)), if (list?.autoplay == true) QueueSource.Autoplay.encoded() else list?.source)
        }
        val start = if (found[window.indexOf(current)] != null && current.entry == saved.currentEntry) current.entry else kept.map { window[it] }.minWith(compareBy({ it.qk }, { it.entry })).entry
        // The edits made while a list was being written: the window shows
        // them as the listener left them, and Room has them at the join.
        val pending = building.flatMap { dao.pending(it.listId) }.sortedBy { it.seq }.mapNotNull { runCatching { queueEditOf(it.body) }.getOrNull() }
        val wanted = pending.flatMap { edit ->
            when (edit) {
                is QueueEdit.Insert -> edit.rows.map { it.trackId }
                is QueueEdit.PutBack -> edit.rows.map { it.trackId }
                else -> emptyList()
            }
        }.distinct().take(sizes.window)
        val pendingItems = wanted.zip(items(wanted)).mapNotNull { (id, item) -> item?.let { id to it } }.toMap()
        Saved(
            generation, kept.map { window[it] }, byEntry, start, if (start == saved.currentEntry) saved.positionMs else 0, saved, building,
            store.count(generation, savedFloors), pending, pendingItems,
        )
    }

    // Brings a read saved queue into the player, paused where it was left.
    fun restore(saved: Saved, name: String): Boolean {
        if (player.mediaItemCount > 0) return false
        queueId = saved.queueId
        floors = Floors.of(saved.account)
        _listenerRepeat = saved.account.repeatMode
        places.clear()
        saved.building.forEach { places[it.listId] = Place(it.listId, it.qStep == null, null) }
        publishPlaces()
        _windowed = saved.count > sizes.window
        _logicalCount.value = saved.count
        val byQk = saved.rows.sortedWith(compareBy({ it.qk }, { it.entry }))
        val now = inner()
        now.setMediaItems(byQk.mapNotNull { saved.items[it.entry] }, byQk.indexOfFirst { it.entry == saved.start }.coerceAtLeast(0), saved.positionMs)
        now.setPlayOrder(shuffleOrder(byQk))
        now.shuffleModeEnabled = saved.account.shuffle
        now.repeatMode = innerRepeat()
        replay(now, saved)
        version++
        if (saved.account.account != name) store.post { dao.renameAccount(saved.account.account, name) }
        val generation = saved.queueId
        // A build a server switch stopped ends before its list builds again,
        // so its release never takes the new build's copy.
        val building = saved.building.filter { it.fillState == QueueBuilds.BUILDING }
        if (building.isNotEmpty()) {
            store.scope.launch {
                building.forEach { store.builderOf(it.listId)?.join() }
                building.forEach { fill.start(it.listId, it.queueId) }
            }
        }
        store.post { heal(generation) }
        refreshEnds()
        return true
    }

    // The edits made while a list was being written, on the restored
    // window in order, by entry: removes drop items, moves reorder them,
    // inserts go in beside their neighbors when those are held, and a big
    // add's place takes what is past it out of the player. Room has them at
    // the list's join.
    private fun replay(now: EditableQueue, saved: Saved) {
        fun at(entry: Long?): Int? = entry?.let { e -> (0 until now.mediaItemCount).firstOrNull { entryOfKey(now.getMediaItemAt(it).entryId) == e } }
        fun removeWhere(gone: (MediaItem) -> Boolean) {
            val current = now.currentMediaItemIndex
            val out = (0 until now.mediaItemCount).filter { it != current && gone(now.getMediaItemAt(it)) }
            if (out.isNotEmpty()) now.removeRuns(removalRuns(out))
        }
        for (edit in saved.pending) {
            when (edit) {
                is QueueEdit.Insert -> {
                    val items = edit.rows.mapNotNull { row ->
                        saved.pendingItems[row.trackId]?.let { withSavedSource(it.withEntry(entryKey(row.entry)), edit.list?.source) }
                    }
                    val index = at(edit.play.after)?.plus(1) ?: at(edit.play.before) ?: if (edit.play == Spot.END && !_windowed) now.mediaItemCount else null
                    if (index != null && items.isNotEmpty()) now.addMediaItems(index, items)
                }
                is QueueEdit.Remove -> {
                    val gone = edit.entries.toHashSet()
                    removeWhere { entryOfKey(it.entryId) in gone }
                }
                is QueueEdit.RemoveSongs -> {
                    val gone = edit.trackIds.toHashSet()
                    removeWhere { it.mediaId in gone }
                }
                is QueueEdit.Move -> {
                    val from = at(edit.entry) ?: continue
                    val after = at(edit.play.after)
                    val before = at(edit.play.before)
                    val to = when {
                        after != null -> if (after < from) after + 1 else after
                        before != null -> if (before < from) before else before - 1
                        else -> null
                    }
                    if (to != null && to != from) now.moveMediaItem(from, to)
                }
                is QueueEdit.PutBack -> {
                    val rows = edit.rows.map { it.row(saved.queueId) }
                    val offered = rows.mapNotNull { row -> saved.pendingItems[row.trackId]?.let { row.entry to it.withEntry(entryKey(row.entry)) } }.toMap()
                    // Placed by key among the restored rows.
                    place(now, saved.rows + rows, offered)
                }
                is QueueEdit.AddList -> {
                    places[edit.listId] = Place(edit.listId, play = false, after = edit.play.after)
                    if (at(edit.play.after) != null) cutPast(now, edit.play.after)
                }
                is QueueEdit.FloorsExpire -> Unit
            }
        }
        if (saved.pending.any { it is QueueEdit.AddList }) publishPlaces()
    }

    // Lets the player's queue go without taking a new one: a server
    // switch empties the player, and the account's queue waits in Room.
    // Its lists still being written stop, so none walks the next server's
    // library; restore starts them again when the account comes back.
    fun leave() {
        saveState()
        store.cancelBuilders(queueId)
        queueId = 0L
        places.clear()
        publishPlaces()
        floors = Floors.NONE
        _windowed = false
    }

    // ---- Clear and Remove played ----

    // A generation kept for Clear's Undo, until the next play.
    private var heldForUndo: Long? = null

    // Everything but the current song goes: a new generation holding it,
    // written from values. Answers the Undo, which brings the old one back.
    suspend fun clear(): (() -> Unit)? {
        val old = queueId
        val now = inner()
        val current = entryOfKey(now.currentMediaItem?.entryId) ?: return null
        if (old == 0L) return null
        val account = account()
        val keptPlaces = places.toMap()
        val fresh = store.claimQueue()
        store.post {
            val row = dao.entry(old, current) ?: return@post
            store.db.withTransaction {
                dao.putEntries(listOf(row.copy(queueId = fresh)))
                val saved = dao.account(account)
                dao.putAccount(QueueAccountEntity(account, fresh, current, saved?.positionMs ?: 0, saved?.repeatMode ?: 0, saved?.shuffle ?: false, null, null, saved?.healedAt, System.currentTimeMillis()))
            }
        }
        heldForUndo = old
        queueId = fresh
        floors = Floors.NONE
        places.clear()
        publishPlaces()
        val others = (0 until now.mediaItemCount).filter { it != now.currentMediaItemIndex }
        if (others.isNotEmpty()) now.removeRuns(removalRuns(others))
        version++
        refreshCount()
        return {
            if (heldForUndo == old) {
                heldForUndo = null
                store.unretire(old)
                queueId = old
                // Until Room says which lists still are written, the places
                // before Clear hold the window.
                places.putAll(keptPlaces)
                publishPlaces()
                store.post {
                    val saved = dao.account(account)
                    dao.putAccount(QueueAccountEntity(account, old, current, saved?.positionMs ?: 0, saved?.repeatMode ?: 0, saved?.shuffle ?: false, null, null, saved?.healedAt, System.currentTimeMillis()))
                }
                store.deleteGeneration(fresh)
                scope.launch { undoClear(old, keptPlaces) }
            }
        }
    }

    // Clear's Undo: the old generation's places come from Room as its lists
    // stand now, since a list that joined meanwhile no longer holds the
    // window, then the window is made again around the current song. A list
    // still being written keeps the place it held before Clear.
    private suspend fun undoClear(old: Long, keptPlaces: Map<Long, Place>) {
        val unjoined = withContext(Dispatchers.IO) {
            store.inTurn {
                val lists = dao.listsOf(old)
                // An edit made since Undo that waited on a list joined before
                // it goes to Room now.
                for (list in lists.filter { it.fillState == QueueBuilds.JOINED && it.listId in keptPlaces }) {
                    val waiting = dao.pending(list.listId)
                    if (waiting.isEmpty()) continue
                    waiting.forEach { row -> runCatching { queueEditOf(row.body) }.getOrNull()?.takeIf { it !is QueueEdit.AddList }?.let { apply.apply(old, it) } }
                    dao.deletePending(list.listId)
                }
                lists.filter { it.fillState != QueueBuilds.JOINED }
            }
        }
        if (queueId != old) return
        places.clear()
        unjoined.forEach { list -> places[list.listId] = keptPlaces[list.listId] ?: Place(list.listId, list.qStep == null, null) }
        publishPlaces()
        // Every list still being written has its builder.
        unjoined.filter { it.fillState == QueueBuilds.BUILDING }.forEach { fill.start(it.listId, it.queueId) }
        refreshCount()
        rebuild()
    }

    // The Undo of Clear is gone: its generation goes once nothing builds it.
    private fun dropHeld() {
        val held = heldForUndo ?: return
        heldForUndo = null
        store.cancelBuilders(held)
        if (store.retire(held)) store.deleteGeneration(held)
    }

    // The songs before the current one in the order that plays go: the
    // floors move to the current song's keys. Answers the Undo.
    suspend fun removePlayed(): (() -> Unit)? {
        val queueId = queueId
        val now = inner()
        val current = entryOfKey(now.currentMediaItem?.entryId) ?: return null
        if (queueId == 0L) return null
        val account = account()
        val shuffled = now.shuffleModeEnabled
        val row = store.inTurn { dao.entry(queueId, current) } ?: return null
        val old = floors
        val new = if (shuffled) old.copy(s = row.sk) else old.copy(q = row.qk)
        floors = new
        store.post { dao.setFloors(account, queueId, new.q.takeIf { it != Long.MIN_VALUE }, new.s.takeIf { it != Long.MIN_VALUE }) }
        val gone = playedBefore(now)
        if (gone.isNotEmpty()) now.removeRuns(removalRuns(gone))
        version++
        atLogicalStart = true
        refreshCount()
        return {
            if (this.queueId == queueId) {
                floors = old
                store.post { dao.setFloors(account, queueId, old.q.takeIf { it != Long.MIN_VALUE }, old.s.takeIf { it != Long.MIN_VALUE }) }
                rebuild()
            }
        }
    }

    // Remove played's Undo is gone: the rows below the floors go.
    fun expireFloors() {
        if (floors == Floors.NONE) return
        record(QueueEdit.FloorsExpire(account()))
    }

    // Songs taken out wherever they are (their files are being deleted):
    // their rows go to a generation held for the Undo.
    suspend fun removeSongs(trackIds: Set<String>): (() -> Unit)? {
        val queueId = queueId
        if (queueId == 0L || trackIds.isEmpty()) return null
        val holding = store.claimQueue()
        trackIds.chunked(QUEUE_WINDOW).forEach { record(QueueEdit.RemoveSongs(it, holding)) }
        val now = inner()
        val gone = (0 until now.mediaItemCount).filter { now.getMediaItemAt(it).mediaId in trackIds }
        if (gone.isNotEmpty()) now.removeRuns(removalRuns(gone))
        afterEdit()
        return {
            store.post {
                val rows = fill.page(holding, QueueOrder.Play, null, true, Int.MAX_VALUE, Floors.NONE)
                rows.map { it.entry }.chunked(900).forEach { dao.moveEntries(holding, queueId, it) }
            }
            rebuild()
        }
    }

    // ---- Healing ----

    // Follows rows whose song a merge took away to the song it is now, a
    // page at a time, when the library's swap count moved since the
    // generation was last healed. Runs on the worker.
    private suspend fun heal(generation: Long) {
        val healedAt = dao.accounts().firstOrNull { it.queueId == generation }?.healedAt
        val now = store.swapCount()
        if (healedAt == now) {
            dao.listsOf(generation).filter { it.fillState == QueueBuilds.JOINED && it.swapCount != null && it.swapCount != now }.forEach { list ->
                store.healRange(generation, firstEntryOf(list.listId) - 1, lastEntryOf(list.listId), now, emptyList(), list.listId)
            }
            return
        }
        store.healRange(generation, Long.MIN_VALUE, Long.MAX_VALUE, now, emptyList(), null)
    }

    // A group of a save renamed songs by follow (`renames`, old id to new):
    // only the generation in use's rows still holding an old id take the
    // song it is now. Once the save has ended (null), the generation heals
    // if its count moved.
    fun swapped(renames: Map<String, String>? = null) {
        val generation = queueId
        if (generation == 0L) return
        if (renames == null) {
            store.post { heal(generation) }
            return
        }
        store.post {
            val rows = renames.flatMap { (old, new) -> dao.rows(queueQuery(QUEUE_HOLDING_SQL, old, generation)).map { it to new } }
            if (rows.isNotEmpty()) store.rename(generation, rows)
        }
    }

    // ---- Autoplay and a station's refill ----

    // Which of these songs the queue already holds anywhere.
    suspend fun queuedAmong(trackIds: List<String>): Set<String> {
        val generation = queueId
        if (generation == 0L) return emptySet()
        return store.inTurn { store.heldAmong(generation, trackIds) }
    }

    // Songs added after every other in both orders, as Autoplay's own.
    fun addAtEnd(items: List<MediaItem>, autoplay: Boolean, source: String) {
        if (items.isEmpty() || queueId == 0L) return
        val listId = store.claimListNow()
        val rows = items.mapIndexed { i, item -> NewRow(entryOf(listId, i.toLong()), item.mediaId, listId, item.mediaMetadata.durationMs ?: 0) }
        val played = items.mapIndexed { i, item -> item.withEntry(entryKey(rows[i].entry)).let { if (autoplay) it.markedAutoplay() else it } }
        insertRun(rows, played, next = false, list = StoredList(listId, source, autoplay = autoplay), autoplay = autoplay)
    }

    // ---- Up next past the window ----

    // How many songs come after the window and how long they play.
    fun refreshSummary() {
        val queueId = queueId
        if (queueId == 0L) return
        val now = inner()
        val last = lastEntryOf(now)
        val order = order
        val floors = floors
        val building = places.values.map { BuildingRow(it.listId, it.play, it.after) }
        scope.launch {
            val tail = withContext(Dispatchers.IO) {
                store.inTurn {
                    val row = last?.let { dao.entry(queueId, it) } ?: return@inTurn 0 to 0L
                    if (building.isNotEmpty()) 0 to 0L else store.tail(queueId, order, row, floors)
                }
            }
            _summary.value = QueueSummary(tail.first, tail.second, building)
        }
    }

    // A page of the queue past the window (or before it), for Up next.
    suspend fun pageOf(after: Long?, forward: Boolean, limit: Int): List<QueueEntryEntity> {
        val queueId = queueId
        if (queueId == 0L) return emptyList()
        val order = order
        val floors = floors
        return store.inTurn {
            val from = after?.let { dao.entry(queueId, it) }
            fill.page(queueId, order, from, forward, limit, floors)
        }
    }
}
