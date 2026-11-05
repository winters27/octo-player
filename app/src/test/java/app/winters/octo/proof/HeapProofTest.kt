package app.winters.octo.proof

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.data.Session
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.MemoryPrefs
import app.winters.octo.listening.TestRebuilds
import app.winters.octo.server.CopyStep
import app.winters.octo.server.CopyTracker
import app.winters.octo.server.StagedCopy
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Collections
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class HeapProofTest {
    // The copy, the merge and the steps after a copy at 200,000 songs,
    // 20,000 albums and 50,000 starred songs, in a heap the size a phone
    // gives an app. Base runs out of memory on the same library.
    @Test
    fun copyMergeAndStepsAfterAtProofSize() = runBlocking {
        assumeTrue(System.getProperty("octo.heapProof") == "true")
        val songs = 200_000
        val fake = LazySubsonic(LazyLibrary(songs = songs, albums = 20_000, artists = 5_000), starredSongs = 50_000)
        val session = Session(fake.client(), serverType = null, serverVersion = null, isOcto = false, adminReachable = false, extensions = emptySet(), id = "proof")
        val source = session.sourceId
        val db = openTestCatalog()
        val merge = db.testMerge(source)
        // The copy says how far it is as the app's does, and when each step begins is noted, for
        // the share of the ring each step gets.
        val tracker = CopyTracker()
        val began = Collections.synchronizedMap(LinkedHashMap<CopyStep, Long>())
        tracker.seen = { run -> run?.let { began.putIfAbsent(it.step, System.nanoTime()) } }
        val token = tracker.begin("proof", "Proof", arriving = true, whole = true)
        val pass = tracker.nextPass(token, arriving = true, whole = true)!!
        tracker.planned(token, pass, arriving = true, whole = true)
        StagedCopy(db, merge, FreeSpace { Long.MAX_VALUE }).copy("proof", source, session.client, tracker.report(token, pass))
        val ended = System.nanoTime()
        val steps = listOf(CopyStep.Read, CopyStep.Build, CopyStep.Add, CopyStep.Save).filter { it in began }
        val from = began.getValue(CopyStep.Read)
        val shares = steps.mapIndexed { i, step ->
            val end = steps.getOrNull(i + 1)?.let(began::getValue) ?: ended
            "${step.name.lowercase()}=${"%.3f".format(Locale.ENGLISH, (end - began.getValue(step)).toDouble() / (ended - from))}"
        }
        println("RESULT copy-shares ${shares.joinToString(" ")}, ${(ended - from) / 1_000_000} ms")
        val rows = db.listeningDao()
        val store = ListeningStore(MemoryPrefs(), MutableStateFlow("proof"), rows)
        ListeningSync({ session }, db.sourceDao(), db.userDao(), db.dao(), rows, store, TestRebuilds()).afterSync()

        val w = db.openHelper.writableDatabase
        w.query("SELECT COUNT(*) FROM track").use { it.moveToFirst(); assertEquals(songs, it.getInt(0)) }
        w.query("SELECT COUNT(*) FROM liked_track").use { it.moveToFirst(); assertEquals(50_000, it.getInt(0)) }
        val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
        println("RESULT heap-proof change $songs songs, cap ${Runtime.getRuntime().maxMemory() / 1_000_000} MB, peak heap ${peak / 1_000_000} MB")
        db.close()
        fake.close()
    }
}
