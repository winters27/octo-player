package app.winters.octo.desktop.system

import app.winters.octo.audio.HookReport
import app.winters.octo.desktop.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

// The memory log: its line says "?" for what could not be read and adds up
// the memory's parts, its file rolls over at a cap, a live reading holds the
// counters, and bug reports name no other program's module.
class MemoryLogTest {
    @get:Rule val temp = TemporaryFolder()

    private val unknown = MemoryReading(null, null, null, null, null, null, null, null, 120, 256, null)

    @Test
    fun aLineWithNothingReadableShowsQuestionMarksAndTheHeap() {
        val line = memoryLine("2026-01-01T10:00:00", 11, unknown, "tray", false, 3, null, windows = true)
        assertEquals(
            "2026-01-01T10:00:00 min=11 private=? ws=? handles=? threads=? gpu=?/? cpu=? io=? heap=120/256 reopens=? trims=3 trimFreed=? " +
                "gpuc=? swap=? nonheap=?/? direct=?/? mapped=? other=? covers=?/? loops=? streams=?/? infos=? overlays=? window=tray playing=no",
            line,
        )
    }

    private val known = MemoryReading(
        privateMb = 1000, workingSetMb = 800, handles = 2000, threads = 150, gpuDedicatedMb = 200, gpuSharedMb = 40,
        ioOtherMb = 9, cpuSeconds = 60, heapUsedMb = 60, heapCommittedMb = 100, deviceReopens = 0,
        gpuCommittedMb = 300, swapMb = 50, nonHeapUsedMb = 70, nonHeapCommittedMb = 80, directMb = 20, directCount = 12,
        mappedMb = 3, coverMemMb = 40, coverCount = 210, loops = 1, httpStreams = 2, hlsStreams = 0, trackInfos = 4,
        overlays = listOf(OverlayReading("nvidia-overlay", 5, 4600), OverlayReading("rtss", 1, null)),
    )

    @Test
    fun theLineCarriesEveryPartInOrderAsOneWordEach() {
        val line = memoryLine("2026-01-01T10:00:00", 11, known, "shown", true, 0, 5, windows = true)
        assertEquals(
            "2026-01-01T10:00:00 min=11 private=1000 ws=800 handles=2000 threads=150 gpu=200/40 cpu=60 io=9 heap=60/100 reopens=0 " +
                "trims=0 trimFreed=5 gpuc=300 swap=50 nonheap=70/80 direct=20/12 mapped=3 other=500 covers=40/210 loops=1 " +
                "streams=2/0 infos=4 overlays=nvidia-overlay:4600:5,rtss:?:1 window=shown playing=yes",
            line,
        )
        // Every field after the time is key=value with no space inside.
        assertTrue(line.split(' ').drop(1).all { it.count { c -> c == '=' } == 1 })
    }

    @Test
    fun noOverlayRunningShowsADash() {
        val line = memoryLine("t", 1, known.copy(overlays = emptyList()), "shown", true, 0, null, windows = true)
        assertTrue(line.contains(" overlays=- "))
    }

    @Test
    fun otherOnWindowsIsWhatNoPartHolds() {
        // 1000 - 100 heap committed - 80 non-heap committed - 20 direct - 300 graphics committed.
        assertEquals(500L, known.otherMb(windows = true))
        // A driver that counts its memory outside the private bytes shows as negative.
        assertEquals(-100L, known.copy(gpuCommittedMb = 900).otherMb(windows = true))
        assertNull(known.copy(privateMb = null).otherMb(windows = true))
        assertNull(known.copy(gpuCommittedMb = null).otherMb(windows = true))
    }

    @Test
    fun otherOnLinuxCountsSwapAndCommittedMemory() {
        // (600 + 50 swap) - 70 heap committed - 60 non-heap committed - 20 direct; the used
        // figures (30, 40) would give 560.
        val linux = known.copy(privateMb = 600, swapMb = 50, heapUsedMb = 30, heapCommittedMb = 70, nonHeapUsedMb = 40, nonHeapCommittedMb = 60, gpuCommittedMb = null)
        assertEquals(500L, linux.otherMb(windows = false))
        assertNull(linux.copy(swapMb = null).otherMb(windows = false))
    }

    @Test
    fun aReadingHoldsTheJvmsOtherMemoryAndDirectBuffers() {
        val buffer = java.nio.ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val r = readMemory { null }
        assertNotNull(r.nonHeapUsedMb)
        assertTrue((r.nonHeapCommittedMb ?: 0) >= (r.nonHeapUsedMb ?: 0))
        assertTrue("direct ${r.directMb}", (r.directMb ?: 0) >= 4)
        assertTrue((r.directCount ?: 0) >= 1)
        buffer.put(0, 1)
    }

    @Test
    fun theNonHeapSumLeavesTheClassSpaceOut() {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.NON_HEAP }
        val classSpace = pools.first { it.name == "Compressed Class Space" }.usage.committed
        assertTrue(classSpace > 0)
        val expected = (pools.sumOf { it.usage.committed } - classSpace) / (1024 * 1024)
        assertTrue("${nonHeapPools().second} vs $expected", kotlin.math.abs(nonHeapPools().second - expected) <= 1)
    }

    @Test
    fun metaspaceAlreadyHoldsTheClassSpace() {
        // The non-heap sum leaves Compressed Class Space out because the
        // Metaspace pool's committed already holds it: it matches the JVM's
        // own figure for both spaces together.
        val server = ManagementFactory.getPlatformMBeanServer()
        val name = ObjectName("com.sun.management:type=DiagnosticCommand")
        val report = server.invoke(name, "vmMetaspace", arrayOf<Any>(arrayOf<String>()), arrayOf(Array<String>::class.java.name)) as String
        val metaspace = ManagementFactory.getMemoryPoolMXBeans().first { it.name == "Metaspace" }.usage.committed
        val both = Regex("""Both:\s+[\d.]+ [KMG]?B reserved,\s+([\d.]+) ([KMG]?B)""").find(report)
        assertNotNull(report, both)
        val (value, unit) = both!!.destructured
        val bytes = value.toDouble() * when (unit) { "KB" -> 1024.0; "MB" -> 1024.0 * 1024; "GB" -> 1024.0 * 1024 * 1024; else -> 1.0 }
        assertTrue("Metaspace $metaspace vs both $bytes", kotlin.math.abs(metaspace - bytes) <= 1024 * 1024)
    }

    @Test
    fun theHooksLineComesOnlyWhenTheModulesChange() {
        val lines = HookLines()
        val first = lines.next("t1", listOf("nvspcap64.dll"), 2, listOf("a.dll", "b.dll"))
        assertEquals("t1 hooks known=nvspcap64.dll foreign=2" to "t1 a.dll,b.dll", first)
        assertNull(lines.next("t2", listOf("nvspcap64.dll"), 2, listOf("a.dll", "b.dll")))
        assertEquals("t3 hooks known=- foreign=0" to "t3 -", lines.next("t3", emptyList(), 0, emptyList()))
    }

    @Test
    fun theBugDetailsNameKnownHooksButNoOtherModule() {
        val dir = File(temp.root, "logs")
        val log = MemoryLog(dir)
        val modules = MemoryLog(dir, name = "modules.log")
        val report = HookReport(listOf("nvspcap64.dll"), 2u, listOf("secret-a.dll", "secret-b.dll"))
        logHooks("2026-01-01T10:00:00", report, HookLines(), log, modules)
        val text = bugDetails(
            "1.2.3", "Direct3D | NVIDIA", 60_000, known, emptyList(), log.lastLines(24),
            hooks = hooksText(report.hooks, report.foreign.toLong()),
        )
        assertTrue(text.contains("Hooks: known=nvspcap64.dll foreign=2"))
        assertTrue(text.contains("hooks known=nvspcap64.dll foreign=2"))
        assertTrue(text.contains("other=${known.otherMb()} "))
        assertFalse(text.contains("secret-a.dll"))
        assertFalse(File(dir, "memory.log").readText().contains("secret"))
        assertTrue(File(dir, "modules.log").readText().contains("secret-a.dll,secret-b.dll"))
    }

    @Test
    fun anUnreadModuleListSaysSoAndWritesNoNames() {
        val dir = File(temp.root, "logs")
        val log = MemoryLog(dir)
        val modules = MemoryLog(dir, name = "modules.log")
        val lines = HookLines()
        logHooks("t1", null, lines, log, modules)
        logHooks("t2", null, lines, log, modules)
        assertEquals(listOf("t1 hooks ?"), log.lastLines(5))
        assertFalse(File(dir, "modules.log").exists())
        val text = bugDetails("1.2.3", null, 0, known.copy(overlays = null), emptyList(), emptyList(), hooks = null)
        assertTrue(text.contains("Hooks: unknown"))
        // The last field of the "Memory now" line.
        assertTrue(text.contains(" overlays=?\n"))
    }

    @Test
    fun theModulesFileRollsLikeTheMemoryLog() {
        val dir = File(temp.root, "logs")
        val log = MemoryLog(dir, capBytes = 200, name = "modules.log")
        (1..20).forEach { log.append("names-%02d".format(it).padEnd(40, 'x')) }
        assertEquals(listOf("modules.log", "modules.log.1"), dir.list()!!.sorted())
    }

    @Test
    fun theLogRollsOverAtItsCapAndKeepsTheNewestLines() {
        val dir = File(temp.root, "logs")
        val log = MemoryLog(dir, capBytes = 200)
        val lines = (1..20).map { "line-%02d-".format(it).padEnd(40, 'x') }
        lines.forEach(log::append)
        assertEquals(listOf("memory.log", "memory.log.1"), dir.list()!!.sorted())
        assertEquals(lines.takeLast(5), log.lastLines(5))
        assertEquals(lines.subList(13, 20), log.lastLines(7))
    }

    @Test
    fun theBugSettingsHoldNoCertificateOrSearch() {
        val settings = AppSettings(
            trustedCertificates = mapOf("music.example.com" to "ab"),
            recentSearches = listOf("me@example.com"),
        )
        val text = bugDetails("1.2.3", null, 0, unknown, bugSettings(settings, 5), emptyList())
        assertFalse(text.contains("example.com"))
        assertFalse(text.contains("@"))
        assertTrue(text.contains("side panel=none"))
    }

    @Test
    fun aReadingNeverThrowsAndKnowsTheCpuTime() {
        val r = readMemory { 1L }
        assertNotNull(r.cpuSeconds)
    }

    @Test
    fun theBugDetailsHoldTheReportAndNoAccountDetail() {
        val text = bugDetails(
            "1.2.3",
            "Direct3D | NVIDIA",
            3_900_000,
            unknown,
            listOf("ambience" to "Glow", "close to tray" to "false"),
            listOf("first log line", "second log line"),
        )
        assertTrue(text.contains("Renderer: Direct3D | NVIDIA"))
        assertTrue(text.contains("Up for 1 h 5 min"))
        assertTrue(text.contains("Memory now: private=?"))
        assertTrue(text.contains("ambience=Glow, close to tray=false"))
        assertTrue(text.indexOf("first log line") in 0 until text.indexOf("second log line"))
        assertFalse(text.contains("http"))
        assertFalse(text.contains("@"))
    }

    @Test
    fun aLiveReadingHoldsTheCounters() {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val r = readMemory { 3L }
        assertNotNull(r.privateMb)
        assertNotNull(r.workingSetMb)
        assertNotNull(r.handles)
        assertNotNull(r.threads)
        assertEquals(3L, r.deviceReopens)
        assertTrue(r.heapCommittedMb >= r.heapUsedMb)
    }
}
