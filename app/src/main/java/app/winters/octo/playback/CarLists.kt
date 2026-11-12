package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.accountId
import app.winters.octo.ui.paging.ListLoaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// A part fetched behind a list, or nothing when the server had none.
data class Behind<T>(val value: T?)

// A read a car keeps in the database under an owner of its own, never in
// memory: the owner's token, and what the read answered (a list's head).
data class CarOwned<T>(val owner: String, val value: T)

// What a kept read answers when it holds more than its rows while kept (a
// live list kept up to date by walks of its own): let go before its rows.
interface CarHeld {
    suspend fun release()
}

// What the server answered for lists a car opened: a chart, an artist
// beyond the library, an artist's top songs; and for a list whose songs are
// read into the database (an Octo list, a station, an album beyond the
// library, a library album as the server lists it), only the owner its rows
// are under. Every read asks the server again, joining an ask already on
// its way for the same list, so a list is fresh each time it opens; the
// answer is kept for the songs chosen inside it to play as shown. A failed
// ask leaves nothing kept. Each answer is kept under the account it was
// asked for, and another account in use lets them all go. An owner no
// longer kept (replaced, pushed out by newer lists, or let go with them)
// has its rows let go (`letGo`), once nothing reading it holds a pin.
@Singleton
class CarLists internal constructor(
    private val account: () -> String?,
    private val scope: CoroutineScope,
    // Pins an owner for a reader, so letting it go waits for the reader.
    private val pin: (String) -> Unit = {},
    private val letGo: suspend (String) -> Unit = {},
) {
    @Inject constructor(sessions: SessionRepository, owners: WorkOwners, loaders: ListLoaders) : this(
        account = { sessions.state.value.accountId },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        letGo = { owner -> owners.clear(owner) { loaders.release(owner) } },
        pin = owners::pin,
    ) {
        scope.launch { sessions.state.map { it.accountId }.distinctUntilChanged().collect { clear() } }
    }

    private data class Key(val account: String, val node: CarNode)

    private val lock = Mutex()
    private val kept = object : LinkedHashMap<Key, Any>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>?): Boolean =
            (size > KEPT).also { out -> if (out) eldest?.value?.let(::released) }
    }

    // Lets the rows of an owned read go, off the caller's lock, after what
    // it holds besides them.
    private fun released(value: Any) {
        val owned = value as? CarOwned<*> ?: return
        scope.launch {
            runCatching { (owned.value as? CarHeld)?.release() }
            runCatching { letGo(owned.owner) }
        }
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
                    lock.withLock {
                        if ((account() ?: NO_ACCOUNT) == key.account) {
                            kept.put(key, got)?.takeIf { it !== got }?.let(::released)
                        } else {
                            released(got)
                        }
                    }
                    Result.success(got)
                } catch (e: Throwable) {
                    lock.withLock { kept.remove(key)?.let(::released) }
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

    // A read of `node` under an owner of its own (`newOwner`): the one kept,
    // or with `fresh` or none kept, a new read (joining one on its way)
    // that then takes the kept one's place. It comes back pinned for the
    // caller, who unpins it when done: found kept and pinned in one step
    // under the lock, so a newer read that takes its place meanwhile lets
    // its rows go only after the caller has. A read replaced before it
    // could be pinned gives way to the one kept in its place, or to a new
    // read when none is; the last try reads under the lock, so nothing can
    // take its place before it is pinned, and it ends in an answer unless
    // the read itself fails.
    suspend fun <T : Any> owned(node: CarNode, fresh: Boolean, newOwner: () -> String, read: suspend (owner: String) -> T): CarOwned<T> {
        var anew = fresh
        repeat(OWNED_TRIES - 1) {
            if (!anew) keptPinned<T>(node)?.let { return it }
            val got = fetch(node) { ownedRead(newOwner, read) }
            val pinned = lock.withLock { (kept[keyOf(node)] === got).also { if (it) pin(got.owner) } }
            if (pinned) return got
            anew = false
        }
        return lock.withLock { keptOrRead(node, newOwner, read) }
    }

    // The last try, under the lock: the read kept in this one's place
    // pinned, or a new read kept and pinned. A read that fails leaves
    // nothing kept, as a failed ask does; one finished for an account no
    // longer in use is answered pinned and let go once unpinned.
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> keptOrRead(node: CarNode, newOwner: () -> String, read: suspend (owner: String) -> T): CarOwned<T> {
        val key = keyOf(node)
        (kept[key] as? CarOwned<T>)?.let {
            pin(it.owner)
            return it
        }
        val got = try {
            ownedRead(newOwner, read)
        } catch (e: Throwable) {
            kept.remove(key)?.let(::released)
            throw e
        }
        pin(got.owner)
        if ((account() ?: NO_ACCOUNT) == key.account) kept.put(key, got)?.takeIf { it !== got }?.let(::released) else released(got)
        return got
    }

    // A new read under an owner of its own; one that fails lets its rows go.
    private suspend fun <T : Any> ownedRead(newOwner: () -> String, read: suspend (owner: String) -> T): CarOwned<T> {
        val owner = newOwner()
        try {
            return CarOwned(owner, read(owner))
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { letGo(owner) } }
            throw e
        }
    }

    // The kept read of `node`, pinned for the caller, or null with none.
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> keptPinned(node: CarNode): CarOwned<T>? = lock.withLock {
        (kept[keyOf(node)] as? CarOwned<T>)?.also { pin(it.owner) }
    }

    // How many owned reads are kept, for tests.
    internal suspend fun ownedCount(): Int = lock.withLock { kept.values.count { it is CarOwned<*> } }

    suspend fun clear() {
        lock.withLock {
            kept.values.forEach(::released)
            kept.clear()
            built.clear()
        }
    }

    private companion object {
        const val KEPT = 16
        const val NO_ACCOUNT = ""

        // How many tries a read of a list into the database takes: each but
        // the last gives way when its kept place is taken before it could be
        // pinned; the last holds the lock.
        const val OWNED_TRIES = 3
    }
}
