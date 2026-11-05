package app.winters.octo.desktop.system

import app.winters.octo.audio.HookReport
import app.winters.octo.audio.engineCounters
import app.winters.octo.audio.loadedHooks
import app.winters.octo.audio.overlayProcesses
import app.winters.octo.audio.processStats
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.copyWords
import app.winters.octo.desktop.library.CoverMemory
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.ui.GpuCacheTrim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.swing.SwingUtilities

// What the app holds at one moment: memory, handles, threads, graphics card
// memory, CPU and I/O, the Java heap and how often the audio device reopened,
// and the parts that make up the memory: the JVM's own outside the heap,
// direct buffers, covers, the engine's open streams, loops and track infos,
// and the overlay programs running beside the app. A field is null when it
// could not be read.
data class MemoryReading(
    val privateMb: Long?,
    val workingSetMb: Long?,
    val handles: Long?,
    val threads: Long?,
    val gpuDedicatedMb: Long?,
    val gpuSharedMb: Long?,
    val ioOtherMb: Long?,
    val cpuSeconds: Long?,
    val heapUsedMb: Long,
    val heapCommittedMb: Long,
    val deviceReopens: Long?,
    val gpuCommittedMb: Long? = null,
    val swapMb: Long? = null,
    val nonHeapUsedMb: Long? = null,
    val nonHeapCommittedMb: Long? = null,
    val directMb: Long? = null,
    val directCount: Long? = null,
    val mappedMb: Long? = null,
    // The covers' memory cache: the MB of the covers it keeps, and how many
    // covers it has a key for (kept, or still held elsewhere).
    val coverMemMb: Long? = null,
    val coverCount: Long? = null,
    val loops: Long? = null,
    val httpStreams: Long? = null,
    val hlsStreams: Long? = null,
    val trackInfos: Long? = null,
    val overlays: List<OverlayReading>? = null,
)

// One overlay program beside the app: its processes and their private memory.
data class OverlayReading(val key: String, val count: Int, val privateMb: Long?)

private val onWindows = System.getProperty("os.name").startsWith("Windows")

// The memory no named part accounts for: on Windows the private bytes less
// the heap, the JVM's other memory, direct buffers and the graphics memory
// the driver committed (which Windows counts in the private bytes); on Linux
// the anonymous memory in RAM and in swap less the same, without graphics
// memory, which Linux does not count per process. Committed, not used: the
// pages a collection empties stay held. Negative when a part is counted
// outside the private bytes on this system; null when any part is unread.
fun MemoryReading.otherMb(windows: Boolean = onWindows): Long? {
    val private = privateMb ?: return null
    val nonHeap = nonHeapCommittedMb ?: return null
    val direct = directMb ?: return null
    return if (windows) {
        private - heapCommittedMb - nonHeap - direct - (gpuCommittedMb ?: return null)
    } else {
        private + (swapMb ?: return null) - heapCommittedMb - nonHeap - direct
    }
}

private const val MB = 1024L * 1024L

// The counters now: the engine library's for the process, the JVM's for the
// heap, its other memory and CPU time.
fun readMemory(reopens: () -> Long?): MemoryReading {
    val stats = runCatching { processStats() }.getOrNull()
    val cpuSeconds = runCatching { ProcessHandle.current().info().totalCpuDuration().map { it.seconds }.orElse(null) }.getOrNull()
    val heap = runCatching {
        val runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory()) / MB to runtime.totalMemory() / MB
    }.getOrNull()
    val nonHeap = runCatching { nonHeapPools() }.getOrNull()
    val buffers = runCatching { ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).associateBy { it.name } }.getOrNull()
    val covers = runCatching { CoverMemory.cache?.let { it.size / MB to it.keys.size.toLong() } }.getOrNull()
    val counters = runCatching { engineCounters() }.getOrNull()
    return MemoryReading(
        privateMb = runCatching { stats?.privateBytes?.let { it.toLong() / MB } }.getOrNull(),
        workingSetMb = runCatching { stats?.workingSet?.let { it.toLong() / MB } }.getOrNull(),
        handles = runCatching { stats?.handles?.toLong() }.getOrNull(),
        threads = runCatching { stats?.threads?.toLong() }.getOrNull(),
        gpuDedicatedMb = runCatching { stats?.gpuDedicated?.let { it.toLong() / MB } }.getOrNull(),
        gpuSharedMb = runCatching { stats?.gpuShared?.let { it.toLong() / MB } }.getOrNull(),
        ioOtherMb = runCatching { stats?.ioOtherBytes?.let { it.toLong() / MB } }.getOrNull(),
        cpuSeconds = cpuSeconds,
        heapUsedMb = heap?.first ?: 0L,
        heapCommittedMb = heap?.second ?: 0L,
        deviceReopens = runCatching(reopens).getOrNull(),
        gpuCommittedMb = runCatching { stats?.gpuCommitted?.let { it.toLong() / MB } }.getOrNull(),
        swapMb = runCatching { stats?.swap?.let { it.toLong() / MB } }.getOrNull(),
        nonHeapUsedMb = nonHeap?.first,
        nonHeapCommittedMb = nonHeap?.second,
        directMb = buffers?.get("direct")?.memoryUsed?.div(MB),
        directCount = buffers?.get("direct")?.count,
        mappedMb = buffers?.get("mapped")?.memoryUsed?.div(MB),
        coverMemMb = covers?.first,
        coverCount = covers?.second,
        loops = counters?.loopVideos,
        httpStreams = counters?.httpStreams,
        hlsStreams = counters?.hlsStreams,
        trackInfos = counters?.trackInfos,
        overlays = runCatching {
            overlayProcesses()?.map { OverlayReading(it.key, it.count.toInt(), it.privateBytes?.let { b -> b.toLong() / MB }) }
        }.getOrNull(),
    )
}

// The JVM's memory outside the heap (class data, compiled code), used and
// committed, in MB. Compressed Class Space is left out: Metaspace's figures
// already hold it.
internal fun nonHeapPools(): Pair<Long, Long> {
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.NON_HEAP && it.name != "Compressed Class Space" }
    return pools.sumOf { it.usage.used } / MB to pools.sumOf { it.usage.committed } / MB
}

// The overlay programs as one field: key:MB:processes for each, "-" for none
// running, "?" when they could not be listed.
private fun overlaysText(overlays: List<OverlayReading>?): String = when {
    overlays == null -> "?"
    overlays.isEmpty() -> "-"
    else -> overlays.joinToString(",") { "${it.key}:${it.privateMb ?: "?"}:${it.count}" }
}

// One log line for a reading, as the memory log writes it. Every value is
// one word, so the line splits on spaces.
fun memoryLine(
    time: String,
    minute: Long,
    r: MemoryReading,
    window: String,
    playing: Boolean,
    trims: Long,
    trimFreedMb: Long?,
    windows: Boolean = onWindows,
): String =
    "$time min=$minute private=${r.privateMb ?: "?"} ws=${r.workingSetMb ?: "?"} handles=${r.handles ?: "?"} " +
        "threads=${r.threads ?: "?"} gpu=${r.gpuDedicatedMb ?: "?"}/${r.gpuSharedMb ?: "?"} cpu=${r.cpuSeconds ?: "?"} " +
        "io=${r.ioOtherMb ?: "?"} heap=${r.heapUsedMb}/${r.heapCommittedMb} reopens=${r.deviceReopens ?: "?"} " +
        "trims=$trims trimFreed=${trimFreedMb ?: "?"} " +
        "gpuc=${r.gpuCommittedMb ?: "?"} swap=${r.swapMb ?: "?"} " +
        "nonheap=${r.nonHeapUsedMb ?: "?"}/${r.nonHeapCommittedMb ?: "?"} direct=${r.directMb ?: "?"}/${r.directCount ?: "?"} " +
        "mapped=${r.mappedMb ?: "?"} other=${r.otherMb(windows) ?: "?"} covers=${r.coverMemMb ?: "?"}/${r.coverCount ?: "?"} " +
        "loops=${r.loops ?: "?"} streams=${r.httpStreams ?: "?"}/${r.hlsStreams ?: "?"} infos=${r.trackInfos ?: "?"} " +
        "overlays=${overlaysText(r.overlays)} " +
        "window=$window playing=${if (playing) "yes" else "no"}"

// The hook line and the names line for a module report, when it differs from
// the last one given: the hook line names only known overlay modules and how
// many others there are (it goes into bug reports); the names line lists the
// others' file names and stays in a file on this computer. A report that
// could not be read (null hooks) gives "hooks ?" and no names line.
internal class HookLines {
    private var last: Any? = Unit

    fun next(time: String, hooks: List<String>?, foreign: Long, foreignNames: List<String>): Pair<String, String?>? {
        val now: Any? = hooks?.let { Triple(it, foreign, foreignNames) }
        if (now == last) return null
        last = now
        if (hooks == null) return "$time hooks ?" to null
        return "$time hooks ${hooksText(hooks, foreign)}" to "$time ${foreignNames.joinToString(",").ifEmpty { "-" }}"
    }
}

// Writes the hooks line to the memory log, and the other modules' names to
// their own file, when the report changed.
internal fun logHooks(time: String, report: HookReport?, lines: HookLines, log: MemoryLog, modules: MemoryLog) {
    lines.next(time, report?.hooks, report?.foreign?.toLong() ?: 0, report?.foreignNames ?: emptyList())?.let { (hooks, names) ->
        log.append(hooks)
        names?.let(modules::append)
    }
}

// The known hooks and the count of other modules, as bug reports show them.
internal fun hooksText(hooks: List<String>, foreign: Long): String =
    "known=${hooks.joinToString(",").ifEmpty { "-" }} foreign=$foreign"

// A rolling log file in a folder: when the file passes the cap it becomes
// <name>.1 (the one before is dropped), so two files hold the recent past.
class MemoryLog(internal val dir: File, private val capBytes: Long = 512L * 1024, private val name: String = "memory.log") {
    private val current get() = File(dir, name)
    private val older get() = File(dir, "$name.1")

    // Adds a line; a failure to write is let go.
    fun append(line: String) {
        runCatching {
            dir.mkdirs()
            if (current.length() > capBytes) {
                older.delete()
                current.renameTo(older)
            }
            current.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    // The last n lines, oldest first, across both files.
    fun lastLines(n: Int): List<String> = runCatching {
        (listOf(older, current).filter { it.isFile }.flatMap { it.readLines(Charsets.UTF_8) }).takeLast(n)
    }.getOrDefault(emptyList())
}

// The drawing surface inside a window, found by walking its components.
internal fun findSkiaLayer(window: java.awt.Window): org.jetbrains.skiko.SkiaLayer? {
    fun find(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? {
        if (component is org.jetbrains.skiko.SkiaLayer) return component
        if (component is java.awt.Container) component.components.forEach { child -> find(child)?.let { return it } }
        return null
    }
    return find(window)
}

// The graphics API and the first lines of what the drawing reports about the
// graphics card; null until the drawing is up.
fun rendererText(window: java.awt.Window): String? {
    fun read(): String? {
        val layer = findSkiaLayer(window) ?: return null
        val info = layer.renderInfo
        if (info.contains("isn't initialized")) return null
        return "${layer.renderApi} | " + info.lineSequence().map(String::trim).filter(String::isNotEmpty).take(4).joinToString(" | ")
    }
    return runCatching {
        if (SwingUtilities.isEventDispatchThread()) {
            read()
        } else {
            val f = java.util.concurrent.CompletableFuture<String?>()
            SwingUtilities.invokeLater { f.complete(runCatching { read() }.getOrNull()) }
            f.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }.getOrNull()
}

// The folder the memory log lives in: beside the cache folder on Windows,
// inside it elsewhere.
fun logFolder(os: DesktopOs, places: AppPlaces = AppPlaces.forSystem()): File =
    if (os == DesktopOs.Windows) File(places.cache.parentFile, "logs") else File(places.cache, "logs")

// A plain-text report to paste into a bug report: version, system, graphics,
// the overlay modules in the app, uptime, memory now, the settings that
// matter and the recent memory log. It holds no server address, user name or
// other account detail, and no name of another program's module.
fun bugDetails(
    version: String?,
    renderer: String?,
    uptimeMs: Long,
    reading: MemoryReading,
    settings: List<Pair<String, String>>,
    logLines: List<String>,
    hooks: String? = null,
    nvidia: String? = null,
): String {
    val minutes = uptimeMs / 60_000
    val fields = memoryLine("", 0, reading, "", false, GpuCacheTrim.trims.get(), GpuCacheTrim.lastFreedMb).substringAfter("private=").substringBefore(" window=")
    return buildString {
        appendLine("Octo ${version ?: "unknown"}")
        appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
        appendLine("Renderer: ${renderer ?: "unknown"}")
        appendLine("Hooks: ${hooks ?: "unknown"}")
        nvidia?.let(::appendLine)
        appendLine("Up for ${minutes / 60} h ${minutes % 60} min")
        appendLine("Memory now: private=$fields")
        appendLine("Settings: ${settings.joinToString(", ") { (name, value) -> "$name=$value" }}")
        appendLine()
        appendLine("Memory log (newest last):")
        logLines.forEach(::appendLine)
    }
}

// Puts the bug report on the clipboard, with a notice that it is there.
suspend fun copyBugDetails(app: AppState, window: java.awt.Window?, logs: File) {
    val text = withContext(Dispatchers.IO) {
        runCatching {
            val renderer = window?.let(::rendererText) ?: java.awt.Window.getWindows().firstNotNullOfOrNull(::rendererText)
            bugDetails(
                version = System.getProperty("octo.version"),
                renderer = renderer,
                uptimeMs = runCatching {
                    ProcessHandle.current().info().startInstant()
                        .map { java.time.Duration.between(it, java.time.Instant.now()).toMillis() }.orElse(0L)
                }.getOrDefault(0L),
                reading = readMemory { (app.player as? EnginePlayer)?.engine?.deviceReopens() },
                settings = bugSettings(app.settings.current, app.library?.index?.songs?.size),
                logLines = MemoryLog(logs).lastLines(24),
                hooks = runCatching { loadedHooks()?.let { hooksText(it.hooks, it.foreign.toLong()) } }.getOrNull(),
                nvidia = if (onWindows) runCatching { NvidiaOverlays.reportLine() }.getOrNull() else null,
            )
        }.getOrNull()
    }
    if (text != null) {
        withContext(Dispatchers.Main) { app.copyWords(text, "Copied the details.") }
    }
}

// The settings a bug report names: choices only, never an address, name or
// certificate.
internal fun bugSettings(settings: AppSettings, songs: Int?): List<Pair<String, String>> = buildList {
    add("animated art" to settings.appearance.animatedArt)
    add("ambience" to settings.appearance.ambience.name)
    add("wash moving" to settings.appearance.wash.moving.toString())
    add("ambient glow" to settings.appearance.ambientGlow.toString())
    add("Discord status" to settings.discord.on.toString())
    add("close to tray" to settings.system.closeToTray.toString())
    add("mini player open" to settings.system.miniPlayerOpen.toString())
    add("start in tray" to settings.system.startInTray.toString())
    add("side panel" to (settings.sidePanel ?: "none"))
    songs?.let { add("library songs" to it.toString()) }
}

private fun nowText(): String =LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

// Writes a start line, then one memory line a minute in and every 10 minutes
// after, and the renderer once it is known. From then on, with each memory
// line, a hooks line when the modules other programs put into the app
// change; their names go to modules.log beside it, never into the memory log
// that bug reports quote. Nothing here throws into the app.
fun startMemoryLog(
    scope: CoroutineScope,
    log: MemoryLog,
    renderer: () -> String?,
    state: () -> Pair<String, Boolean>,
    reopens: () -> Long?,
) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            log.append(
                "${nowText()} start version=${System.getProperty("octo.version")} " +
                    "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} java=${System.getProperty("java.version")}",
            )
            var rendererLogged = false
            val hookLines = HookLines()
            val modules = MemoryLog(log.dir, name = "modules.log")
            val started = System.currentTimeMillis()
            delay(60_000)
            while (true) {
                runCatching {
                    val (window, playing) = state()
                    val minute = (System.currentTimeMillis() - started) / 60_000
                    log.append(memoryLine(nowText(), minute, readMemory(reopens), window, playing, GpuCacheTrim.trims.get(), GpuCacheTrim.lastFreedMb))
                    if (!rendererLogged) {
                        renderer()?.let {
                            log.append("${nowText()} renderer $it")
                            // Once a start: an entry NVIDIA's settings dropped shows here.
                            if (onWindows) log.append("${nowText()} ${NvidiaOverlays.reportLine()}")
                            rendererLogged = true
                        }
                    }
                    // The graphics hooks load with the drawing, so they are
                    // looked for once it is up.
                    if (rendererLogged) {
                        logHooks(nowText(), runCatching { loadedHooks() }.getOrNull(), hookLines, log, modules)
                    }
                }
                delay(10 * 60_000L)
            }
        }
    }
}
