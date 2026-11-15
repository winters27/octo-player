package app.winters.octo.playlists

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// One change to the server's playlists at a time. Every holder runs the
// one-time base step (`base`, PlaylistSteps.ensureBase) for the server it
// works with first, as its own guarded call: a failure there (a full disk)
// is logged and the holder's own step still runs, with the rules for a
// playlist that has no base; the next holder tries the step again.
internal class SyncLock(private val base: suspend (SyncServer) -> Unit) {
    private val mutex = Mutex()

    suspend fun <T> withLock(server: SyncServer, block: suspend () -> T): T = mutex.withLock {
        baseFirst(server)
        block()
    }

    private suspend fun baseFirst(server: SyncServer) {
        try {
            base(server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "playlist sync: the base step failed (${e.javaClass.simpleName}), tried again next time")
        }
    }
}
