package app.winters.octo.desktop.perf

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.library.rememberSorted
import app.winters.octo.desktop.library.sortSongs
import app.winters.octo.desktop.library.sortedByName
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortList
import app.winters.octo.sort.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import javax.swing.SwingUtilities

// Times the library work and the Songs table on made-up libraries of 20,000
// and 100,000 songs, and writes what it found to build/perf/perf.txt. It
// only runs when asked: OCTO_PERF=1 ./gradlew :desktop:test --tests '*LibraryPerfTest*'.
// OCTO_PERF_HEAP sets the test's heap limit (2g when not given), and
// OCTO_PERF_JVM adds JVM options, for trying the app's heap options.
class LibraryPerfTest {
    @get:Rule val folder = TemporaryFolder()

    private val lines = mutableListOf<String>()

    // Keeps results alive, so the JIT cannot drop the work that made them.
    private var sink = 0L

    private fun say(line: String) {
        println(line)
        lines += line
    }

    @Test
    fun measureTheLibrary() {
        assumeTrue(System.getenv("OCTO_PERF") == "1")
        val runtime = ManagementFactory.getRuntimeMXBean()
        say("Octo library performance, ${LocalDateTime.now().withNano(0)}")
        say("JVM ${runtime.vmVendor} ${runtime.vmName} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, max heap ${Runtime.getRuntime().maxMemory() / MB} MB")
        say("Times are the median of $RUNS runs after $WARMUPS warm-ups, in milliseconds.")
        say("JVM options: ${runtime.inputArguments.filter { it.startsWith("-X") }.joinToString(" ")}")
        // The heap first, before anything is drawn, since a drawn table
        // keeps hold of the last library it showed.
        say("")
        for (size in SIZES) weigh(size)
        for (size in SIZES) measure(size)
        System.getenv("OCTO_PERF_IDLE")?.toIntOrNull()?.let(::idle)
        val out = File("build/perf").apply { mkdirs() }
        File(out, "perf.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        println("sink $sink")
    }

    // How much heap a library and its index hold once the garbage is gone:
    // the heap used after GC with it loaded, less the heap used without it.
    private fun weigh(size: Int) {
        val empty = usedAfterGc()
        val loaded = heldWith(size)
        val after = usedAfterGc()
        say("Heap used after GC, ${"%,d".format(size)} songs: ${after / MB} MB without the library, ${loaded / MB} MB with it and its index (the library holds ${(loaded - minOf(empty, after)) / MB} MB)")
    }

    private fun heldWith(size: Int): Long {
        val index = LibraryIndex.of(madeUpLibrary(size))
        sink += index.genres.size + index.history.size
        val used = usedAfterGc()
        sink += index.songs.size
        return used
    }

    private fun measure(size: Int) {
        say("")
        say("== ${"%,d".format(size)} songs ==")
        val lib = madeUpLibrary(size)
        say("${lib.songs.size} songs, ${lib.albums.size} albums, ${lib.artists.size} artists")

        time("readLibrary dedupe (songs, albums, artists by id)") {
            lib.songs.distinctBy { it.id }.size + lib.albums.distinctBy { it.id }.size + lib.artists.distinctBy { it.id }.size
        }
        time("LibraryIndex build") { LibraryIndex(lib.songs, lib.albums, lib.artists).hashCode() }
        // A fresh index each run, since the index keeps what it worked out.
        timeFresh("genres (first read)", { LibraryIndex(lib.songs, lib.albums, lib.artists) }) { it.genres.size }
        timeFresh("history (first read)", { LibraryIndex(lib.songs, lib.albums, lib.artists) }) { it.history.size }
        val index = LibraryIndex(lib.songs, lib.albums, lib.artists)
        time("songsInGenre($BIG_GENRE)") { index.songsInGenre(BIG_GENRE).size }
        time("albumsInGenre($BIG_GENRE)") { index.albumsInGenre(BIG_GENRE).size }
        say("  ($BIG_GENRE has ${index.songsInGenre(BIG_GENRE).size} songs, ${index.albumsInGenre(BIG_GENRE).size} albums; ${index.genres.size} genres; history ${index.history.size} songs)")
        for (sort in SONG_SORTS) {
            val order = SortList.Songs.default.picking(sort)
            time("sort songs by ${sort.name}${if (order.descending) " (down)" else ""}") { sortSongs(index.songs, order).size }
        }
        time("sort albums by Title") { sortAlbums(index.albums, SortOrder(AlbumSort.Title, false)).size }
        time("sort artists A to Z") { sortedByName(index.artists) { it.name }.size }

        sink += index.songs.size
        frames(index)
    }

    // Draws the Songs table at 1440 by 900 off screen and scrolls through it
    // a row or two a frame, then clicks through column headings.
    private fun frames(index: LibraryIndex) {
        val settings = SettingsStore(File(folder.newFolder(), "settings.json"))
        settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
        val http = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var app: AppState
        SwingUtilities.invokeAndWait { app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, restored = null) }
        val list = LazyListState()
        var order by mutableStateOf(SortList.Songs.default)
        val columns = listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album, SongColumn.Year, SongColumn.Length, SongColumn.Plays, SongColumn.Added)
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            CompositionLocalProvider(LocalTyping provides TypingState()) {
                // As the Songs page does: the sort runs in composition.
                // As the pages do: sorted off the window's thread.
                val songs = rememberSorted(index.songs, order) ?: emptyList()
                SongTable(app, songs, columns, list, Modifier.fillMaxSize(), order = order, onSort = { order = it })
            }
        }
        var clock = 0L
        fun frame(): Long {
            var took = 0L
            SwingUtilities.invokeAndWait {
                val start = System.nanoTime()
                scene.render(clock)
                took = System.nanoTime() - start
            }
            clock += FRAME_NS
            return took
        }
        fun scroll(frames: Int): List<Long> = List(frames) {
            SwingUtilities.invokeAndWait { list.dispatchRawDelta(SCROLL_PX) }
            frame()
        }
        val first = frame()
        scroll(WARM_FRAMES)
        SwingUtilities.invokeAndWait { list.requestScrollToItem(0) }
        frame()
        val times = scroll(FRAMES).sorted()
        val mean = times.average() / 1e6
        val p95 = times[(times.size * 95 / 100).coerceAtMost(times.size - 1)] / 1e6
        say("First frame of the Songs table: ${ms(first / 1e6)} ms")
        say("Scrolling the Songs table, $FRAMES frames of ${SCROLL_PX.toInt()} px: mean ${ms(mean)} ms, p95 ${ms(p95)} ms, worst ${ms(times.last() / 1e6)} ms")
        // A click on a column heading: the frame that follows, while the new
        // order is worked out away from the window's thread.
        val clicks = listOf(SongSort.Artist, SongSort.RecentlyAdded, SongSort.MostPlayed, SongSort.Album, SongSort.Title).map { sort ->
            SwingUtilities.invokeAndWait { order = SortList.Songs.default.picking(sort) }
            frame() / 1e6
        }
        say("A frame after clicking a column heading (Artist, Added, Plays, Album, Title): ${clicks.joinToString(", ") { ms(it) }} ms")
        val withUi = usedAfterGc()
        say("Heap used after GC with the table drawn (the whole test process): ${withUi / MB} MB")
        SwingUtilities.invokeAndWait { scene.close() }
        scope.cancel()
    }

    // With OCTO_PERF_IDLE=<seconds>: a 100,000 song library is loaded and
    // sorted every way, as clicking through the columns does, and then left
    // alone, to see how much heap the JVM keeps committed while nothing
    // happens. That is what the app's heap options are chosen by.
    private fun idle(seconds: Int) {
        val memory = ManagementFactory.getMemoryMXBean()
        fun heap() = memory.heapMemoryUsage.let { "${it.used / MB} MB used, ${it.committed / MB} MB committed" }
        say("")
        say("== Idle for $seconds s with 100,000 songs loaded ==")
        val index = LibraryIndex.of(madeUpLibrary(100_000))
        repeat(3) { for (sort in SONG_SORTS) sink += sortSongs(index.songs, SortList.Songs.default.picking(sort)).size }
        say("After sorting: ${heap()}")
        val step = 5
        for (at in step..seconds step step) {
            Thread.sleep(step * 1_000L)
            if (at % 30 == 0 || at == seconds) say("  after $at s idle: ${heap()}")
        }
        sink += index.songs.size
    }

    private fun time(name: String, block: () -> Int) {
        repeat(WARMUPS) { sink += block() }
        val runs = List(RUNS) {
            val start = System.nanoTime()
            sink += block()
            (System.nanoTime() - start) / 1e6
        }.sorted()
        say("  ${name.padEnd(48)} ${ms(runs[RUNS / 2]).padStart(9)}")
    }

    private fun <T> timeFresh(name: String, make: () -> T, block: (T) -> Int) {
        repeat(WARMUPS) { sink += block(make()) }
        val runs = List(RUNS) {
            val made = make()
            val start = System.nanoTime()
            sink += block(made)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        say("  ${name.padEnd(48)} ${ms(runs[RUNS / 2]).padStart(9)}")
    }

    private fun ms(value: Double) = "%.2f".format(value)

    private fun usedAfterGc(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(150)
        }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private companion object {
        const val MB = 1024 * 1024
        const val WARMUPS = 2
        const val RUNS = 5
        const val WARM_FRAMES = 120
        const val FRAMES = 400
        const val SCROLL_PX = 96f
        const val FRAME_NS = 16_666_667L
        val SIZES = listOf(20_000, 100_000)
        val SONG_SORTS = listOf(SongSort.Title, SongSort.Artist, SongSort.Album, SongSort.Year, SongSort.Length, SongSort.RecentlyAdded, SongSort.RecentlyPlayed, SongSort.MostPlayed)
    }
}
