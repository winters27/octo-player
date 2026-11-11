package app.winters.octo.catalog

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Tries a stable read makes before its last, which no swap can cross.
private const val FREE_ATTEMPTS = 2

// In a coroutine's context while it runs a stable read of `merge`, so a
// stable read inside it runs at once: the outer read's check covers it.
internal class InStableRead(val merge: CatalogMerge) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InStableRead>
}

// Reads `block` as one state of the library, across as many reads as it
// makes. Each try waits for no swap to be running, runs `prepare` and then
// `block`, and keeps the answer when no swap began meanwhile. After two
// tries the last runs `prepare` and then `block` inside
// betweenRebuilds(kind), where no swap can land, so it always ends.
// `prepare` holds whatever may wait (another job, the network) and every
// full pass; `block` waits for nothing, so the last try holds the lock no
// longer than its own reads. Inside a hold of the merge lock, or inside
// another stable read of the same merge, `block` runs at once and
// `prepare` not at all.
suspend fun <T> stableRead(merge: CatalogMerge, kind: HoldKind, prepare: suspend () -> Unit = {}, block: suspend () -> T): T =
    stableRead(merge, kind, prepare, ReadCheck.None, block)

// What a stable read checks beside the swap stamp, waits for before each
// try, and holds around its last try. None checks nothing more, waits for
// nothing and holds nothing.
internal interface ReadCheck {
    // Waits until a try may read, before the try notes its stamps.
    suspend fun ready() {}

    // A stamp, even while what the read sees is whole, that moves whenever
    // it changed for its own reasons; a try that began on an odd one, or
    // whose stamp moved, is run again.
    fun stamp(): Long

    // Runs the last try, `inside` being betweenRebuilds and `block`.
    suspend fun <T> last(inside: suspend () -> T): T

    object None : ReadCheck {
        override fun stamp() = 0L

        override suspend fun <T> last(inside: suspend () -> T): T = inside()
    }
}

// A stable read for a reader that decides from songs' rows together, which
// a save part way would show half moved: every try waits for no save to be
// part way, a try that a save began or ended across runs again, and so does
// the last, until one runs with no save part way.
internal class WholeCheck(private val merge: CatalogMerge) : ReadCheck {
    override suspend fun ready() = merge.awaitWhole()

    override fun stamp(): Long = merge.wholeStamp()

    override suspend fun <T> last(inside: suspend () -> T): T {
        while (true) {
            merge.awaitWhole()
            val before = merge.wholeStamp()
            val answer = inside()
            if (before % 2 == 0L && merge.wholeStamp() == before) return answer
        }
    }
}

internal suspend fun <T> stableRead(
    merge: CatalogMerge,
    kind: HoldKind,
    prepare: suspend () -> Unit,
    check: ReadCheck,
    block: suspend () -> T,
): T {
    if (merge.holdsMergeLock() || currentCoroutineContext()[InStableRead]?.merge === merge) return block()
    repeat(FREE_ATTEMPTS) {
        check.ready()
        val swaps = merge.swapStamp.first { it % 2 == 0L }
        val own = check.stamp()
        prepare()
        val answer = withContext(InStableRead(merge)) { block() }
        if (merge.swapStamp.value == swaps && own % 2 == 0L && check.stamp() == own) return answer
    }
    check.ready()
    prepare()
    return check.last { merge.betweenRebuilds(kind) { block() } }
}
