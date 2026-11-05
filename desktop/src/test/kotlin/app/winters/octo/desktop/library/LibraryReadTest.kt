package app.winters.octo.desktop.library

import app.winters.octo.desktop.system.trayTooltip
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.ReadProgress
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The desktop's read of the library says how far it is, from the server to
// the built library, and where it stopped when it fails.
class LibraryReadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private fun run(step: CopyStep = CopyStep.Read, done: Long = 0, of: Long? = null) =
        CopyRunState(1, 1, "", "Home", true, true, step, done, of, null, null, 0, 0, false)

    @Test
    fun aReadCountsOnlyUpUntilItIsWholeThenTheSongsKept() {
        val a = reading(run(CopyStep.Connect), ReadProgress(500, null, cover = "s-1"))
        assertEquals(CopyStep.Read, a.step)
        assertEquals("s-1", a.artwork)
        val b = reading(a, ReadProgress(1_000, 2_000))
        val late = reading(b, ReadProgress(500, 2_000))
        assertEquals(1_000L, late.done)
        assertEquals(2_000L, late.of)
        assertEquals("s-1", late.artwork)
        val whole = reading(late, ReadProgress(990, 2_000, whole = true))
        assertEquals(990L, whole.done)
        assertTrue(whole.fraction!! >= late.fraction!!)
        assertEquals(CopyStep.Build, reading(run(CopyStep.Build), ReadProgress(5, null)).step)
    }

    @Test
    fun aReadGoesFromTheServerToTheBuiltLibraryAndThenShowsNothing() = runBlocking {
        val halfway = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val store = LibraryStore({ onProgress ->
            onProgress(ReadProgress(500, 1_000))
            halfway.complete(Unit)
            go.await()
            Library(listOf(Song("s1", "One")), emptyList(), emptyList())
        }, scope, server = "Home")
        store.load()
        withTimeout(10_000) { halfway.await() }
        val at = store.progress.value!!
        assertEquals(CopyStep.Read, at.step)
        assertEquals(500L, at.done)
        assertEquals("Home", at.server)
        go.complete(Unit)
        withTimeout(10_000) { store.state.first { it is LibraryState.Ready } }
        withTimeout(10_000) { store.progress.first { it == null } }
        assertNull(store.progress.value)
    }

    @Test
    fun aFailedFirstReadSaysTheStepItStoppedAt() = runBlocking {
        val store = LibraryStore({ onProgress ->
            onProgress(ReadProgress(48_210, 200_000))
            throw SubsonicException.NotFound("gone")
        }, scope)
        store.load()
        val failed = withTimeout(10_000) { store.state.first { it is LibraryState.Failed } } as LibraryState.Failed
        assertEquals(CopyStep.Read, failed.step)
        assertEquals(48_210L, failed.done)
    }

    @Test
    fun theTraySaysHowFarAReadIs() {
        assertEquals("Octo: Reading your songs, 300 of 2,000", trayTooltip(null, run(done = 300, of = 2_000)))
        assertEquals("Octo: Connecting to Home", trayTooltip(null, run(CopyStep.Connect)))
        assertEquals("Octo", trayTooltip(null, null))
    }
}
