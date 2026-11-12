package app.winters.octo.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// What a shared hold of the merge lock is for: a Read is one bounded read,
// a Page one page of a background walk, and a Walk a whole walk. Reads and
// Pages are short holds, Walks long ones (see SwapGate).
enum class HoldKind {
    Read,
    Page,
    Walk,
    ;

    val long: Boolean get() = this == Walk
}

// In a coroutine's context while it holds `gate`, shared or alone, so a
// call inside that asks for the same gate again runs at once.
internal class HoldsMergeLock(val gate: SwapGate) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HoldsMergeLock>
}

// The merge lock. Each group of a save holds it alone; whatever must never
// see song ids halfway changed shares it, and every sharer keeps its own
// lock for its own rows. A group that asks for its turn waits until no
// sharer is inside: from its ask, new long holds (Walks) queue while short
// ones (Reads and Pages) still enter; once no long hold is inside, new
// short holds queue too; once no short hold is inside, the group runs, and
// when it leaves, every queued hold enters. So no group lands under a hold,
// a group waits at most for the long holds already inside and then the
// short ones, and a short hold waits at most one group.
//
// Who holds it and who waits is kept under `books`, which is taken only to
// change that, never across a hold. Each waiter waits on its own deferred.
// A waiter let in is counted as holding before it is woken, so one stopped
// in between lets go as a holder would, and one stopped before it was let
// in is only taken off the queue. Whoever leaves or gives up lets in
// whoever may enter then.
internal class SwapGate {
    // kind is null for the side that holds alone.
    private class Waiter(val kind: HoldKind?) {
        val admitted = CompletableDeferred<Unit>()
        var granted = false
    }

    private val books = Mutex()
    private val waiting = ArrayList<Waiter>()
    private var short = 0
    private var long = 0
    private var alone = false

    // Called when the side alone has to wait for sharers, once it waits.
    // A test watches it.
    internal var aloneWaits: () -> Unit = {}

    // Whether this coroutine holds the gate, shared or alone.
    suspend fun held(): Boolean = currentCoroutineContext()[HoldsMergeLock]?.gate === this

    // How many wait to enter, for a test to look at.
    internal suspend fun waiters(): Int = books.withLock { waiting.size }

    // Runs `block` sharing the gate; inside a hold it runs at once.
    suspend fun <T> shared(kind: HoldKind, block: suspend () -> T): T {
        if (held()) return block()
        return holding(Waiter(kind), block)
    }

    // Runs `block` holding the gate alone, once no sharer is inside.
    suspend fun <T> exclusive(block: suspend () -> T): T = holding(Waiter(null), block)

    private suspend fun <T> holding(waiter: Waiter, block: suspend () -> T): T {
        enter(waiter)
        try {
            return withContext(HoldsMergeLock(this)) { block() }
        } finally {
            withContext(NonCancellable) {
                books.withLock {
                    release(waiter)
                    admit()
                }
            }
        }
    }

    private suspend fun enter(waiter: Waiter) {
        books.withLock {
            waiting += waiter
            admit()
            if (waiter.kind == null && !waiter.granted) aloneWaits()
        }
        try {
            waiter.admitted.await()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                books.withLock {
                    if (waiter.granted) release(waiter) else waiting.remove(waiter)
                    admit()
                }
            }
            throw e
        }
    }

    private fun release(waiter: Waiter) {
        when {
            waiter.kind == null -> alone = false
            waiter.kind.long -> long--
            else -> short--
        }
    }

    // Under `books`: the side alone enters when nothing holds the gate.
    // While it waits, short holds enter as long as a long hold is inside;
    // with none waiting, every waiting sharer enters.
    private fun admit() {
        if (alone) return
        val group = waiting.firstOrNull { it.kind == null }
        if (group == null) {
            waiting.filter { it.kind != null }.forEach { grant(it) }
            return
        }
        if (short == 0 && long == 0) {
            grant(group)
            return
        }
        if (long > 0) waiting.filter { it.kind != null && !it.kind.long }.forEach { grant(it) }
    }

    private fun grant(waiter: Waiter) {
        waiting.remove(waiter)
        waiter.granted = true
        when {
            waiter.kind == null -> alone = true
            waiter.kind.long -> long++
            else -> short++
        }
        waiter.admitted.complete(Unit)
    }
}
