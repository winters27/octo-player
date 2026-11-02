package app.winters.octo.playback

import app.winters.octo.data.SessionRepository
import app.winters.octo.data.accountId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// A part fetched behind a list, or nothing when the server had none.
data class Behind<T>(val value: T?)

// What the server answered for lists a car opened: an Octo list's or a
// station's songs, a chart, an album or artist beyond the library, a library
// album whole, an artist's top songs. Every read asks the server again,
// joining an ask already on its way for the same list, so a list is fresh
// each time it opens; the answer is kept for the songs chosen inside it to
// play as shown. A failed ask leaves nothing kept. Each answer is kept
// under the account it was asked for, and another account in use lets them
// all go.
@Singleton
class CarLists internal constructor(
    private val account: () -> String?,
    private val scope: CoroutineScope,
) {
    @Inject constructor(sessions: SessionRepository) : this(
        account = { sessions.state.value.accountId },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) {
        scope.launch { sessions.state.map { it.accountId }.distinctUntilChanged().collect { clear() } }
    }

    private data class Key(val account: String, val node: CarNode)

    private val lock = Mutex()
    private val kept = object : LinkedHashMap<Key, Any>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>?): Boolean = size > KEPT
    }
    private val asking = HashMap<Key, Deferred<Result<Any>>>()

    // For a part fetched behind a list: the answer the list was last built
    // with, to tell whether a new one changes it. As many as answers kept.
    private val built = object : LinkedHashMap<Key, Any>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>?): Boolean = size > KEPT
    }

    // How many lists' built answers are held, for tests.
    internal suspend fun builtCount(): Int = lock.withLock { built.size }

    private fun keyOf(node: CarNode) = Key(account() ?: NO_ACCOUNT, node)

    // Asks the server for `node` (with `load`), or joins the ask on its way.
    // The ask keeps its own answer, so a reader that stops waiting changes
    // nothing. An ask that throws holds what it threw rather than failing in
    // its scope, and every reader of it gets that thrown.
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> fetch(node: CarNode, load: suspend () -> T): T {
        val key = keyOf(node)
        val ask = lock.withLock {
            asking[key] ?: scope.async {
                try {
                    val got: Any = load()
                    lock.withLock { if ((account() ?: NO_ACCOUNT) == key.account) kept[key] = got }
                    Result.success(got)
                } catch (e: Throwable) {
                    lock.withLock { kept.remove(key) }
                    Result.failure(e)
                } finally {
                    lock.withLock { asking.remove(key) }
                }
            }.also { asking[key] = it }
        }
        return ask.await().getOrThrow() as T
    }

    // The last answer for `node`, for the account in use.
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> keptFor(node: CarNode): T? = lock.withLock { kept[keyOf(node)] as T? }

    // A part fetched behind a list: what was kept answers now, and a fresh
    // ask goes out. When it lands with something other than what the list
    // was built with this time, `changed` hears it, once.
    suspend fun <T : Any> behind(node: CarNode, load: suspend () -> T?, changed: () -> Unit): T? {
        val key = keyOf(node)
        val now: Behind<T>? = keptFor(node)
        lock.withLock { built[key] = now ?: Behind<T>(null) }
        scope.launch {
            val got = runCatching { fetch(node) { Behind(load()) } }.getOrNull() ?: return@launch
            val differs = lock.withLock { (built[key] != got).also { if (it) built[key] = got } }
            if (differs) changed()
        }
        return now?.value
    }

    suspend fun clear() {
        lock.withLock {
            kept.clear()
            built.clear()
        }
    }

    private companion object {
        const val KEPT = 16
        const val NO_ACCOUNT = ""
    }
}
