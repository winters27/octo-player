package app.winters.octo.ui.paging

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.OWNED_WORK_TABLES
import app.winters.octo.catalog.PIN_TABLE
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SERVER_TABLES
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.catalog.holdsOwned
import app.winters.octo.catalog.rawQuery
import app.winters.octo.data.Drains
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.runsOcto
import app.winters.octo.discovery.Downloads
import app.winters.octo.discovery.ServerReads
import app.winters.octo.listening.PlayTotals
import app.winters.octo.livelists.LivePicks
import app.winters.octo.playback.ArtistOrder
import app.winters.octo.playback.GroupKind
import app.winters.octo.playback.ListSpec
import app.winters.octo.query.LibraryQuery
import app.winters.octo.server.serverSourceId
import app.winters.octo.sort.DownloadSort
import app.winters.octo.sort.Listening
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.sort.SortedLibrary
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.search.searchCaps
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

// Builds every list's loader: a screen's live walk of its list, or for a
// queue's fill (an owner `queue:<listId>`) the list's own copy, built from
// the list itself under that owner and then walked as it stands, for the
// lists whose order a like, a rating, a finish or the network moves.
@Singleton
class ListLoaders internal constructor(
    private val db: CatalogDatabase,
    private val merge: CatalogMerge,
    private val freeSpace: FreeSpace,
    private val sets: PlaylistSets,
    private val owners: WorkOwners,
    private val drains: Drains,
    private val totals: PlayTotals,
    // The signed-in server's session, if any.
    private val signedIn: () -> app.winters.octo.data.Session?,
    private val downloads: Lazy<Downloads>,
    private val library: Lazy<SortedLibrary>,
    private val health: Lazy<HealthBuild>,
    private val live: Lazy<LivePicks>,
    // The server in use's source, where it is known without a session.
    private val server: (() -> String?)? = null,
    // The lists the server answers for, read into a fill's copy.
    private val servers: Lazy<ServerReads>? = null,
    // The finds the library has taken in, by find, where known without
    // the downloads.
    private val adoptions: (() -> Map<String, String>)? = null,
    // Takes the server's own songs an album listing named that the copy
    // lacks (Discovery.ownedSongs).
    private val owned: (String, List<app.winters.octo.subsonic.Song>) -> Unit = { _, _ -> },
) {
    @Inject constructor(
        db: CatalogDatabase,
        merge: CatalogMerge,
        freeSpace: FreeSpace,
        sets: PlaylistSets,
        owners: WorkOwners,
        drains: Drains,
        totals: PlayTotals,
        sessions: SessionRepository,
        downloads: Lazy<Downloads>,
        library: Lazy<SortedLibrary>,
        health: Lazy<HealthBuild>,
        live: Lazy<LivePicks>,
        servers: Lazy<ServerReads>,
        discovery: Lazy<app.winters.octo.discovery.Discovery>,
    ) : this(
        db, merge, freeSpace, sets, owners, drains, totals, { (sessions.state.value as? SessionState.SignedIn)?.session }, downloads, library, health, live,
        servers = servers,
        owned = { sourceId, songs -> discovery.get().ownedSongs(sourceId, songs, starredKnown = true) },
    )

    private val dao = db.listsDao()
    private val songLists = SongLists(dao)
    val folders = PhoneFolders(db, merge, freeSpace)
    val sortCopies = SortCopies(db, merge, freeSpace)
    private val downloadLists = DownloadsLists(dao)

    val inputs = FilterInputs(
        liked = { ids -> ids.chunked(900).flatMap { dao.ids(rawQuery("SELECT trackId FROM liked_track WHERE trackId IN ${marks(it.size)}", it.map { id -> SqlValue.Text(id) })) }.toSet() },
        plays = { ids -> totals.playsOf(ids).mapValues { (_, p) -> Listening(p.plays, p.lastPlayedAt) } },
        songs = { ids -> ids.chunked(900).flatMap { db.dao().tracksByIdsUnordered(it) } },
    )

    // A song list, through its filter when it has one.
    fun songs(scope: SongScope, order: SortOrder, filter: LibraryQuery? = null): ListLoader<TrackEntity> {
        val base = songLists.loader(scope, order)
        if (filter == null || !filter.filters) return base
        val sort = order.by as? SongSort
        val heading: ((TrackEntity) -> String)? = when (sort) {
            SongSort.Title -> { t -> t.sortKey }
            SongSort.Artist -> { t -> t.artistSortKey }
            SongSort.Album -> { t -> t.albumSortKey }
            else -> null
        }
        return FilteredLoader(base, filter, inputs, if (scope == SongScope.All) heading else null, order.descending)
    }

    fun albumLibrary(albumId: String) = albumLibraryLoader(dao, albumId)

    fun albumCopy(owner: String, keepGone: Boolean = false) = albumCopyLoader(dao, owner, keepGone)

    // Streams an album's listing into `owner`'s copy; whether it holds a
    // find. A listing with none writes nothing, and the album reads as the
    // library has it, unless `keepLibrary` asks for its library songs all
    // the same.
    suspend fun buildAlbum(owner: String, albumId: String, keepLibrary: Boolean = true): Boolean {
        val session = signedIn()
        val taken = adoptions ?: { downloads.get().adoptions.value.orEmpty() }
        return AlbumListing(db, merge, drains, freeSpace, taken, owned)
            .build(owner, albumId, session?.client, session?.runsOcto == true, keepLibrary = keepLibrary)
    }

    fun artist(artistId: String, order: ArtistOrder) = ArtistLoaderSource(dao).loader(artistId, order)

    fun playlist(playlistId: String, owner: String) = PlaylistLoader(db, sets, playlistId, owner)

    fun downloads(order: SortOrder, sourceId: String?, doneOnly: Boolean = false) = downloadLists.loader(sourceId, order, doneOnly)

    fun downloadTotals() = downloadLists

    fun groups(kind: GroupKind, order: SortOrder): ListLoader<TrackEntity> {
        val listening = order.by.byListening
        val tables = listOfNotNull("track", "album", "artist", if (listening) PLAY_TOTAL else null).toTypedArray()
        return GroupsLoader(
            dao,
            artists = kind == GroupKind.Artists,
            groupIds = { if (kind == GroupKind.Albums) library.get().albumIds(order) else library.get().artistIds(order) },
            tablesRead = tables,
        )
    }

    fun search(query: String, filter: app.winters.octo.ui.search.SearchFilter) = SearchLoader(dao, query, searchCaps(filter).songs)

    // Lets an owner's rows of the lists' work tables go, a page at a time.
    suspend fun release(owner: String) {
        for (table in LIST_TABLES) db.deleteOwned(table, owner)
    }

    // The library's swap count a server list's whole read into `owner` was
    // resolved at, or null with none.
    suspend fun copyStamp(owner: String): Long? = servers?.get()?.head(owner)?.takeIf { it.complete }?.swapCount

    // Lets every row an owner holds in any work table go: its playlist
    // set pins through their release, the rest a page at a time.
    suspend fun releaseOwned(owner: String) {
        sets.releasePins(owner)
        for (table in OWNED_WORK_TABLES) if (table != PIN_TABLE) db.deleteOwned(table, owner)
    }

    // The same for an owner that may hold nothing: each table read first on
    // its owner's key, and cleared only where it holds rows, so an owner with
    // none is let go with no write (releasePins reads its pins first too).
    suspend fun releaseHeld(owner: String) {
        sets.releasePins(owner)
        for (table in OWNED_WORK_TABLES) if (table != PIN_TABLE && db.holdsOwned(table, owner)) db.deleteOwned(table, owner)
    }

    // The server in use, whose downloads the Downloads page lists.
    fun serverInUse(): String? = server?.invoke() ?: signedIn()?.let { serverSourceId(it.client.primaryUrl) }

    // A list's loader for `owner`: a screen's or a car's walks the list
    // live; a queue's fill (`queue:<listId>`, after the launch's mark) first
    // builds the list's own copy under its owner where the list needs one,
    // then walks that copy as it stands. Null for the lists another part of
    // the app builds.
    suspend fun forSpec(spec: ListSpec, owner: String): ListLoader<*>? {
        val fill = WorkOwners.kindOf(owner) == "queue"
        return when (spec) {
            is ListSpec.Songs -> if (fill && readsUserData(spec.scope, spec.order)) {
                val keep: suspend (List<TrackEntity>) -> List<TrackEntity> = { rows ->
                    if (spec.filter?.filters == true) {
                        val filtered = FilteredLoader(StaticLoader(rows), spec.filter, inputs)
                        filtered.after(null, rows.size).map { it.row }
                    } else {
                        rows
                    }
                }
                sortCopies.fillSongs(owner, spec.scope, spec.order, keep)
                sortCopies.loader(owner, spec.order.by as SongSort, downloads = false, down = spec.order.descending)
            } else {
                songs(spec.scope, spec.order, spec.filter)
            }
            // A fill walks only its own copy; any other reader of an album
            // whose listing holds no find reads the library's songs.
            is ListSpec.Album -> if (!spec.withFinds) {
                albumLibrary(spec.albumId)
            } else if (buildAlbum(owner, spec.albumId, keepLibrary = fill) || fill) {
                albumCopy(owner, keepGone = fill)
            } else {
                albumLibrary(spec.albumId)
            }
            is ListSpec.Artist -> artist(spec.artistId, spec.order)
            is ListSpec.Groups -> groups(spec.kind, spec.order)
            is ListSpec.Playlist -> playlist(spec.playlistId, owner)
            is ListSpec.Folder -> {
                val tree = folders.tree() ?: return StaticLoader(emptyList())
                val node = tree.root.at(spec.path.split('/').filter(String::isNotEmpty)) ?: return StaticLoader(emptyList())
                val order = spec.order ?: SortOrder(SongSort.FolderOrder, false)
                if (spec.recursive) {
                    folders.copy(owner, node.inPlayOrder(), SortOrder(SongSort.FolderOrder, false), ranked = true)
                } else {
                    folders.copy(owner, listOf(node), order, ranked = false)
                }
                folders.loader(owner, keepGone = fill)
            }
            is ListSpec.Downloads -> {
                val source = serverInUse()
                if (fill && source != null) {
                    sortCopies.fillDownloads(owner, source, spec.order)
                    sortCopies.loader(owner, null, downloads = true, down = spec.order.descending, titles = spec.order.by == DownloadSort.Title)
                } else {
                    downloads(spec.order, source)
                }
            }
            is ListSpec.Selection -> {
                @Suppress("UNCHECKED_CAST")
                val base = (forSpec(spec.base, owner) ?: return null) as ListLoader<Any>
                SelectionLoader(base, spec.ids) { id ->
                    if (spec.base is ListSpec.Playlist) base.rekey(RowKey(0, listOf(SqlValue.Int64(0)), id)) else base.keyOf(id)
                }
            }
            is ListSpec.Search -> search(spec.query, spec.filter)
            // Read as the list or the report shows it until a fill makes
            // its own copy through the loader's `copy`.
            is ListSpec.Health -> health.get().loader(spec.check, owner)
            is ListSpec.Live -> live.get().forSpec(spec.listId, spec.query, owner)
            // A fill's own copy of the list as the server answers it: a page's
            // whole read copied, or the list read anew; a server folder's own
            // songs read anew in their order.
            is ListSpec.ServerFolder -> servers?.get()?.let { lists ->
                if (spec.recursive) {
                    lists.fill(spec, owner)
                    lists.loader(owner)
                } else {
                    lists.fillLevel(spec, owner)
                }
            }
            is ListSpec.Online, ListSpec.ServerQueue -> servers?.get()?.let { lists ->
                lists.fill(spec, owner)
                lists.loader(owner, (spec as? ListSpec.Online)?.libraryOnly == true)
            }
            else -> null
        }
    }
}

// The work tables a list's copy is written to, each keyed by its owner.
val LIST_TABLES = listOf("lists_folder_order", "lists_sort_copy", "lists_album_entry", "lists_server_dir") + SERVER_TABLES

// A list already in memory, in its order, keyed by place: for walking a few
// rows through a filter.
class StaticLoader(private val rows: List<TrackEntity>) : ListLoader<TrackEntity> {
    private val keyed = rows.mapIndexed { i, t -> Keyed(RowKey(0, listOf(SqlValue.Int64(i.toLong())), t.id), t) }
    override val tables = emptyArray<String>()
    override val order: Comparator<RowKey> = SqlOrder.rows()
    override val songsOnce = true
    override suspend fun count() = rows.size
    override suspend fun survey(anchor: RowKey?) = Survey(rows.size, null, anchor?.let { a -> keyed.count { order.compare(it.key, a) < 0 } })
    override suspend fun after(key: RowKey?, limit: Int) = keyed.filter { key == null || order.compare(it.key, key) > 0 }.take(limit)
    override suspend fun before(key: RowKey, limit: Int) = keyed.filter { order.compare(it.key, key) < 0 }.asReversed().take(limit)
    override suspend fun keyAt(position: Int) = keyed.getOrNull(position)?.key
    override suspend fun keyOf(trackId: String) = keyed.firstOrNull { it.row.id == trackId }?.key
    override suspend fun rekey(key: RowKey): RowKey? = key
    override suspend fun present(ids: List<String>) = ids.filterTo(HashSet()) { id -> rows.any { it.id == id } }
    override fun trackIdOf(row: TrackEntity) = row.id
}
