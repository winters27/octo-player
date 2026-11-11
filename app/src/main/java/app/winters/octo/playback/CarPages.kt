@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.ServerHeadEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LiveShown
import app.winters.octo.query.LibraryQuery
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.common.isOutsideLibrary
import app.winters.octo.ui.home.songCountLine
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A long list the car reads a page at a time from the phone's own loader of
// it, and what it lets go once read (a playlist's set, a live list's or a
// server list's rows in use).
internal class Paged<T : Any>(val listing: CarPagedListing<T>, val release: suspend () -> Unit = {})

// A live list the car keeps up to date under an owner of its own while the
// car keeps it: a watch of the list's rules that walks it again after each
// change into a generation of its own, what its last walk showed, and the
// parts the car's answers cut from each generation. Letting it go stops
// the watch, then empties the owner's rows.
internal class CarLive(
    private val watch: Job,
    val shown: StateFlow<LiveShown>,
    val parts: CarKeptParts,
    private val clear: suspend () -> Unit,
) : CarHeld {
    override suspend fun release() {
        watch.cancelAndJoin()
        clear()
    }
}

// The car's long lists, each read a page at a time from the loader the
// phone's own page reads, and what each keeps while the car shows it. A
// playlist's answer pins its set under a token of its own. A library album
// with an Octo server reads the server's listing under an owner of the
// car's, and keeps nothing when the listing holds no find; a list the
// server answers for is read into an owner of the car's. A live list is
// kept under an owner of the car's and brought up to date by walks of its
// own, each following a change as a page's do into a new generation, so an
// answer reads one generation as it stands, pinned while it reads, and
// walks nothing.
internal class CarPages(
    private val loaders: ListLoaders,
    private val lists: CarLists,
    private val owners: WorkOwners,
    private val servers: ServerReads,
    private val liveSongs: LiveListSongs,
    // A saved live list's rules as they change; null once it is gone.
    private val liveRules: (String) -> Flow<LibraryQuery?>,
    private val catalog: CatalogDao,
    private val look: CarLook,
    // Which of these songs are downloaded.
    private val doneAmong: suspend (List<String>) -> Set<String>,
    // The finds the library has taken in.
    private val adopted: () -> Set<String>,
    private val order: suspend (SortList) -> SortOrder,
    // Whether a library album shows the songs it lacks, from the server's
    // listing: an Octo server signed in, and Library songs only off.
    private val albumWithFinds: suspend () -> Boolean,
    private val libraryOnly: suspend () -> Boolean,
    // Where a live list's watch runs while the car keeps it.
    private val scope: CoroutineScope,
    // What a kept live list's parts depend on beyond its songs: the
    // library's swaps (song ids and fields) and the downloads finished (the
    // marks each item carries, and so its size).
    private val keptState: () -> Any = { Unit },
) {
    // A long list's rows as the car shows them, from the phone's own loader
    // of it: the listener's order for the list, and each page's download
    // marks read for that page alone. `fresh` asks the server again.
    suspend fun paged(node: CarNode, fresh: Boolean): Paged<*>? = when (node) {
        CarNode.Songs -> Paged(songListing(loaders.songs(SongScope.All, order(SortList.Songs)), node, lettered = true))
        CarNode.Liked -> Paged(songListing(loaders.songs(SongScope.Liked, order(SortList.Liked)), node, lettered = true))
        is CarNode.Genre -> {
            val albums = catalog.genreAlbums(node.name).first().map { CarRow(look.grouped(look.album(it), "Albums"), null) }
            Paged(songListing(loaders.songs(SongScope.Genre(node.name), order(SortList.GenreSongs)), node, group = "Songs", lead = albums, covers = false))
        }
        CarNode.Downloads -> {
            val loader = loaders.downloads(order(SortList.Downloads), loaders.serverInUse(), doneOnly = true)
            Paged(CarPagedListing(loader) { rows -> songRows(catalog.tracksByIds(rows.map { it.trackId }).associateBy { it.id }.let { found -> rows.map { found[it.trackId] } }, node, covers = false) })
        }
        is CarNode.Album -> albumPaged(node, fresh)
        is CarNode.Playlist -> {
            // Each answer pins the set it reads under its own owner.
            val loader = loaders.playlist(node.id, owners.car())
            Paged(CarPagedListing(loader) { rows -> songRows(rows.map { it.track }, node, covers = false) }) { loader.retire() }
        }
        is CarNode.LiveList -> livePaged(node)
        is CarNode.OctoList -> serverPaged(node, ServerListKind.List, node.id, fresh, libraryOnly = false)
        is CarNode.Station -> serverPaged(node, ServerListKind.Station, node.id, fresh, libraryOnly = false)
        is CarNode.OnlineAlbum -> serverPaged(node, ServerListKind.Album, node.id, fresh, libraryOnly())
        else -> null
    }

    private fun songListing(
        loader: ListLoader<TrackEntity>,
        list: CarNode,
        lettered: Boolean = false,
        group: String? = null,
        lead: List<CarRow> = emptyList(),
        covers: Boolean = true,
        kept: CarKeptCut? = null,
    ) = CarPagedListing(loader, countLine = ::songCountLine, lettered = lettered, lead = lead, kept = kept) { rows -> songRows(rows, list, group, covers) }

    // A page of songs as a car shows them in `list`, marked as on the phone;
    // a row with no song shows nothing.
    private suspend fun songRows(tracks: List<TrackEntity?>, list: CarNode, group: String? = null, covers: Boolean = true): List<CarRow?> {
        val taken = adopted()
        val done = doneAmong(tracks.mapNotNull { it?.id })
        return tracks.map { track ->
            track?.let {
                val item = look.song(it, list, isOutsideLibrary(it.id, taken), it.id in done)
                CarRow(group?.let { title -> look.grouped(item, title) } ?: item, it.artwork.takeIf { covers })
            }
        }
    }

    // A library album as its page lists it: with an Octo server, the songs
    // the library lacks in their places, from the server's listing read
    // under an owner of the car's own (again when the car opens it itself);
    // otherwise, with Library songs only, with the server out of reach, or
    // with a listing that holds no find (which writes nothing), the
    // library's songs alone.
    private suspend fun albumPaged(node: CarNode.Album, fresh: Boolean): Paged<*> {
        val library = Paged(songListing(loaders.albumLibrary(node.id), node, covers = false))
        if (!albumWithFinds()) return library
        val owned = try {
            lists.owned(node, fresh, owners::car) { owner -> loaders.buildAlbum(owner, node.id, keepLibrary = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return library
        if (!owned.value) {
            owners.unpin(owned.owner)
            return library
        }
        return Paged(songListing(loaders.albumCopy(owned.owner), node, covers = false)) { owners.unpin(owned.owner) }
    }

    // A live list's songs as its page shows them, from the car's own copy
    // of it, which walks of its own keep up to date while the car keeps it:
    // the first answer waits for the first walk, and each after reads the
    // generation shown when it starts, pinned until it ends, so a walk
    // meanwhile never changes what it reads. The parts an earlier answer cut
    // from that generation are kept for it, so only the rows sent are read.
    private suspend fun livePaged(node: CarNode.LiveList): Paged<*>? {
        if (liveRules(node.id).first() == null) return null
        val owned = lists.owned(node, fresh = false, owners::car) { owner -> keepLive(owner, node.id) }
        try {
            (owned.value.shown.value as? LiveShown.Problem)?.let { throw CarWords(it.words) }
            // Nothing published to pin: the list as shown now, its parts cut
            // by every answer.
            val reading = liveSongs.pinShown(owned.owner)
                ?: return Paged(songListing(liveSongs.pages(owned.owner), node, covers = false)) { owners.unpin(owned.owner) }
            try {
                val kept = owned.value.parts.at(Triple(reading.gen, reading.changes, keptState()))
                return Paged(songListing(liveSongs.pages(owned.owner, reading), node, covers = false, kept = kept)) {
                    try {
                        liveSongs.unpinShown(owned.owner, reading.gen)
                    } finally {
                        owners.unpin(owned.owner)
                    }
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { liveSongs.unpinShown(owned.owner, reading.gen) }
                throw e
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { owners.unpin(owned.owner) }
            throw e
        }
    }

    // Starts keeping a live list up to date under `owner` and waits for its
    // first walk. One that cannot show the list stops, with the words for
    // why, and keeps nothing.
    private suspend fun keepLive(owner: String, listId: String): CarLive {
        val shown = MutableStateFlow<LiveShown>(LiveShown.Loading)
        val watch = scope.launch { liveSongs.watch(owner, listId, liveRules(listId).filterNotNull(), keepHeld = true).collect { shown.value = it } }
        val live = CarLive(watch, shown, CarKeptParts()) { liveSongs.clear(owner) }
        try {
            val first = shown.first { it !is LiveShown.Loading }
            if (first is LiveShown.Problem && first.before == null) throw CarWords(first.words)
            return live
        } catch (e: Throwable) {
            withContext(NonCancellable) { live.release() }
            throw e
        }
    }

    // A server list as the server lists it now, read into the car's own
    // owner (again when the car opens it itself) and shown from its rows.
    private suspend fun serverPaged(node: CarNode, kind: ServerListKind, id: String, fresh: Boolean, libraryOnly: Boolean): Paged<*> {
        val owned = serverRead(node, kind, id, fresh)
        return Paged(songListing(servers.loader(owned.owner, libraryOnly), node, covers = false)) { owners.unpin(owned.owner) }
    }

    // The car's read of a server list, pinned for the caller.
    private suspend fun serverRead(node: CarNode, kind: ServerListKind, id: String, fresh: Boolean): CarOwned<ServerHeadEntity> =
        lists.owned(node, fresh, owners::car) { owner -> servers.read(kind, id, owner) ?: throw CarNotSignedIn() }

    // An album plays as the car shows it, as its page plays it: songs named
    // one by one when it shows QUEUE_WINDOW or fewer, else the album itself,
    // its finds in their places, started from the listing the car shows.
    suspend fun albumPlay(node: CarNode.Album, title: String): CarList {
        val source = QueueSource.Played(title)
        val paged = albumPaged(node, fresh = false)
        try {
            val shown = shownIds(paged.listing.loader, QUEUE_WINDOW + 1)
            if (shown.size <= QUEUE_WINDOW) return CarList(ListSpec.Ids(shown), source)
            val owner = lists.keptFor<CarOwned<Boolean>>(node)?.takeIf { it.value }?.owner
            return CarList(ListSpec.Album(node.id, withFinds = owner != null), source, shownOverWindow = true, pageOwner = owner)
        } finally {
            withContext(NonCancellable) { paged.release() }
        }
    }

    // An album beyond the library plays as the car shows it: the album the
    // server lists, or with Library songs only the library's songs it holds,
    // as the phone's pages leave the rest out: named one by one when they
    // are QUEUE_WINDOW or fewer, else the album's library songs, all of
    // them, started from the read the car shows.
    suspend fun onlineAlbumPlay(node: CarNode.OnlineAlbum, fresh: Boolean): CarList {
        val owned = serverRead(node, ServerListKind.Album, node.id, fresh)
        try {
            val source = QueueSource.Played(owned.value.title)
            if (!libraryOnly()) return CarList(ListSpec.Online(OnlineKind.Album, node.id), source, network = true)
            val shown = shownIds(servers.loader(owned.owner, libraryOnly = true), QUEUE_WINDOW + 1)
            if (shown.size <= QUEUE_WINDOW) return carIds(shown, source)
            return CarList(ListSpec.Online(OnlineKind.Album, node.id, libraryOnly = true), source, network = true, shownOverWindow = true)
        } finally {
            withContext(NonCancellable) { owners.unpin(owned.owner) }
        }
    }

    private suspend fun <T : Any> shownIds(loader: ListLoader<T>, limit: Int): List<String> =
        loader.after(null, limit).mapNotNull { loader.trackIdOf(it.row) }
}
