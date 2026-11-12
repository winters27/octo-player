package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.RENAME_SONG
import app.winters.octo.catalog.SERVER_TRACK_AT_SQL
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.read
import app.winters.octo.catalog.stringOrNull
import app.winters.octo.data.userMessage
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.discovery.ServerStart
import app.winters.octo.discovery.shownRows
import app.winters.octo.discovery.what
import app.winters.octo.ui.paging.SqlOrder
import app.winters.octo.ui.paging.SqlValue
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import app.winters.octo.livelists.LivePicks
import app.winters.octo.livelists.LiveSpecLoader
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import app.winters.octo.ui.paging.PlaylistStartLoader
import app.winters.octo.ui.paging.Retiring
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SelectionLoader
import app.winters.octo.ui.paging.StaticLoader
import dagger.Lazy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// The words a list that cannot be played as asked is refused with.
const val COULD_NOT_PLAY_LIST = "Couldn't play that list."
const val NOTHING_TO_PLAY = "Nothing to play"
const val SIGN_IN_TO_PLAY = "Sign in to your server to play this."

// The refusal of a list the server made anew since it was shown, for a
// route that reads it again.
const val LIST_MADE_ANEW = "That list was made anew on the server."

// What a play's first read found. Read with no write, lock or wait, so a
// tap never waits for a swap or a build: the whole list when it has at
// most QUEUE_WINDOW rows, else the song it starts with. A list the server
// answers for is read the same way, from its answer drained to a file.
// `cut` says a server folder's songs stopped at their limit.
sealed interface StartRead {
    // The whole list in its order, and which of its rows plays first; a
    // shuffled play's first is picked at random and plays before every
    // other.
    // `songsOnce` as the list's loader says, for the queue's list. `finds`
    // are those of its songs the server found, whose rows are not written
    // yet: the play holds them (FindMap) until the queue keeps them.
    data class Whole(
        val ids: List<String>,
        val start: Int,
        val firstShuffled: Boolean,
        val positionMs: Long = 0,
        val cut: Boolean = false,
        val songsOnce: Boolean = false,
        val finds: List<OnlineSongEntity> = emptyList(),
    ) : StartRead

    // A bigger list: the song it starts with and its key among the list's
    // rows (null when there is none to hand: then the start's song is found
    // again by the build), or `noAnchor` when every row comes after it.
    data class Big(
        val trackId: String,
        val key: RowKey?,
        val firstShuffled: Boolean,
        val noAnchor: Boolean = false,
        val positionMs: Long = 0,
        val cut: Boolean = false,
        // The list's rows as the start's own read counted them, when it
        // did: the build takes it as the rows it expects, with no count of
        // its own.
        val count: Int? = null,
        // The start's song, when it is a find whose row is not written yet.
        val find: OnlineSongEntity? = null,
    ) : StartRead

    data class Refused(val words: String) : StartRead
}

// Whether a list is one the server answers for.
val ListSpec.fromServer: Boolean
    get() = this is ListSpec.Online || this is ListSpec.ServerFolder || this == ListSpec.ServerQueue

// Song ids as the renames in force have them (save_rename): an old id a
// save renamed plays as the id it became, by follow or by key alike, as
// the id is that old song's own. One read a chunk, no write.
internal suspend fun renamedSongs(db: CatalogDatabase, ids: List<String>): List<String> {
    val asked = ids.filterNot { isFind(it) || isRadio(it) || isOpenedFile(it) }.distinct()
    if (asked.isEmpty()) return ids
    val renames = HashMap<String, String>()
    withContext(Dispatchers.IO) {
        asked.chunked(ID_CHUNK).forEach { chunk ->
            db.read(renamedSongsSql(chunk.size), *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }.forEach { (old, new) -> renames[old] = new }
        }
    }
    return if (renames.isEmpty()) ids else ids.map { renames[it] ?: it }
}

internal fun renamedSongsSql(n: Int) = "SELECT old, new FROM save_rename WHERE kind = '$RENAME_SONG' AND old IN (${marks(n)})"

// Points a test can stop a start at. Each does nothing in the app.
internal open class StartHooks {
    // A start through a list's loader read it, and is about to let its
    // walk's rows go.
    open suspend fun read(owner: String) {}

    // That walk's rows went, and its loader with them.
    open suspend fun letGo(owner: String, loader: ListLoader<*>) {}

    // A playlist's start is about to run its statement `n`, from 0.
    open suspend fun statement(n: Int) {}
}

// A play's first read, per kind of list: one statement over the library or
// stored rows where it can (a list's first QUEUE_WINDOW + 1 rows, or the
// one row it starts at), a list's owner's bounded read where the list is
// built in memory today (a folder's songs). A list whose page knows it
// shows more than QUEUE_WINDOW rows is not read at all past its start.
@Singleton
class QueueStarts @Inject constructor(
    private val db: CatalogDatabase,
    private val loaders: Lazy<ListLoaders>,
    private val live: Lazy<LivePicks>,
    private val health: Lazy<HealthBuild>,
    private val owners: WorkOwners,
    private val servers: Lazy<ServerReads> = Lazy { error("no server lists") },
) {
    internal var random: Random = Random.Default
    internal var hooks = StartHooks()

    // The play whose start is still reading the server, by its list id, if
    // any: a newer play (a bigger list id) stops it, so it never streams or
    // writes for a play nobody wants.
    @Volatile private var reading: Pair<Long, Job>? = null

    // `listId` names the play the read is for (an add's own list id, or 0):
    // the answer of a list the server answers for, drained for a big start,
    // goes to that list's build (`queue:<listId>`). `adding` says the list
    // is added whole, so a big one needs no start song.
    suspend fun read(
        spec: ListSpec,
        start: ListStart?,
        shuffle: Boolean,
        shownOverWindow: Boolean,
        pageOwner: String?,
        listId: Long = 0L,
        adding: Boolean = false,
    ): StartRead = withContext(WriteLabels.of(QUEUE_START_WRITE)) {
        if (listId != 0L && !adding) reading?.takeIf { it.first < listId }?.second?.cancel()
        when (spec) {
            is ListSpec.Ids -> ids(spec, start, shuffle)
            is ListSpec.Radio -> StartRead.Big(spec.seedId, null, firstShuffled = false, noAnchor = true)
            is ListSpec.ServerFolder -> if (spec.recursive) fromServer(spec, start, shuffle, shownOverWindow, listId, adding) else serverLevel(spec, start, shuffle)
            is ListSpec.Online, ListSpec.ServerQueue -> fromServer(spec, start, shuffle, shownOverWindow, listId, adding)
            is ListSpec.Folder -> folder(spec, start, shuffle, shownOverWindow)
            is ListSpec.Album -> if (spec.withFinds) albumWithFinds(spec, start, shuffle, shownOverWindow, pageOwner) else viaLoader(spec, start, shuffle, shownOverWindow)
            else -> viaLoader(spec, start, shuffle, shownOverWindow, pageOwner)
        }
    }

    // A list the server answers for, read with no write. One a page holds a
    // whole read of (a list's or an album's page), or this play's own copy,
    // decides from that read. Any other is drained from the server into a
    // file (a recursive folder after its walk) and worked out from it the
    // way the copy works it out (StartResolver): whole when it has at most
    // QUEUE_WINDOW entries, every entry in memory and no copy written; else
    // its start entry alone, and the file goes to the list's build, which
    // copies it. A refusal comes only from the server's own answer.
    private suspend fun fromServer(spec: ListSpec, start: ListStart?, shuffle: Boolean, shownOverWindow: Boolean, listId: Long, adding: Boolean): StartRead {
        val lists = servers.get()
        val own = if (listId != 0L) owners.queue(listId) else owners.hold()
        var kept = false
        val job = coroutineContext[Job]
        val mine = job?.let { listId to it }
        if (listId != 0L && !adding) reading = mine
        // The play's own copy, read already: it holds rows to let go.
        var copied = false
        try {
            val (kind, id) = what(spec)
            // The play's own copy, or a whole read a page holds, pinned
            // while it is read.
            copied = lists.head(own)?.complete == true
            val stored = if (!copied && spec is ListSpec.Online) lists.newest(kind, id.orEmpty())?.takeIf { it != own } else null
            stored?.let { owners.pin(it) }
            try {
                val from = when {
                    copied -> own
                    stored != null && lists.head(stored)?.complete == true -> stored
                    else -> return drained(lists, spec, kind, id, own, start, shuffle, shownOverWindow, listId, adding)
                }
                val head = lists.head(from)
                val cut = head?.cut == true
                @Suppress("UNCHECKED_CAST")
                val loader = lists.loader(from, (spec as? ListSpec.Online)?.libraryOnly == true) as ListLoader<Any>
                // The play queue starts at the song it was on, where it was.
                val begin = if (spec == ListSpec.ServerQueue) queueStart(lists, from, loader) else start
                if (!shownOverWindow) {
                    loader.small()?.let { rows ->
                        if (rows.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
                        return when (val read = whole(rows, begin, shuffle, { songOf(loader, it) }, loader.order, loader.songsOnce)) {
                            is StartRead.Whole -> read.copy(cut = cut)
                            else -> read
                        }
                    }
                }
                val read = if (begin == null && shuffle) {
                    @Suppress("UNCHECKED_CAST")
                    lists.shuffledRow(loader as ListLoader<TrackEntity>, random.nextLong())?.let { StartRead.Big(it.row.id, it.key, true) }
                        ?: StartRead.Refused(NOTHING_TO_PLAY)
                } else {
                    big(loader, begin, shuffle)
                }
                kept = read is StartRead.Big && from == own && listId != 0L
                return when (read) {
                    is StartRead.Big -> read.copy(cut = cut)
                    is StartRead.Whole -> read.copy(cut = cut)
                    else -> read
                }
            } finally {
                stored?.let { withContext(NonCancellable) { owners.unpin(it) } }
            }
        } finally {
            if (listId != 0L && reading === mine) reading = null
            // Only a copy of the play's own holds rows; nothing else was written.
            if (copied && !kept) withContext(NonCancellable) { runCatching { lists.clear(own) } }
        }
    }

    // A list the server answers for with no read to decide from: its answer
    // drained (or the one this play's start drained already and handed to
    // its build), then worked out from the file.
    private suspend fun drained(
        lists: ServerReads,
        spec: ListSpec,
        kind: ServerListKind,
        id: String?,
        own: String,
        start: ListStart?,
        shuffle: Boolean,
        shownOverWindow: Boolean,
        listId: Long,
        adding: Boolean,
    ): StartRead {
        val handed = lists.handed(own)
        val answer = handed ?: try {
            lists.drainStart(kind, id, own) ?: return StartRead.Refused(SIGN_IN_TO_PLAY)
        } catch (e: SubsonicException.NotFound) {
            return StartRead.Refused(if (spec is ListSpec.Online) LIST_MADE_ANEW else e.userMessage())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "queue: ${spec.javaClass.simpleName} could not be read: ${e.javaClass.simpleName}")
            return StartRead.Refused(e.userMessage())
        }
        var kept = handed != null
        try {
            val libraryOnly = (spec as? ListSpec.Online)?.libraryOnly == true
            val read = fromAnswer(lists, answer, spec, start, shuffle, shownOverWindow, libraryOnly, adding)
            if (read is StartRead.Big && listId != 0L && !kept) {
                lists.hand(own, answer)
                kept = true
            }
            if (read !is StartRead.Big && handed != null) {
                lists.dropStart(own)
                kept = true
            }
            return read
        } finally {
            if (!kept) answer.delete()
        }
    }

    // A drained answer's start, worked out the copy's way with reads
    // alone. At most QUEUE_WINDOW entries play whole, every entry worked
    // out in memory. A bigger list starts with one entry: the song tapped
    // (found again by the build), the play queue's own entry, the first
    // entry a row shows for, or, shuffled or keeping library songs alone,
    // the first such entry of a seeded walk of the places; an add starts
    // none. A tapped row's key names another read's places, so it is left
    // for the build to find its song.
    private suspend fun fromAnswer(
        lists: ServerReads,
        answer: ServerStart,
        spec: ListSpec,
        start: ListStart?,
        shuffle: Boolean,
        shownOverWindow: Boolean,
        libraryOnly: Boolean,
        adding: Boolean,
    ): StartRead = withContext(Dispatchers.IO) {
        val resolver = lists.resolver(answer)
        val cut = answer.head.cut
        val queueAt = if (spec == ListSpec.ServerQueue) answer.head.currentAt else null
        val queueMs = if (spec == ListSpec.ServerQueue) answer.head.positionMs else 0L
        if (!shownOverWindow && answer.count <= QUEUE_WINDOW) {
            val all = resolver.all()
            val shown = all.shownRows(libraryOnly)
            if (shown.isEmpty()) return@withContext StartRead.Refused(NOTHING_TO_PLAY)
            lists.noteStarted(shown)
            val rows = shown.map { Keyed(rowKeyAt(it.position, it.trackId), it.trackId) }
            // A song tapped is found by song, as the renames in force name it.
            val tapped = when (start) {
                is ListStart.Row -> start.trackId
                is ListStart.First -> start.trackId
                is ListStart.Track -> start.trackId
                else -> null
            }?.let { renamedSongs(db, listOf(it)).single() }
            val begin = when {
                queueAt != null -> {
                    val song = all.getOrNull(queueAt.toInt())?.trackId
                    shown.indexOfFirst { it.trackId == song }.takeIf { it >= 0 }?.let { ListStart.At(it, queueMs) } ?: ListStart.At(0, 0)
                }
                start is ListStart.Row -> ListStart.Row(tapped!!, null)
                start is ListStart.First -> ListStart.First(tapped!!, null)
                start is ListStart.Track -> ListStart.Track(tapped!!)
                else -> start
            }
            return@withContext when (val read = whole(rows, begin, shuffle, { it }, SqlOrder.rows(), songsOnce = true)) {
                is StartRead.Whole -> read.copy(cut = cut, finds = shown.mapNotNull { it.find })
                else -> read
            }
        }
        if (adding) return@withContext StartRead.Big("", null, firstShuffled = false, noAnchor = true, cut = cut)
        // The song tapped plays as the screen named it.
        when (start) {
            is ListStart.Row -> return@withContext StartRead.Big(start.trackId, null, false, cut = cut)
            is ListStart.First -> return@withContext StartRead.Big(start.trackId, null, shuffle, cut = cut)
            is ListStart.Track -> return@withContext StartRead.Big(start.trackId, null, false, cut = cut)
            else -> Unit
        }
        // The entry that starts, its row's place, and how far into it.
        val (entry, place, ms) = when {
            queueAt != null -> {
                val (atQueue, anchor) = resolver.at(queueAt) ?: (null to queueAt)
                if (atQueue != null && atQueue.present) Triple(atQueue, anchor, queueMs) else resolver.first(false).let { Triple(it, it?.position, 0L) }
            }
            start is ListStart.At -> resolver.nth(start.index, libraryOnly).let { Triple(it, it?.position, start.positionMs) }
            shuffle -> resolver.shuffled(random.nextLong(), libraryOnly).let { Triple(it, it?.position, 0L) }
            else -> resolver.first(libraryOnly).let { Triple(it, it?.position, 0L) }
        }
        if (entry == null || place == null) return@withContext StartRead.Refused(NOTHING_TO_PLAY)
        lists.noteStarted(listOf(entry))
        StartRead.Big(entry.trackId, rowKeyAt(place, entry.trackId), shuffle && start == null && queueAt == null, positionMs = ms, cut = cut, find = entry.find)
    }

    // A row's key among a server read's rows: its place, then its song.
    private fun rowKeyAt(position: Long, trackId: String) = RowKey(0, listOf(SqlValue.Int64(position)), trackId)

    // A server folder's own songs, tapped on its page: the song tapped plays
    // at once, as a big phone folder's does, and the build reads the folder
    // into its own rows behind it. A start with no song to name is refused.
    private fun serverLevel(spec: ListSpec.ServerFolder, start: ListStart?, shuffle: Boolean): StartRead = when (start) {
        is ListStart.Row -> StartRead.Big(start.trackId, start.key, false)
        is ListStart.First -> StartRead.Big(start.trackId, start.key, shuffle)
        is ListStart.Track -> StartRead.Big(start.trackId, null, false)
        else -> {
            Log.w("Octo", "queue: refused a server folder's own songs with no song to start from (${spec.order})")
            StartRead.Refused(COULD_NOT_PLAY_LIST)
        }
    }

    // Where a play queue read into `owner` starts: the first row of the
    // song it was on, and how far into it; with that song not shown, its
    // first row from the start.
    private suspend fun queueStart(lists: ServerReads, owner: String, loader: ListLoader<Any>): ListStart {
        val head = lists.head(owner) ?: return ListStart.At(0, 0)
        val at = head.currentAt ?: return ListStart.At(0, 0)
        val song = withContext(Dispatchers.IO) { db.read(SERVER_TRACK_AT_SQL, owner, at) { it.stringOrNull(0) }.firstOrNull() } ?: return ListStart.At(0, 0)
        val key = loader.keyOf(song) ?: return ListStart.At(0, 0)
        val index = loader.survey(key).anchorAt ?: return ListStart.At(0, 0)
        return ListStart.At(index, head.positionMs)
    }

    // Lets the answer a play's start handed its build go, when no build
    // took it over: a file, so nothing is written.
    fun dropCopy(listId: Long) {
        servers.get().dropStart(owners.queue(listId))
    }

    private fun ids(spec: ListSpec.Ids, start: ListStart?, shuffle: Boolean): StartRead {
        if (spec.ids.size > QUEUE_WINDOW) {
            Log.w("Octo", "queue: refused Ids of ${spec.ids.size}")
            return StartRead.Refused(COULD_NOT_PLAY_LIST)
        }
        if (spec.ids.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
        return whole(spec.ids.map { Keyed(RowKey(0, emptyList(), it), it) }, start, shuffle, { it }, null, songsOnce = false)
    }

    // The list's first QUEUE_WINDOW + 1 rows through its own loader, read
    // with no copy: whole when that is all of it, else its start. A live
    // list no reader shows starts from its rules (unshownLive).
    @Suppress("UNCHECKED_CAST")
    private suspend fun viaLoader(spec: ListSpec, start: ListStart?, shuffle: Boolean, shownOverWindow: Boolean, pageOwner: String? = null): StartRead {
        val owner = owners.walk()
        val made = startLoader(spec, owner, pageOwner) ?: return StartRead.Refused(COULD_NOT_PLAY_LIST)
        val loader = made as ListLoader<Any>
        try {
            val unshown = (made as? LiveSpecLoader)?.takeUnless { it.shown() }
            val read = unshown?.let { unshownLive(it, start, shuffle) } ?: small(loader, start, shuffle, shownOverWindow) ?: big(loader, start, shuffle)
            hooks.read(owner)
            return read
        } finally {
            withContext(NonCancellable) { letGo(loader, owner) }
        }
    }

    // The whole list, when it is small and no page says it shows more.
    private suspend fun small(loader: ListLoader<Any>, start: ListStart?, shuffle: Boolean, shownOverWindow: Boolean): StartRead? {
        if (shownOverWindow) return null
        val rows = loader.small() ?: return null
        if (rows.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
        return whole(rows, start, shuffle, { songOf(loader, it) }, loader.order, loader.songsOnce)
    }

    // A live list none of its pages, its preview or the page it was chosen
    // from shows: its start worked out from its rules with no write
    // (LiveSpecLoader.firstPicks). It plays whole when it picks at most
    // QUEUE_WINDOW songs; else it starts with the song tapped, the song at
    // a place, a song drawn at random or its first, and the build walks its
    // rules behind it.
    private suspend fun unshownLive(loader: LiveSpecLoader, start: ListStart?, shuffle: Boolean): StartRead {
        val draw = shuffle && start == null
        val picks = loader.firstPicks(QUEUE_WINDOW + 1, if (draw) random.nextLong() else null)
        if (picks.count <= QUEUE_WINDOW) {
            if (picks.rows.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
            return whole(picks.rows, start, shuffle, { it }, loader.order, loader.songsOnce)
        }
        val first = picks.rows.first()
        return when (start) {
            is ListStart.Row -> StartRead.Big(start.trackId, start.key, false)
            is ListStart.First -> StartRead.Big(start.trackId, start.key, shuffle)
            // A song past the first rows read is found again by the build.
            is ListStart.Track -> StartRead.Big(start.trackId, picks.rows.firstOrNull { it.row == start.trackId }?.key, false)
            is ListStart.At -> picks.rows.getOrNull(start.index)?.let { StartRead.Big(it.row, it.key, false, positionMs = start.positionMs) }
                ?: StartRead.Big(first.row, first.key, false)
            null -> if (draw) (picks.drawn ?: first).let { StartRead.Big(it.row, it.key, true, count = picks.count) } else StartRead.Big(first.row, first.key, false)
        }
    }

    // The loader a play reads its start from: the list as a page shows it,
    // never a copy of its own; a live list's falls back on `pageOwner`, the
    // page it was chosen from. A playlist's is read with no pin
    // (PlaylistStartLoader), so its start writes nothing; its build pins.
    private suspend fun startLoader(spec: ListSpec, owner: String, pageOwner: String?): ListLoader<*>? = when (spec) {
        is ListSpec.Live -> live.get().forSpec(spec.listId, spec.query, owner, fallback = pageOwner)
        is ListSpec.Health -> health.get().loader(spec.check, owner)
        is ListSpec.AlbumList -> AlbumListLoader(db, spec.albumIds)
        is ListSpec.Playlist -> playlistStart(spec.playlistId)
        is ListSpec.Selection -> if (spec.base is ListSpec.Playlist) {
            val base = playlistStart(spec.base.playlistId)
            SelectionLoader(base, spec.ids) { id -> base.rekey(RowKey(0, listOf(SqlValue.Int64(0)), id)) }
        } else {
            loaders.get().forSpec(spec, owner)
        }
        else -> loaders.get().forSpec(spec, owner)
    }

    private fun playlistStart(playlistId: String) = PlaylistStartLoader(db, playlistId) { hooks.statement(it) }

    // Lets every row the start's walk holds go: its loader retired first, so
    // it adds no row after, then each work table it holds rows in found by a
    // read and cleared. A walk that wrote nothing, every start's walk today,
    // is let go with no write, so a start writes nothing before the player
    // has its song.
    private suspend fun letGo(loader: ListLoader<*>, owner: String) {
        runCatching { (loader as? Retiring)?.retire() }
        runCatching { loaders.get().releaseHeld(owner) }
        hooks.letGo(owner, loader)
    }

    // A folder: its songs as today's code reads them, in memory, when it
    // has at most QUEUE_WINDOW; else its start from the tree or one probe.
    private suspend fun folder(spec: ListSpec.Folder, start: ListStart?, shuffle: Boolean, shownOverWindow: Boolean): StartRead {
        val folders = loaders.get().folders
        val tree = folders.tree() ?: return StartRead.Refused(NOTHING_TO_PLAY)
        val node = tree.root.at(spec.path.split('/').filter(String::isNotEmpty)) ?: return StartRead.Refused(NOTHING_TO_PLAY)
        if (!shownOverWindow) {
            folders.songs(node, spec.recursive, spec.order, QUEUE_WINDOW)?.let { songs ->
                if (songs.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
                // A folder's files are its songs, each once, as its loader says.
                return whole(songs.map { Keyed(RowKey(0, emptyList(), it.id), it.id) }, start, shuffle, { it }, null, songsOnce = true)
            }
        }
        return when (start) {
            is ListStart.Row -> StartRead.Big(start.trackId, start.key, false)
            is ListStart.First -> StartRead.Big(start.trackId, start.key, shuffle)
            is ListStart.Track -> StartRead.Big(start.trackId, null, false)
            is ListStart.At -> songAt(folders, node, spec.recursive, start.index)?.let { StartRead.Big(it, null, false, positionMs = start.positionMs) }
            null -> {
                val total = if (spec.recursive) node.total else node.own
                if (shuffle && total > 0) {
                    songAt(folders, node, spec.recursive, firstPick(random, total))?.let { StartRead.Big(it, null, true, count = total) }
                } else {
                    (if (spec.recursive) node.inPlayOrder().firstNotNullOfOrNull { it.first } else node.first)?.let { StartRead.Big(it.id, null, false) }
                }
            }
        } ?: StartRead.Refused(NOTHING_TO_PLAY)
    }

    private suspend fun songAt(folders: app.winters.octo.ui.paging.PhoneFolders, node: app.winters.octo.ui.paging.PhoneFolder, recursive: Boolean, k: Int): String? {
        val (folder, at) = if (recursive) node.songAt(k) ?: return null else node to k
        return folders.fileAt(folder, at)
    }

    // An album with the songs it is missing found online. Its library
    // songs alone over QUEUE_WINDOW make it big at once, with no network;
    // its start then comes from the page's own copy when the page had one,
    // else from the library's rows.
    @Suppress("UNCHECKED_CAST")
    private suspend fun albumWithFinds(spec: ListSpec.Album, start: ListStart?, shuffle: Boolean, shownOverWindow: Boolean, pageOwner: String?): StartRead {
        val library = loaders.get().albumLibrary(spec.albumId) as ListLoader<Any>
        if (!shownOverWindow) {
            // A play with no page to say how many it shows: the library's
            // songs alone decide, and at most QUEUE_WINDOW play as the album.
            val rows = library.after(null, QUEUE_WINDOW + 1)
            if (rows.size <= QUEUE_WINDOW) {
                if (rows.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
                return whole(rows, start, shuffle, { songOf(library, it) }, library.order, library.songsOnce)
            }
        }
        val page = pageOwner?.let { loaders.get().albumCopy(it) as ListLoader<Any> }
        val from = page?.takeIf { it.count() > 0 } ?: library
        return big(from, start, shuffle)
    }

    // The start of a list too big to play whole.
    private suspend fun big(loader: ListLoader<Any>, start: ListStart?, shuffle: Boolean): StartRead = when (start) {
        is ListStart.Row -> StartRead.Big(start.trackId, start.key, false)
        is ListStart.First -> StartRead.Big(start.trackId, start.key, shuffle)
        is ListStart.Track -> loader.keyOf(start.trackId)?.let { StartRead.Big(start.trackId, it, false) }
            // A song the list no longer holds plays alone.
            ?: StartRead.Whole(listOf(start.trackId), 0, false)
        is ListStart.At -> rowAt(loader, start.index)?.let { StartRead.Big(songOf(loader, it.row) ?: return@let null, it.key, false, positionMs = start.positionMs) }
            ?: StartRead.Refused(NOTHING_TO_PLAY)
        null -> if (shuffle) shuffledStart(loader) else firstRow(loader)
    }

    private suspend fun firstRow(loader: ListLoader<Any>): StartRead =
        loader.after(null, 1).firstOrNull()?.let { row -> songOf(loader, row.row)?.let { StartRead.Big(it, row.key, false) } }
            ?: StartRead.Refused(NOTHING_TO_PLAY)

    // A uniform first song: a place drawn over the list's count and the row
    // there; a place a swap emptied meanwhile is drawn once more, then the
    // first row stands in.
    internal suspend fun shuffledStart(loader: ListLoader<Any>): StartRead {
        repeat(2) {
            val count = loader.count()
            if (count == 0) return StartRead.Refused(NOTHING_TO_PLAY)
            val row = rowAt(loader, firstPick(random, count))
            if (row != null) songOf(loader, row.row)?.let { return StartRead.Big(it, row.key, true, count = count) }
        }
        return (firstRow(loader) as? StartRead.Big)?.copy(firstShuffled = true) ?: StartRead.Refused(NOTHING_TO_PLAY)
    }

    private suspend fun rowAt(loader: ListLoader<Any>, index: Int): Keyed<Any>? {
        if (index < 0) return null
        if (index == 0) return loader.after(null, 1).firstOrNull()
        val before = loader.keyAt(index - 1) ?: return null
        return loader.after(before, 1).firstOrNull()
    }

    // The whole list, its start placed: the tapped row (by its key, else
    // its song), a place, or with no start the first row or, shuffled, one
    // picked at random. `songsOnce` as the list's loader says.
    private fun <T> whole(rows: List<Keyed<T>>, start: ListStart?, shuffle: Boolean, songOf: (T) -> String?, order: Comparator<RowKey>?, songsOnce: Boolean): StartRead {
        val ids = rows.map { songOf(it.row) }
        val kept = ids.indices.filter { ids[it] != null }
        if (kept.isEmpty()) return StartRead.Refused(NOTHING_TO_PLAY)
        fun byKey(key: RowKey?, trackId: String): Int? {
            if (key != null && order != null) rows.indexOfFirst { order.compare(it.key, key) == 0 }.takeIf { it >= 0 }?.let { return it }
            return ids.indexOf(trackId).takeIf { it >= 0 }
        }
        val list = kept.map { ids[it]!! }
        val place = { index: Int -> kept.indexOf(index).takeIf { it >= 0 } }
        return when (start) {
            is ListStart.Row -> StartRead.Whole(list, byKey(start.key, start.trackId)?.let(place) ?: 0, false, songsOnce = songsOnce)
            is ListStart.First -> StartRead.Whole(list, byKey(start.key, start.trackId)?.let(place) ?: 0, shuffle, songsOnce = songsOnce)
            is ListStart.Track -> byKey(null, start.trackId)?.let(place)?.let { StartRead.Whole(list, it, false, songsOnce = songsOnce) }
                ?: StartRead.Whole(listOf(start.trackId), 0, false)
            is ListStart.At -> StartRead.Whole(list, start.index.coerceIn(0, list.size - 1), false, start.positionMs, songsOnce = songsOnce)
            null -> StartRead.Whole(list, if (shuffle) firstPick(random, list.size) else 0, shuffle, songsOnce = songsOnce)
        }
    }

}
