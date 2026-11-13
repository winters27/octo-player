package app.winters.octo.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.data.Session
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.serverReads
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsRig
import app.winters.octo.livelists.LiveListSongs
import app.winters.octo.livelists.LivePicks
import app.winters.octo.query.LibraryQuery
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.library.health.HealthBuild
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files

// The car's long lists over a test library, without the rest of the car
// (Home, Radio, search, the service): the phone's own loaders, the car's
// kept reads, its live lists, and a fake Octo server for the lists it
// answers for and for library albums' listings. Answers are read as the
// car reads them, a page at a time within a budget.
@OptIn(UnstableApi::class)
internal class CarRig(
    val db: CatalogDatabase = openTestCatalog(),
    val server: ListServer? = null,
    livePage: Int = 50,
    val budget: Int = CAR_ANSWER_BUDGET,
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val merge = db.testMerge(SERVER_SOURCE)
    val owners = WorkOwners()
    val totals = TotalsRig(db, merge)
    val live = LivePicks(db, merge, totals.totals, owners, { Long.MAX_VALUE }, { NOW }, livePage)
    val liveSongs = LiveListSongs(live, db, owners)
    val client = server?.client()
    val session = client?.let { Session(it, "octo", null, isOcto = true, adminReachable = false, extensions = emptySet(), id = "car") }
    val reads = db.serverReads(server, merge = merge, owners = owners)
    val drains = Files.createTempDirectory("drain").toFile()
    private val health = HealthBuild(db, merge, owners, { Long.MAX_VALUE }, 500, 100)
    val loaders = ListLoaders(
        db, merge, { Long.MAX_VALUE }, PlaylistSets(db), owners, Drains(drains), totals.totals, { session },
        { error("no downloads") }, { error("no sorted library") }, { health }, { live }, server = { SERVER_SOURCE }, servers = { reads },
        adoptions = { emptyMap() },
    )
    val lists = CarLists({ "car" }, scope, letGo = { owner -> owners.clear(owner) { loaders.release(owner) } }, pin = owners::pin)

    // Saved live lists' rules, by id.
    val liveLists = MutableStateFlow<Map<String, LibraryQuery>>(emptyMap())
    @Volatile var libraryOnly = false
    @Volatile var orders: Map<SortList, SortOrder> = emptyMap()

    val look = CarLook(context)
    val pages = CarPages(
        loaders, lists, owners, reads, liveSongs,
        liveRules = { id -> liveLists.map { it[id] } },
        catalog = db.dao(),
        look = look,
        doneAmong = { emptySet() },
        adopted = { emptySet() },
        order = { list -> orders[list] ?: list.default },
        albumWithFinds = { session != null && !libraryOnly },
        libraryOnly = { libraryOnly },
        scope = scope,
    )
    val carReads = CarReads(merge, totals.totals)
    private val rows = CarSplitRows(
        letter = { node, style, covers, count -> look.heading(node, style, covers, count) },
        part = { node, range, firstTitle, style, covers -> look.part(node, range, firstTitle, style, covers) },
    )

    // A car's answer for `node`, a list or one of its letters or parts, as
    // the car's library answers it.
    suspend fun answer(node: CarNode, fresh: Boolean = false): List<MediaItem> {
        val whole = wholeOf(node)
        val paged = pages.paged(whole, fresh) ?: return emptyList()
        try {
            return carReads.answer(node, whole, paged.listing, rows, budget)
        } finally {
            withContext(NonCancellable) { paged.release() }
        }
    }

    private fun wholeOf(node: CarNode): CarNode = when (node) {
        is CarNode.Heading -> wholeOf(node.parent)
        is CarNode.Part -> wholeOf(node.parent)
        else -> node
    }

    // A live list's watch tidies up as it stops, and a shelves read the car
    // asked for may still run, so both end before the database closes.
    override fun close() {
        runBlocking {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            totals.stop()
        }
        db.close()
        server?.close()
        drains.deleteRecursively()
    }
}
