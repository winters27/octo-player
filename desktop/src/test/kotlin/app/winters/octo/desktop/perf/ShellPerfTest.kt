package app.winters.octo.desktop.perf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.library.coverLoader
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.search.SearchFilter
import app.winters.octo.desktop.search.SearchState
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.LocalWindowShown
import app.winters.octo.desktop.ui.Shell
import app.winters.octo.query.LibraryQuery
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

// Times the whole window off screen, signed in to a pretend server that
// lists a made-up library of 2,500 and of 20,000 songs: reading the library,
// the heap it holds, going from page to page, scrolling the Songs table and
// the Albums grid (covers and their cache), the filter bar and the search
// box, and what the window does while nothing happens. Writes what it found
// to build/perf/shell.txt. It only runs when asked:
// OCTO_PERF=1 ./gradlew :desktop:test --tests '*ShellPerfTest*'.
class ShellPerfTest {
    @get:Rule val folder = TemporaryFolder()

    private val lines = mutableListOf<String>()

    private fun say(line: String) {
        println(line)
        lines += line
    }

    @Test
    fun measureTheWindow() {
        assumeTrue(System.getenv("OCTO_PERF") == "1")
        val runtime = ManagementFactory.getRuntimeMXBean()
        say("Octo window performance, ${LocalDateTime.now().withNano(0)}")
        say("JVM ${runtime.vmVendor} ${runtime.vmName} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, max heap ${Runtime.getRuntime().maxMemory() / MB} MB")
        say("JVM options: ${runtime.inputArguments.filter { it.startsWith("-X") }.joinToString(" ")}")
        say("The whole window at 1440 x 900, drawn with Direct3D off screen and timed until the graphics card finished (on the processor where Direct3D is missing); a frame is offered every 16.7 ms and drawn only when something changed, as the window does.")
        val events = CountingQueue().also { Toolkit.getDefaultToolkit().systemEventQueue.push(it) }
        val sizes = System.getenv("OCTO_PERF_SIZES")?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: SIZES
        for (size in sizes) measure(size, events)
        val out = File("build/perf").apply { mkdirs() }
        File(out, "shell.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun measure(size: Int, events: CountingQueue) {
        say("")
        say("== ${"%,d".format(size)} songs ==")
        val library = madeUpLibrary(size)
        FakeServer().use { server ->
            serve(server, library)
            val root = folder.newFolder()
            val settings = SettingsStore(File(root, "settings.json"))
            // Never the real online lyrics library.
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val player = CountingPlayer(SilentPlayer())
            lateinit var app: AppState
            SwingUtilities.invokeAndWait {
                app = AppState(settings, Accounts(settings, SessionOnlySecrets(), http), http, scope, DesktopOs.Windows, player, restored = null)
            }
            // The app's own cover loader: its memory and disk caches.
            val loader = coverLoader(PlatformContext.INSTANCE, http, File(root, "cache"))
            SingletonImageLoader.setUnsafe(loader)
            var shown by mutableStateOf(true)
            val scene = ImageComposeScene(1440, 900, Density(1f), coroutineContext = Dispatchers.Main) {
                CompositionLocalProvider(LocalTyping provides TypingState(), LocalWindowShown provides shown) { Shell(app, null) {} }
            }
            val gpu = offscreenGpu()
            val target = gpu?.let { GpuTarget(it, 1440, 900) }
            target?.let { swapSurface(scene, it.surface) }
            if (target == null) say("(Direct3D off screen is not available: frames drawn on the processor)")
            val frames = Frames(scene, target)
            // The sign-in page moves by design, so it never settles.
            val signIn = frames.count { frames.runFor(2_000) }
            say("Sign-in page: ${"%.1f".format(signIn / 2.0)} frames drawn a second")
            val before = usedAfterGc()

            // Signing in: the library read in pages from the server (on
            // this machine, so the network is not what is timed), its
            // repeats dropped and the index built.
            val done = runBlocking { app.accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            val start = System.nanoTime()
            SwingUtilities.invokeAndWait { app.signedIn(done.connection) }
            while (app.library?.state?.value !is LibraryState.Ready) {
                check(System.nanoTime() - start < 120_000_000_000L) { "the library never loaded" }
                frames.frame()
                Thread.sleep(5)
            }
            say("Library read and indexed after signing in: ${ms((System.nanoTime() - start) / 1e6)} ms (${app.library!!.index!!.songs.size} songs)")
            frames.settle()

            // Going from page to page: how long until the page has drawn
            // everything it will (sorting and filtering run off the window's
            // thread), and the longest single frame on the way.
            val pages = listOf(Page.Songs, Page.Albums, Page.Artists, Page.Genres, Page.Home, Page.History, Page.Favourites, Page.Songs, Page.Albums, Page.Genres)
            say("Going to a page: ms until it settled / longest frame on the way")
            for (page in pages) {
                val switch = frames.settle { app.navigator.go(page) }
                say("  ${pageName(page).padEnd(14)} ${ms(switch.settledMs).padStart(8)} / ${ms(switch.worstMs).padStart(7)}")
            }
            val held = usedAfterGc()
            say("Heap used after GC: ${before / MB} MB signed out, ${held / MB} MB signed in with the library shown (${(held - before) / MB} MB more)")

            // Scrolling the Songs table with the mouse wheel.
            frames.settle { app.navigator.go(Page.Songs) }
            val songs = frames.scroll(SCROLL_FRAMES)
            say("Scrolling Songs, ${songs.count} frames, a wheel click every other frame: mean ${ms(songs.mean)} ms, p95 ${ms(songs.p95)} ms, worst ${ms(songs.worst)} ms")

            // The Albums grid, whose covers come from the server as they
            // scroll into view.
            frames.settle { app.navigator.go(Page.Albums) }
            val covers = server.calls.count { it.url.pathSegments.last() == "getCoverArt" }
            val albums = frames.scroll(SCROLL_FRAMES)
            frames.settle(quietMs = 1_000)
            val fetched = server.calls.count { it.url.pathSegments.last() == "getCoverArt" } - covers
            say("Scrolling Albums, ${albums.count} frames: mean ${ms(albums.mean)} ms, p95 ${ms(albums.p95)} ms, worst ${ms(albums.worst)} ms; $fetched covers fetched")
            val cache = loader.memoryCache
            say("Cover memory cache: ${(cache?.size ?: 0) / KB} KB held of ${(cache?.maxSize ?: 0) / KB} KB allowed; disk cache ${(loader.diskCache?.size ?: 0) / KB} KB")

            // The filter bar on Songs: a word typed a letter at a time, as
            // the field sends it, then how long until the table shows the
            // songs left.
            frames.settle { app.navigator.go(Page.Songs) }
            val visit = app.navigator.current
            val typedAt = System.nanoTime()
            for (text in listOf("l", "lo", "lov")) {
                SwingUtilities.invokeAndWait { app.navigator.keepFilter(visit, LibraryQuery(text = text)) }
                frames.runFor(KEY_GAP_MS)
            }
            val filtered = frames.settle { app.navigator.keepFilter(visit, LibraryQuery(text = "love")) }
            say("Filter bar, \"love\" typed a letter every $KEY_GAP_MS ms: the table showed the result ${ms(filtered.settledMs)} ms after the last letter (longest frame ${ms(filtered.worstMs)} ms; ${ms((System.nanoTime() - typedAt) / 1e6)} ms from the first)")
            frames.settle { app.navigator.keepFilter(visit, LibraryQuery()) }

            // The search box: the answer for a word, without the pause the
            // box waits for typing to stop, and how long the list takes to
            // show once it is there.
            val model = app.search!!
            val direct = List(5) {
                val begin = System.nanoTime()
                runBlocking { model.search("love", SearchFilter.All) }
                (System.nanoTime() - begin) / 1e6
            }.sorted()
            say("Search box, asking the server and splitting the answer (median of 5): ${ms(direct[2])} ms")
            // Opened without the keyboard in it: a focused field's caret
            // blinks, which is not what is measured here.
            SwingUtilities.invokeAndWait { app.omnibox.open = true }
            frames.settle()
            val asked = System.nanoTime()
            SwingUtilities.invokeAndWait { model.type("night") }
            while (model.state !is SearchState.Done) {
                check(System.nanoTime() - asked < 30_000_000_000L) { "the search never answered" }
                frames.frame()
                Thread.sleep(2)
            }
            val answered = (System.nanoTime() - asked) / 1e6
            val listed = frames.settle()
            say("Search box, \"night\" typed: answered ${ms(answered)} ms after typing (${SEARCH_PAUSE_MS} of them the pause), the list drawn ${ms(listed.settledMs)} ms later (longest frame ${ms(listed.worstMs)} ms)")
            SwingUtilities.invokeAndWait { app.omnibox.open = false }
            frames.settle()

            // Idle: a song paused in the player, as after starting up, the
            // queue open beside the page, the window shown and then
            // minimised; then the same while it plays. Counts the frames drawn, the
            // window thread's wake-ups, the player asked where it is, and
            // the processor time of the whole test process.
            SwingUtilities.invokeAndWait {
                app.play(app.library!!.index!!.songs.take(20), 0)
                app.player.pause()
                if (app.sidePanel != SidePanel.Queue) app.toggleSidePanel(SidePanel.Queue)
            }
            if (TRACING) trace(frames)
            frames.settle(quietMs = 1_000)
            for (playing in listOf(false, true)) {
                if (playing) SwingUtilities.invokeAndWait { app.player.resume() }
                for ((label, visible) in listOf("shown" to true, "minimised" to false)) {
                    shown = visible
                    // Playing never settles: the progress moves.
                    if (playing) frames.runFor(500) else frames.settle(quietMs = 500)
                    val idle = frames.idle(IDLE_SECONDS, events, player)
                    if (TRACING) events.kinds.entries.sortedByDescending { it.value }.take(8).forEach { say("  event ${it.value}x ${it.key}") }
                    say("${if (playing) "Playing" else "Idle, paused"} ${IDLE_SECONDS} s, window $label: ${"%.1f".format(idle.framesPerSecond)} frames/s, ${"%.1f".format(idle.wakesPerSecond)} window-thread events/s, player asked its place ${"%.1f".format(idle.positionsPerSecond)} times/s, process CPU ${ms(idle.cpuMsPerSecond)} ms/s")
                }
            }
            SwingUtilities.invokeAndWait { app.player.pause() }
            shown = true

            SwingUtilities.invokeAndWait { scene.close() }
            target?.close()
            gpu?.close()
            scope.cancel()
            SingletonImageLoader.reset()
        }
    }

    // The library as a server lists it: albums and songs a page at a time
    // (asked by offset), artists in one index, words searched by title.
    private fun serve(server: FakeServer, library: Library) {
        val json = Json { encodeDefaults = false; explicitNulls = false }
        fun songs(list: List<Song>) = json.encodeToString(ListSerializer(Song.serializer()), list)
        fun albums(list: List<Album>) = json.encodeToString(ListSerializer(Album.serializer()), list)
        fun artists(list: List<Artist>) = json.encodeToString(ListSerializer(Artist.serializer()), list)
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""", type = "octo")
        server.answerBy("getAlbumList2") { request ->
            val size = request.url.queryParameter("size")?.toIntOrNull() ?: 10
            val offset = request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            server.ok(""""albumList2":{"album":${albums(library.albums.drop(offset).take(size))}}""")
        }
        server.answer("getArtists", """"artists":{"index":[{"name":"A","artist":${artists(library.artists)}}]}""")
        server.answerBy("search3") { request ->
            val query = request.url.queryParameter("query").orEmpty().trim('"', ' ')
            val count = request.url.queryParameter("songCount")?.toIntOrNull() ?: 20
            val offset = request.url.queryParameter("songOffset")?.toIntOrNull() ?: 0
            val found = if (query.isEmpty()) library.songs.drop(offset).take(count) else library.songs.asSequence().filter { it.title.contains(query, ignoreCase = true) }.take(count).toList()
            val albumsFound = if (query.isEmpty()) emptyList() else library.albums.asSequence().filter { it.name.contains(query, ignoreCase = true) }.take(request.url.queryParameter("albumCount")?.toIntOrNull() ?: 0).toList()
            server.ok(""""searchResult3":{"song":${songs(found)},"album":${albums(albumsFound)},"artist":[]}""")
        }
        server.answer("getStarred2", """"starred2":{}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
        val covers = ConcurrentHashMap<String, ByteArray>()
        server.fileBy("getCoverArt") { request -> covers.getOrPut(request.url.queryParameter("id").orEmpty()) { madeUpCover(request.url.queryParameter("id").orEmpty()) } }
    }

    // Which states change while nothing should: every applied change for
    // three seconds, counted by what it was.
    private fun trace(frames: Frames) {
        val seen = ConcurrentHashMap<String, Int>()
        val handle = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { changed, _ ->
            changed.forEach { item -> seen.merge(item.toString().take(160), 1, Int::plus) }
        }
        frames.runFor(3_000)
        handle.dispose()
        seen.entries.sortedByDescending { it.value }.take(15).forEach { say("  trace ${it.value}x ${it.key}") }
    }

    private fun pageName(page: Page) = page.toString().substringAfterLast('.').substringBefore('(').substringBefore('@')

    // Draws the scene on the window's thread as the window would: a frame
    // offered every 16.7 ms, drawn only when something changed.
    private class Frames(private val scene: ImageComposeScene, private val target: GpuTarget?) {
        private val begin = System.nanoTime()
        private var drawn = 0

        // Draws one frame if one is due; the ms it took, or null.
        fun frame(): Double? {
            var took: Double? = null
            SwingUtilities.invokeAndWait {
                if (scene.hasInvalidations()) {
                    val start = System.nanoTime()
                    scene.render(System.nanoTime() - begin).close()
                    target?.finish()
                    took = (System.nanoTime() - start) / 1e6
                    drawn++
                }
            }
            return took
        }

        class Settled(val settledMs: Double, val worstMs: Double)

        // Does `change` on the window's thread, then offers frames until
        // none has been due for `quietMs`.
        fun settle(quietMs: Long = 300, change: () -> Unit = {}): Settled {
            val start = System.nanoTime()
            SwingUtilities.invokeAndWait(change)
            var last = System.nanoTime()
            var worst = 0.0
            var next = System.nanoTime()
            while ((System.nanoTime() - last) / 1_000_000 < quietMs) {
                check(System.nanoTime() - start < 120_000_000_000L) { "the window never settled" }
                frame()?.let { took ->
                    last = System.nanoTime()
                    worst = maxOf(worst, took)
                }
                next += FRAME_NANOS
                waitUntil(next)
            }
            return Settled((last - start) / 1e6, worst)
        }

        // How many frames were drawn while `block` ran.
        fun count(block: () -> Unit): Int {
            val from = drawn
            block()
            return drawn - from
        }

        fun runFor(ms: Long) {
            val until = System.nanoTime() + ms * 1_000_000
            var next = System.nanoTime()
            while (System.nanoTime() < until) {
                frame()
                next += FRAME_NANOS
                waitUntil(next)
            }
        }

        class Scrolled(val count: Int, val mean: Double, val p95: Double, val worst: Double)

        // Turns the wheel over the page every other frame and times every
        // frame drawn.
        fun scroll(count: Int): Scrolled {
            val times = ArrayList<Double>()
            var next = System.nanoTime()
            for (i in 0 until count) {
                if (i % 2 == 0) SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 500f), scrollDelta = Offset(0f, 1f)) }
                frame()?.let(times::add)
                next += FRAME_NANOS
                waitUntil(next)
            }
            times.sort()
            return Scrolled(times.size, times.average(), times[(times.size * 95 / 100).coerceAtMost(times.size - 1)], times.last())
        }

        class Idle(val framesPerSecond: Double, val wakesPerSecond: Double, val positionsPerSecond: Double, val cpuMsPerSecond: Double)

        // Leaves the window alone for `seconds`, drawing only frames that
        // fall due, looked for with sleeps rather than a spin, so the
        // process's own time is the window's. Wake-ups the looking causes
        // are taken off.
        fun idle(seconds: Int, events: CountingQueue, player: CountingPlayer): Idle {
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val drawnBefore = drawn
            val eventsBefore = events.count.get()
            val positionsBefore = player.positions.get()
            events.kinds.clear()
            val cpuBefore = os.processCpuTime
            val start = System.nanoTime()
            var looked = 0
            while (System.nanoTime() - start < seconds * 1_000_000_000L) {
                Thread.sleep(16)
                if (scene.hasInvalidations()) {
                    frame()
                    looked++
                }
            }
            val elapsed = (System.nanoTime() - start) / 1e9
            val cpu = (os.processCpuTime - cpuBefore) / 1e6
            val wakes = events.count.get() - eventsBefore - looked
            return Idle((drawn - drawnBefore) / elapsed, wakes / elapsed, (player.positions.get() - positionsBefore) / elapsed, cpu / elapsed)
        }

        private fun waitUntil(moment: Long) {
            while (moment - System.nanoTime() > 2_000_000) Thread.sleep(minOf((moment - System.nanoTime()) / 1_000_000 - 1, 9))
            while (System.nanoTime() < moment) Thread.onSpinWait()
        }
    }

    // Counts what the window's thread is woken for.
    class CountingQueue : EventQueue() {
        val count = AtomicLong()

        // What each event was, for OCTO_PERF_TRACE.
        val kinds = ConcurrentHashMap<String, Int>()

        override fun dispatchEvent(event: AWTEvent) {
            count.incrementAndGet()
            if (TRACING) kinds.merge(event.toString().replace(Regex("""@[0-9a-f]+|,when=\d+|[$][$]Lambda[^,\]]*"""), "").take(200), 1, Int::plus)
            super.dispatchEvent(event)
        }
    }

    // The silent player, counting how often it is asked where the song is.
    class CountingPlayer(private val inner: DesktopPlayer) : DesktopPlayer by inner {
        val positions = AtomicLong()

        override fun positionMs(): Long {
            positions.incrementAndGet()
            return inner.positionMs()
        }
    }

    private fun ms(value: Double) = "%.1f".format(value)

    private fun usedAfterGc(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(150)
        }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    // A made-up 600 pixel cover, different for each id.
    private fun madeUpCover(id: String): ByteArray {
        val palette = listOf(0xFF1B2A4A, 0xFFE0703A, 0xFF3AA6A0, 0xFFF2D06B, 0xFF6B3A7A, 0xFFB8C4C9, 0xFF2F5D3A, 0xFFC0463F).map { it.toInt() }
        val seed = id.hashCode() and 0x7fffffff
        fun colour(n: Int) = palette[(seed / (n + 1) + n) % palette.size]
        val surface = Surface.makeRasterN32Premul(600, 600)
        surface.canvas.clear(colour(0))
        val paint = Paint()
        paint.color = colour(1)
        surface.canvas.drawCircle(120f + seed % 180, 200f, 180f, paint)
        paint.color = colour(2)
        surface.canvas.drawCircle(440f, 280f + seed % 160, 220f, paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85)!!.bytes
    }

    private companion object {
        const val MB = 1024 * 1024
        const val KB = 1024
        const val FRAME_NANOS = 16_666_667L
        const val SCROLL_FRAMES = 600
        const val KEY_GAP_MS = 120L
        const val SEARCH_PAUSE_MS = 250
        const val IDLE_SECONDS = 10
        val SIZES = listOf(2_500, 20_000)
        val TRACING = System.getenv("OCTO_PERF_TRACE") == "1"
    }
}
