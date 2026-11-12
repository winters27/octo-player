package app.winters.octo.listening

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// The totals could not be brought up to date, and a read that would wait
// for them ends here instead: `words` say why, for the screen to show with
// Try again.
class TotalsFailed(val words: String) : Exception(words)

// In a coroutine's context while it holds a read hold of `holds`, so a
// hold asked for inside it runs at once.
internal class HoldsReadHold(val holds: TotalsHolds) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HoldsReadHold>
}

// Read holds on the stored totals, and the writes they keep out. While any
// hold is held no write of the totals lands, so whoever holds one reads
// them still. A write enters only when no hold is held, and while it runs
// (or, after an apply that failed, while `applying` stays odd) no hold
// enters. A write that waits turns every new hold away, and the last hold
// to leave lets it in before any of them.
//
// Who holds and who waits is kept under `books`, taken only to change that,
// never across a hold or a write. Each waiter waits on its own deferred. A
// waiter let in is counted before it is woken, so one stopped in between
// lets go as a holder would, and one stopped before it was let in is only
// taken off the queue. Whoever leaves or gives up lets in whoever may
// enter then.
internal class TotalsHolds(
    // The totals stamp: odd while a rebuild's apply runs, and after one
    // that failed, until a rebuild finishes.
    private val applying: MutableStateFlow<Long>,
    // The words while the totals failed; null otherwise.
    private val failure: () -> String?,
) {
    // `apply` for a write that turns `applying` odd as it enters; `turned`
    // once it did.
    private class Waiter(val apply: Boolean = false) {
        val admitted = CompletableDeferred<Unit>()
        var granted = false
        var turned = false
    }

    private val books = Mutex()
    private val waiting = ArrayList<Waiter>()
    private var held = 0
    private var writing = false
    private var write: Waiter? = null

    // Called when a write has to wait for holds, once it waits and turns
    // new holds away. A test watches it; the app does nothing there.
    internal var writeWaits: () -> Unit = {}

    // Called when a hold has to wait, once it waits. A test watches it.
    internal var holdWaits: () -> Unit = {}

    // Runs `block` holding the totals still. Inside a hold it runs at once.
    // While the totals failed in the middle of an apply it ends at once with
    // TotalsFailed, and so does a hold waiting when that happens.
    suspend fun <T> hold(block: suspend () -> T): T {
        if (currentCoroutineContext()[HoldsReadHold]?.holds === this) return block()
        val waiter = Waiter()
        books.withLock {
            val failed = failure()
            if (failed != null && applying.value % 2 != 0L && !writing) throw TotalsFailed(failed)
            waiting += waiter
            admit()
            if (!waiter.admitted.isCompleted) holdWaits()
        }
        try {
            waiter.admitted.await()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                books.withLock {
                    if (waiter.granted) held-- else waiting.remove(waiter)
                    admit()
                }
            }
            throw e
        }
        try {
            return withContext(HoldsReadHold(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                books.withLock {
                    held--
                    admit()
                }
            }
        }
    }

    // Runs `block` as the one write of the totals, once no hold is held.
    // An `apply` turns `applying` odd as it enters (a failed apply left it
    // odd already) and even again when `block` returns; one that throws
    // leaves it odd, since the table is half applied.
    suspend fun <T> writing(apply: Boolean, block: suspend () -> T): T {
        val waiter = Waiter(apply)
        books.withLock {
            check(write == null && !writing) { "one totals write at a time" }
            write = waiter
            admit()
            if (!waiter.granted) writeWaits()
        }
        try {
            waiter.admitted.await()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                books.withLock {
                    if (waiter.granted) {
                        writing = false
                        if (waiter.turned) applying.value += 1
                    } else {
                        write = null
                    }
                    admit()
                }
            }
            throw e
        }
        var done = false
        try {
            return block().also { done = true }
        } finally {
            withContext(NonCancellable) {
                books.withLock {
                    if (apply && done) applying.value += 1
                    writing = false
                    admit()
                }
            }
        }
    }

    // Under `books`: a waiting write enters once no hold is held, turning
    // `applying` odd in the same step when it applies, and turns every new
    // hold away until it has run; otherwise the waiting holds enter while
    // no write runs and `applying` is even. While the totals failed with
    // `applying` odd, the waiting holds end at once.
    private fun admit() {
        if (writing) return
        val next = write
        if (next != null) {
            if (held == 0) {
                write = null
                writing = true
                next.granted = true
                if (next.apply && applying.value % 2 == 0L) {
                    applying.value += 1
                    next.turned = true
                }
                next.admitted.complete(Unit)
            }
            return
        }
        if (applying.value % 2 != 0L) {
            val failed = failure() ?: return
            waiting.toList().forEach {
                waiting.remove(it)
                it.admitted.completeExceptionally(TotalsFailed(failed))
            }
            return
        }
        waiting.toList().forEach {
            waiting.remove(it)
            it.granted = true
            held++
            it.admitted.complete(Unit)
        }
    }

    // The totals just failed: holds waiting on a half-applied table end.
    suspend fun failed() = books.withLock { admit() }
}
